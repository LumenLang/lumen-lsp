package dev.lumenlang.lumen.lsp.server.service;

import dev.lumenlang.lumen.lsp.server.LumenLanguageServer;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.jetbrains.annotations.NotNull;

/**
 * Handles workspace-level events such as configuration changes
 * and file watching for documentation.json reloads.
 */
public final class LumenWorkspaceService implements WorkspaceService {

    private final LumenLanguageServer server;

    /**
     * Creates a new workspace service backed by the given language server.
     *
     * @param server the parent language server
     */
    public LumenWorkspaceService(@NotNull LumenLanguageServer server) {
        this.server = server;
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
    }

    /**
     * Reloads documentation.json when a relevant file change is detected.
     */
    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        boolean reload = params.getChanges().stream()
                .anyMatch(change -> change.getUri().endsWith("documentation.json")
                        && change.getType() != FileChangeType.Deleted);

        if (reload) {
            server.loadDocumentation();
            if (server.client() != null) {
                server.client().logMessage(new MessageParams(
                        MessageType.Info,
                        "Reloaded documentation.json"
                ));
            }
        }
    }
}
