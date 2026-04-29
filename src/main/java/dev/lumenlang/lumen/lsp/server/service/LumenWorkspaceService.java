package dev.lumenlang.lumen.lsp.server.service;

import dev.lumenlang.lumen.lsp.server.LumenLanguageServer;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.jetbrains.annotations.NotNull;

/**
 * Workspace level service. Stub for now, retained so the server can register it.
 */
public final class LumenWorkspaceService implements WorkspaceService {

    private final @NotNull LumenLanguageServer server;

    /**
     * @param server the parent server
     */
    public LumenWorkspaceService(@NotNull LumenLanguageServer server) {
        this.server = server;
    }

    @Override
    public void didChangeConfiguration(@NotNull DidChangeConfigurationParams params) {
    }

    @Override
    public void didChangeWatchedFiles(@NotNull DidChangeWatchedFilesParams params) {
    }
}
