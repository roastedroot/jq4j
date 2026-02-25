package io.roastedroot.jq4j;

import com.dylibso.chicory.annotations.WasmModuleInterface;
import com.dylibso.chicory.runtime.ByteArrayMemory;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasi.WasiExitException;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@WasmModuleInterface(WasmResource.absoluteFile)
public final class Jq {
    private static WasmModule MODULE = JqModule.load();

    public static Builder builder() {
        return new Builder();
    }

    private JqResult process(byte[] stdin, List<String> args, Map<String, Path> directories) {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        var wasiOptsBuilder =
                WasiOptions.builder()
                        .withStdout(stdout)
                        .withStderr(stderr)
                        .withStdin(
                                new ByteArrayInputStream(
                                        stdin != null ? stdin : new byte[0]))
                        .withArguments(args);

        if (directories != null) {
            for (var entry : directories.entrySet()) {
                wasiOptsBuilder.withDirectory(entry.getKey(), entry.getValue());
            }
        }

        try (var wasi = WasiPreview1.builder().withOptions(wasiOptsBuilder.build()).build()) {
            Instance.builder(MODULE)
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
        } catch (WasiExitException e) {
            return new JqResult(stdout.toByteArray(), stderr.toByteArray(), e.exitCode());
        }

        return new JqResult(stdout.toByteArray(), stderr.toByteArray(), 0);
    }

    public static final class Builder {
        private byte[] stdin;
        private List<String> args;
        private Map<String, Path> directories;

        private Builder() {}

        public Builder withStdin(byte[] stdin) {
            this.stdin = stdin;
            return this;
        }

        public Builder withArgs(String... args) {
            this.args = new ArrayList<>();
            this.args.add("jq"); // program name
            for (var arg : args) {
                this.args.add(arg);
            }
            return this;
        }

        public Builder withDirectory(String guest, Path host) {
            if (this.directories == null) {
                this.directories = new LinkedHashMap<>();
            }
            this.directories.put(guest, host);
            return this;
        }

        public JqResult run() {
            return new Jq().process(stdin, args, directories);
        }
    }
}
