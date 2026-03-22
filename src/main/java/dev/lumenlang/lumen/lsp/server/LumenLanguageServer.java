package dev.lumenlang.lumen.lsp.server;

import dev.lumenlang.lumen.lsp.documentation.DocumentationData;
import dev.lumenlang.lumen.lsp.documentation.DocumentationDownloader;
import dev.lumenlang.lumen.lsp.documentation.DocumentationLoader;
import dev.lumenlang.lumen.lsp.server.service.LumenTextDocumentService;
import dev.lumenlang.lumen.lsp.server.service.LumenWorkspaceService;
import dev.lumenlang.lumen.lsp.server.tokens.LumenSemanticTokens;
import dev.lumenlang.lumen.lsp.typebindings.BuiltinTypeBindingsRegistrar;
import dev.lumenlang.lumen.lsp.typebindings.util.RegistryBuilder;
import dev.lumenlang.lumen.pipeline.language.pattern.PatternRegistry;
import dev.lumenlang.lumen.pipeline.typebinding.TypeRegistry;
import org.eclipse.lsp4j.ColorProviderOptions;
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
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Core language server implementation for the Lumen language.
 *
 * <p>Handles initialization, capability negotiation, documentation loading,
 * and delegates document operations to {@link LumenTextDocumentService}.
 */
@SuppressWarnings("DataFlowIssue")
public final class LumenLanguageServer implements LanguageServer, LanguageClientAware {

    private final LumenTextDocumentService textDocumentService;
    private final LumenWorkspaceService workspaceService;
    private final @NotNull List<Path> workspaceFolders = new ArrayList<>();
    private @Nullable LanguageClient client;
    private @Nullable PatternRegistry registry;
    private @Nullable TypeRegistry types;
    private @Nullable DocumentationData documentation;

    /**
     * Creates a new language server with its document and workspace services.
     */
    public LumenLanguageServer() {
        this.textDocumentService = new LumenTextDocumentService(this);
        this.workspaceService = new LumenWorkspaceService(this);
    }

    /**
     * Registers server capabilities including completion, hover, semantic tokens,
     * document symbols, go to definition, and color support.
     */
    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        if (params.getWorkspaceFolders() != null) {
            for (var folder : params.getWorkspaceFolders()) {
                workspaceFolders.add(Path.of(URI.create(folder.getUri())));
            }
        }

        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
        capabilities.setCompletionProvider(new CompletionOptions(false, List.of(":", "%", " ", "{", "<")));
        capabilities.setHoverProvider(true);
        capabilities.setDocumentSymbolProvider(true);
        capabilities.setDefinitionProvider(true);
        capabilities.setColorProvider(new ColorProviderOptions());

        SemanticTokensLegend legend = new SemanticTokensLegend(
                LumenSemanticTokens.TOKEN_TYPES,
                LumenSemanticTokens.TOKEN_MODIFIERS
        );
        SemanticTokensWithRegistrationOptions semanticOptions = new SemanticTokensWithRegistrationOptions();
        semanticOptions.setLegend(legend);
        semanticOptions.setFull(true);
        semanticOptions.setRange(false);
        capabilities.setSemanticTokensProvider(semanticOptions);

        return CompletableFuture.completedFuture(new InitializeResult(capabilities));
    }

    /**
     * Triggers documentation loading once the client signals readiness.
     */
    @Override
    public void initialized(InitializedParams params) {
        loadDocumentation();
        if (client != null) {
            client.logMessage(new MessageParams(MessageType.Info, "Lumen LSP initialized"));
        }
    }

    /**
     * Loads the documentation.json from the local cache or workspace, populating
     * the pattern registry and type registry used for completions and diagnostics.
     * After loading, kicks off an async download to check for updates.
     */
    public void loadDocumentation() {
        types = new TypeRegistry();
        registry = new PatternRegistry(types);
        BuiltinTypeBindingsRegistrar.register(registry, types);
        documentation = DocumentationData.EMPTY;

        boolean loaded = loadFromCache();

        for (Path folder : workspaceFolders) {
            File docFile = findDocumentation(folder.toFile());
            if (docFile == null) continue;
            try {
                DocumentationData workspaceDocs = DocumentationLoader.load(docFile.toPath());
                types = new TypeRegistry();
                registry = new PatternRegistry(types);
                BuiltinTypeBindingsRegistrar.register(registry, types);
                documentation = workspaceDocs;
                RegistryBuilder.populate(registry, types, documentation);
                loaded = true;
                if (client != null) {
                    client.logMessage(new MessageParams(
                            MessageType.Info,
                            "Loaded workspace documentation.json with " + documentation.statements().size() + " statements, "
                                    + documentation.expressions().size() + " expressions, "
                                    + documentation.events().size() + " events"
                    ));
                }
                break;
            } catch (Exception e) {
                if (client != null) {
                    client.logMessage(new MessageParams(MessageType.Error, "Failed to load workspace documentation.json: " + e.getMessage()));
                }
            }
        }

        if (!loaded) {
            loaded = forceDownload();
        }

        if (!loaded && client != null) {
            client.logMessage(new MessageParams(
                    MessageType.Warning,
                    "No documentation.json found. Completions and diagnostics will be limited."
            ));
        }

        asyncUpdateDocumentation();
    }

    /**
     * Attempts to load the cached documentation.json from the LSP data directory.
     *
     * @return true if the file was found and loaded successfully
     */
    private boolean loadFromCache() {
        DocumentationData cached = DocumentationDownloader.loadCached(dataDir());
        if (cached == null) return false;
        documentation = cached;
        RegistryBuilder.populate(registry, types, documentation);
        if (client != null) {
            client.logMessage(new MessageParams(
                    MessageType.Info,
                    "Loaded cached documentation.json with " + documentation.statements().size() + " statements, "
                            + documentation.expressions().size() + " expressions, "
                            + documentation.events().size() + " events"
            ));
        }
        return true;
    }

    /**
     * Downloads documentation.json synchronously and loads it immediately.
     *
     * @return true if the download and load succeeded
     */
    private boolean forceDownload() {
        if (client != null) {
            client.logMessage(new MessageParams(MessageType.Info, "No cached documentation found, downloading..."));
        }
        boolean downloaded = DocumentationDownloader.downloadAndUpdate(dataDir());
        if (!downloaded) return false;
        return loadFromCache();
    }

    /**
     * Asynchronously downloads the latest documentation.json and updates the
     * local cache if the content has changed. The update takes effect on the
     * next restart.
     */
    private void asyncUpdateDocumentation() {
        CompletableFuture.runAsync(() -> {
            boolean updated = DocumentationDownloader.downloadAndUpdate(dataDir());
            if (updated && client != null) {
                client.logMessage(new MessageParams(
                        MessageType.Info,
                        "Downloaded updated documentation.json. Changes will take effect after restart."
                ));
            }
        });
    }

    /**
     * Returns the LSP data directory for storing cached files.
     *
     * @return the data directory path
     */
    private @NotNull Path dataDir() {
        return Path.of(System.getProperty("user.home"), ".lumen-lsp");
    }

    /**
     * Recursively searches for a documentation.json file starting at the given root directory.
     *
     * @param root the starting directory
     * @return the documentation file if found, or null
     */
    private @Nullable File findDocumentation(@NotNull File root) {
        File direct = new File(root, "documentation.json");
        if (direct.isFile()) return direct;

        File[] children = root.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.isDirectory()) {
                File found = new File(child, "documentation.json");
                if (found.isFile()) return found;
                File[] grandchildren = child.listFiles();
                if (grandchildren != null) {
                    for (File grandchild : grandchildren) {
                        if (grandchild.isDirectory()) {
                            File deep = new File(grandchild, "documentation.json");
                            if (deep.isFile()) return deep;
                        }
                    }
                }
            }
        }
        return null;
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
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
    }

    /**
     * Returns the connected language client, or null if not yet connected.
     *
     * @return the language client, or null
     */
    public @Nullable LanguageClient client() {
        return client;
    }

    /**
     * Returns the loaded pattern registry, or null if documentation has not been loaded.
     *
     * @return the pattern registry, or null
     */
    public @Nullable PatternRegistry registry() {
        return registry;
    }

    /**
     * Returns the loaded documentation data, or null if not yet loaded.
     *
     * @return the documentation data, or null
     */
    public @Nullable DocumentationData documentation() {
        return documentation;
    }
}
