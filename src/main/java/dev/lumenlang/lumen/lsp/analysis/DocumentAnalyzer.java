package dev.lumenlang.lumen.lsp.analysis;

import dev.lumenlang.lumen.api.pattern.Categories;
import dev.lumenlang.lumen.api.pattern.PatternMeta;
import dev.lumenlang.lumen.lsp.analysis.line.LineInfo;
import dev.lumenlang.lumen.lsp.analysis.line.LineKind;
import dev.lumenlang.lumen.lsp.diagnostic.util.LumenDiagnostic;
import dev.lumenlang.lumen.lsp.diagnostic.util.LumenSeverity;
import dev.lumenlang.lumen.lsp.documentation.DocumentationData;
import dev.lumenlang.lumen.lsp.entries.documentation.BlockEntry;
import dev.lumenlang.lumen.lsp.entries.documentation.EventEntry;
import dev.lumenlang.lumen.lsp.entries.documentation.PatternEntry;
import dev.lumenlang.lumen.lsp.entries.documentation.variables.BlockVariable;
import dev.lumenlang.lumen.lsp.entries.documentation.variables.EventVariable;
import dev.lumenlang.lumen.pipeline.codegen.BlockContext;
import dev.lumenlang.lumen.pipeline.codegen.TypeEnv;
import dev.lumenlang.lumen.pipeline.data.DataSchema;
import dev.lumenlang.lumen.pipeline.language.nodes.BlockNode;
import dev.lumenlang.lumen.pipeline.language.nodes.ConditionalBlockNode;
import dev.lumenlang.lumen.pipeline.language.nodes.Node;
import dev.lumenlang.lumen.pipeline.language.nodes.RawBlockNode;
import dev.lumenlang.lumen.pipeline.language.nodes.StatementNode;
import dev.lumenlang.lumen.pipeline.language.parse.LumenParser;
import dev.lumenlang.lumen.pipeline.language.pattern.PatternRegistry;
import dev.lumenlang.lumen.pipeline.language.pattern.RegisteredBlockMatch;
import dev.lumenlang.lumen.pipeline.language.pattern.RegisteredExpressionMatch;
import dev.lumenlang.lumen.pipeline.language.pattern.RegisteredPatternMatch;
import dev.lumenlang.lumen.pipeline.language.tokenization.Line;
import dev.lumenlang.lumen.pipeline.language.tokenization.Token;
import dev.lumenlang.lumen.pipeline.language.tokenization.Tokenizer;
import dev.lumenlang.lumen.pipeline.language.typed.Expr;
import dev.lumenlang.lumen.pipeline.language.typed.StatementClassifier;
import dev.lumenlang.lumen.pipeline.language.typed.TypedStatement;
import dev.lumenlang.lumen.pipeline.var.RefType;
import dev.lumenlang.lumen.pipeline.var.VarRef;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Performs full analysis of a Lumen script, producing diagnostics,
 * variable scopes, and classification results for every line.
 */
@SuppressWarnings("DataFlowIssue")
public final class DocumentAnalyzer {

    private final Tokenizer tokenizer = new Tokenizer();
    private final LumenParser parser = new LumenParser();

    /**
     * Analyzes a complete Lumen script source.
     *
     * @param source   the raw source text
     * @param registry the pattern registry
     * @param docs     the documentation data for event lookups
     * @return the analysis result
     */
    public @NotNull AnalysisResult analyze(@NotNull String source,
                                           @NotNull PatternRegistry registry,
                                           @NotNull DocumentationData docs) {
        List<Line> lines;
        BlockNode root;
        try {
            lines = tokenizer.tokenize(source);
            root = parser.parse(lines);
        } catch (Exception e) {
            lines = List.of();
            root = new BlockNode(-1, -1, "", List.of());
            return new AnalysisResult(root, lines, List.of(new LumenDiagnostic(1, 0, 0,
                    "Failed to parse script: " + (e.getMessage() != null ? e.getMessage() : "unknown error"),
                    LumenSeverity.ERROR)), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
        }

        TypeEnv env = new TypeEnv();
        env.enterBlock(new BlockContext(root, null, root.children(), 0));

        AnalysisState state = new AnalysisState(
                registry, env, docs,
                new ArrayList<>(),
                new HashMap<>(),
                new HashMap<>(),
                new HashMap<>(),
                new HashMap<>(),
                new HashMap<>()
        );

        checkIndentation(lines, state);
        walk(root.children(), state);

        return new AnalysisResult(root, lines, state.diagnostics(), state.lineInfo(),
                state.allVariables(), state.dataSchemas(), state.scopeByLine());
    }

    /**
     * Recursively walks a list of AST nodes, analyzing each one.
     *
     * @param nodes the nodes to walk
     * @param state the current analysis state
     */
    private void walk(@NotNull List<Node> nodes, @NotNull AnalysisState state) {
        for (Node node : nodes) {
            try {
                if (node instanceof StatementNode stmt) {
                    state.snapshot(stmt.line());
                    classify(stmt, state);
                    state.snapshot(stmt.line());
                } else if (node instanceof RawBlockNode) {
                    state.record(new LineInfo(node.line(), node.head(), LineKind.RAW_BLOCK, null, null));
                    state.snapshot(node.line());
                } else if (node instanceof ConditionalBlockNode cond) {
                    state.record(new LineInfo(cond.line(), cond.head(), LineKind.CONDITIONAL, null, null));
                    state.snapshot(cond.line());
                    walk(cond.children(), state);
                } else if (node instanceof BlockNode block) {
                    analyzeBlock(block, state);
                }
            } catch (Exception e) {
                state.report(new LumenDiagnostic(
                        node.line(), 0, 0,
                        e.getMessage() != null ? e.getMessage() : "Internal analysis error",
                        LumenSeverity.ERROR
                ));
            }
        }
    }

    /**
     * Analyzes a single block node by classifying its keyword and recursively walking its children.
     *
     * @param block the block node to analyze
     * @param state the current analysis state
     */
    private void analyzeBlock(@NotNull BlockNode block, @NotNull AnalysisState state) {
        List<Token> head = block.head();
        if (head.isEmpty()) {
            walk(block.children(), state);
            return;
        }

        String keyword = head.get(0).text().toLowerCase();
        Map<String, VarDeclaration> outerVars = new HashMap<>(state.variables());
        state.env().enterBlock(new BlockContext(block, state.env().blockContext(), block.children(), 0));

        try {
            switch (keyword) {
                case "on" -> analyzeEventBlock(block, head, state);
                case "if", "else" -> analyzeConditionalBlock(block, head, state);
                case "loop" -> analyzeLoopBlock(block, head, state);
                case "data" -> analyzeDataBlock(block, head, state);
                case "config" -> analyzeConfigBlock(block, head, state, outerVars);
                default -> analyzeDefaultBlock(block, head, keyword, state);
            }
        } finally {
            state.env().leaveBlock();
            state.variables().clear();
            state.variables().putAll(outerVars);
        }
    }

    /**
     * Analyzes an event block, resolving the event name and injecting event variables.
     *
     * @param block the block node
     * @param head  the header tokens
     * @param state the analysis state
     */
    private void analyzeEventBlock(@NotNull BlockNode block, @NotNull List<Token> head,
                                   @NotNull AnalysisState state) {
        String eventName = extractEventName(head);
        EventEntry event = findEvent(state.docs(), eventName);
        if (event != null) {
            state.record(new LineInfo(block.line(), head, LineKind.EVENT_BLOCK, null, event));
            for (EventVariable var : event.variables()) {
                RefType refType = var.refTypeId() != null ? RefType.byId(var.refTypeId()) : null;
                String javaType = var.javaType() != null ? var.javaType() : "Object";
                state.env().defineVar(var.name(), new VarRef(refType, var.name()));
                VarDeclaration decl = new VarDeclaration(var.name(), javaType, block.line(), true);
                state.variables().put(var.name(), decl);
                state.allVariables().put(var.name(), decl);
            }
        } else {
            state.record(new LineInfo(block.line(), head, LineKind.EVENT_BLOCK, null, null));
            state.report(diagnostic(block, "Unknown event '" + eventName + "'", LumenSeverity.WARNING));
        }
        state.snapshot(block.line());
        walk(block.children(), state);
    }

    /**
     * Analyzes a conditional (if/else) block.
     *
     * @param block the block node
     * @param head  the header tokens
     * @param state the analysis state
     */
    private void analyzeConditionalBlock(@NotNull BlockNode block, @NotNull List<Token> head,
                                         @NotNull AnalysisState state) {
        state.record(new LineInfo(block.line(), head, LineKind.CONDITIONAL, null, null));
        state.snapshot(block.line());
        walk(block.children(), state);
    }

    /**
     * Analyzes a loop block, extracting the loop variable and optional value variable.
     *
     * @param block the block node
     * @param head  the header tokens
     * @param state the analysis state
     */
    private void analyzeLoopBlock(@NotNull BlockNode block, @NotNull List<Token> head,
                                  @NotNull AnalysisState state) {
        BlockContext parentCtx = state.env().blockContext().parent();
        boolean loopAtRoot = parentCtx != null && parentCtx.parent() == null;
        if (loopAtRoot) {
            state.report(diagnostic(block, "'loop' cannot be used at the top level", LumenSeverity.ERROR));
        }

        state.record(new LineInfo(block.line(), head, LineKind.LOOP_BLOCK, null, null));

        if (head.size() >= 2) {
            String loopVar = head.get(1).text();
            declare(loopVar, "Object", null, block.line(), true, state);

            if (head.size() >= 4 && head.get(2).text().equalsIgnoreCase("val")) {
                String valVar = head.get(2).text();
                declare(valVar, "Object", null, block.line(), true, state);
            }
        }

        state.snapshot(block.line());
        walk(block.children(), state);
    }

    /**
     * Analyzes a data class block, parsing field declarations and building a schema.
     *
     * @param block the block node
     * @param head  the header tokens
     * @param state the analysis state
     */
    private void analyzeDataBlock(@NotNull BlockNode block, @NotNull List<Token> head,
                                  @NotNull AnalysisState state) {
        BlockContext parentCtx = state.env().blockContext().parent();
        boolean atRoot = parentCtx != null && parentCtx.parent() == null;
        if (!atRoot) {
            state.report(diagnostic(block, "'data' cannot be used inside a block", LumenSeverity.ERROR));
        }
        if (head.size() < 2) {
            state.report(diagnostic(block, "'data' requires a name", LumenSeverity.ERROR));
        }

        String typeName = head.size() >= 2 ? head.get(1).text() : "unknown";
        state.record(new LineInfo(block.line(), head, LineKind.DATA_BLOCK, null, null));

        DataSchema.Builder builder = DataSchema.builder(typeName);
        for (Node child : block.children()) {
            if (child instanceof StatementNode fieldStmt) {
                List<Token> fieldTokens = fieldStmt.head();
                state.record(new LineInfo(fieldStmt.line(), fieldTokens, LineKind.DATA_FIELD, null, null));
                if (fieldTokens.size() >= 2) {
                    String fieldName = fieldTokens.get(0).text();
                    String fieldTypeName = fieldTokens.get(1).text();
                    try {
                        builder.field(fieldName, DataSchema.FieldType.fromName(fieldTypeName));
                    } catch (IllegalArgumentException e) {
                        state.report(new LumenDiagnostic(fieldStmt.line(),
                                fieldTokens.get(1).start(), fieldTokens.get(1).end(),
                                "Unknown data field type '" + fieldTypeName + "'",
                                LumenSeverity.ERROR));
                    }
                } else if (fieldTokens.size() == 1) {
                    state.report(new LumenDiagnostic(fieldStmt.line(),
                            fieldTokens.get(0).start(), fieldTokens.get(0).end(),
                            "Data field '" + fieldTokens.get(0).text() + "' is missing a type",
                            LumenSeverity.ERROR));
                }
            }
        }

        state.dataSchemas().put(typeName.toLowerCase(), builder.build());
        state.snapshot(block.line());
    }

    /**
     * Analyzes a config block, parsing key/value entries as variables promoted to the outer scope.
     *
     * @param block     the block node
     * @param head      the header tokens
     * @param state     the analysis state
     * @param outerVars the outer scope variables to propagate config entries into
     */
    private void analyzeConfigBlock(@NotNull BlockNode block, @NotNull List<Token> head,
                                    @NotNull AnalysisState state,
                                    @NotNull Map<String, VarDeclaration> outerVars) {
        BlockContext parentCtx = state.env().blockContext().parent();
        boolean atRoot = parentCtx != null && parentCtx.parent() == null;
        if (!atRoot) {
            state.report(diagnostic(block, "'config' cannot be used inside a block", LumenSeverity.ERROR));
        }

        state.record(new LineInfo(block.line(), head, LineKind.CONFIG_BLOCK, null, null));

        for (Node child : block.children()) {
            if (child instanceof StatementNode entryStmt) {
                List<Token> tokens = entryStmt.head();
                state.record(new LineInfo(entryStmt.line(), tokens, LineKind.CONFIG_ENTRY, null, null));

                if (tokens.size() >= 2) {
                    String name = tokens.get(0).text();
                    String valueType = inferConfigType(tokens);
                    declare(name, valueType, null, entryStmt.line(), true, state);
                } else if (tokens.size() == 1) {
                    state.report(new LumenDiagnostic(entryStmt.line(),
                            tokens.get(0).start(), tokens.get(0).end(),
                            "Config entry '" + tokens.get(0).text() + "' is missing a value",
                            LumenSeverity.WARNING));
                }
            }
        }

        outerVars.putAll(state.variables());
        state.snapshot(block.line());
    }

    /**
     * Analyzes a block that is not a built-in keyword, checking documentation and registry for matches.
     *
     * @param block   the block node
     * @param head    the header tokens
     * @param keyword the first token lowercased
     * @param state   the analysis state
     */
    private void analyzeDefaultBlock(@NotNull BlockNode block, @NotNull List<Token> head,
                                     @NotNull String keyword, @NotNull AnalysisState state) {
        BlockEntry docBlock = findBlock(state.docs(), keyword);
        if (docBlock != null) {
            checkNesting(block, docBlock, state);
            state.record(new LineInfo(block.line(), head, LineKind.PATTERN_BLOCK, null, null));
            injectVariables(docBlock, block, state);
        } else {
            RegisteredBlockMatch blockMatch = null;
            try {
                blockMatch = state.registry().matchBlock(head, state.env());
            } catch (Exception ignored) {
            }
            if (blockMatch != null) {
                state.record(new LineInfo(block.line(), head, LineKind.PATTERN_BLOCK,
                        blockMatch.reg().meta(), null));
                injectVariables(keyword, block, state);
            } else {
                classifyUnknownBlock(block, head, keyword, state);
            }
        }
        state.snapshot(block.line());
        walk(block.children(), state);
    }

    /**
     * Classifies an unrecognized block, checking if it might be a misplaced statement or expression.
     *
     * @param block   the block node
     * @param head    the header tokens
     * @param keyword the block keyword
     * @param state   the analysis state
     */
    private void classifyUnknownBlock(@NotNull BlockNode block, @NotNull List<Token> head,
                                      @NotNull String keyword, @NotNull AnalysisState state) {
        boolean isStatement = false;
        boolean isExpression = false;
        try {
            RegisteredPatternMatch stmtMatch = state.registry().matchStatement(head, state.env());
            if (stmtMatch != null) isStatement = true;
        } catch (Exception ignored) {
        }
        if (!isStatement) {
            try {
                RegisteredExpressionMatch exprMatch = state.registry().matchExpression(head, state.env());
                if (exprMatch != null) isExpression = true;
            } catch (Exception ignored) {
            }
        }
        if (isStatement || isExpression) {
            state.report(diagnostic(block, "'" + keyword + "' is not a block and cannot have indented children", LumenSeverity.ERROR));
        } else {
            state.report(diagnostic(block, "Unknown block '" + keyword + "'", LumenSeverity.WARNING));
        }
        state.record(new LineInfo(block.line(), head, LineKind.UNKNOWN_BLOCK, null, null));
    }

    /**
     * Classifies a single statement node, dispatching to the pipeline classifier
     * and handling the result by type.
     *
     * @param stmt  the statement node to classify
     * @param state the analysis state
     */
    private void classify(@NotNull StatementNode stmt, @NotNull AnalysisState state) {
        TypedStatement typed;
        try {
            typed = StatementClassifier.classify(stmt, state.registry(), state.env());
        } catch (Exception e) {
            state.report(diagnostic(stmt,
                    e.getMessage() != null ? e.getMessage() : "Classification error",
                    LumenSeverity.ERROR));
            state.record(new LineInfo(stmt.line(), stmt.head(), LineKind.ERROR, null, null));
            return;
        }

        if (typed instanceof TypedStatement.ErrorStmt err) {
            handleError(stmt, err, state);
        } else if (typed instanceof TypedStatement.PatternStmt ps) {
            state.record(new LineInfo(stmt.line(), stmt.head(), LineKind.STATEMENT,
                    ps.match().reg().meta(), null));
        } else if (typed instanceof TypedStatement.VarStmt vs) {
            handleVarDecl(stmt, vs.name(), inferRefType(vs.expr()), LineKind.VAR_DECL, state);
        } else if (typed instanceof TypedStatement.ExprVarStmt evs) {
            handleExprVarDecl(stmt, evs, state);
        } else if (typed instanceof TypedStatement.ExprStmt es) {
            state.record(new LineInfo(stmt.line(), stmt.head(), LineKind.EXPRESSION,
                    es.match().reg().meta(), null));
        } else if (typed instanceof TypedStatement.StoreVarStmt sv) {
            handleVarDecl(stmt, sv.name(), inferRefType(sv.expr()), LineKind.STORE_VAR, state);
        } else if (typed instanceof TypedStatement.GlobalVarStmt gv) {
            handleVarDecl(stmt, gv.name(), inferRefType(gv.expr()), LineKind.GLOBAL_VAR, state);
        }
    }

    /**
     * Handles an error classification result, applying structural matching to find
     * close documentation patterns.
     *
     * @param stmt  the statement node
     * @param err   the error classification result
     * @param state the analysis state
     */
    private void handleError(@NotNull StatementNode stmt, @NotNull TypedStatement.ErrorStmt err,
                             @NotNull AnalysisState state) {
        List<Token> errorTokens = err.errorTokens();
        int colStart = 0;
        int colEnd = 0;
        if (errorTokens != null && !errorTokens.isEmpty()) {
            colStart = errorTokens.get(0).start();
            colEnd = errorTokens.get(errorTokens.size() - 1).end();
        }

        ClosestMatch closest = findClosestMatch(stmt.head(), state.docs());

        // 90%+ confidence: treat as a successful match without warning
        if (closest != null && closest.confidence() >= 0.90) {
            state.record(new LineInfo(stmt.line(), stmt.head(), LineKind.STATEMENT,
                    closestMeta(closest.entry(), closest.confidence()), null));
            // 70%+ confidence: accept but warn about potential mismatch
        } else if (closest != null && closest.confidence() >= 0.70) {
            state.record(new LineInfo(stmt.line(), stmt.head(), LineKind.STATEMENT,
                    closestMeta(closest.entry(), closest.confidence()), null));
            int pct = (int) (closest.confidence() * 100);
            state.report(new LumenDiagnostic(stmt.line(), colStart, colEnd,
                    "Resolved by structural matching (" + pct + "% confidence): " + closest.pattern()
                            + "\nThis match may not be correct.",
                    LumenSeverity.WARNING));
        } else {
            state.record(new LineInfo(stmt.line(), stmt.head(), LineKind.ERROR, null, null));
            String message = err.message();
            if (closest != null) {
                int pct = (int) (closest.confidence() * 100);
                message += "\n\nClosest match (" + pct + "% confidence, may be incorrect): " + closest.pattern();
            }
            state.report(new LumenDiagnostic(stmt.line(), colStart, colEnd, message, LumenSeverity.ERROR));
        }
    }

    /**
     * Handles a simple variable declaration (var, store, global), recording the line
     * and declaring the variable in scope.
     *
     * @param stmt    the statement node
     * @param name    the variable name
     * @param refType the inferred ref type, or null
     * @param kind    the line kind to record
     * @param state   the analysis state
     */
    private void handleVarDecl(@NotNull StatementNode stmt, @NotNull String name,
                               @Nullable RefType refType,
                               @NotNull LineKind kind,
                               @NotNull AnalysisState state) {
        state.record(new LineInfo(stmt.line(), stmt.head(), kind, null, null));
        if (state.variables().containsKey(name)) {
            state.report(diagnostic(stmt, "Variable '" + name + "' is already defined", LumenSeverity.ERROR));
        }
        declare(name, "Object", refType, stmt.line(), false, state);
    }

    /**
     * Handles an expression variable declaration, inferring the return type from
     * the matched expression.
     *
     * @param stmt  the statement node
     * @param evs   the expression var classification result
     * @param state the analysis state
     */
    private void handleExprVarDecl(@NotNull StatementNode stmt, @NotNull TypedStatement.ExprVarStmt evs,
                                   @NotNull AnalysisState state) {
        state.record(new LineInfo(stmt.line(), stmt.head(), LineKind.EXPR_VAR_DECL,
                evs.match().reg().meta(), null));
        if (state.variables().containsKey(evs.name())) {
            state.report(diagnostic(stmt, "Variable '" + evs.name() + "' is already defined", LumenSeverity.ERROR));
        }
        RefType exprRef = inferExprVarRefType(evs.raw().head());
        String exprType = "Object";
        if (exprRef == null) {
            ExpressionReturn ret = inferExpressionReturn(evs.match().reg().meta().description(), state.docs());
            exprRef = ret.refType();
            if (ret.javaType() != null) exprType = ret.javaType();
        }
        declare(evs.name(), exprType, exprRef, stmt.line(), false, state);
    }

    /**
     * Declares a variable in the current scope, the all-variables map, and the type environment.
     *
     * @param name     the variable name
     * @param type     the Java type name
     * @param refType  the ref type, or null
     * @param line     the declaration line
     * @param provided whether this variable is provided by a block context
     * @param state    the analysis state
     */
    private void declare(@NotNull String name, @NotNull String type, @Nullable RefType refType,
                         int line, boolean provided, @NotNull AnalysisState state) {
        VarDeclaration decl = new VarDeclaration(name, type, line, provided);
        state.variables().put(name, decl);
        state.allVariables().put(name, decl);
        state.env().defineVar(name, new VarRef(refType, name));
    }

    /**
     * Infers the {@link RefType} from a parsed expression.
     *
     * @param expr the parsed expression
     * @return the inferred ref type, or null if unknown
     */
    private @Nullable RefType inferRefType(@NotNull Expr expr) {
        if (expr.resolvedType() != null && expr.resolvedType().refType() != null) {
            return expr.resolvedType().refType();
        }
        if (expr instanceof Expr.RawExpr raw) {
            return inferTokenRefType(raw.tokens());
        }
        return null;
    }

    /**
     * Infers the {@link RefType} for an expression variable by extracting tokens after the equals sign.
     *
     * @param head the full token list from the statement
     * @return the inferred ref type, or null if unknown
     */
    private @Nullable RefType inferExprVarRefType(@NotNull List<Token> head) {
        for (int i = 0; i < head.size(); i++) {
            if ("=".equals(head.get(i).text()) && i + 1 < head.size()) {
                return inferTokenRefType(head.subList(i + 1, head.size()));
            }
        }
        return null;
    }

    /**
     * Infers the {@link RefType} from expression tokens by checking for constructors
     * like {@code new list} or {@code new map}.
     *
     * @param tokens the expression tokens
     * @return the inferred ref type, or null if unrecognized
     */
    private @Nullable RefType inferTokenRefType(@NotNull List<Token> tokens) {
        if (tokens.size() < 2) return null;
        String first = tokens.get(0).text().toLowerCase();
        if (!"new".equals(first)) return null;
        String second = tokens.get(1).text().toLowerCase();
        if ("list".equals(second)) return RefType.byId("LIST");
        if ("map".equals(second)) return RefType.byId("MAP");
        return null;
    }

    /**
     * Infers the return type of a matched expression by looking up its description in the documentation.
     *
     * @param description the expression description from pattern metadata
     * @param docs        the documentation data
     * @return the inferred return info
     */
    private @NotNull ExpressionReturn inferExpressionReturn(@Nullable String description,
                                                            @NotNull DocumentationData docs) {
        if (description == null) return new ExpressionReturn(null, null);
        for (PatternEntry entry : docs.expressions()) {
            if (description.equals(entry.description())) {
                RefType ref = entry.returnRefTypeId() != null ? RefType.byId(entry.returnRefTypeId()) : null;
                return new ExpressionReturn(ref, entry.returnJavaType());
            }
        }
        return new ExpressionReturn(null, null);
    }

    /**
     * Searches for the closest matching pattern from documentation when a statement
     * could not be classified.
     *
     * @param tokens the tokens from the unrecognized statement
     * @param docs   the documentation data to search
     * @return the closest match, or null if no reasonable match is found
     */
    private @Nullable ClosestMatch findClosestMatch(@NotNull List<Token> tokens,
                                                    @NotNull DocumentationData docs) {
        if (tokens.isEmpty()) return null;

        List<String> tokenTexts = new ArrayList<>();
        for (Token t : tokens) {
            tokenTexts.add(t.text().toLowerCase());
        }

        String bestPattern = null;
        PatternEntry bestEntry = null;
        double bestConfidence = 0;

        for (PatternEntry entry : docs.statements()) {
            for (String pattern : entry.patterns()) {
                double confidence = patternConfidence(tokenTexts, pattern);
                if (confidence > bestConfidence) {
                    bestConfidence = confidence;
                    bestPattern = pattern;
                    bestEntry = entry;
                }
            }
        }
        for (PatternEntry entry : docs.expressions()) {
            for (String pattern : entry.patterns()) {
                double confidence = patternConfidence(tokenTexts, pattern);
                if (confidence > bestConfidence) {
                    bestConfidence = confidence;
                    bestPattern = pattern;
                    bestEntry = entry;
                }
            }
        }

        if (bestPattern == null || bestConfidence < 0.30) return null;
        return new ClosestMatch(bestPattern, bestEntry, bestConfidence);
    }

    /**
     * Computes a confidence score (0.0 to 1.0) for how well input tokens match a pattern.
     * Uses ordered subsequence matching (70% weight) combined with token count alignment (30% weight).
     *
     * @param tokenTexts the lowercased token texts
     * @param pattern    the pattern string to score against
     * @return the confidence score
     */
    private double patternConfidence(@NotNull List<String> tokenTexts, @NotNull String pattern) {
        List<String> literals = extractLiterals(pattern);
        if (literals.isEmpty() || tokenTexts.isEmpty()) return 0;

        // first literal must match the first token exactly
        if (!literals.get(0).equals(tokenTexts.get(0))) return 0;

        // ordered subsequence match: count how many literals appear in order
        int matched = 0;
        int li = 0;
        for (int ti = 0; ti < tokenTexts.size() && li < literals.size(); ti++) {
            if (tokenTexts.get(ti).equals(literals.get(li))) {
                matched++;
                li++;
            }
        }
        double literalRatio = (double) matched / literals.size();

        // penalize token count mismatch
        int expectedTokens = countPatternTokens(pattern);
        int diff = tokenTexts.size() - expectedTokens;
        double tokenFit;
        if (diff >= 0) {
            tokenFit = 1.0 - 0.3 * diff / Math.max(expectedTokens, 1);
        } else {
            tokenFit = 1.0 + (double) diff / Math.max(expectedTokens, 1);
        }
        tokenFit = Math.max(0, Math.min(1.0, tokenFit));

        // weighted combination: 70% literal match, 30% token count fit
        return literalRatio * 0.7 + tokenFit * 0.3;
    }

    /**
     * Counts expected tokens in a pattern by splitting on whitespace.
     *
     * @param pattern the pattern string
     * @return the expected token count
     */
    private int countPatternTokens(@NotNull String pattern) {
        String[] parts = pattern.split("\\s+");
        int count = 0;
        for (String part : parts) {
            if (!part.isEmpty()) count++;
        }
        return count;
    }

    /**
     * Extracts literal (non-placeholder) words from a pattern string in order.
     *
     * @param pattern the pattern string
     * @return the ordered list of lowercased literal words
     */
    private @NotNull List<String> extractLiterals(@NotNull String pattern) {
        List<String> literals = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inPlaceholder = false;

        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            // toggle placeholder state on '%' delimiters
            if (c == '%') {
                inPlaceholder = !inPlaceholder;
                if (!inPlaceholder) current.append(' ');
                continue;
            }
            if (!inPlaceholder) {
                // treat brackets as word separators
                if (c == '[' || c == ']') {
                    current.append(' ');
                } else if (c == '(') {
                    // for alternation groups like (a|b|c), take only the first option
                    int end = pattern.indexOf(')', i);
                    if (end > i) {
                        String group = pattern.substring(i + 1, end);
                        current.append(' ').append(group.split("\\|")[0]).append(' ');
                        i = end;
                    }
                } else {
                    current.append(c);
                }
            }
        }

        for (String word : current.toString().split("\\s+")) {
            if (!word.isEmpty()) {
                literals.add(word.toLowerCase());
            }
        }
        return literals;
    }

    /**
     * Creates a synthetic {@link PatternMeta} for a structurally matched pattern.
     *
     * @param entry      the matched documentation entry
     * @param confidence the confidence score
     * @return the constructed metadata
     */
    private @NotNull PatternMeta closestMeta(@Nullable PatternEntry entry, double confidence) {
        int pct = (int) (confidence * 100);
        String note = "Matched by structural similarity (" + pct + "% confidence, may be incorrect)";
        String desc = entry != null && entry.description() != null
                ? note + "\n\n" + entry.description()
                : note;
        return new PatternMeta(
                entry != null ? entry.by() : null,
                desc,
                entry != null ? entry.examples() : List.of(),
                entry != null ? entry.since() : null,
                entry != null && entry.category() != null ? Categories.createOrGet(entry.category()) : null,
                entry != null && entry.deprecated()
        );
    }

    /**
     * Injects block-provided variables by looking up the block entry for the given keyword.
     *
     * @param keyword the block keyword
     * @param block   the block node
     * @param state   the analysis state
     */
    private void injectVariables(@NotNull String keyword, @NotNull BlockNode block,
                                 @NotNull AnalysisState state) {
        BlockEntry entry = findBlock(state.docs(), keyword);
        if (entry == null || entry.variables() == null) return;
        injectVariables(entry, block, state);
    }

    /**
     * Injects block-provided variables from a known block entry into the current scope.
     *
     * @param entry the block entry with variable definitions
     * @param block the block node
     * @param state the analysis state
     */
    private void injectVariables(@NotNull BlockEntry entry, @NotNull BlockNode block,
                                 @NotNull AnalysisState state) {
        if (entry.variables() == null) return;
        for (BlockVariable var : entry.variables()) {
            RefType refType = var.refType() != null ? RefType.byId(var.refType()) : null;
            declare(var.name(), var.type(), refType, block.line(), true, state);
        }
    }

    /**
     * Checks whether a block is used at the correct nesting level.
     *
     * @param block the block node
     * @param entry the block documentation entry
     * @param state the analysis state
     */
    private void checkNesting(@NotNull BlockNode block, @NotNull BlockEntry entry,
                              @NotNull AnalysisState state) {
        BlockContext parentCtx = state.env().blockContext().parent();
        boolean atRoot = parentCtx != null && parentCtx.parent() == null;
        if (atRoot && !entry.supportsRootLevel()) {
            state.report(diagnostic(block,
                    "'" + block.head().get(0).text() + "' cannot be used at the top level",
                    LumenSeverity.ERROR));
        }
        if (!atRoot && !entry.supportsBlock()) {
            state.report(diagnostic(block,
                    "'" + block.head().get(0).text() + "' cannot be used inside a block",
                    LumenSeverity.ERROR));
        }
    }

    /**
     * Finds a block entry from documentation matching the given keyword.
     *
     * @param docs    the documentation data
     * @param keyword the block keyword
     * @return the matching block entry, or null
     */
    private @Nullable BlockEntry findBlock(@NotNull DocumentationData docs, @NotNull String keyword) {
        String lower = keyword.toLowerCase();
        for (BlockEntry entry : docs.blocks()) {
            for (String pattern : entry.patterns()) {
                if (pattern.toLowerCase().startsWith(lower + " ") || pattern.toLowerCase().equals(lower)) {
                    return entry;
                }
            }
        }
        return null;
    }

    /**
     * Extracts the event name from a block header by joining all tokens after the first.
     *
     * @param head the header token list
     * @return the event name string
     */
    private @NotNull String extractEventName(@NotNull List<Token> head) {
        if (head.size() < 2) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < head.size(); i++) {
            if (i > 1) sb.append(' ');
            sb.append(head.get(i).text());
        }
        return sb.toString();
    }

    /**
     * Looks up an event entry from documentation by name.
     *
     * @param docs the documentation data
     * @param name the event name
     * @return the matching event entry, or null
     */
    private @Nullable EventEntry findEvent(@NotNull DocumentationData docs, @NotNull String name) {
        String normalized = name.replace(' ', '_').toLowerCase();
        for (EventEntry event : docs.events()) {
            if (event.name().replace(' ', '_').toLowerCase().equals(normalized)) {
                return event;
            }
        }
        return null;
    }

    /**
     * Infers the Java type of a config entry value from its tokens.
     *
     * @param tokens the config entry tokens
     * @return the inferred type name
     */
    private @NotNull String inferConfigType(@NotNull List<Token> tokens) {
        if (tokens.size() < 2) return "Object";
        String value = tokens.get(1).text();
        if (value.startsWith("\"") && value.endsWith("\"")) return "String";
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) return "boolean";
        try {
            Double.parseDouble(value);
            return "double";
        } catch (NumberFormatException ignored) {
        }
        return "Object";
    }

    /**
     * Creates a {@link LumenDiagnostic} for a given AST node using its token range.
     *
     * @param node     the AST node
     * @param message  the diagnostic message
     * @param severity the severity
     * @return the constructed diagnostic
     */
    private @NotNull LumenDiagnostic diagnostic(@NotNull Node node, @NotNull String message,
                                                @NotNull LumenSeverity severity) {
        List<Token> tokens = node.head();
        int colStart = 0;
        int colEnd = 0;
        if (tokens != null && !tokens.isEmpty()) {
            colStart = tokens.get(0).start();
            colEnd = tokens.get(tokens.size() - 1).end();
        }
        return new LumenDiagnostic(node.line(), colStart, colEnd, message, severity);
    }

    /**
     * Validates indentation consistency across all lines.
     *
     * @param lines the tokenized lines
     * @param state the analysis state
     */
    private void checkIndentation(@NotNull List<Line> lines, @NotNull AnalysisState state) {
        try {
            if (lines.size() < 2) return;

            // count how often each indent increment appears
            Map<Integer, Integer> incrementCounts = new HashMap<>();
            for (int i = 1; i < lines.size(); i++) {
                int diff = lines.get(i).indent() - lines.get(i - 1).indent();
                if (diff > 0) {
                    incrementCounts.merge(diff, 1, Integer::sum);
                }
            }
            if (incrementCounts.isEmpty()) return;

            // the most common increment is treated as the expected indent width
            int dominantIncrement = incrementCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .get()
                    .getKey();

            if (incrementCounts.size() > 1) {
                state.report(new LumenDiagnostic(1, 0, 0,
                        "Inconsistent indentation: found indent widths of " + incrementCounts.keySet()
                                + ", expected a consistent width of " + dominantIncrement + " spaces",
                        LumenSeverity.WARNING));
            }

            for (Line line : lines) {
                if (line.indent() > 0 && line.indent() % dominantIncrement != 0) {
                    state.report(new LumenDiagnostic(line.lineNumber(), 0, line.indent(),
                            "Indent of " + line.indent()
                                    + " spaces is not a multiple of the detected indent width ("
                                    + dominantIncrement + ")",
                            LumenSeverity.WARNING));
                }
            }
        } catch (Exception ignored) {
        }
    }
}
