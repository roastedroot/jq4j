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
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Reactor-mode jq wrapper that uses WASM linear memory for I/O.
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
    private final WasiPreview1 wasi;
    private final Jq_ModuleExports exports;

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    private JqReactor() {
        this.wasi = WasiPreview1.builder()
                .withOptions(WasiOptions.builder()
                        .withStdout(stdout)
                        .withStderr(stderr)
                        .build())
                .build();

        this.instance = Instance.builder(MODULE)
                .withMachineFactory(JqModule::create)
                .withMemoryFactory(ByteArrayMemory::new)
                .withImportValues(
                        ImportValues.builder()
                                .addFunction(wasi.toHostFunctions())
                                .addFunction(Jq.threadSpawnStub())
                                .build())
                .build();

        this.exports = new Jq_ModuleExports(instance);

        exports._initialize();
    }

    /**
     * Run a jq filter against the given JSON input.
     */
    public byte[] process(byte[] input, byte[] filter, int flags) {
        int inputPtr  = exports.alloc(input.length);
        int filterPtr = exports.alloc(filter.length);

        try {
            exports.memory().write(inputPtr, input);
            exports.memory().write(filterPtr, filter);

            int ret = exports.process(inputPtr, input.length, filterPtr, filter.length, flags);

            switch (ret) {
                case 0:
                    return exports.memory().readBytes(exports.getOutputPtr(), exports.getOutputLen());
                case RC_ERROR_INIT:
                    throw new RuntimeException("jq runtime initialization failed");
                case RC_ERROR_COMPILE:
                    throw new RuntimeException("jq filter compilation failed: " + filter);
                default:
                    throw new RuntimeException("Unknown error from jq wrapper: " + ret);
            }
        } finally {
            exports.dealloc(inputPtr, input.length);
            exports.dealloc(filterPtr, filter.length);
        }
    }

    public byte[] stdout() {
        return stdout.toByteArray();
    }

    public byte[] stderr() {
        return stderr.toByteArray();
    }

    @Override
    public void close() {
        if (wasi != null) {
            wasi.close();
        }
        if (stdout != null) {
            try {
                stdout.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        if (stderr != null) {
            try {
                stderr.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static Builder build() {
        return new Builder(new JqReactor());
    }

    public static final class Builder implements AutoCloseable {
        public final JqReactor reactor;

        private byte[] input;
        private byte[] filter;
        private int flags;

        private Builder(JqReactor reactor) {
            this.reactor = reactor;
        }

        public JqReactor reactor() {
            return reactor;
        }

        public Builder withInput(String input) {
            return withInput(input.getBytes(StandardCharsets.UTF_8));
        }

        public Builder withInput(byte[] input) {
            this.input = input;
            return this;
        }

        public Builder withFilter(String filter) {
            return withFilter(filter.getBytes(StandardCharsets.UTF_8));
        }

        public Builder withFilter(byte[] filter) {
            this.filter = filter;
            return this;
        }

        public Builder withCompactOutput() {
            this.flags |= FLAG_COMPACT;
            return this;
        }

        public Builder withSlurp() {
            this.flags |= FLAG_SLURP;
            return this;
        }

        public Builder withNullInput() {
            this.flags |= FLAG_NULL_INPUT;
            return this;
        }

        public Builder withSortKeys() {
            this.flags |= FLAG_SORT_KEYS;
            return this;
        }

        public byte[] run() {
            Objects.requireNonNull(input);
            Objects.requireNonNull(filter);

            var result = reactor.process(input, filter, flags);

            // clean up for next execution
            reset();

            return result;
        }

        /** Package-private: used by {@link JqReactorPool} on return. */
        void reset() {
            input = null;
            filter = null;
            flags = 0;
        }

        @Override
        public void close() {
            reactor.close();
        }
    }
}
