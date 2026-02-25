package io.roastedroot.jq4j;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.roastedroot.zerofs.Configuration;
import io.roastedroot.zerofs.ZeroFs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Tests that map 1-to-1 the jq usage in NodeService.calculateJqValues.
 *
 * <p>NodeService invokes {@code /usr/bin/jq} as a subprocess with:
 *
 * <ul>
 *   <li>{@code --from-file <filter>} — the jq filter written to a temp file
 *   <li>{@code --null-input} when the filter uses {@code inputs} (see {@code
 *       JqNode.isNullInput})
 *   <li>{@code --slurp} when there are multiple source files
 *   <li>{@code --compact-output}
 *   <li>{@code --} followed by input JSON file paths
 * </ul>
 *
 * <p>These tests exercise the same patterns using the embedded jq4j API with a ZeroFs in-memory
 * VFS, proving that the subprocess can be replaced by the in-process WASM execution. Each
 * file-based test is paired with a stdin-based alternative to compare results.
 */
public class CalculateJqValuesTest {

    private static Path createVfsDir(FileSystem fs) throws IOException {
        Path dir = fs.getPath("/work");
        Files.createDirectories(dir);
        return dir;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Single source — no --slurp, no --null-input
    // NodeService path: sources.size()==1 && sourceValues.size()==1
    // ──────────────────────────────────────────────────────────────────────

    @Test
    public void singleSourceVfs() throws IOException {
        try (FileSystem fs = ZeroFs.newFileSystem(
                        Configuration.unix().toBuilder()
                                .setAttributeViews("unix")
                                .build())) {
            Path dir = createVfsDir(fs);
            Files.writeString(dir.resolve("filter.jq"), ".foo");
            Files.writeString(dir.resolve("input.json"), "{\"foo\": \"bar\", \"baz\": 123}");

            var result =
                    Jq.builder()
                            .withArgs(
                                    "-M",
                                    "--from-file",
                                    "/work/filter.jq",
                                    "--compact-output",
                                    "--",
                                    "/work/input.json")
                            .withDirectory("/work", dir)
                            .run();

            assertTrue(result.success(), "stderr: " + new String(result.stderr(), UTF_8));
            assertEquals("\"bar\"\n", new String(result.stdout(), UTF_8));
        }
    }

    @Test
    public void singleSourceStdin() {
        var result =
                Jq.builder()
                        .withStdin("{\"foo\": \"bar\", \"baz\": 123}".getBytes(UTF_8))
                        .withArgs("-M", "--compact-output", ".foo")
                        .run();

        assertTrue(result.success(), "stderr: " + new String(result.stderr(), UTF_8));
        assertEquals("\"bar\"\n", new String(result.stdout(), UTF_8));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Multiple sources with --slurp
    // NodeService path: sources.size()>1 || sourceValues.size()>1
    // ──────────────────────────────────────────────────────────────────────

    @Test
    public void multipleSourcesSlurpVfs() throws IOException {
        try (FileSystem fs = ZeroFs.newFileSystem(
                        Configuration.unix().toBuilder()
                                .setAttributeViews("unix")
                                .build())) {
            Path dir = createVfsDir(fs);
            Files.writeString(dir.resolve("filter.jq"), "[.[].name]");
            Files.writeString(
                    dir.resolve("input1.json"), "{\"name\": \"alice\", \"age\": 30}");
            Files.writeString(
                    dir.resolve("input2.json"), "{\"name\": \"bob\", \"age\": 25}");

            var result =
                    Jq.builder()
                            .withArgs(
                                    "-M",
                                    "--from-file",
                                    "/work/filter.jq",
                                    "--slurp",
                                    "--compact-output",
                                    "--",
                                    "/work/input1.json",
                                    "/work/input2.json")
                            .withDirectory("/work", dir)
                            .run();

            assertTrue(result.success(), "stderr: " + new String(result.stderr(), UTF_8));
            assertEquals("[\"alice\",\"bob\"]\n", new String(result.stdout(), UTF_8));
        }
    }

    @Test
    public void multipleSourcesSlurpStdin() {
        // Concatenate multiple JSON values on stdin; --slurp collects them into an array
        String combined =
                "{\"name\": \"alice\", \"age\": 30}\n{\"name\": \"bob\", \"age\": 25}";
        var result =
                Jq.builder()
                        .withStdin(combined.getBytes(UTF_8))
                        .withArgs("-M", "--slurp", "--compact-output", "[.[].name]")
                        .run();

        assertTrue(result.success(), "stderr: " + new String(result.stderr(), UTF_8));
        assertEquals("[\"alice\",\"bob\"]\n", new String(result.stdout(), UTF_8));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Null input with inputs builtin
    // NodeService path: JqNode.isNullInput(operation) returns true
    // ──────────────────────────────────────────────────────────────────────

    @Test
    public void nullInputWithInputsVfs() throws IOException {
        try (FileSystem fs = ZeroFs.newFileSystem(
                        Configuration.unix().toBuilder()
                                .setAttributeViews("unix")
                                .build())) {
            Path dir = createVfsDir(fs);
            Files.writeString(dir.resolve("filter.jq"), "[inputs | .name]");
            Files.writeString(dir.resolve("input1.json"), "{\"name\": \"alice\"}");
            Files.writeString(dir.resolve("input2.json"), "{\"name\": \"bob\"}");

            var result =
                    Jq.builder()
                            .withArgs(
                                    "-M",
                                    "--from-file",
                                    "/work/filter.jq",
                                    "--null-input",
                                    "--compact-output",
                                    "--",
                                    "/work/input1.json",
                                    "/work/input2.json")
                            .withDirectory("/work", dir)
                            .run();

            assertTrue(result.success(), "stderr: " + new String(result.stderr(), UTF_8));
            assertEquals("[\"alice\",\"bob\"]\n", new String(result.stdout(), UTF_8));
        }
    }

    @Test
    public void nullInputWithInputsStdin() {
        // With stdin, --null-input + inputs reads all stdin JSON values
        String combined = "{\"name\": \"alice\"}\n{\"name\": \"bob\"}";
        var result =
                Jq.builder()
                        .withStdin(combined.getBytes(UTF_8))
                        .withArgs(
                                "-M",
                                "--null-input",
                                "--compact-output",
                                "[inputs | .name]")
                        .run();

        assertTrue(result.success(), "stderr: " + new String(result.stderr(), UTF_8));
        assertEquals("[\"alice\",\"bob\"]\n", new String(result.stdout(), UTF_8));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Multiple output values (one filter producing several JSON roots)
    // NodeService parses each JSON root from the output as a separate Value
    // ──────────────────────────────────────────────────────────────────────

    @Test
    public void multipleOutputValuesVfs() throws IOException {
        try (FileSystem fs = ZeroFs.newFileSystem(
                        Configuration.unix().toBuilder()
                                .setAttributeViews("unix")
                                .build())) {
            Path dir = createVfsDir(fs);
            Files.writeString(dir.resolve("filter.jq"), ".items[]");
            Files.writeString(dir.resolve("input.json"), "{\"items\": [1, 2, 3]}");

            var result =
                    Jq.builder()
                            .withArgs(
                                    "-M",
                                    "--from-file",
                                    "/work/filter.jq",
                                    "--compact-output",
                                    "--",
                                    "/work/input.json")
                            .withDirectory("/work", dir)
                            .run();

            assertTrue(result.success(), "stderr: " + new String(result.stderr(), UTF_8));
            assertEquals("1\n2\n3\n", new String(result.stdout(), UTF_8));
        }
    }

    @Test
    public void multipleOutputValuesStdin() {
        var result =
                Jq.builder()
                        .withStdin("{\"items\": [1, 2, 3]}".getBytes(UTF_8))
                        .withArgs("-M", "--compact-output", ".items[]")
                        .run();

        assertTrue(result.success(), "stderr: " + new String(result.stderr(), UTF_8));
        assertEquals("1\n2\n3\n", new String(result.stdout(), UTF_8));
    }

    // ──────────────────────────────────────────────────────────────────────
    // No explicit sources — all values passed as files
    // NodeService path: node.sources.isEmpty()
    // ──────────────────────────────────────────────────────────────────────

    @Test
    public void noSourcesAllInputsVfs() throws IOException {
        try (FileSystem fs = ZeroFs.newFileSystem(
                        Configuration.unix().toBuilder()
                                .setAttributeViews("unix")
                                .build())) {
            Path dir = createVfsDir(fs);
            Files.writeString(dir.resolve("filter.jq"), "map(select(.active))");
            Files.writeString(
                    dir.resolve("a.json"), "{\"name\": \"x\", \"active\": true}");
            Files.writeString(
                    dir.resolve("b.json"), "{\"name\": \"y\", \"active\": false}");
            Files.writeString(
                    dir.resolve("c.json"), "{\"name\": \"z\", \"active\": true}");

            var result =
                    Jq.builder()
                            .withArgs(
                                    "-M",
                                    "--from-file",
                                    "/work/filter.jq",
                                    "--slurp",
                                    "--compact-output",
                                    "--",
                                    "/work/a.json",
                                    "/work/b.json",
                                    "/work/c.json")
                            .withDirectory("/work", dir)
                            .run();

            assertTrue(result.success(), "stderr: " + new String(result.stderr(), UTF_8));
            assertEquals(
                    "[{\"name\":\"x\",\"active\":true},{\"name\":\"z\",\"active\":true}]\n",
                    new String(result.stdout(), UTF_8));
        }
    }

    @Test
    public void noSourcesAllInputsStdin() {
        String combined =
                "{\"name\": \"x\", \"active\": true}\n"
                        + "{\"name\": \"y\", \"active\": false}\n"
                        + "{\"name\": \"z\", \"active\": true}";
        var result =
                Jq.builder()
                        .withStdin(combined.getBytes(UTF_8))
                        .withArgs(
                                "-M",
                                "--slurp",
                                "--compact-output",
                                "map(select(.active))")
                        .run();

        assertTrue(result.success(), "stderr: " + new String(result.stderr(), UTF_8));
        assertEquals(
                "[{\"name\":\"x\",\"active\":true},{\"name\":\"z\",\"active\":true}]\n",
                new String(result.stdout(), UTF_8));
    }
}
