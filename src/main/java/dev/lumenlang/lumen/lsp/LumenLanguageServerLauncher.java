package dev.lumenlang.lumen.lsp;

import dev.lumenlang.lumen.lsp.server.LumenLanguageServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.jetbrains.annotations.NotNull;

/**
 * Process entry point for the Lumen language server.
 */
public final class LumenLanguageServerLauncher {

    private LumenLanguageServerLauncher() {
    }

    /**
     * Starts the server on stdin/stdout and blocks until the client disconnects.
     *
     * @param args ignored
     * @throws Exception if launching or listening fails
     */
    public static void main(@NotNull String[] args) throws Exception {
        LumenLanguageServer server = new LumenLanguageServer();
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, System.in, System.out);
        server.connect(launcher.getRemoteProxy());
        launcher.startListening().get();
    }
}
