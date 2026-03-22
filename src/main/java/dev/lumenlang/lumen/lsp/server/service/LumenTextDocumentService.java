package dev.lumenlang.lumen.lsp.server.service;

import dev.lumenlang.lumen.lsp.analysis.AnalysisResult;
import dev.lumenlang.lumen.lsp.analysis.DocumentAnalyzer;
import dev.lumenlang.lumen.lsp.diagnostic.DiagnosticPublisher;
import dev.lumenlang.lumen.lsp.documentation.DocumentationData;
import dev.lumenlang.lumen.lsp.providers.CompletionProvider;
import dev.lumenlang.lumen.lsp.providers.DefinitionProvider;
import dev.lumenlang.lumen.lsp.providers.DocumentColorProvider;
import dev.lumenlang.lumen.lsp.providers.DocumentSymbolProvider;
import dev.lumenlang.lumen.lsp.providers.HoverProvider;
import dev.lumenlang.lumen.lsp.providers.SemanticTokenProvider;
import dev.lumenlang.lumen.lsp.server.LumenLanguageServer;
import dev.lumenlang.lumen.pipeline.language.pattern.PatternRegistry;
import dev.lumenlang.lumen.pipeline.typebinding.TypeRegistry;
import org.eclipse.lsp4j.ColorInformation;
import org.eclipse.lsp4j.ColorPresentation;
import org.eclipse.lsp4j.ColorPresentationParams;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentColorParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles all text document events and dispatches to specialized providers.
 */
public final class LumenTextDocumentService implements TextDocumentService {

    private final LumenLanguageServer server;
    private final DocumentAnalyzer analyzer = new DocumentAnalyzer();
    private final Map<String, String> openDocuments = new ConcurrentHashMap<>();
    private final Map<String, AnalysisResult> analysisCache = new ConcurrentHashMap<>();

    private final CompletionProvider completionProvider;
    private final HoverProvider hoverProvider;
    private final SemanticTokenProvider semanticTokenProvider;
    private final DocumentSymbolProvider documentSymbolProvider;
    private final DefinitionProvider definitionProvider;
    private final DocumentColorProvider documentColorProvider;
    private final DiagnosticPublisher diagnosticPublisher;

    /**
     * Creates a new text document service backed by the given language server.
     *
     * @param server the parent language server providing registries and client access
     */
    public LumenTextDocumentService(@NotNull LumenLanguageServer server) {
        this.server = server;
        this.completionProvider = new CompletionProvider();
        this.hoverProvider = new HoverProvider();
        this.semanticTokenProvider = new SemanticTokenProvider();
        this.documentSymbolProvider = new DocumentSymbolProvider();
        this.definitionProvider = new DefinitionProvider();
        this.documentColorProvider = new DocumentColorProvider();
        this.diagnosticPublisher = new DiagnosticPublisher();
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        String text = params.getTextDocument().getText();
        openDocuments.put(uri, text);
        analyzeAndPublish(uri, text);
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        List<TextDocumentContentChangeEvent> changes = params.getContentChanges();
        if (!changes.isEmpty()) {
            String text = changes.get(changes.size() - 1).getText();
            openDocuments.put(uri, text);
            analyzeAndPublish(uri, text);
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        openDocuments.remove(uri);
        analysisCache.remove(uri);
        diagnosticPublisher.clear(server, uri);
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            String source = openDocuments.get(uri);
            DocumentationData docs = server.documentation();
            AnalysisResult analysis = analysisCache.get(uri);
            if (docs == null) docs = DocumentationData.EMPTY;

            return Either.forLeft(completionProvider.complete(params, source, docs, analysis));
        });
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            AnalysisResult analysis = analysisCache.get(uri);
            DocumentationData docs = server.documentation();
            String source = openDocuments.get(uri);
            if (docs == null) docs = DocumentationData.EMPTY;
            return hoverProvider.hover(params, analysis, docs, source);
        });
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            AnalysisResult analysis = analysisCache.get(uri);
            String source = openDocuments.get(uri);
            DocumentationData docs = server.documentation();
            if (docs == null) docs = DocumentationData.EMPTY;
            return semanticTokenProvider.tokens(source, analysis, docs);
        });
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        return CompletableFuture.supplyAsync(() -> {
            AnalysisResult analysis = analysisCache.get(params.getTextDocument().getUri());
            DocumentationData docs = server.documentation();
            if (docs == null) docs = DocumentationData.EMPTY;
            return documentSymbolProvider.symbols(analysis, docs);
        });
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            AnalysisResult analysis = analysisCache.get(uri);
            String source = openDocuments.get(uri);
            return Either.forLeft(definitionProvider.definition(params, analysis, source, uri));
        });
    }

    @Override
    public CompletableFuture<List<ColorInformation>> documentColor(DocumentColorParams params) {
        return CompletableFuture.supplyAsync(() -> documentColorProvider.colors(openDocuments.get(params.getTextDocument().getUri())));
    }

    @Override
    public CompletableFuture<List<ColorPresentation>> colorPresentation(ColorPresentationParams params) {
        return CompletableFuture.supplyAsync(() -> documentColorProvider.presentations(params));
    }

    /**
     * Analyzes the given document source and publishes the resulting diagnostics to the client.
     *
     * @param uri    the document URI
     * @param source the full document text
     */
    private void analyzeAndPublish(@NotNull String uri, @NotNull String source) {
        PatternRegistry registry = server.registry();
        DocumentationData docs = server.documentation();
        if (registry == null) {
            registry = new PatternRegistry(new TypeRegistry());
        }
        if (docs == null) {
            docs = DocumentationData.EMPTY;
        }

        try {
            AnalysisResult result = analyzer.analyze(source, registry, docs);
            analysisCache.put(uri, result);
            diagnosticPublisher.publish(server, uri, result);
        } catch (Exception e) {
            if (server.client() != null) {
                server.client().logMessage(new MessageParams(
                        MessageType.Error,
                        "Analysis failed: " + e.getMessage()
                ));
            }
        }
    }
}
