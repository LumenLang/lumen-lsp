package dev.lumenlang.lumen.lsp.wasm;

import org.jetbrains.annotations.NotNull;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * {@link OutputStream} backed by the JavaScript bridge. Bytes are buffered into UTF-8
 * strings and forwarded to the host on flush or when newline framed segments accumulate.
 */
public final class JSOutputStream extends OutputStream {

    private final byte[] buffer = new byte[8192];
    private int size;

    /**
     * Default constructor required for reflective instantiation by the launcher.
     */
    public JSOutputStream() {
    }

    /**
     * Writes a single byte to the buffer, flushing automatically when full.
     *
     * @param b byte value, low 8 bits used
     */
    @Override
    public void write(int b) {
        if (size == buffer.length) flush();
        buffer[size++] = (byte) b;
    }

    /**
     * Writes a contiguous range from {@code b} into the buffer, flushing as needed.
     *
     * @param b   source array
     * @param off start offset
     * @param len number of bytes to write
     */
    @Override
    public void write(@NotNull byte[] b, int off, int len) {
        int written = 0;
        while (written < len) {
            int free = buffer.length - size;
            if (free == 0) {
                flush();
                free = buffer.length;
            }
            int chunk = Math.min(free, len - written);
            System.arraycopy(b, off + written, buffer, size, chunk);
            size += chunk;
            written += chunk;
        }
    }

    /**
     * Forwards any buffered bytes to the host as one UTF-8 string.
     */
    @Override
    public void flush() {
        if (size == 0) return;
        String chunk = new String(buffer, 0, size, StandardCharsets.UTF_8);
        size = 0;
        JSBridge.writeChunk(chunk);
    }

    /**
     * Equivalent to {@link #flush()}; the bridge has no underlying resource to release.
     */
    @Override
    public void close() {
        flush();
    }
}
