package io.roastedroot.jq4j;

import java.nio.charset.StandardCharsets;

public class JqResult {
    private final byte[] stdout;
    private final byte[] stderr;
    private final int exitCode;

    public JqResult(byte[] stdout, byte[] stderr, int exitcode) {
        this.stdout = stdout;
        this.stderr = stderr;
        this.exitCode = exitcode;
    }

    public byte[] stdout() {
        return stdout;
    }

    public byte[] stderr() {
        return stderr;
    }

    public int exitCode() {
        return exitCode;
    }

    public boolean success() {
        return exitCode == 0;
    }

    public boolean failure() {
        return exitCode != 0;
    }

    public void printStdout() {
        System.out.println(new String(stdout, StandardCharsets.UTF_8));
    }

    public void printStderr() {
        System.err.println(new String(stderr, StandardCharsets.UTF_8));
    }
}
