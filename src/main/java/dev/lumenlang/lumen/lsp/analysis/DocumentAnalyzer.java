package dev.lumenlang.lumen.lsp.analysis;

import dev.lumenlang.lumen.api.diagnostic.DiagnosticException;
import dev.lumenlang.lumen.api.diagnostic.LumenDiagnostic;
import dev.lumenlang.lumen.api.emit.BlockEnterHook;
import dev.lumenlang.lumen.api.emit.BlockFormHandler;
import dev.lumenlang.lumen.api.emit.ScriptLine;
import dev.lumenlang.lumen.lsp.analysis.util.SimpleScriptLine;
import dev.lumenlang.lumen.lsp.bootstrap.LumenBootstrap;
import dev.lumenlang.lumen.pipeline.codegen.BlockContextImpl;
import dev.lumenlang.lumen.pipeline.codegen.CodegenContextImpl;
import dev.lumenlang.lumen.pipeline.codegen.HandlerContextImpl;
import dev.lumenlang.lumen.pipeline.codegen.TypeEnvImpl;
import dev.lumenlang.lumen.pipeline.codegen.output.NoOpJavaOutput;
import dev.lumenlang.lumen.pipeline.codegen.source.SourceMapImpl;
import dev.lumenlang.lumen.pipeline.language.incremental.MatchCache;
import dev.lumenlang.lumen.pipeline.language.incremental.ScriptMatchCache;
import dev.lumenlang.lumen.pipeline.language.nodes.BlockNode;
import dev.lumenlang.lumen.pipeline.language.nodes.Node;
import dev.lumenlang.lumen.pipeline.language.nodes.RawBlockNode;
import dev.lumenlang.lumen.pipeline.language.nodes.StatementNode;
import dev.lumenlang.lumen.pipeline.language.parse.LumenParser;
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredBlockMatch;
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredPatternMatch;
import dev.lumenlang.lumen.pipeline.language.simulator.PatternSimulator;
import dev.lumenlang.lumen.pipeline.language.simulator.suggestions.SuggestionDiagnostics;
import dev.lumenlang.lumen.pipeline.language.tokenization.Line;
import dev.lumenlang.lumen.pipeline.language.tokenization.Token;
import dev.lumenlang.lumen.pipeline.language.tokenization.Tokenizer;
import dev.lumenlang.lumen.pipeline.language.typed.TypedStatement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks a Lumen document and produces a per line analysis snapshot together with
 * diagnostics. Pattern match results are routed through a {@link ScriptMatchCache}
 * so unchanged AST nodes skip pattern simulation between edits.
 */
public final class DocumentAnalyzer {

    private final @NotNull LumenBootstrap bootstrap;
    private final @NotNull Map<String, ScriptMatchCache> caches = new HashMap<>();

    /**
     * Creates a new analyser bound to the given bootstrap. The {@code singleThread}
     * argument is accepted for ABI compatibility and ignored, since the analyser
     * is now serial regardless of host.
     *
     * @param bootstrap    the populated bootstrap holding the registries
     * @param singleThread retained for the launcher's benefit
     */
    public DocumentAnalyzer(@NotNull LumenBootstrap bootstrap, @SuppressWarnings("unused") boolean singleThread) {
        this.bootstrap = bootstrap;
    }

    /**
     * Tokenises, parses, and walks the source under a per document match cache.
     *
     * @param uri    the document URI, used to scope the match cache
     * @param source the document text
     * @return the analysis result
     */
    public @NotNull AnalysisResult analyze(@NotNull String uri, @NotNull String source) {
        ScriptMatchCache cache = caches.computeIfAbsent(uri, k -> new ScriptMatchCache());
        List<Line> tokenizedLines = new Tokenizer().tokenize(source);
        BlockNode root = new LumenParser().parse(tokenizedLines);
        cache.beginParse(root);
        Map<Integer, Integer> indentByLine = indentMap(source);
        CodegenContextImpl ctx = new CodegenContextImpl(uriToScriptName(uri));
        ctx.setRawJavaEnabled(true);
        TypeEnvImpl env = new TypeEnvImpl();
        env.setSourceMap(new SourceMapImpl(source, tokenizedLines));
        List<LumenDiagnostic> diagnostics = new ArrayList<>();
        List<LineAnalysis> analyses = new ArrayList<>();
        try {
            for (int i = 0; i < root.children().size(); i++) {
                walkOne(root.children().get(i), null, root.children(), i, env, ctx, cache, diagnostics, analyses, indentByLine);
            }
        } finally {
            cache.commit(root);
        }
        analyses.sort(Comparator.comparingInt(LineAnalysis::lineNumber));
        diagnostics.sort(Comparator.comparingInt(LumenDiagnostic::line));
        return new AnalysisResult(uri, source, List.copyOf(diagnostics), List.copyOf(analyses));
    }

    /**
     * Drops the cached match table for the given document. Called when a client
     * closes the document so memory is reclaimed.
     *
     * @param uri the document URI
     */
    public void forget(@NotNull String uri) {
        caches.remove(uri);
    }

    private void walkOne(@NotNull Node child, @Nullable BlockContextImpl parentBlock, @NotNull List<Node> siblings, int index, @NotNull TypeEnvImpl env, @NotNull CodegenContextImpl ctx, @NotNull MatchCache cache, @NotNull List<LumenDiagnostic> diagnostics, @NotNull List<LineAnalysis> analyses, @NotNull Map<Integer, Integer> indentByLine) {
        if (child instanceof RawBlockNode) return;
        if (child instanceof BlockNode block) {
            analyzeBlock(block, parentBlock, siblings, index, env, ctx, cache, diagnostics, analyses, indentByLine);
        } else if (child instanceof StatementNode stmt) {
            analyzeStatement(stmt, parentBlock, siblings, index, env, ctx, cache, diagnostics, analyses, indentByLine);
        }
    }

    private void analyzeStatement(@NotNull StatementNode stmt, @Nullable BlockContextImpl parentBlock, @NotNull List<Node> siblings, int index, @NotNull TypeEnvImpl env, @NotNull CodegenContextImpl ctx, @NotNull MatchCache cache, @NotNull List<LumenDiagnostic> diagnostics, @NotNull List<LineAnalysis> analyses, @NotNull Map<Integer, Integer> indentByLine) {
        List<Token> tokens = stmt.head();
        if (tokens.isEmpty()) return;
        int indent = indentByLine.getOrDefault(stmt.line(), 0);
        TypeEnvImpl before = env.deepClone();
        BlockContextImpl block = parentBlock != null ? parentBlock : new BlockContextImpl(stmt, null, siblings, index);
        try {
            RegisteredPatternMatch match = matchStatement(stmt, env, cache);
            if (match != null) {
                HandlerContextImpl hctx = new HandlerContextImpl(match.match(), env, ctx, block, NoOpJavaOutput.INSTANCE);
                match.reg().handler().handle(hctx);
                Map<String, Object> meta = new HashMap<>();
                meta.put(MetaKeys.STATEMENT_MATCH, match);
                stashVarDecl(meta, match);
                analyses.add(new LineAnalysis(stmt.line(), indent, tokens, null, before, env.deepClone(), meta));
                return;
            }
            List<PatternSimulator.Suggestion> suggestions = PatternSimulator.suggestStatementsAndExpressions(tokens, bootstrap.patterns(), env);
            LumenDiagnostic diag = buildUnknownDiagnostic("Unknown statement", stmt.line(), stmt.raw(), tokens, suggestions, env);
            diagnostics.add(diag);
            Map<String, Object> meta = new HashMap<>();
            if (!suggestions.isEmpty()) meta.put(MetaKeys.SUGGESTIONS, suggestions);
            analyses.add(new LineAnalysis(stmt.line(), indent, tokens, diag, before, null, meta));
        } catch (DiagnosticException e) {
            env.restoreFrom(before);
            diagnostics.add(e.diagnostic());
            analyses.add(new LineAnalysis(stmt.line(), indent, tokens, e.diagnostic(), before, null, new HashMap<>()));
        } catch (RuntimeException e) {
            env.restoreFrom(before);
            LumenDiagnostic diag = LumenDiagnostic.error(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                    .at(stmt.line(), stmt.raw())
                    .build();
            diagnostics.add(diag);
            analyses.add(new LineAnalysis(stmt.line(), indent, tokens, diag, before, null, new HashMap<>()));
        }
    }

    private void analyzeBlock(@NotNull BlockNode node, @Nullable BlockContextImpl parentBlock, @NotNull List<Node> siblings, int index, @NotNull TypeEnvImpl env, @NotNull CodegenContextImpl ctx, @NotNull MatchCache cache, @NotNull List<LumenDiagnostic> diagnostics, @NotNull List<LineAnalysis> analyses, @NotNull Map<Integer, Integer> indentByLine) {
        List<Token> head = node.head();
        int indent = indentByLine.getOrDefault(node.line(), 0);
        TypeEnvImpl before = env.deepClone();
        BlockContextImpl block = new BlockContextImpl(node, parentBlock, siblings, index);
        BlockFormHandler form = matchBlockForm(head);
        if (form != null) {
            handleBlockForm(form, node, head, indent, env, ctx, cache, diagnostics, analyses, before, indentByLine);
            return;
        }
        boolean entered = false;
        try {
            RegisteredBlockMatch match = matchBlock(node, env, cache);
            if (match != null) {
                HandlerContextImpl hctx = new HandlerContextImpl(match.match(), env, ctx, block, NoOpJavaOutput.INSTANCE);
                env.enterBlock(block);
                entered = true;
                match.reg().handler().begin(hctx);
                runBlockEnterHooks(env, ctx, block);
                Map<String, Object> meta = new HashMap<>();
                meta.put(MetaKeys.BLOCK_MATCH, match);
                analyses.add(new LineAnalysis(node.line(), indent, head, null, before, env.deepClone(), meta));
                List<Node> children = node.children();
                for (int i = 0; i < children.size(); i++) {
                    walkOne(children.get(i), block, children, i, env, ctx, cache, diagnostics, analyses, indentByLine);
                }
                match.reg().handler().end(hctx);
                env.leaveBlock();
                return;
            }
            List<PatternSimulator.Suggestion> suggestions = PatternSimulator.suggestBlocks(head, bootstrap.patterns(), env);
            LumenDiagnostic diag = buildUnknownDiagnostic("Unknown block", node.line(), node.raw(), head, suggestions, env);
            diagnostics.add(diag);
            Map<String, Object> meta = new HashMap<>();
            if (!suggestions.isEmpty()) meta.put(MetaKeys.SUGGESTIONS, suggestions);
            analyses.add(new LineAnalysis(node.line(), indent, head, diag, before, null, meta));
            List<Node> children = node.children();
            for (int i = 0; i < children.size(); i++) {
                walkOne(children.get(i), block, children, i, env, ctx, cache, diagnostics, analyses, indentByLine);
            }
        } catch (DiagnosticException e) {
            if (entered) env.leaveBlock();
            env.restoreFrom(before);
            diagnostics.add(e.diagnostic());
            analyses.add(new LineAnalysis(node.line(), indent, head, e.diagnostic(), before, null, new HashMap<>()));
        } catch (RuntimeException e) {
            if (entered) env.leaveBlock();
            env.restoreFrom(before);
            LumenDiagnostic diag = LumenDiagnostic.error(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                    .at(node.line(), node.raw())
                    .build();
            diagnostics.add(diag);
            analyses.add(new LineAnalysis(node.line(), indent, head, diag, before, null, new HashMap<>()));
        }
    }

    private @Nullable RegisteredPatternMatch matchStatement(@NotNull StatementNode stmt, @NotNull TypeEnvImpl env, @NotNull MatchCache cache) {
        TypedStatement cached = cache.statement(stmt);
        if (cached instanceof TypedStatement.PatternStmt p) {
            return p.match();
        }
        RegisteredPatternMatch match = bootstrap.patterns().matchStatement(stmt.head(), env);
        if (match != null) cache.putStatement(stmt, new TypedStatement.PatternStmt(stmt, match));
        return match;
    }

    private @Nullable RegisteredBlockMatch matchBlock(@NotNull BlockNode node, @NotNull TypeEnvImpl env, @NotNull MatchCache cache) {
        RegisteredBlockMatch cached = cache.block(node);
        if (cached != null) return cached;
        if (node.head().isEmpty()) return null;
        RegisteredBlockMatch match = bootstrap.patterns().matchBlock(node.head(), env);
        if (match != null) cache.putBlock(node, match);
        return match;
    }

    private @Nullable BlockFormHandler matchBlockForm(@NotNull List<Token> head) {
        if (head.isEmpty()) return null;
        for (BlockFormHandler handler : bootstrap.emit().blockForms()) {
            if (handler.matches(head)) return handler;
        }
        return null;
    }

    private void handleBlockForm(@NotNull BlockFormHandler form, @NotNull BlockNode node, @NotNull List<Token> head, int indent, @NotNull TypeEnvImpl env, @NotNull CodegenContextImpl ctx, @NotNull MatchCache cache, @NotNull List<LumenDiagnostic> diagnostics, @NotNull List<LineAnalysis> analyses, @NotNull TypeEnvImpl snapshot, @NotNull Map<Integer, Integer> indentByLine) {
        List<ScriptLine> children = new ArrayList<>(node.children().size());
        for (Node child : node.children()) {
            children.add(new SimpleScriptLine(child));
        }
        BlockContextImpl block = new BlockContextImpl(node, null, List.of(node), 0);
        HandlerContextImpl hctx = new HandlerContextImpl(null, env, ctx, block, NoOpJavaOutput.INSTANCE);
        env.enterBlock(block);
        try {
            form.handle(head, children, hctx);
            Map<String, Object> meta = new HashMap<>();
            meta.put(MetaKeys.BLOCK_FORM_NAME, head.get(0).text());
            analyses.add(new LineAnalysis(node.line(), indent, head, null, snapshot, env.deepClone(), meta));
            recordChildren(node, env, snapshot, analyses, indentByLine);
        } catch (DiagnosticException e) {
            env.restoreFrom(snapshot);
            diagnostics.add(e.diagnostic());
            analyses.add(new LineAnalysis(node.line(), indent, head, e.diagnostic(), snapshot, null, new HashMap<>()));
            recordChildren(node, env, snapshot, analyses, indentByLine);
        } catch (RuntimeException e) {
            env.restoreFrom(snapshot);
            LumenDiagnostic diag = LumenDiagnostic.error(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                    .at(node.line(), node.raw())
                    .build();
            diagnostics.add(diag);
            analyses.add(new LineAnalysis(node.line(), indent, head, diag, snapshot, null, new HashMap<>()));
            recordChildren(node, env, snapshot, analyses, indentByLine);
        } finally {
            env.leaveBlock();
        }
    }

    private void recordChildren(@NotNull BlockNode node, @NotNull TypeEnvImpl env, @NotNull TypeEnvImpl before, @NotNull List<LineAnalysis> analyses, @NotNull Map<Integer, Integer> indentByLine) {
        for (Node child : node.children()) {
            if (!(child instanceof StatementNode stmt)) continue;
            List<Token> tokens = stmt.head();
            if (tokens.isEmpty()) continue;
            int childIndent = indentByLine.getOrDefault(stmt.line(), 0);
            analyses.add(new LineAnalysis(stmt.line(), childIndent, tokens, null, before, env.deepClone(), new HashMap<>()));
        }
    }

    private void runBlockEnterHooks(@NotNull TypeEnvImpl env, @NotNull CodegenContextImpl ctx, @NotNull BlockContextImpl block) {
        HandlerContextImpl hookCtx = new HandlerContextImpl(null, env, ctx, block, NoOpJavaOutput.INSTANCE);
        for (BlockEnterHook hook : bootstrap.emit().blockEnterHooks()) {
            try {
                hook.onBlockEnter(hookCtx);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private @NotNull LumenDiagnostic buildUnknownDiagnostic(@NotNull String title, int line, @NotNull String raw, @NotNull List<Token> tokens, @NotNull List<PatternSimulator.Suggestion> suggestions, @NotNull TypeEnvImpl env) {
        if (suggestions.isEmpty()) {
            return LumenDiagnostic.error(title)
                    .at(line, raw)
                    .highlight(tokens.get(0).start(), tokens.get(tokens.size() - 1).end())
                    .build();
        }
        return SuggestionDiagnostics.build(title, line, raw, tokens, suggestions, env);
    }

    private void stashVarDecl(@NotNull Map<String, Object> meta, @NotNull RegisteredPatternMatch match) {
        if (!"set %name:IDENT% to %val:EXPR%".equals(match.reg().pattern().raw())) return;
        var nameBound = match.match().values().get("name");
        if (nameBound == null || nameBound.tokens().isEmpty()) return;
        Token nameToken = nameBound.tokens().get(0);
        meta.put(MetaKeys.VAR_DECL_NAME, nameToken.text());
        meta.put(MetaKeys.VAR_DECL_RANGE, new int[]{nameToken.start(), nameToken.end()});
    }

    private @NotNull Map<Integer, Integer> indentMap(@NotNull String source) {
        String[] split = source.split("\\r?\\n", -1);
        Map<Integer, Integer> out = new HashMap<>();
        for (int i = 0; i < split.length; i++) {
            String line = split[i];
            int p = 0;
            while (p < line.length() && (line.charAt(p) == ' ' || line.charAt(p) == '\t')) p++;
            out.put(i + 1, p);
        }
        return out;
    }

    private @NotNull String uriToScriptName(@NotNull String uri) {
        int slash = uri.lastIndexOf('/');
        String name = slash >= 0 ? uri.substring(slash + 1) : uri;
        if (!name.endsWith(".luma")) name = name + ".luma";
        return name;
    }
}
