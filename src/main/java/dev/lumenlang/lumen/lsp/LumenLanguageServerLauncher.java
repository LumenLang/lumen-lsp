package dev.lumenlang.lumen.lsp;

import dev.lumenlang.lumen.lsp.server.LumenLanguageServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Entry point for the Lumen Language Server.
 */
public final class LumenLanguageServerLauncher {

    private LumenLanguageServerLauncher() {
    }

    /**
     * Launches the language server.
     *
     * @param args unused
     * @throws Exception if the launcher fails to start or the listening future is interrupted
     */
    public static void main(String[] args) throws Exception {
        InputStream in = System.in;
        OutputStream out = System.out;

        boolean noErrors = false;
        for (String arg : args) {
            if (arg.equals("--no-errors")) noErrors = true;
        }

        LumenLanguageServer server = new LumenLanguageServer(noErrors);
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, in, out);
        server.connect(launcher.getRemoteProxy());
        launcher.startListening().get();
    }
}
