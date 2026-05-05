package dev.lumenlang.lumen.lsp.providers.semantic;

import dev.lumenlang.lumen.lsp.analysis.AnalysisResult;
import dev.lumenlang.lumen.lsp.analysis.LineAnalysis;
import dev.lumenlang.lumen.lsp.analysis.MetaKeys;
import dev.lumenlang.lumen.api.codegen.TypeEnv;
import dev.lumenlang.lumen.pipeline.language.match.BoundValue;
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredBlockMatch;
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredPatternMatch;
import dev.lumenlang.lumen.pipeline.language.tokenization.Token;
import dev.lumenlang.lumen.api.codegen.TypeEnv.VarHandle;
import org.eclipse.lsp4j.SemanticTokens;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds semantic tokens for a document analysis. Token classification mixes
 * lexical kind with the matched pattern overlay so multi token expressions
 * still surface inner numbers, strings, operators, and resolved variables in
 * their natural color rather than collapsing into one solid block.
 */
public final class SemanticTokenProvider {

    private SemanticTokenProvider() {
    }

    /**
     * Builds the encoded semantic tokens payload for the given analysis.
     *
     * @param result the analysis of the document
     * @return the LSP semantic tokens payload
     */
    public static @NotNull SemanticTokens tokens(@NotNull AnalysisResult result) {
        List<int[]> raw = new ArrayList<>();
        for (LineAnalysis line : result.lines()) {
            classifyLine(line, raw);
        }
        scanComments(result.source(), raw);
        raw.sort(Comparator.<int[]>comparingInt(a -> a[0]).thenComparingInt(a -> a[1]));
        return new SemanticTokens(encode(raw));
    }

    /**
     * Walks the raw document text once, emitting a comment semantic entry for
     * each {@code #} or {@code //} sequence that appears outside of a string
     * literal. The tokeniser strips comments so they never reach the line
     * analyses, leaving direct source scanning as the only way to color them.
     *
     * @param source the document text
     * @param out    the accumulator of raw semantic entries
     */
    private static void scanComments(@NotNull String source, @NotNull List<int[]> out) {
        String[] lines = source.split("\\r?\\n", -1);
        for (int row = 0; row < lines.length; row++) {
            String line = lines[row];
            int commentStart = findCommentStart(line);
            if (commentStart < 0) continue;
            int length = line.length() - commentStart;
            if (length <= 0) continue;
            out.add(new int[]{row, commentStart, length, SemanticLegend.TYPE_COMMENT, 0});
        }
    }

    /**
     * Returns the column at which a line comment begins, ignoring any
     * {@code #} or {@code //} occurrence that sits inside a double quoted
     * string literal.
     *
     * @param line the source line
     * @return the start column of the comment, or {@code -1} when none exists
     */
    private static int findCommentStart(@NotNull String line) {
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (c == '\\' && inString && i + 1 < line.length()) {
                i++;
                continue;
            }
            if (inString) continue;
            if (c == '#') return i;
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') return i;
        }
        return -1;
    }

    /**
     * Classifies every token of one line and appends raw entries to the output.
     *
     * @param line the line analysis
     * @param out  the accumulator of raw entries shaped as {@code {row, col, length, type, mods}}
     */
    private static void classifyLine(@NotNull LineAnalysis line, @NotNull List<int[]> out) {
        Map<Integer, BoundValue> bvByStart = new HashMap<>();
        RegisteredPatternMatch sm = line.meta(MetaKeys.STATEMENT_MATCH, RegisteredPatternMatch.class);
        if (sm != null) collectBindings(sm.match().values(), bvByStart);
        RegisteredBlockMatch bm = line.meta(MetaKeys.BLOCK_MATCH, RegisteredBlockMatch.class);
        if (bm != null) collectBindings(bm.match().values(), bvByStart);
        int[] declRange = line.meta(MetaKeys.VAR_DECL_RANGE, int[].class);
        TypeEnv env = line.afterEnv();
        int indent = line.indent();
        for (Token token : line.tokens()) {
            int row = Math.max(0, token.line() - 1);
            int contentCol = token.start();
            int length = token.end() - token.start();
            if (length <= 0) continue;
            BoundValue bv = bvByStart.get(contentCol);
            int type = classify(token, bv, env);
            int mods = 0;
            if (declRange != null && contentCol == declRange[0] && token.end() == declRange[1]) {
                type = SemanticLegend.TYPE_VARIABLE;
                mods |= SemanticLegend.MOD_DECLARATION;
            }
            out.add(new int[]{row, contentCol + indent, length, type, mods});
        }
    }

    /**
     * Records every consumed token start to its bound value so each token can
     * later be classified against the placeholder it belongs to.
     *
     * @param values the bound values of a match
     * @param out    the start to bound value map being built
     */
    private static void collectBindings(@NotNull Map<String, BoundValue> values, @NotNull Map<Integer, BoundValue> out) {
        for (BoundValue bv : values.values()) {
            for (Token tok : bv.tokens()) {
                out.put(tok.start(), bv);
            }
        }
    }

    /**
     * Returns the semantic type for a single token, blending its lexical kind
     * with the placeholder it sits in and any variable resolution available in
     * the post line environment.
     *
     * @param token the token to classify
     * @param bv    the bound value covering this token, or {@code null} when none does
     * @param env   the post line type environment, or {@code null} when none was recorded
     * @return the semantic token type index
     */
    private static int classify(@NotNull Token token, @Nullable BoundValue bv, @Nullable TypeEnv env) {
        return switch (token.kind()) {
            case STRING -> SemanticLegend.TYPE_STRING;
            case NUMBER -> SemanticLegend.TYPE_NUMBER;
            case SYMBOL -> SemanticLegend.TYPE_OPERATOR;
            case IDENT, RAW -> identType(token, bv, env);
        };
    }

    /**
     * Returns the semantic type for an identifier or raw token, preferring a
     * variable lookup when the token resolves in scope and falling back to
     * keyword color for literals not bound by any placeholder.
     *
     * @param token the identifier token
     * @param bv    the bound value covering this token, or {@code null} when this token is a literal
     * @param env   the post line type environment, or {@code null} when none was recorded
     * @return the semantic token type index
     */
    private static int identType(@NotNull Token token, @Nullable BoundValue bv, @Nullable TypeEnv env) {
        if (bv == null) return SemanticLegend.TYPE_KEYWORD;
        VarHandle ref = env != null ? env.lookupVar(token.text()) : null;
        if (ref != null) return SemanticLegend.TYPE_VARIABLE;
        String id = bv.binding().id();
        return switch (id) {
            case "IDENT" -> SemanticLegend.TYPE_VARIABLE;
            case "BOOL", "BOOLEAN" -> SemanticLegend.TYPE_KEYWORD;
            case "TYPE" -> SemanticLegend.TYPE_TYPE;
            default -> SemanticLegend.TYPE_PROPERTY;
        };
    }

    /**
     * Encodes raw entries into the delta encoded integer list expected by the
     * LSP semantic tokens spec.
     *
     * @param raw the list of {@code {row, col, length, type, mods}} entries, must be sorted
     * @return the encoded payload
     */
    private static @NotNull List<Integer> encode(@NotNull List<int[]> raw) {
        List<Integer> data = new ArrayList<>(raw.size() * 5);
        int prevLine = 0;
        int prevStart = 0;
        for (int[] e : raw) {
            int dl = e[0] - prevLine;
            int ds = dl == 0 ? e[1] - prevStart : e[1];
            data.add(dl);
            data.add(ds);
            data.add(e[2]);
            data.add(e[3]);
            data.add(e[4]);
            prevLine = e[0];
            prevStart = e[1];
        }
        return data;
    }
}
