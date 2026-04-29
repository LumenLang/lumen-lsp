package dev.lumenlang.lumen.lsp.server;

import dev.lumenlang.lumen.lsp.analysis.DocumentAnalyzer;
import dev.lumenlang.lumen.lsp.analysis.DocumentStore;
import dev.lumenlang.lumen.lsp.bootstrap.LumenBootstrap;
import dev.lumenlang.lumen.lsp.server.service.LumenTextDocumentService;
import dev.lumenlang.lumen.lsp.server.service.LumenWorkspaceService;
import dev.lumenlang.lumen.lsp.providers.semantic.SemanticLegend;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;

import java.util.List;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Top level Lumen language server.
 */
public final class LumenLanguageServer implements LanguageServer, LanguageClientAware {

    private final @NotNull DocumentStore store = new DocumentStore();
    private final @NotNull LumenTextDocumentService textDocumentService;
    private final @NotNull LumenWorkspaceService workspaceService;
    private @Nullable LanguageClient client;
    private @Nullable DocumentAnalyzer analyzer;
    private @Nullable LumenBootstrap bootstrap;

    /**
     * Creates a new server, deferring registry init until {@link #initialize}.
     */
    public LumenLanguageServer() {
        this.textDocumentService = new LumenTextDocumentService(this);
        this.workspaceService = new LumenWorkspaceService(this);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(@NotNull InitializeParams params) {
        bootstrap = LumenBootstrap.get();
        analyzer = new DocumentAnalyzer(bootstrap);
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental);
        capabilities.setHoverProvider(true);
        capabilities.setDefinitionProvider(true);
        capabilities.setDocumentSymbolProvider(true);
        capabilities.setInlayHintProvider(true);
        capabilities.setCompletionProvider(new CompletionOptions(false, List.of(" ", "\n")));
        SemanticTokensWithRegistrationOptions semantic = new SemanticTokensWithRegistrationOptions();
        semantic.setLegend(new SemanticTokensLegend(SemanticLegend.TYPES, SemanticLegend.MODIFIERS));
        semantic.setFull(true);
        semantic.setRange(false);
        capabilities.setSemanticTokensProvider(semantic);
        return CompletableFuture.completedFuture(new InitializeResult(capabilities));
    }

    @Override
    public void initialized(@NotNull InitializedParams params) {
        if (client != null) {
            client.logMessage(new MessageParams(MessageType.Info, "Lumen LSP ready"));
        }
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        System.exit(0);
    }

    @Override
    public @NotNull TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public @NotNull WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    @Override
    public void connect(@NotNull LanguageClient client) {
        this.client = client;
    }

    /**
     * Returns the connected client, or {@code null} before connect was called.
     *
     * @return the client
     */
    public @Nullable LanguageClient client() {
        return client;
    }

    /**
     * Returns the analyser, or {@code null} before initialize was called.
     *
     * @return the analyser
     */
    public @Nullable DocumentAnalyzer analyzer() {
        return analyzer;
    }

    /**
     * Returns the document store.
     *
     * @return the store
     */
    public @NotNull DocumentStore store() {
        return store;
    }

    /**
     * Returns the bootstrap, or {@code null} before initialize was called.
     *
     * @return the bootstrap
     */
    public @Nullable LumenBootstrap bootstrap() {
        return bootstrap;
    }
}
