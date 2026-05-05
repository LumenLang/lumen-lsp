package dev.lumenlang.lumen.lsp;

import dev.lumenlang.lumen.lsp.server.LumenLanguageServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Process entry point for the Lumen language server.
 */
public final class LumenLanguageServerLauncher {

    private LumenLanguageServerLauncher() {
    }

    /**
     * Starts the server and blocks until the client disconnects. Recognises the
     * single {@code --wasm} flag, which routes I/O through the WebAssembly JS
     * bridge and forces every analysis stage onto the request thread.
     *
     * @param args optional flags as described above
     * @throws Exception if launching or listening fails
     */
    public static void main(@NotNull String[] args) throws Exception {
        boolean wasm = false;
        for (String arg : args) {
            if ("--wasm".equals(arg)) wasm = true;
        }

        InputStream in;
        OutputStream out;
        if (wasm) {
            Class<?> bridge = Class.forName("dev.lumenlang.lumen.lsp.wasm.JSBridge");
            bridge.getMethod("initialize").invoke(null);
            in = (InputStream) Class.forName("dev.lumenlang.lumen.lsp.wasm.JSInputStream").getDeclaredConstructor().newInstance();
            out = (OutputStream) Class.forName("dev.lumenlang.lumen.lsp.wasm.JSOutputStream").getDeclaredConstructor().newInstance();
        } else {
            in = System.in;
            out = System.out;
        }

        LumenLanguageServer server = new LumenLanguageServer(wasm);
        Launcher<LanguageClient> launcher = build(server, in, out, wasm);
        server.connect(launcher.getRemoteProxy());
        launcher.startListening().get();
    }

    private static @NotNull Launcher<LanguageClient> build(@NotNull LumenLanguageServer server, @NotNull InputStream in, @NotNull OutputStream out, boolean singleThread) {
        if (!singleThread) {
            return LSPLauncher.createServerLauncher(server, in, out);
        }
        Launcher.Builder<LanguageClient> b = new LSPLauncher.Builder<LanguageClient>()
                .setLocalService(server)
                .setRemoteInterface(LanguageClient.class)
                .setInput(in)
                .setOutput(out)
                .setExecutorService(new DirectExecutorService())
                .wrapMessages((Function) Function.identity());
        return b.create();
    }

    /**
     * {@link ExecutorService} that runs every submitted task on the caller's thread.
     * Required for the WebAssembly target where worker threads are unavailable.
     */
    private static final class DirectExecutorService extends AbstractExecutorService {

        private volatile boolean shutdown;

        @Override
        public void execute(@NotNull Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public @NotNull List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, @NotNull TimeUnit unit) {
            return true;
        }
    }
}
