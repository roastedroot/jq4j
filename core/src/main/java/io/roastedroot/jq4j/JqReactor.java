package io.roastedroot.jq4j;

import com.dylibso.chicory.runtime.ByteArrayMemory;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Reactor-mode jq wrapper that uses WASM linear memory for I/O.
 *
 * <p>Unlike {@link Jq}, which creates a new WASM instance per invocation,
 * this class keeps a single long-lived instance and calls exported functions
 * directly.  The jq runtime ({@code jq_init()}) runs once during module
 * initialization, so each {@link #process} call only pays for
 * compilation + evaluation.
 *
 * <p><b>Not thread-safe.</b> Use one instance per thread, or synchronise
 * externally.
 */
public final class JqReactor implements AutoCloseable {

    /* ── flags — must match jq_wrapper.c ───────────────────────────── */
    public static final int FLAG_SLURP      = 1 << 0;
    public static final int FLAG_NULL_INPUT = 1 << 1;
    public static final int FLAG_COMPACT    = 1 << 2;
    public static final int FLAG_SORT_KEYS  = 1 << 3;

    /* ── return codes from the C side ──────────────────────────────── */
    private static final int RC_ERROR_INIT    = -3;
    private static final int RC_ERROR_COMPILE = -1;

    private static WasmModule MODULE = JqModule.load();

    private final Instance instance;
    private final Memory memory;
    private final ExportFunction allocFn;
    private final ExportFunction deallocFn;
    private final ExportFunction processFn;
    private final ExportFunction getOutputPtrFn;
    private final ExportFunction getOutputLenFn;

    public JqReactor() {
        var wasi = WasiPreview1.builder()
                .withOptions(WasiOptions.builder()
                        .withStdout(new ByteArrayOutputStream())
                        .withStderr(new ByteArrayOutputStream())
                        .build())
                .build();

        this.instance = Instance.builder(MODULE)
                .withMachineFactory(JqModule::create)
                .withMemoryFactory(ByteArrayMemory::new)
                .withImportValues(
                        ImportValues.builder()
                                .addFunction(wasi.toHostFunctions())
                                .addFunction(
                                        new HostFunction(
                                                "wasi",
                                                "thread-spawn",
                                                FunctionType.of(
                                                        List.of(ValType.I32),
                                                        List.of(ValType.I32)),
                                                (i, a) -> {
                                                    throw new UnsupportedOperationException(
                                                            "--run-tests is not supported");
                                                }))
                                .build())
                .build();

        this.memory         = instance.memory();
        this.allocFn        = instance.export("alloc");
        this.deallocFn      = instance.export("dealloc");
        this.processFn      = instance.export("process");
        this.getOutputPtrFn = instance.export("get_output_ptr");
        this.getOutputLenFn = instance.export("get_output_len");

        instance.export("_initialize").apply();
    }

    /**
     * Run a jq filter against the given JSON input.
     *
     * @param input  JSON text (may contain multiple values separated by whitespace)
     * @param filter jq filter expression (e.g. {@code ".[0].name"})
     * @param flags  bitmask of {@code FLAG_*} constants
     * @return the filter output (each result on its own line)
     * @throws JqException on compilation or runtime errors
     */
    public String process(byte[] input, String filter, int flags) {
        byte[] filterBytes = filter.getBytes(StandardCharsets.UTF_8);

        int inputPtr  = (int) allocFn.apply(input.length)[0];
        int filterPtr = (int) allocFn.apply(filterBytes.length)[0];

        try {
            memory.write(inputPtr, input);
            memory.write(filterPtr, filterBytes);

            long[] rc = processFn.apply(
                    inputPtr, input.length,
                    filterPtr, filterBytes.length,
                    flags);
            int ret = (int) rc[0];

            if (ret == RC_ERROR_COMPILE) {
                throw new JqException("jq filter compilation failed: " + filter);
            }
            if (ret == RC_ERROR_INIT) {
                throw new JqException("jq runtime initialization failed");
            }
            if (ret < 0) {
                throw new JqException("Unknown error from jq wrapper: " + ret);
            }

            int outPtr = (int) getOutputPtrFn.apply()[0];
            int outLen = (int) getOutputLenFn.apply()[0];
            return memory.readString(outPtr, outLen, StandardCharsets.UTF_8);
        } finally {
            deallocFn.apply(inputPtr, input.length);
            deallocFn.apply(filterPtr, filterBytes.length);
        }
    }

    /**
     * Convenience: process with compact output and no special flags.
     */
    public String process(byte[] input, String filter) {
        return process(input, filter, FLAG_COMPACT);
    }

    @Override
    public void close() {
        // Instance doesn't currently need explicit cleanup in Chicory,
        // but implementing AutoCloseable for future-proofing.
    }

    /** Thrown when jq compilation or processing fails. */
    public static class JqException extends RuntimeException {
        public JqException(String message) {
            super(message);
        }
    }
}
