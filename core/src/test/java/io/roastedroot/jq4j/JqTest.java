package io.roastedroot.jq4j;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

public class JqTest {
    // Tests mostly from:
    // https://www.baeldung.com/linux/jq-command-json

    @Test
    public void basicUsage() {
        // Arrange
        var jq =
                Jq.builder()
                        .withStdin("{\n  \"foo\":   0   \n}".getBytes(UTF_8))
                        .withArgs("-M", "--compact-output", ".");

        // Act
        var result = jq.run();

        // Assert
        assertTrue(result.success());
        assertEquals(0, result.stderr().length);
        assertEquals("{\"foo\":0}\n", new String(result.stdout(), UTF_8));
    }

    @Test
    public void reactorMode() throws Exception {
        // Arrange
        var jq = JqReactor.build();

        // Act
        var basic = jq
                .withInput("{\n  \"foo\":   0   \n}")
                .withFilter(".")
                .withCompactOutput()
                .run();
        var fruits = jq
                .withInput(JqTest.class.getResourceAsStream("/fruits.json").readAllBytes())
                .withFilter(".[].name")
                .run();

        // Assert
        assertEquals("{\"foo\":0}\n", new String(basic, UTF_8));
        var fruitsList = "\"apple\"\n" + "\"banana\"\n" + "\"kiwi\"\n";
        assertEquals(fruitsList, new String(fruits, UTF_8));

        // stdout and err are kept for debugging purposes
        assertEquals(0, jq.reactor().stdout().length);
        assertEquals(0, jq.reactor().stderr().length);
        jq.close();
    }

    @Test
    public void error() {
        // Arrange
        var jq =
                Jq.builder()
                        .withStdin("{\n  \"foo\":   0   \n}".getBytes(UTF_8))
                        .withArgs("--helptypo");

        // Act
        var result = jq.run();

        // Assert
        var errorMsg =
                "jq: Unknown option --helptypo\n"
                        + "Use jq --help for help with command-line options,\n"
                        + "or see the jq manpage, or online docs  at https://jqlang.github.io/jq\n";
        assertTrue(result.failure());
        assertEquals(0, result.stdout().length);
        assertEquals(errorMsg, new String(result.stderr(), UTF_8));
    }

    @Test
    public void readArrays() throws IOException {
        // Arrange
        var jq =
                Jq.builder()
                        .withStdin(JqTest.class.getResourceAsStream("/fruits.json").readAllBytes())
                        .withArgs("-M", ".[].name");

        // Act
        var result = jq.run();

        // Assert
        var fruitsList = "\"apple\"\n" + "\"banana\"\n" + "\"kiwi\"\n";
        assertTrue(result.success());
        assertEquals(0, result.stderr().length);
        assertEquals(fruitsList, new String(result.stdout(), UTF_8));
    }

    @Test
    public void functions() throws IOException {
        // Arrange
        var jq =
                Jq.builder()
                        .withStdin(JqTest.class.getResourceAsStream("/fruits.json").readAllBytes())
                        .withArgs("-M", ".[2].name | length");

        // Act
        var result = jq.run();

        // Assert
        assertTrue(result.success());
        assertEquals(0, result.stderr().length);
        assertEquals("4\n", new String(result.stdout(), UTF_8)); // kiwi length
    }
}
