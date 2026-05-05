package dev.lumenlang.lumen.lsp.wasm;

import org.graalvm.webimage.api.JS;
import org.jetbrains.annotations.NotNull;

/**
 * Native bridge between the WebAssembly module and the surrounding JavaScript runtime.
 * Replaces stdin/stdout with environment specific I/O so the LSP can run under Node.js
 * or inside a browser web worker.
 */
public final class JSBridge {

    private JSBridge() {
    }

    /**
     * Detects the host (Node.js vs browser worker) and installs JavaScript side I/O hooks.
     * Browser workers must publish two SharedArrayBuffers as
     * {@code globalThis.__lumenLSP_control} and {@code globalThis.__lumenLSP_data} before
     * calling this method.
     */
    @JS("""
            const g = globalThis;
            g.__lumenLSP_isNode = (typeof process !== 'undefined' && !!process.versions && !!process.versions.node);
            if (g.__lumenLSP_isNode) {
                const fs = require('fs');
                g.__lumenLSP_readByte = function() {
                    const buf = Buffer.alloc(1);
                    try {
                        const n = fs.readSync(0, buf, 0, 1, null);
                        if (n <= 0) return -1;
                        return buf[0] & 0xff;
                    } catch (e) {
                        return -1;
                    }
                };
                g.__lumenLSP_writeChunk = function(s) {
                    process.stdout.write(s);
                };
            } else {
                const ctrl = g.__lumenLSP_control;
                const data = g.__lumenLSP_data;
                let lastSignal = 0;
                let cursor = 0;
                let writeEnd = 0;
                g.__lumenLSP_readByte = function() {
                    while (cursor >= writeEnd) {
                        Atomics.wait(ctrl, 2, lastSignal);
                        lastSignal = Atomics.load(ctrl, 2);
                        writeEnd = Atomics.load(ctrl, 0);
                        cursor = Atomics.load(ctrl, 1);
                    }
                    const b = data[cursor] & 0xff;
                    cursor++;
                    Atomics.store(ctrl, 1, cursor);
                    if (cursor >= writeEnd) {
                        cursor = 0;
                        writeEnd = 0;
                        Atomics.store(ctrl, 0, 0);
                        Atomics.store(ctrl, 1, 0);
                    }
                    return b;
                };
                g.__lumenLSP_writeChunk = function(s) {
                    self.postMessage({ type: 'stdout', data: s });
                };
            }
            """)
    public static native void initialize();

    /**
     * Reads one byte from the host stream, blocking until available.
     *
     * @return the byte value in {@code 0..255}, or {@code -1} on end of stream
     */
    @JS.Coerce
    @JS("return globalThis.__lumenLSP_readByte();")
    public static native int readByte();

    /**
     * Writes a UTF-8 string chunk to the host stream.
     *
     * @param chunk text to emit verbatim, no framing applied
     */
    @JS.Coerce
    @JS(args = {"chunk"}, value = "globalThis.__lumenLSP_writeChunk(chunk);")
    public static native void writeChunk(@NotNull String chunk);
}
