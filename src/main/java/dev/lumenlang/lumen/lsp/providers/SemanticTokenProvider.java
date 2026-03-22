package dev.lumenlang.lumen.lsp.providers;

import dev.lumenlang.lumen.lsp.analysis.AnalysisResult;
import dev.lumenlang.lumen.lsp.analysis.line.LineInfo;
import dev.lumenlang.lumen.lsp.analysis.line.LineKind;
import dev.lumenlang.lumen.lsp.documentation.DocumentationData;
import dev.lumenlang.lumen.lsp.entries.documentation.BlockEntry;
import dev.lumenlang.lumen.lsp.server.tokens.LumenSemanticTokens;
import dev.lumenlang.lumen.pipeline.minicolorize.tag.ColorTag;
import dev.lumenlang.lumen.pipeline.minicolorize.tag.DecorationTag;
import dev.lumenlang.lumen.pipeline.minicolorize.tag.ResetTag;
import org.eclipse.lsp4j.SemanticTokens;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provides semantic tokens for Lumen scripts.
 */
public final class SemanticTokenProvider {

    private static final Set<String> CONTROL_KEYWORDS = Set.of(
            "if", "else", "loop", "stop", "cancel"
    );

    private static final Set<String> DECLARATION_KEYWORDS = Set.of(
            "var", "global", "stored", "store", "java", "data"
    );

    private static final Set<String> MODIFIER_KEYWORDS = Set.of(
            "in", "as", "default", "for", "ref", "type", "of", "from", "to"
    );

    private static final Set<String> LOGIC_KEYWORDS = Set.of(
            "is", "not", "and", "or", "contains", "equals"
    );

    private static final Set<String> ACTION_KEYWORDS = Set.of(
            "add", "subtract", "multiply", "divide", "set", "remove"
    );

    private static final Set<String> LITERAL_KEYWORDS = Set.of(
            "true", "false", "null"
    );

    private static final int K_IDENT = 0;
    private static final int K_NUMBER = 1;
    private static final int K_STRING = 2;
    private static final int K_SYMBOL = 3;
    private static final int K_COMMENT = 4;
    private static final int K_MINI_TAG = 5;

    /**
     * Computes semantic tokens for the given source document.
     *
     * @param source   the full document text, or null if unavailable
     * @param analysis the analysis result, or null
     * @param docs     the documentation data
     * @return the computed semantic tokens
     */
    public @NotNull SemanticTokens tokens(@Nullable String source,
                                          @Nullable AnalysisResult analysis,
                                          @NotNull DocumentationData docs) {
        if (source == null) {
            return new SemanticTokens(List.of());
        }

        Set<String> blockKw = blockKeywords(docs);
        Map<Integer, LineInfo> infoMap = analysis != null ? analysis.lineInfo() : Map.of();
        Map<String, ?> variables = analysis != null ? analysis.variables() : Map.of();

        String[] lines = source.split("\\r?\\n", -1);
        List<int[]> raw = new ArrayList<>();

        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            String line = lines[lineIdx];
            if (line.isBlank()) continue;

            LineInfo info = infoMap.get(lineIdx + 1);
            List<Tok> toks = tokenize(line);

            toks = expandMiniTags(toks);

            for (int idx = 0; idx < toks.size(); idx++) {
                Tok tok = toks.get(idx);
                int type = classify(tok, idx, toks, info, variables, blockKw);
                int mods = modifiers(tok, idx, toks, info);
                raw.add(new int[]{lineIdx, tok.start, tok.length, type, mods});
            }
        }

        // encode as delta values per LSP semantic tokens spec:
        // [deltaLine, deltaStart, length, tokenType, tokenModifiers]
        List<Integer> data = new ArrayList<>(raw.size() * 5);
        int prevLine = 0;
        int prevStart = 0;
        for (int[] entry : raw) {
            int deltaLine = entry[0] - prevLine;
            int deltaStart = deltaLine == 0 ? entry[1] - prevStart : entry[1];
            data.add(deltaLine);
            data.add(deltaStart);
            data.add(entry[2]);
            data.add(entry[3]);
            data.add(entry[4]);
            prevLine = entry[0];
            prevStart = entry[1];
        }

        return new SemanticTokens(data);
    }

    /**
     * Tokenizes a single source line into a flat list of tokens with start, length, text, and kind.
     *
     * @param line the raw source line to tokenize
     * @return the list of lexed tokens
     */
    private @NotNull List<Tok> tokenize(@NotNull String line) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int len = line.length();

        while (i < len) {
            char c = line.charAt(i);

            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            if (c == '#' || (c == '/' && i + 1 < len && line.charAt(i + 1) == '/')) {
                out.add(new Tok(i, len - i, line.substring(i), K_COMMENT));
                break;
            }

            if (c == '"') {
                int start = i;
                i++;
                while (i < len && line.charAt(i) != '"') {
                    if (line.charAt(i) == '\\' && i + 1 < len) i++;
                    i++;
                }
                if (i < len) i++;
                out.add(new Tok(start, i - start, line.substring(start, i), K_STRING));
                continue;
            }

            if (Character.isDigit(c)) {
                int start = i;
                while (i < len && Character.isDigit(line.charAt(i))) i++;
                if (i < len && line.charAt(i) == '.' && i + 1 < len && Character.isDigit(line.charAt(i + 1))) {
                    do i++;
                    while (i < len && Character.isDigit(line.charAt(i)));
                }
                out.add(new Tok(start, i - start, line.substring(start, i), K_NUMBER));
                continue;
            }

            if (isIdentStart(c)) {
                int start = i;
                while (i < len && isIdentPart(line.charAt(i))) i++;
                out.add(new Tok(start, i - start, line.substring(start, i), K_IDENT));
                continue;
            }

            out.add(new Tok(i, 1, String.valueOf(c), K_SYMBOL));
            i++;
        }

        return out;
    }

    /**
     * Returns true if the character can start an identifier.
     *
     * @param c the character to test
     * @return true if letter or underscore
     */
    private boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    /**
     * Returns true if the character can continue an identifier.
     *
     * @param c the character to test
     * @return true if letter, digit, or underscore
     */
    private boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * Classifies a token into a semantic token type based on its kind and context.
     *
     * @param tok           the token to classify
     * @param idx           the token's index in the line token list
     * @param toks          all tokens on the line
     * @param info          the line info, may be null
     * @param variables     the known variable names
     * @param blockKeywords the block keywords from documentation
     * @return the semantic token type index
     */
    private int classify(@NotNull Tok tok, int idx, @NotNull List<Tok> toks,
                         @Nullable LineInfo info, @NotNull Map<String, ?> variables,
                         @NotNull Set<String> blockKeywords) {
        return switch (tok.kind) {
            case K_COMMENT -> LumenSemanticTokens.TYPE_COMMENT;
            case K_NUMBER -> LumenSemanticTokens.TYPE_NUMBER;
            case K_STRING -> LumenSemanticTokens.TYPE_STRING;
            case K_MINI_TAG -> classifyMiniTag(tok.text);
            case K_SYMBOL -> {
                if (tok.text.equals("%")) yield LumenSemanticTokens.TYPE_TYPE;
                yield LumenSemanticTokens.TYPE_OPERATOR;
            }
            case K_IDENT -> classifyIdent(tok, idx, toks, info, variables, blockKeywords);
            default -> LumenSemanticTokens.TYPE_VARIABLE;
        };
    }

    /**
     * Classifies an identifier token into a semantic token type.
     *
     * @param tok           the identifier token
     * @param idx           the token's index in the line token list
     * @param toks          all tokens on the line
     * @param info          the line info, may be null
     * @param variables     the known variable names
     * @param blockKeywords the block keywords from documentation
     * @return the semantic token type index
     */
    private int classifyIdent(@NotNull Tok tok, int idx, @NotNull List<Tok> toks,
                              @Nullable LineInfo info, @NotNull Map<String, ?> variables,
                              @NotNull Set<String> blockKeywords) {
        String text = tok.text;
        String lower = text.toLowerCase();

        if (LITERAL_KEYWORDS.contains(lower)) {
            return LumenSemanticTokens.TYPE_NUMBER;
        }

        if (info != null) {
            switch (info.kind()) {
                case EVENT_BLOCK -> {
                    if (lower.equals("on")) return LumenSemanticTokens.TYPE_KEYWORD;
                    return LumenSemanticTokens.TYPE_EVENT;
                }
                case LOOP_BLOCK -> {
                    if (lower.equals("loop") || lower.equals("in")) return LumenSemanticTokens.TYPE_KEYWORD;
                    int identIdx = identIndex(idx, toks);
                    if (identIdx == 1) return LumenSemanticTokens.TYPE_PARAMETER;
                    if (identIdx == 2) {
                        Tok next = nextIdent(idx, toks);
                        if (next != null && next.text.equalsIgnoreCase("in")) {
                            return LumenSemanticTokens.TYPE_PARAMETER;
                        }
                    }
                    if (variables.containsKey(text)) return LumenSemanticTokens.TYPE_VARIABLE;
                    return LumenSemanticTokens.TYPE_FUNCTION;
                }
                case CONDITIONAL -> {
                    if (lower.equals("if") || lower.equals("else")) return LumenSemanticTokens.TYPE_KEYWORD;
                    if (LOGIC_KEYWORDS.contains(lower)) return LumenSemanticTokens.TYPE_KEYWORD;
                    if (isAnyKeyword(lower, blockKeywords)) return LumenSemanticTokens.TYPE_KEYWORD;
                    if (variables.containsKey(text)) return LumenSemanticTokens.TYPE_VARIABLE;
                    return LumenSemanticTokens.TYPE_FUNCTION;
                }
                case VAR_DECL, EXPR_VAR_DECL -> {
                    if (lower.equals("var")) return LumenSemanticTokens.TYPE_KEYWORD;
                    if (isVarNameTok(idx, toks)) return LumenSemanticTokens.TYPE_VARIABLE;
                    if (isAnyKeyword(lower, blockKeywords)) return LumenSemanticTokens.TYPE_KEYWORD;
                    if (isInsidePlaceholder(idx, toks)) return LumenSemanticTokens.TYPE_TYPE;
                    if (variables.containsKey(text)) return LumenSemanticTokens.TYPE_VARIABLE;
                    return LumenSemanticTokens.TYPE_FUNCTION;
                }
                case GLOBAL_VAR -> {
                    if (lower.equals("global") || lower.equals("var") || lower.equals("stored")
                            || lower.equals("default") || lower.equals("for") || lower.equals("ref")
                            || lower.equals("type")) {
                        return LumenSemanticTokens.TYPE_KEYWORD;
                    }
                    if (isGlobalVarNameTok(idx, toks)) return LumenSemanticTokens.TYPE_NAMESPACE;
                    if (isInsidePlaceholder(idx, toks)) return LumenSemanticTokens.TYPE_TYPE;
                    if (variables.containsKey(text)) return LumenSemanticTokens.TYPE_VARIABLE;
                    return LumenSemanticTokens.TYPE_FUNCTION;
                }
                case STORE_VAR -> {
                    if (lower.equals("stored") || lower.equals("var") || lower.equals("store")
                            || lower.equals("default") || lower.equals("for") || lower.equals("ref")
                            || lower.equals("type")) {
                        return LumenSemanticTokens.TYPE_KEYWORD;
                    }
                    if (isStoreVarNameTok(idx, toks)) return LumenSemanticTokens.TYPE_NAMESPACE;
                    if (isInsidePlaceholder(idx, toks)) return LumenSemanticTokens.TYPE_TYPE;
                    if (variables.containsKey(text)) return LumenSemanticTokens.TYPE_VARIABLE;
                    return LumenSemanticTokens.TYPE_FUNCTION;
                }
                case RAW_BLOCK -> {
                    if (lower.equals("java")) return LumenSemanticTokens.TYPE_KEYWORD;
                    return LumenSemanticTokens.TYPE_NAMESPACE;
                }
                case DATA_BLOCK -> {
                    if (lower.equals("data")) return LumenSemanticTokens.TYPE_KEYWORD;
                    return LumenSemanticTokens.TYPE_TYPE;
                }
                case DATA_FIELD -> {
                    int identIdx = identIndex(idx, toks);
                    if (identIdx == 0) return LumenSemanticTokens.TYPE_PROPERTY;
                    return LumenSemanticTokens.TYPE_TYPE;
                }
                case CONFIG_BLOCK -> {
                    if (lower.equals("config")) return LumenSemanticTokens.TYPE_KEYWORD;
                    return LumenSemanticTokens.TYPE_VARIABLE;
                }
                case CONFIG_ENTRY -> {
                    int identIdx = identIndex(idx, toks);
                    if (identIdx == 0) return LumenSemanticTokens.TYPE_PROPERTY;
                    if (LITERAL_KEYWORDS.contains(lower)) return LumenSemanticTokens.TYPE_NUMBER;
                    return LumenSemanticTokens.TYPE_STRING;
                }
                case PATTERN_BLOCK, STATEMENT, EXPRESSION -> {
                    if (isAnyKeyword(lower, blockKeywords)) return LumenSemanticTokens.TYPE_KEYWORD;
                    if (isInsidePlaceholder(idx, toks)) return LumenSemanticTokens.TYPE_TYPE;
                    if (variables.containsKey(text)) return LumenSemanticTokens.TYPE_VARIABLE;
                    return LumenSemanticTokens.TYPE_FUNCTION;
                }
                case ERROR -> {
                    if (isAnyKeyword(lower, blockKeywords)) return LumenSemanticTokens.TYPE_KEYWORD;
                    if (variables.containsKey(text)) return LumenSemanticTokens.TYPE_VARIABLE;
                    return LumenSemanticTokens.TYPE_VARIABLE;
                }
                default -> {
                }
            }
        }

        if (CONTROL_KEYWORDS.contains(lower)) return LumenSemanticTokens.TYPE_KEYWORD;
        if (DECLARATION_KEYWORDS.contains(lower)) return LumenSemanticTokens.TYPE_KEYWORD;
        if (blockKeywords.contains(lower)) return LumenSemanticTokens.TYPE_KEYWORD;
        if (MODIFIER_KEYWORDS.contains(lower)) return LumenSemanticTokens.TYPE_KEYWORD;
        if (ACTION_KEYWORDS.contains(lower)) return LumenSemanticTokens.TYPE_KEYWORD;
        if (LOGIC_KEYWORDS.contains(lower)) return LumenSemanticTokens.TYPE_KEYWORD;

        if (isInsidePlaceholder(idx, toks)) {
            return LumenSemanticTokens.TYPE_TYPE;
        }

        return LumenSemanticTokens.TYPE_VARIABLE;
    }

    /**
     * Returns true if the given lowercase identifier matches any keyword set.
     *
     * @param lower         the lowercase identifier to test
     * @param blockKeywords the block keywords from documentation
     * @return true if the identifier is a known keyword
     */
    private boolean isAnyKeyword(@NotNull String lower, @NotNull Set<String> blockKeywords) {
        return CONTROL_KEYWORDS.contains(lower)
                || DECLARATION_KEYWORDS.contains(lower)
                || blockKeywords.contains(lower)
                || MODIFIER_KEYWORDS.contains(lower)
                || ACTION_KEYWORDS.contains(lower)
                || LOGIC_KEYWORDS.contains(lower)
                || LITERAL_KEYWORDS.contains(lower);
    }

    /**
     * Returns true if the token at the given index appears inside a placeholder expression
     * delimited by an odd number of preceding percent signs.
     *
     * @param idx  the index of the token to test
     * @param toks all tokens on the line
     * @return true if the token is inside a placeholder
     */
    private boolean isInsidePlaceholder(int idx, @NotNull List<Tok> toks) {
        // odd number of preceding '%' means we are between an open and close placeholder
        int percentCount = 0;
        for (int i = 0; i < idx; i++) {
            if (toks.get(i).kind == K_SYMBOL && toks.get(i).text.equals("%")) {
                percentCount++;
            }
        }
        return percentCount % 2 != 0;
    }

    /**
     * Returns the 0-based index of the given token among all identifier tokens on the line.
     *
     * @param tokIdx the position of the token in the full token list
     * @param toks   all tokens on the line
     * @return the identifier-only index
     */
    private int identIndex(int tokIdx, @NotNull List<Tok> toks) {
        int count = 0;
        for (int i = 0; i <= tokIdx; i++) {
            if (toks.get(i).kind == K_IDENT) count++;
        }
        return count - 1;
    }

    /**
     * Returns the next identifier token after the given index, or null if none exists.
     *
     * @param tokIdx the current position in the full token list
     * @param toks   all tokens on the line
     * @return the next identifier token, or null
     */
    private @Nullable Tok nextIdent(int tokIdx, @NotNull List<Tok> toks) {
        for (int i = tokIdx + 1; i < toks.size(); i++) {
            if (toks.get(i).kind == K_IDENT) return toks.get(i);
        }
        return null;
    }

    /**
     * Computes the semantic token modifier bitmask for the given token based on its declaration
     * or definition role in the line context.
     *
     * @param tok  the token to compute modifiers for
     * @param idx  the token's index in the line token list
     * @param toks all tokens on the line
     * @param info the line info for context, may be null
     * @return the modifier bitmask
     */
    private int modifiers(@NotNull Tok tok, int idx, @NotNull List<Tok> toks,
                          @Nullable LineInfo info) {
        if (tok.kind != K_IDENT) return 0;
        int mods = 0;

        if (info != null) {
            if (info.kind() == LineKind.VAR_DECL || info.kind() == LineKind.EXPR_VAR_DECL) {
                if (isVarNameTok(idx, toks)) {
                    mods |= LumenSemanticTokens.MOD_DECLARATION;
                }
            }

            if (info.kind() == LineKind.GLOBAL_VAR) {
                if (isGlobalVarNameTok(idx, toks)) {
                    mods |= LumenSemanticTokens.MOD_DECLARATION | LumenSemanticTokens.MOD_READONLY;
                }
            }

            if (info.kind() == LineKind.STORE_VAR) {
                if (isStoreVarNameTok(idx, toks)) {
                    mods |= LumenSemanticTokens.MOD_DECLARATION | LumenSemanticTokens.MOD_READONLY;
                }
            }

            if (info.kind() == LineKind.LOOP_BLOCK && identIndex(idx, toks) == 1) {
                mods |= LumenSemanticTokens.MOD_DECLARATION;
            }

            if (info.kind() == LineKind.EVENT_BLOCK && !tok.text.equalsIgnoreCase("on")) {
                mods |= LumenSemanticTokens.MOD_DEFINITION;
            }

            if (info.kind() == LineKind.DATA_BLOCK && identIndex(idx, toks) == 1) {
                mods |= LumenSemanticTokens.MOD_DEFINITION;
            }

            if (info.meta() != null && info.meta().deprecated()) {
                mods |= LumenSemanticTokens.MOD_DEPRECATED;
            }
        }

        return mods;
    }

    /**
     * Expands string tokens that contain MiniColorize tags into separate string and tag sub-tokens
     * for more precise highlighting of inline color and decoration markup.
     *
     * @param toks the list of tokens to process
     * @return a new list with MiniColorize tags split out as separate tokens
     */
    private @NotNull List<Tok> expandMiniTags(@NotNull List<Tok> toks) {
        List<Tok> result = new ArrayList<>(toks.size());
        for (Tok tok : toks) {
            // only split string tokens that could contain tags
            if (tok.kind != K_STRING || tok.text.length() < 3 || !tok.text.contains("<")) {
                result.add(tok);
                continue;
            }
            String content = tok.text;
            int base = tok.start;
            int pos = 0;
            int len = content.length();
            int segStart = 0;

            while (pos < len) {
                // look for '<' that could be a tag opener (not at position 0 since that's the quote)
                if (content.charAt(pos) == '<' && pos > 0 && pos + 1 < len && content.charAt(pos + 1) != ' ') {
                    int close = content.indexOf('>', pos + 1);
                    if (close > pos + 1) {
                        // emit the plain string segment before this tag
                        if (pos > segStart) {
                            result.add(new Tok(base + segStart, pos - segStart,
                                    content.substring(segStart, pos), K_STRING));
                        }
                        String tagText = content.substring(pos, close + 1);
                        // strip closing slash and negation prefix to get the base tag name
                        String tagInner = content.substring(pos + 1, close);
                        if (tagInner.startsWith("/")) tagInner = tagInner.substring(1);
                        if (tagInner.startsWith("!")) tagInner = tagInner.substring(1);
                        if (isMiniTag(tagInner)) {
                            result.add(new Tok(base + pos, close + 1 - pos, tagText, K_MINI_TAG));
                        } else {
                            result.add(new Tok(base + pos, close + 1 - pos, tagText, K_STRING));
                        }
                        pos = close + 1;
                        segStart = pos;
                        continue;
                    }
                }
                pos++;
            }
            if (segStart < len) {
                result.add(new Tok(base + segStart, len - segStart,
                        content.substring(segStart), K_STRING));
            }
        }
        return result;
    }

    /**
     * Returns true if the given inner tag text is a recognized MiniColorize tag.
     *
     * @param inner the tag content without surrounding angle brackets
     * @return true if the tag is a known color, decoration, reset, or special tag
     */
    private boolean isMiniTag(@NotNull String inner) {
        if (inner.isEmpty()) return false;
        if (ColorTag.parse(inner) != null) return true;
        if (DecorationTag.parse(inner, false) != null) return true;
        if (ResetTag.isReset(inner)) return true;
        String lower = inner.toLowerCase();
        return lower.startsWith("gradient:") || lower.startsWith("rainbow")
                || lower.startsWith("click:") || lower.startsWith("hover:")
                || lower.startsWith("key:") || lower.startsWith("lang:")
                || lower.startsWith("insert:") || lower.startsWith("transition:");
    }

    /**
     * Classifies a MiniColorize tag token into a semantic token type based on its inner content.
     *
     * @param tagText the full tag text including angle brackets
     * @return the semantic token type index
     */
    private int classifyMiniTag(@NotNull String tagText) {
        String inner = tagText;
        if (inner.startsWith("<")) inner = inner.substring(1);
        if (inner.endsWith(">")) inner = inner.substring(0, inner.length() - 1);
        if (inner.startsWith("/")) inner = inner.substring(1);
        if (inner.startsWith("!")) inner = inner.substring(1);
        if (ColorTag.parse(inner) != null) return LumenSemanticTokens.TYPE_NUMBER;
        if (DecorationTag.parse(inner, false) != null) return LumenSemanticTokens.TYPE_KEYWORD;
        if (ResetTag.isReset(inner)) return LumenSemanticTokens.TYPE_KEYWORD;
        return LumenSemanticTokens.TYPE_TYPE;
    }

    /**
     * Returns true if the token at the given index is the declared variable name in a var-declaration line.
     *
     * @param idx  the token index to test
     * @param toks all tokens on the line
     * @return true if this token immediately follows the 'var' keyword
     */
    private boolean isVarNameTok(int idx, @NotNull List<Tok> toks) {
        for (int i = 0; i < toks.size(); i++) {
            if (toks.get(i).kind == K_IDENT && toks.get(i).text.equalsIgnoreCase("var") && i + 1 < toks.size()) {
                Tok next = toks.get(i + 1);
                if (next.kind == K_IDENT) {
                    return i + 1 == idx;
                }
            }
        }
        return false;
    }

    /**
     * Returns true if the token at the given index is the declared variable name in a global var-declaration line.
     *
     * @param idx  the token index to test
     * @param toks all tokens on the line
     * @return true if this token is the global variable name
     */
    private boolean isGlobalVarNameTok(int idx, @NotNull List<Tok> toks) {
        for (int i = 0; i < toks.size(); i++) {
            if (toks.get(i).kind == K_IDENT && toks.get(i).text.equalsIgnoreCase("var") && i + 1 < toks.size()) {
                Tok next = toks.get(i + 1);
                if (next.kind == K_IDENT) return i + 1 == idx;
            }
        }
        if (toks.size() >= 2 && toks.get(0).kind == K_IDENT && toks.get(0).text.equalsIgnoreCase("global")) {
            return idx == 1 && toks.get(1).kind == K_IDENT;
        }
        return false;
    }

    /**
     * Returns true if the token at the given index is the declared variable name in a stored var-declaration line.
     *
     * @param idx  the token index to test
     * @param toks all tokens on the line
     * @return true if this token is the stored variable name
     */
    private boolean isStoreVarNameTok(int idx, @NotNull List<Tok> toks) {
        for (int i = 0; i < toks.size(); i++) {
            if (toks.get(i).kind == K_IDENT && toks.get(i).text.equalsIgnoreCase("var") && i + 1 < toks.size()) {
                Tok next = toks.get(i + 1);
                if (next.kind == K_IDENT) return i + 1 == idx;
            }
        }
        if (toks.size() >= 2 && toks.get(0).kind == K_IDENT && toks.get(0).text.equalsIgnoreCase("store")) {
            return idx == 1 && toks.get(1).kind == K_IDENT;
        }
        return false;
    }

    /**
     * Collects block keywords from the documentation by taking the first word of each block's first pattern.
     *
     * @param docs the documentation data
     * @return the set of lowercase block keywords
     */
    private @NotNull Set<String> blockKeywords(@NotNull DocumentationData docs) {
        Set<String> result = new HashSet<>();
        for (BlockEntry entry : docs.blocks()) {
            if (entry.patterns().isEmpty()) continue;
            result.add(entry.patterns().get(0).split("\\s")[0].toLowerCase());
        }
        return result;
    }

    private record Tok(int start, int length, @NotNull String text, int kind) {
    }
}
