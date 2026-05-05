package dev.lumenlang.lumen.lsp.analysis;

import dev.lumenlang.lumen.api.diagnostic.DiagnosticException;
import dev.lumenlang.lumen.api.diagnostic.LumenDiagnostic;
import dev.lumenlang.lumen.api.emit.BlockEnterHook;
import dev.lumenlang.lumen.api.emit.BlockFormHandler;
import dev.lumenlang.lumen.api.emit.ScriptLine;
import dev.lumenlang.lumen.lsp.analysis.util.EnvCopier;
import dev.lumenlang.lumen.lsp.analysis.util.NoopJavaOutput;
import dev.lumenlang.lumen.lsp.analysis.util.SimpleScriptLine;
import dev.lumenlang.lumen.lsp.bootstrap.LumenBootstrap;
import dev.lumenlang.lumen.pipeline.codegen.BlockContext;
import dev.lumenlang.lumen.pipeline.codegen.CodegenContext;
import dev.lumenlang.lumen.pipeline.codegen.HandlerContextImpl;
import dev.lumenlang.lumen.pipeline.codegen.TypeEnv;
import dev.lumenlang.lumen.pipeline.language.nodes.BlockNode;
import dev.lumenlang.lumen.pipeline.language.nodes.Node;
import dev.lumenlang.lumen.pipeline.language.nodes.RawBlockNode;
import dev.lumenlang.lumen.pipeline.language.nodes.StatementNode;
import dev.lumenlang.lumen.pipeline.language.parse.LumenParser;
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredBlockMatch;
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredPatternMatch;
import dev.lumenlang.lumen.pipeline.language.resolve.PatternSimulator;
import dev.lumenlang.lumen.pipeline.language.resolve.SuggestionDiagnostics;
import dev.lumenlang.lumen.pipeline.language.tokenization.Line;
import dev.lumenlang.lumen.pipeline.language.tokenization.Token;
import dev.lumenlang.lumen.pipeline.language.tokenization.Tokenizer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Walks a Lumen document and produces a per line analysis snapshot together
 * with diagnostics. Supports both a full parse and an incremental reparse
 * scoped to the innermost enclosing block of an edit.
 *
 * <p>Top level work splits along the upstream {@code isImportantBlock} rule:
 * blocks recognised by a registered {@link BlockFormHandler} run sequentially
 * because their handler mutates the shared env, while every other top level
 * child runs in parallel against a forked env so independent blocks never
 * wait on each other.
 */
public final class DocumentAnalyzer {

    private final @NotNull LumenBootstrap bootstrap;
    private final boolean singleThread;
    private final @Nullable ExecutorService pool;

    /**
     * Creates a new analyser bound to the given bootstrap.
     *
     * @param bootstrap    the populated bootstrap holding the registries
     * @param singleThread when true, all top level blocks are walked sequentially on the calling thread
     */
    public DocumentAnalyzer(@NotNull LumenBootstrap bootstrap, boolean singleThread) {
        this.bootstrap = bootstrap;
        this.singleThread = singleThread;
        this.pool = singleThread ? null : Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() - 1));
    }

    /**
     * Tokenises, parses, and analyses the given source from scratch.
     *
     * @param uri    the document URI
     * @param source the document text
     * @return the analysis result
     */
    public @NotNull AnalysisResult analyze(@NotNull String uri, @NotNull String source) {
        return analyzeIncremental(uri, source, null, null);
    }

    /**
     * Analyses the given source, reusing the cached prior result for any line
     * untouched by the edit. When {@code editLine} is non null, only the
     * innermost enclosing block of that line and its remaining tail are
     * recomputed. When the touched block is a registered block form, every
     * top level independent block is also recomputed in parallel because the
     * shared env produced by block forms feeds them. When {@code prior} is
     * null or the AST shape changed at a level that invalidates the cache,
     * a full parse runs instead.
     *
     * @param uri      the document URI
     * @param source   the document text
     * @param editLine the 1-based source line that changed, or {@code null} for a full parse
     * @param prior    the previous analysis to reuse, or {@code null}
     * @return the analysis result
     */
    public @NotNull AnalysisResult analyzeIncremental(@NotNull String uri, @NotNull String source, @Nullable Integer editLine, @Nullable AnalysisResult prior) {
        List<Line> lines = new Tokenizer().tokenize(source);
        BlockNode root = new LumenParser().parse(lines);
        Map<Integer, Integer> indentByLine = indentMap(source);
        CodegenContext ctx = new CodegenContext(uriToScriptName(uri));
        ctx.setRawJavaEnabled(true);

        IncrementalScope scope = editLine == null || prior == null ? null : computeScope(root, prior, editLine);
        if (scope != null) {
            AnalysisResult result = tryIncremental(uri, source, root, indentByLine, ctx, prior, scope);
            if (result != null) return result;
        }

        return fullAnalyze(uri, source, root, indentByLine, ctx);
    }

    /**
     * Closes the worker pool. Should be called when the server shuts down.
     */
    public void shutdown() {
        if (pool != null) pool.shutdownNow();
    }

    /**
     * Performs a full top down analysis: important blocks sequentially against
     * the live env, then every other top level child in parallel against a
     * forked env.
     *
     * @param uri          the document URI
     * @param source       the raw document text used to seed the analysis result
     * @param root         the parsed document root
     * @param indentByLine the per line indent map
     * @param ctx          the codegen context shared across all blocks
     * @return the merged analysis result
     */
    private @NotNull AnalysisResult fullAnalyze(@NotNull String uri, @NotNull String source, @NotNull BlockNode root, @NotNull Map<Integer, Integer> indentByLine, @NotNull CodegenContext ctx) {
        TypeEnv env = new TypeEnv();
        List<LumenDiagnostic> diagnostics = new ArrayList<>();
        List<LineAnalysis> analyses = new ArrayList<>();

        List<Node> children = root.children();
        List<Node> important = new ArrayList<>();
        List<Node> independent = new ArrayList<>();
        for (Node child : children) {
            if (child instanceof RawBlockNode) continue;
            if (isImportant(child)) important.add(child);
            else independent.add(child);
        }

        for (Node child : important) {
            walkOne(child, null, children, children.indexOf(child), env, ctx, diagnostics, analyses, indentByLine);
        }

        if (singleThread || independent.size() <= 1) {
            for (Node child : independent) {
                walkOne(child, null, children, children.indexOf(child), env, ctx, diagnostics, analyses, indentByLine);
            }
        } else {
            mergeParallel(independent, children, env, ctx, indentByLine, diagnostics, analyses);
        }

        analyses.sort(Comparator.comparingInt(LineAnalysis::lineNumber));
        diagnostics.sort(Comparator.comparingInt(LumenDiagnostic::line));
        return new AnalysisResult(uri, source, List.copyOf(diagnostics), List.copyOf(analyses));
    }

    /**
     * Submits each independent top level block to the worker pool with its own
     * forked env, then drains the futures and merges the per block diagnostics
     * and analyses back into the shared accumulators.
     *
     * @param independent  the independent top level children
     * @param siblings     the original sibling list, used for block context indices
     * @param env          the env to fork from
     * @param ctx          the codegen context
     * @param indentByLine the per line indent map
     * @param diagnostics  the diagnostic accumulator
     * @param analyses     the analysis accumulator
     */
    private void mergeParallel(@NotNull List<Node> independent, @NotNull List<Node> siblings, @NotNull TypeEnv env, @NotNull CodegenContext ctx, @NotNull Map<Integer, Integer> indentByLine, @NotNull List<LumenDiagnostic> diagnostics, @NotNull List<LineAnalysis> analyses) {
        List<Future<BlockResult>> futures = new ArrayList<>(independent.size());
        for (Node child : independent) {
            int siblingIndex = siblings.indexOf(child);
            TypeEnv forked = env.fork();
            futures.add(pool.submit(() -> {
                List<LumenDiagnostic> diags = new ArrayList<>();
                List<LineAnalysis> lines = new ArrayList<>();
                walkOne(child, null, siblings, siblingIndex, forked, ctx, diags, lines, indentByLine);
                return new BlockResult(diags, lines);
            }));
        }
        for (Future<BlockResult> future : futures) {
            try {
                BlockResult result = future.get();
                diagnostics.addAll(result.diagnostics);
                analyses.addAll(result.analyses);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                System.err.println("[LumenLSP] parallel block analysis failed: " + cause);
                cause.printStackTrace(System.err);
            }
        }
    }

    /**
     * Computes the scope of an incremental reparse: the innermost block
     * containing the edit line and whether that scope falls inside an important
     * top level block. Returns {@code null} when the edit cannot be scoped to
     * a known block boundary.
     *
     * @param root     the freshly parsed document root
     * @param prior    the previous analysis whose cache is reused
     * @param editLine the 1-based source line that changed
     * @return the computed scope, or {@code null} when a full parse is required
     */
    private @Nullable IncrementalScope computeScope(@NotNull BlockNode root, @NotNull AnalysisResult prior, int editLine) {
        BlockNode topLevel = findTopLevelEnclosing(root, editLine);
        if (topLevel == null) return null;
        BlockNode innermost = findInnermost(topLevel, editLine);
        if (innermost == null) innermost = topLevel;
        boolean important = isImportant(topLevel);
        int blockEndLine = lastLineOf(innermost);
        return new IncrementalScope(topLevel, innermost, editLine, blockEndLine, important);
    }

    /**
     * Tries to perform the incremental reparse described by the scope, copying
     * untouched line analyses from the prior result, replaying the dirty range
     * inside the innermost block, and re running every top level independent
     * block in parallel when the dirty scope is an important block.
     *
     * @param uri          the document URI
     * @param source       the raw document text
     * @param root         the freshly parsed document root
     * @param indentByLine the per line indent map
     * @param ctx          the codegen context
     * @param prior        the previous analysis
     * @param scope        the computed scope
     * @return the merged analysis result, or {@code null} when the cache was unusable
     */
    private @Nullable AnalysisResult tryIncremental(@NotNull String uri, @NotNull String source, @NotNull BlockNode root, @NotNull Map<Integer, Integer> indentByLine, @NotNull CodegenContext ctx, @NotNull AnalysisResult prior, @NotNull IncrementalScope scope) {
        LineAnalysis topHeader = lineByNumber(prior, scope.topLevel.line());
        if (topHeader == null) return null;
        if (lineCountOf(source) != lineCountOf(prior.source())) return null;
        TypeEnv env = EnvCopier.copy(topHeader.beforeEnv());
        List<LumenDiagnostic> diagnostics = new ArrayList<>();
        List<LineAnalysis> analyses = new ArrayList<>();

        List<Node> children = root.children();
        int dirtyIndex = children.indexOf(scope.topLevel);
        for (int i = 0; i < dirtyIndex; i++) {
            Node child = children.get(i);
            if (!(child instanceof BlockNode b)) continue;
            copyBlockFromPrior(prior, b, analyses, diagnostics);
        }

        walkOne(scope.topLevel, null, children, dirtyIndex, env, ctx, diagnostics, analyses, indentByLine);

        if (!scope.important) {
            for (int i = dirtyIndex + 1; i < children.size(); i++) {
                Node child = children.get(i);
                if (!(child instanceof BlockNode b)) continue;
                copyBlockFromPrior(prior, b, analyses, diagnostics);
            }
        } else {
            for (int i = dirtyIndex + 1; i < children.size(); i++) {
                Node child = children.get(i);
                if (child instanceof RawBlockNode) continue;
                if (isImportant(child)) {
                    walkOne(child, null, children, i, env, ctx, diagnostics, analyses, indentByLine);
                }
            }
            List<Node> independent = new ArrayList<>();
            for (int i = dirtyIndex + 1; i < children.size(); i++) {
                Node child = children.get(i);
                if (child instanceof RawBlockNode) continue;
                if (!isImportant(child)) independent.add(child);
            }
            if (singleThread || independent.size() == 1) {
                for (Node child : independent) {
                    walkOne(child, null, children, children.indexOf(child), env, ctx, diagnostics, analyses, indentByLine);
                }
            } else if (!independent.isEmpty()) {
                mergeParallel(independent, children, env, ctx, indentByLine, diagnostics, analyses);
            }
        }

        analyses.sort(Comparator.comparingInt(LineAnalysis::lineNumber));
        diagnostics.sort(Comparator.comparingInt(LumenDiagnostic::line));
        return new AnalysisResult(uri, source, List.copyOf(diagnostics), List.copyOf(analyses));
    }

    /**
     * Returns the cached line analysis matching the given line number, or
     * {@code null} when not found.
     *
     * @param prior      the previous analysis
     * @param lineNumber the 1-based source line number
     * @return the line analysis, or {@code null}
     */
    private @Nullable LineAnalysis lineByNumber(@NotNull AnalysisResult prior, int lineNumber) {
        for (LineAnalysis line : prior.lines()) {
            if (line.lineNumber() == lineNumber) return line;
        }
        return null;
    }

    /**
     * Copies every cached line analysis and diagnostic that falls inside the
     * given block's source range from the prior result into the accumulators,
     * used to preserve untouched top level blocks across an incremental
     * reparse.
     *
     * @param prior       the prior analysis
     * @param block       the block whose lines to copy
     * @param analyses    the analysis accumulator
     * @param diagnostics the diagnostic accumulator
     */
    private void copyBlockFromPrior(@NotNull AnalysisResult prior, @NotNull BlockNode block, @NotNull List<LineAnalysis> analyses, @NotNull List<LumenDiagnostic> diagnostics) {
        int from = block.line();
        int to = lastLineOf(block);
        for (LineAnalysis line : prior.lines()) {
            if (line.lineNumber() >= from && line.lineNumber() <= to) analyses.add(line);
        }
        for (LumenDiagnostic d : prior.diagnostics()) {
            if (d.line() >= from && d.line() <= to) diagnostics.add(d);
        }
    }

    /**
     * Returns the count of source lines in the given text, used to detect
     * line shifting edits that invalidate the cache.
     *
     * @param source the document text
     * @return the line count
     */
    private int lineCountOf(@NotNull String source) {
        int count = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') count++;
        }
        return count;
    }

    /**
     * Re runs every top level independent child of the document root in
     * parallel, used after an important block was edited.
     *
     * @param root         the freshly parsed document root
     * @param env          the env to fork from after the important blocks ran
     * @param ctx          the codegen context
     * @param indentByLine the per line indent map
     * @param diagnostics  the diagnostic accumulator
     * @param analyses     the analysis accumulator
     */
    private void rerunIndependentTopLevels(@NotNull BlockNode root, @NotNull TypeEnv env, @NotNull CodegenContext ctx, @NotNull Map<Integer, Integer> indentByLine, @NotNull List<LumenDiagnostic> diagnostics, @NotNull List<LineAnalysis> analyses) {
        List<Node> children = root.children();
        List<Node> independent = new ArrayList<>();
        for (Node child : children) {
            if (child instanceof RawBlockNode) continue;
            if (!isImportant(child)) independent.add(child);
        }
        if (singleThread || independent.size() <= 1) {
            for (Node child : independent) {
                walkOne(child, null, children, children.indexOf(child), env, ctx, diagnostics, analyses, indentByLine);
            }
            return;
        }
        mergeParallel(independent, children, env, ctx, indentByLine, diagnostics, analyses);
    }

    /**
     * Walks the children of the innermost block from the edit line forward,
     * leaving everything before the edit line in the cache untouched.
     *
     * @param innermost    the innermost enclosing block
     * @param editLine     the 1-based source line that changed
     * @param block        the block context for the innermost block
     * @param env          the live env to mutate
     * @param ctx          the codegen context
     * @param diagnostics  the diagnostic accumulator
     * @param analyses     the analysis accumulator
     * @param indentByLine the per line indent map
     */
    private void replayBlockTail(@NotNull BlockNode innermost, int editLine, @NotNull BlockContext block, @NotNull TypeEnv env, @NotNull CodegenContext ctx, @NotNull List<LumenDiagnostic> diagnostics, @NotNull List<LineAnalysis> analyses, @NotNull Map<Integer, Integer> indentByLine) {
        if (innermost.line() >= editLine) {
            walkOne(innermost, null, List.of(innermost), 0, env, ctx, diagnostics, analyses, indentByLine);
            return;
        }
        List<Node> children = innermost.children();
        for (int i = 0; i < children.size(); i++) {
            Node child = children.get(i);
            if (child.line() < editLine) continue;
            walkOne(child, block, children, i, env, ctx, diagnostics, analyses, indentByLine);
        }
    }

    /**
     * Builds a synthetic {@link BlockContext} for the given innermost block,
     * matching the parent the original analyser would have produced.
     *
     * @param innermost the innermost block
     * @param topLevel  the top level enclosing block, returned as the context root when the innermost is itself top level
     * @return the block context
     */
    private @NotNull BlockContext blockContextFor(@NotNull BlockNode innermost, @NotNull BlockNode topLevel) {
        return new BlockContext(innermost, null, List.of(innermost), 0);
    }

    /**
     * Returns the cached line analysis sitting immediately at or before the
     * edit line within the innermost block, used as the env resume point.
     *
     * @param prior the previous analysis
     * @param scope the computed scope
     * @return the resume line, or {@code null} when none was cached
     */
    private @Nullable LineAnalysis findResumePoint(@NotNull AnalysisResult prior, @NotNull IncrementalScope scope) {
        LineAnalysis match = null;
        for (LineAnalysis line : prior.lines()) {
            if (line.lineNumber() < scope.editLine) match = line;
            else if (line.lineNumber() == scope.editLine) return line;
            else break;
        }
        if (match != null) return match;
        return prior.lines().isEmpty() ? null : prior.lines().get(0);
    }

    /**
     * Returns the top level child of the document root whose line range
     * contains the given line, or {@code null} when the line falls outside
     * every top level child.
     *
     * @param root the document root
     * @param line the 1-based source line
     * @return the enclosing top level block, or {@code null}
     */
    private @Nullable BlockNode findTopLevelEnclosing(@NotNull BlockNode root, int line) {
        for (Node child : root.children()) {
            if (!(child instanceof BlockNode block)) continue;
            int last = lastLineOf(block);
            if (block.line() <= line && line <= last) return block;
        }
        return null;
    }

    /**
     * Returns the deepest descendant block of the given root whose line range
     * contains the line, or the root itself when no descendant qualifies.
     *
     * @param root the block to descend from
     * @param line the 1-based source line
     * @return the innermost enclosing block
     */
    private @Nullable BlockNode findInnermost(@NotNull BlockNode root, int line) {
        BlockNode best = root;
        for (Node child : root.children()) {
            if (!(child instanceof BlockNode block)) continue;
            int last = lastLineOf(block);
            if (block.line() <= line && line <= last) {
                BlockNode deeper = findInnermost(block, line);
                if (deeper != null) best = deeper;
            }
        }
        return best;
    }

    /**
     * Returns the largest 1-based source line covered by the given block,
     * computed by walking its children recursively.
     *
     * @param block the block to scan
     * @return the last source line
     */
    private int lastLineOf(@NotNull BlockNode block) {
        int last = block.line();
        for (Node child : block.children()) {
            if (child instanceof BlockNode b) {
                int childLast = lastLineOf(b);
                if (childLast > last) last = childLast;
            } else if (child.line() > last) {
                last = child.line();
            }
        }
        return last;
    }

    /**
     * Returns whether the given node is a registered block form, mirroring
     * the upstream {@code isImportantBlock} rule.
     *
     * @param node the AST node
     * @return whether the node is a block form recognised by any registered handler
     */
    private boolean isImportant(@NotNull Node node) {
        if (!(node instanceof BlockNode block)) return false;
        for (BlockFormHandler handler : bootstrap.emit().blockForms()) {
            if (handler.matches(block.head())) return true;
        }
        return false;
    }

    /**
     * Routes a single AST node to the right analysis path, used both by the
     * full walker and by the incremental tail replayer.
     *
     * @param child        the node to analyse
     * @param parentBlock  the parent block context, or {@code null} when at the document root
     * @param siblings     the sibling list the node lives in
     * @param index        the node's index inside its sibling list
     * @param env          the live env
     * @param ctx          the codegen context
     * @param diagnostics  the diagnostic accumulator
     * @param analyses     the analysis accumulator
     * @param indentByLine the per line indent map
     */
    private void walkOne(@NotNull Node child, @Nullable BlockContext parentBlock, @NotNull List<Node> siblings, int index, @NotNull TypeEnv env, @NotNull CodegenContext ctx, @NotNull List<LumenDiagnostic> diagnostics, @NotNull List<LineAnalysis> analyses, @NotNull Map<Integer, Integer> indentByLine) {
        if (child instanceof RawBlockNode) return;
        if (child instanceof BlockNode block) {
            analyzeBlock(block, parentBlock, siblings, index, env, ctx, diagnostics, analyses, indentByLine);
        } else if (child instanceof StatementNode stmt) {
            analyzeStatement(stmt, parentBlock, siblings, index, env, ctx, diagnostics, analyses, indentByLine);
        }
    }

    private void walk(@NotNull List<Node> children, @Nullable BlockContext parentBlock, @NotNull TypeEnv env, @NotNull CodegenContext ctx, @NotNull List<LumenDiagnostic> diagnostics, @NotNull List<LineAnalysis> analyses, @NotNull Map<Integer, Integer> indentByLine) {
        for (int i = 0; i < children.size(); i++) {
            walkOne(children.get(i), parentBlock, children, i, env, ctx, diagnostics, analyses, indentByLine);
        }
    }

    private void analyzeStatement(@NotNull StatementNode stmt, @Nullable BlockContext parentBlock, @NotNull List<Node> siblings, int index, @NotNull TypeEnv env, @NotNull CodegenContext ctx, @NotNull List<LumenDiagnostic> diagnostics, @NotNull List<LineAnalysis> analyses, @NotNull Map<Integer, Integer> indentByLine) {
        List<Token> tokens = stmt.head();
        if (tokens.isEmpty()) return;
        int indent = indentByLine.getOrDefault(stmt.line(), 0);
        TypeEnv before = EnvCopier.copy(env);
        BlockContext block = parentBlock != null ? parentBlock : new BlockContext(stmt, null, siblings, index);
        try {
            RegisteredPatternMatch match = bootstrap.patterns().matchStatement(tokens, env);
            if (match != null) {
                HandlerContextImpl hctx = new HandlerContextImpl(match.match(), env, ctx, block, NoopJavaOutput.INSTANCE, stmt.line(), stmt.raw());
                match.reg().handler().handle(hctx);
                Map<String, Object> meta = new HashMap<>();
                meta.put(MetaKeys.STATEMENT_MATCH, match);
                stashVarDecl(meta, match);
                analyses.add(new LineAnalysis(stmt.line(), indent, tokens, null, before, EnvCopier.copy(env), meta));
                return;
            }
            List<PatternSimulator.Suggestion> suggestions = PatternSimulator.suggestStatementsAndExpressions(tokens, bootstrap.patterns(), env);
            LumenDiagnostic diag = buildUnknownDiagnostic("Unknown statement", stmt.line(), stmt.raw(), tokens, suggestions, env);
            diagnostics.add(diag);
            Map<String, Object> meta = new HashMap<>();
            if (!suggestions.isEmpty()) meta.put(MetaKeys.SUGGESTIONS, suggestions);
            analyses.add(new LineAnalysis(stmt.line(), indent, tokens, diag, before, null, meta));
        } catch (DiagnosticException e) {
            EnvCopier.restore(env, before);
            diagnostics.add(e.diagnostic());
            analyses.add(new LineAnalysis(stmt.line(), indent, tokens, e.diagnostic(), before, null, new HashMap<>()));
        } catch (RuntimeException e) {
            EnvCopier.restore(env, before);
            LumenDiagnostic diag = LumenDiagnostic.error(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                    .at(stmt.line(), stmt.raw())
                    .build();
            diagnostics.add(diag);
            analyses.add(new LineAnalysis(stmt.line(), indent, tokens, diag, before, null, new HashMap<>()));
        }
    }

    private void analyzeBlock(@NotNull BlockNode node, @Nullable BlockContext parentBlock, @NotNull List<Node> siblings, int index, @NotNull TypeEnv env, @NotNull CodegenContext ctx, @NotNull List<LumenDiagnostic> diagnostics, @NotNull List<LineAnalysis> analyses, @NotNull Map<Integer, Integer> indentByLine) {
        List<Token> head = node.head();
        int indent = indentByLine.getOrDefault(node.line(), 0);
        TypeEnv before = EnvCopier.copy(env);
        BlockContext block = new BlockContext(node, parentBlock, siblings, index);
        BlockFormHandler form = matchBlockForm(head);
        if (form != null) {
            handleBlockForm(form, node, head, indent, env, ctx, diagnostics, analyses, before, indentByLine);
            return;
        }
        boolean entered = false;
        try {
            RegisteredBlockMatch match = head.isEmpty() ? null : bootstrap.patterns().matchBlock(head, env);
            if (match != null) {
                HandlerContextImpl hctx = new HandlerContextImpl(match.match(), env, ctx, block, NoopJavaOutput.INSTANCE, node.line(), node.raw());
                env.enterBlock(block);
                entered = true;
                match.reg().handler().begin(hctx);
                runBlockEnterHooks(env, ctx, block, node);
                Map<String, Object> meta = new HashMap<>();
                meta.put(MetaKeys.BLOCK_MATCH, match);
                analyses.add(new LineAnalysis(node.line(), indent, head, null, before, EnvCopier.copy(env), meta));
                walk(node.children(), block, env, ctx, diagnostics, analyses, indentByLine);
                match.reg().handler().end(hctx);
                env.leaveBlock();
                entered = false;
                return;
            }
            List<PatternSimulator.Suggestion> suggestions = PatternSimulator.suggestBlocks(head, bootstrap.patterns(), env);
            LumenDiagnostic diag = buildUnknownDiagnostic("Unknown block", node.line(), node.raw(), head, suggestions, env);
            diagnostics.add(diag);
            Map<String, Object> meta = new HashMap<>();
            if (!suggestions.isEmpty()) meta.put(MetaKeys.SUGGESTIONS, suggestions);
            analyses.add(new LineAnalysis(node.line(), indent, head, diag, before, null, meta));
            walk(node.children(), block, env, ctx, diagnostics, analyses, indentByLine);
        } catch (DiagnosticException e) {
            if (entered) env.leaveBlock();
            EnvCopier.restore(env, before);
            diagnostics.add(e.diagnostic());
            analyses.add(new LineAnalysis(node.line(), indent, head, e.diagnostic(), before, null, new HashMap<>()));
        } catch (RuntimeException e) {
            if (entered) env.leaveBlock();
            EnvCopier.restore(env, before);
            LumenDiagnostic diag = LumenDiagnostic.error(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                    .at(node.line(), node.raw())
                    .build();
            diagnostics.add(diag);
            analyses.add(new LineAnalysis(node.line(), indent, head, diag, before, null, new HashMap<>()));
        }
    }

    /**
     * Returns the first registered block form whose matcher accepts the given
     * head tokens, or {@code null} when none does.
     *
     * @param head the block header tokens, possibly empty
     * @return the matching handler, or {@code null}
     */
    private @Nullable BlockFormHandler matchBlockForm(@NotNull List<Token> head) {
        if (head.isEmpty()) return null;
        for (BlockFormHandler handler : bootstrap.emit().blockForms()) {
            if (handler.matches(head)) return handler;
        }
        return null;
    }

    /**
     * Runs the matched block form handler in the noop output sandbox and
     * records the resulting line analysis. Children are walked separately
     * so providers see token entries for every body line even though the
     * handler processed them privately.
     *
     * @param form         the matched handler
     * @param node         the block node
     * @param head         the head tokens
     * @param indent       the leading whitespace count of the block header line
     * @param env          the live type environment
     * @param ctx          the codegen context
     * @param diagnostics  the diagnostic accumulator
     * @param analyses     the line analysis accumulator
     * @param snapshot     the env snapshot used to roll back on failure
     * @param indentByLine the per line indent map for child rows
     */
    private void handleBlockForm(@NotNull BlockFormHandler form, @NotNull BlockNode node, @NotNull List<Token> head, int indent, @NotNull TypeEnv env, @NotNull CodegenContext ctx, @NotNull List<LumenDiagnostic> diagnostics, @NotNull List<LineAnalysis> analyses, @NotNull TypeEnv snapshot, @NotNull Map<Integer, Integer> indentByLine) {
        List<ScriptLine> children = new ArrayList<>(node.children().size());
        for (Node child : node.children()) {
            children.add(new SimpleScriptLine(child));
        }
        HandlerContextImpl hctx = new HandlerContextImpl(null, env, ctx, null, NoopJavaOutput.INSTANCE, node.line(), node.raw());
        try {
            form.handle(head, children, hctx);
            Map<String, Object> meta = new HashMap<>();
            meta.put(MetaKeys.BLOCK_FORM_NAME, head.get(0).text());
            analyses.add(new LineAnalysis(node.line(), indent, head, null, snapshot, EnvCopier.copy(env), meta));
            recordChildren(node, env, snapshot, analyses, indentByLine);
        } catch (DiagnosticException e) {
            EnvCopier.restore(env, snapshot);
            diagnostics.add(e.diagnostic());
            analyses.add(new LineAnalysis(node.line(), indent, head, e.diagnostic(), snapshot, null, new HashMap<>()));
            recordChildren(node, env, snapshot, analyses, indentByLine);
        } catch (RuntimeException e) {
            EnvCopier.restore(env, snapshot);
            LumenDiagnostic diag = LumenDiagnostic.error(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                    .at(node.line(), node.raw())
                    .build();
            diagnostics.add(diag);
            analyses.add(new LineAnalysis(node.line(), indent, head, diag, snapshot, null, new HashMap<>()));
            recordChildren(node, env, snapshot, analyses, indentByLine);
        }
    }

    /**
     * Stores a tokens only line analysis for each statement child of a block
     * form, so providers like the semantic tokenizer and hover can light up
     * the body lines even though the form handler processed them privately.
     *
     * @param node         the block node whose children to record
     * @param env          the env to attach as the after env on each line
     * @param before       the env snapshot to attach as the before env on each line
     * @param analyses     the analysis accumulator
     * @param indentByLine the per line indent map for column shifting
     */
    private void recordChildren(@NotNull BlockNode node, @NotNull TypeEnv env, @NotNull TypeEnv before, @NotNull List<LineAnalysis> analyses, @NotNull Map<Integer, Integer> indentByLine) {
        for (Node child : node.children()) {
            if (!(child instanceof StatementNode stmt)) continue;
            List<Token> tokens = stmt.head();
            if (tokens.isEmpty()) continue;
            int childIndent = indentByLine.getOrDefault(stmt.line(), 0);
            analyses.add(new LineAnalysis(stmt.line(), childIndent, tokens, null, before, EnvCopier.copy(env), new HashMap<>()));
        }
    }

    /**
     * Runs every registered block enter hook against the env now that the
     * block has been entered, mirroring the upstream code emitter so global
     * field bindings, persistent variable loads, and similar setup land in
     * the env before the children are analysed.
     *
     * @param env   the live env
     * @param ctx   the codegen context
     * @param block the block being entered
     * @param node  the block node providing source location
     */
    private void runBlockEnterHooks(@NotNull TypeEnv env, @NotNull CodegenContext ctx, @NotNull BlockContext block, @NotNull BlockNode node) {
        HandlerContextImpl hookCtx = new HandlerContextImpl(null, env, ctx, block, NoopJavaOutput.INSTANCE, node.line(), node.raw());
        for (BlockEnterHook hook : bootstrap.emit().blockEnterHooks()) {
            try {
                hook.onBlockEnter(hookCtx);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private @NotNull LumenDiagnostic buildUnknownDiagnostic(@NotNull String title, int line, @NotNull String raw, @NotNull List<Token> tokens, @NotNull List<PatternSimulator.Suggestion> suggestions, @NotNull TypeEnv env) {
        if (suggestions.isEmpty()) {
            return LumenDiagnostic.error(title)
                    .at(line, raw)
                    .highlight(tokens.get(0).start(), tokens.get(tokens.size() - 1).end())
                    .build();
        }
        return SuggestionDiagnostics.build(title, line, raw, tokens, suggestions, env);
    }

    /**
     * Records the variable name and its column range on the line metadata when
     * the matched pattern is the built in set declaration.
     *
     * @param meta  the metadata bag to populate
     * @param match the matched statement
     */
    private void stashVarDecl(@NotNull Map<String, Object> meta, @NotNull RegisteredPatternMatch match) {
        if (!"set %name:IDENT% to %val:EXPR%".equals(match.reg().pattern().raw())) return;
        var nameBound = match.match().values().get("name");
        if (nameBound == null || nameBound.tokens().isEmpty()) return;
        Token nameToken = nameBound.tokens().get(0);
        meta.put(MetaKeys.VAR_DECL_NAME, nameToken.text());
        meta.put(MetaKeys.VAR_DECL_RANGE, new int[]{nameToken.start(), nameToken.end()});
    }

    /**
     * Maps each 1-based source line number to the count of leading whitespace
     * characters on that line, used to translate tokeniser local positions
     * into document absolute positions.
     *
     * @param source the document text
     * @return the per line indent in characters
     */
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

    /**
     * Derives the script name passed to {@link CodegenContext} from the
     * document URI, ensuring it ends with the {@code .luma} suffix.
     *
     * @param uri the document URI
     * @return the script name
     */
    private @NotNull String uriToScriptName(@NotNull String uri) {
        int slash = uri.lastIndexOf('/');
        String name = slash >= 0 ? uri.substring(slash + 1) : uri;
        if (!name.endsWith(".luma")) name = name + ".luma";
        return name;
    }

    /**
     * Carries the resolved scope of an incremental reparse, namely the top
     * level block being touched, the innermost block whose tail is being
     * replayed, the dirty edit line, the last source line covered by the
     * innermost block, and whether the top level block is a registered block
     * form.
     *
     * @param topLevel     the enclosing top level block
     * @param innermost    the innermost block whose tail is replayed
     * @param editLine     the 1-based source line that changed
     * @param blockEndLine the last source line covered by the innermost block
     * @param important    whether the top level block runs sequentially
     */
    private record IncrementalScope(@NotNull BlockNode topLevel, @NotNull BlockNode innermost, int editLine, int blockEndLine, boolean important) {
    }

    /**
     * Result of a single parallel block analysis worker, carrying the
     * diagnostics and per line analyses produced inside its forked env.
     *
     * @param diagnostics the diagnostics produced
     * @param analyses    the line analyses produced
     */
    private record BlockResult(@NotNull List<LumenDiagnostic> diagnostics, @NotNull List<LineAnalysis> analyses) {
    }
}
