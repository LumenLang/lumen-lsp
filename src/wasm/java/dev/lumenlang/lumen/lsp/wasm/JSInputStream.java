package dev.lumenlang.lumen.lsp.wasm;

import org.jetbrains.annotations.NotNull;

import java.io.InputStream;

/**
 * {@link InputStream} backed by the JavaScript bridge. Each {@link #read()} call blocks
 * until the host delivers a byte, matching the contract LSP4J expects from a process
 * stdin.
 */
public final class JSInputStream extends InputStream {

    /**
     * Default constructor required for reflective instantiation by the launcher.
     */
    public JSInputStream() {
    }

    /**
     * Reads a single byte from the host, blocking until one is available.
     *
     * @return the byte value, or {@code -1} on end of stream
     */
    @Override
    public int read() {
        return JSBridge.readByte();
    }

    /**
     * Reads up to {@code len} bytes by issuing one bridge call per byte.
     *
     * @param b   destination buffer
     * @param off start offset in {@code b}
     * @param len max number of bytes to read
     * @return number of bytes read, or {@code -1} on immediate end of stream
     */
    @Override
    public int read(@NotNull byte[] b, int off, int len) {
        if (len == 0) return 0;
        int first = read();
        if (first == -1) return -1;
        b[off] = (byte) first;
        int count = 1;
        while (count < len) {
            int next = read();
            if (next == -1) break;
            b[off + count] = (byte) next;
            count++;
        }
        return count;
    }
}
