package dev.lumenlang.lumen.lsp.server.service;

import dev.lumenlang.lumen.lsp.analysis.AnalysisResult;
import dev.lumenlang.lumen.lsp.analysis.DocumentAnalyzer;
import dev.lumenlang.lumen.lsp.analysis.DocumentStore;
import dev.lumenlang.lumen.lsp.bootstrap.LumenBootstrap;
import dev.lumenlang.lumen.lsp.diagnostic.DiagnosticPublisher;
import dev.lumenlang.lumen.lsp.providers.completion.CompletionProvider;
import dev.lumenlang.lumen.lsp.providers.definition.DefinitionProvider;
import dev.lumenlang.lumen.lsp.providers.hover.HoverProvider;
import dev.lumenlang.lumen.lsp.providers.inlay.InlayHintProvider;
import dev.lumenlang.lumen.lsp.providers.semantic.SemanticTokenProvider;
import dev.lumenlang.lumen.lsp.providers.symbol.DocumentSymbolProvider;
import dev.lumenlang.lumen.lsp.server.LumenLanguageServer;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Routes textDocument/* requests, keeping the store in sync and re-running the
 * analyser on every change.
 */
public final class LumenTextDocumentService implements TextDocumentService {

    private final @NotNull LumenLanguageServer server;

    /**
     * @param server the parent server
     */
    public LumenTextDocumentService(@NotNull LumenLanguageServer server) {
        this.server = server;
    }

    @Override
    public void didOpen(@NotNull DidOpenTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        String text = params.getTextDocument().getText();
        server.store().text(uri, text);
        analyzeAndPublish(uri, text, null);
    }

    @Override
    public void didChange(@NotNull DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        List<TextDocumentContentChangeEvent> changes = params.getContentChanges();
        if (changes.isEmpty()) return;
        String text = server.store().text(uri);
        if (text == null) text = "";
        Integer editLine = null;
        for (TextDocumentContentChangeEvent change : changes) {
            if (change.getRange() == null) {
                text = change.getText();
                editLine = null;
            } else {
                int start = offsetOf(text, change.getRange().getStart().getLine(), change.getRange().getStart().getCharacter());
                int end = offsetOf(text, change.getRange().getEnd().getLine(), change.getRange().getEnd().getCharacter());
                text = text.substring(0, start) + change.getText() + text.substring(end);
                int candidate = change.getRange().getStart().getLine() + 1;
                if (editLine == null || candidate < editLine) editLine = candidate;
            }
        }
        server.store().text(uri, text);
        analyzeAndPublish(uri, text, editLine);
    }

    /**
     * Returns the absolute character offset for the given 0-based line and
     * column inside the source text, clamping to the text length when the
     * position runs past the document end.
     *
     * @param text the document text
     * @param line the 0-based line
     * @param col  the 0-based column
     * @return the offset
     */
    private int offsetOf(@NotNull String text, int line, int col) {
        int offset = 0;
        int currentLine = 0;
        while (currentLine < line && offset < text.length()) {
            int next = text.indexOf('\n', offset);
            if (next < 0) return text.length();
            offset = next + 1;
            currentLine++;
        }
        int lineEnd = text.indexOf('\n', offset);
        if (lineEnd < 0) lineEnd = text.length();
        int safeCol = Math.min(col, lineEnd - offset);
        return offset + Math.max(0, safeCol);
    }

    @Override
    public void didClose(@NotNull DidCloseTextDocumentParams params) {
        server.store().close(params.getTextDocument().getUri());
    }

    @Override
    public void didSave(@NotNull DidSaveTextDocumentParams params) {
    }

    @Override
    public CompletableFuture<Hover> hover(@NotNull HoverParams params) {
        String uri = params.getTextDocument().getUri();
        AnalysisResult analysis = server.store().analysis(uri);
        LumenBootstrap bootstrap = server.bootstrap();
        if (analysis == null || bootstrap == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.completedFuture(HoverProvider.hover(bootstrap, analysis, params.getPosition()));
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(@NotNull CompletionParams params) {
        String uri = params.getTextDocument().getUri();
        AnalysisResult analysis = server.store().analysis(uri);
        String source = server.store().text(uri);
        LumenBootstrap bootstrap = server.bootstrap();
        if (analysis == null || source == null || bootstrap == null) {
            return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
        }
        List<CompletionItem> items = CompletionProvider.complete(bootstrap, analysis, source, params.getPosition());
        return CompletableFuture.completedFuture(Either.forLeft(items));
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(@NotNull DefinitionParams params) {
        String uri = params.getTextDocument().getUri();
        AnalysisResult analysis = server.store().analysis(uri);
        if (analysis == null) return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
        List<Location> locs = DefinitionProvider.definition(uri, analysis, params.getPosition());
        return CompletableFuture.completedFuture(Either.forLeft(locs));
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(@NotNull DocumentSymbolParams params) {
        String uri = params.getTextDocument().getUri();
        AnalysisResult analysis = server.store().analysis(uri);
        if (analysis == null) return CompletableFuture.completedFuture(Collections.emptyList());
        List<DocumentSymbol> symbols = DocumentSymbolProvider.symbols(analysis);
        List<Either<SymbolInformation, DocumentSymbol>> out = new ArrayList<>(symbols.size());
        for (DocumentSymbol s : symbols) out.add(Either.forRight(s));
        return CompletableFuture.completedFuture(out);
    }

    @Override
    public CompletableFuture<List<InlayHint>> inlayHint(@NotNull InlayHintParams params) {
        String uri = params.getTextDocument().getUri();
        AnalysisResult analysis = server.store().analysis(uri);
        if (analysis == null) return CompletableFuture.completedFuture(Collections.emptyList());
        return CompletableFuture.completedFuture(InlayHintProvider.hints(analysis, params.getRange()));
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(@NotNull SemanticTokensParams params) {
        String uri = params.getTextDocument().getUri();
        AnalysisResult analysis = server.store().analysis(uri);
        if (analysis == null) return CompletableFuture.completedFuture(new SemanticTokens(List.of()));
        return CompletableFuture.completedFuture(SemanticTokenProvider.tokens(analysis));
    }

    /**
     * Runs the analyser for the given document and publishes resulting
     * diagnostics, scoping the reparse to the edit line when one is supplied
     * so untouched blocks reuse their cached analyses.
     *
     * @param uri      the document URI
     * @param text     the document text
     * @param editLine the 1-based source line that changed, or {@code null} for a full reparse
     */
    private void analyzeAndPublish(@NotNull String uri, @NotNull String text, @Nullable Integer editLine) {
        DocumentAnalyzer analyzer = server.analyzer();
        LanguageClient client = server.client();
        if (analyzer == null || client == null) return;
        DocumentStore store = server.store();
        AnalysisResult prior = store.analysis(uri);
        AnalysisResult result = analyzer.analyzeIncremental(uri, text, editLine, prior);
        store.analysis(uri, result);
        DiagnosticPublisher.publish(client, result);
    }
}
