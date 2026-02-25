package io.roastedroot.jq4j;

import com.dylibso.chicory.runtime.ByteArrayMemory;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.WasmModule;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reactor-mode jq wrapper that uses WASM linear memory for I/O.
 *
 * <p>Unlike {@link Jq}, which creates a new WASM instance per invocation,
 * this class keeps a single long-lived instance and calls exported functions
 * directly.  The jq runtime ({@code jq_init()}) runs once during module
 * initialization (snapshotted by Wizer at build time), so each
 * {@link #process} call only pays for compilation + evaluation.
 *
 * <p><b>Not thread-safe.</b> Use one instance per thread, or synchronise
 * externally.
 *
 * <p>This is a prototype / sketch — the reactor WASM binary does not exist yet.
 */
public final class JqReactor implements AutoCloseable {

    /* ── flags — must match jq_wrapper.c ───────────────────────────── */
    public static final int FLAG_SLURP      = 1 << 0;
    public static final int FLAG_NULL_INPUT = 1 << 1;
    public static final int FLAG_COMPACT    = 1 << 2;
    public static final int FLAG_SORT_KEYS  = 1 << 3;

    /* ── return codes from the C side ──────────────────────────────── */
    private static final int RC_ERROR_INIT     = -3;
    private static final int RC_ERROR_OVERFLOW = -2;
    private static final int RC_ERROR_COMPILE  = -1;

    private static final int DEFAULT_OUTPUT_SIZE = 256 * 1024; // 256 KiB

    private static WasmModule MODULE = JqModule.load();

    private final Instance instance;
    private final Memory memory;
    private final ExportFunction allocFn;
    private final ExportFunction deallocFn;
    private final ExportFunction processFn;

    /**
     * Create a reactor instance from a pre-built reactor WASM module.
     */
    public JqReactor() {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        var wasiOptsBuilder =
                WasiOptions.builder()
                        .withStdout(stdout)
                        .withStderr(stderr);

        var wasi = WasiPreview1.builder().withOptions(wasiOptsBuilder.build()).build();
        // Build the instance — this calls _initialize() which runs
        // the __attribute__((constructor)) that calls jq_init().
        this.instance = Instance.builder(MODULE)
                .withMachineFactory(JqModule::create)
                .withMemoryFactory(ByteArrayMemory::new)
                .withImportValues(ImportValues.builder().addFunction(wasi.toHostFunctions()).build())
                .build();
        this.memory    = instance.memory();
        this.allocFn   = instance.export("alloc");
        this.deallocFn = instance.export("dealloc");
        this.processFn = instance.export("process");
        instance.exports().function("_initialize").apply();
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
        int outputMax = DEFAULT_OUTPUT_SIZE;

        // Allocate buffers in WASM linear memory
        int inputPtr  = alloc(input.length);
        int filterPtr = alloc(filterBytes.length);
        int outputPtr = alloc(outputMax);

        try {
            // Copy data into WASM memory
            memory.write(inputPtr, input);
            memory.write(filterPtr, filterBytes);

            // Call process(input_ptr, input_len, filter_ptr, filter_len,
            //              output_ptr, output_max, flags)
            long[] result = processFn.apply(
                    inputPtr, input.length,
                    filterPtr, filterBytes.length,
                    outputPtr, outputMax,
                    flags);
            int bytesWritten = (int) result[0];

            if (bytesWritten == RC_ERROR_COMPILE) {
                throw new JqException("jq filter compilation failed: " + filter);
            }
            if (bytesWritten == RC_ERROR_OVERFLOW) {
                // TODO: retry with a larger buffer
                throw new JqException("Output buffer overflow (>" + outputMax + " bytes)");
            }
            if (bytesWritten == RC_ERROR_INIT) {
                throw new JqException("jq runtime initialization failed");
            }
            if (bytesWritten < 0) {
                throw new JqException("Unknown error from jq wrapper: " + bytesWritten);
            }

            // Read result from WASM memory
            return memory.readString(outputPtr, bytesWritten, StandardCharsets.UTF_8);
        } finally {
            dealloc(inputPtr, input.length);
            dealloc(filterPtr, filterBytes.length);
            dealloc(outputPtr, outputMax);
        }
    }

    /**
     * Convenience: process with compact output and no special flags.
     */
    public String process(byte[] input, String filter) {
        return process(input, filter, FLAG_COMPACT);
    }

    private int alloc(int size) {
        return (int) allocFn.apply(size)[0];
    }

    private void dealloc(int ptr, int size) {
        deallocFn.apply(ptr, size);
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
