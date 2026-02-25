package io.roastedroot.jq4j;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests that map 1-to-1 the jq usage in NodeService.calculateJqValues.
 */
public class CalculateJqValuesTest {

    public static JqReactor.Builder reactor;

    @BeforeAll
    public static void beforeAll() {
        reactor = JqReactor.build();
    }

    @AfterAll
    public static void afterAll() {
        reactor.close();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Single source — no --slurp, no --null-input
    // NodeService path: sources.size()==1 && sourceValues.size()==1
    // ──────────────────────────────────────────────────────────────────────
    @Test
    public void singleSource() {
        var result =
                Jq.builder()
                        .withStdin("{\"foo\": \"bar\", \"baz\": 123}".getBytes(UTF_8))
                        .withArgs("-M", "--compact-output", ".foo")
                        .run();

        assertTrue(result.success(), "stderr: " + new String(result.stderr(), UTF_8));
        assertEquals("\"bar\"\n", new String(result.stdout(), UTF_8));
    }

    @Test
    public void singleSourceReactor() {
        var result = reactor
                .withInput("{\"foo\": \"bar\", \"baz\": 123}")
                .withFilter(".foo")
                .withCompactOutput()
                .run();

        assertEquals("\"bar\"\n", new String(result, UTF_8));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Multiple sources with --slurp
    // NodeService path: sources.size()>1 || sourceValues.size()>1
    // ──────────────────────────────────────────────────────────────────────
    @Test
    public void multipleSourcesSlurp() {
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

    @Test
    public void multipleSourcesSlurpReactor() {
        String combined =
                "{\"name\": \"alice\", \"age\": 30}\n{\"name\": \"bob\", \"age\": 25}";
        var result = reactor
                .withInput(combined)
                .withFilter("[.[].name]")
                        .withSlurp()
                                .withCompactOutput()
                                        .run();

        assertEquals("[\"alice\",\"bob\"]\n", new String(result, UTF_8));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Null input with inputs builtin
    // NodeService path: JqNode.isNullInput(operation) returns true
    // ──────────────────────────────────────────────────────────────────────
    @Test
    public void nullInputWithInputs() {
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

    @Test
    public void nullInputWithInputsReactor() {
        String combined = "{\"name\": \"alice\"}\n{\"name\": \"bob\"}";
        var result = reactor
                .withInput(combined)
                .withFilter("[inputs | .name]")
                .withNullInput()
                .withCompactOutput()
                .run();

        assertEquals("[\"alice\",\"bob\"]\n", new String(result, UTF_8));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Multiple output values (one filter producing several JSON roots)
    // NodeService parses each JSON root from the output as a separate Value
    // ──────────────────────────────────────────────────────────────────────
    @Test
    public void multipleOutputValues() {
        var result =
                Jq.builder()
                        .withStdin("{\"items\": [1, 2, 3]}".getBytes(UTF_8))
                        .withArgs("-M", "--compact-output", ".items[]")
                        .run();

        assertTrue(result.success(), "stderr: " + new String(result.stderr(), UTF_8));
        assertEquals("1\n2\n3\n", new String(result.stdout(), UTF_8));
    }

    @Test
    public void multipleOutputValuesReactor() {
        var result = reactor
                .withInput("{\"items\": [1, 2, 3]}")
                        .withFilter(".items[]")
                                .withCompactOutput()
                                        .run();

        assertEquals("1\n2\n3\n", new String(result, UTF_8));
    }

    // ──────────────────────────────────────────────────────────────────────
    // No explicit sources — all values passed on stdin
    // NodeService path: node.sources.isEmpty()
    // ──────────────────────────────────────────────────────────────────────
    @Test
    public void noSourcesAllInputs() {
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

    @Test
    public void noSourcesAllInputsReactor() {
        String combined =
                "{\"name\": \"x\", \"active\": true}\n"
                        + "{\"name\": \"y\", \"active\": false}\n"
                        + "{\"name\": \"z\", \"active\": true}";
        var result = reactor
                .withInput(combined)
                .withFilter("map(select(.active))")
                .withSlurp()
                .withCompactOutput()
                .run();

        assertEquals(
                "[{\"name\":\"x\",\"active\":true},{\"name\":\"z\",\"active\":true}]\n",
                new String(result, UTF_8));
    }
}
