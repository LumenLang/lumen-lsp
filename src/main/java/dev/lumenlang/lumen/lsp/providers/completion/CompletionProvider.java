package dev.lumenlang.lumen.lsp.providers.completion;

import dev.lumenlang.lumen.lsp.analysis.AnalysisResult;
import dev.lumenlang.lumen.lsp.analysis.LineAnalysis;
import dev.lumenlang.lumen.lsp.bootstrap.LumenBootstrap;
import dev.lumenlang.lumen.pipeline.codegen.TypeEnv;
import dev.lumenlang.lumen.pipeline.language.pattern.Pattern;
import dev.lumenlang.lumen.pipeline.language.pattern.PatternPart;
import dev.lumenlang.lumen.pipeline.language.pattern.PatternRegistry;
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredBlock;
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredExpression;
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredPattern;
import dev.lumenlang.lumen.pipeline.var.VarRef;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Position;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds context aware completion items. Suggests pattern starts at line begin
 * and variables in scope when the cursor sits inside an expression.
 */
public final class CompletionProvider {

    private CompletionProvider() {
    }

    /**
     * Returns the completion items for the given cursor position.
     *
     * @param bootstrap the populated bootstrap
     * @param result    the analysis of the document
     * @param source    the full document text, used to slice the partial line in front of the cursor
     * @param position  the cursor position
     * @return the completion items, possibly empty
     */
    public static @NotNull List<CompletionItem> complete(@NotNull LumenBootstrap bootstrap, @NotNull AnalysisResult result, @NotNull String source, @NotNull Position position) {
        String[] lines = source.split("\\r?\\n", -1);
        int row = position.getLine();
        int col = position.getCharacter();
        if (row >= lines.length) return List.of();
        String prefix = prefixSlice(lines[row], col).stripLeading();
        TypeEnv env = entryEnv(result, position.getLine() + 1);
        List<CompletionItem> out = new ArrayList<>();
        PatternRegistry registry = bootstrap.patterns();
        String firstWord = firstWordOf(prefix);
        String partial = trailingIdent(prefix);
        boolean lockedHead = !firstWord.isEmpty() && !firstWord.equals(partial);
        for (RegisteredPattern rp : registry.getStatements()) {
            if (relevant(rp.pattern(), firstWord, partial, lockedHead)) {
                addPattern(out, rp.pattern(), CompletionItemKind.Function, "statement");
            }
        }
        for (RegisteredBlock rb : registry.getBlocks()) {
            if (relevant(rb.pattern(), firstWord, partial, lockedHead)) {
                addPattern(out, rb.pattern(), CompletionItemKind.Class, "block");
            }
        }
        for (RegisteredExpression re : registry.getExpressions()) {
            if (relevant(re.pattern(), firstWord, partial, lockedHead)) {
                addPattern(out, re.pattern(), CompletionItemKind.Value, "expression");
            }
        }
        if (env != null) {
            for (String name : env.allVisibleVarNames()) {
                if (partial.isEmpty() || name.toLowerCase().startsWith(partial.toLowerCase())) {
                    addVariable(out, name, env.lookupVar(name));
                }
            }
        }
        return out;
    }

    /**
     * Returns the first whitespace separated token of the trimmed prefix.
     *
     * @param prefix the trimmed line slice in front of the cursor
     * @return the first word, possibly empty
     */
    private static @NotNull String firstWordOf(@NotNull String prefix) {
        for (int i = 0; i < prefix.length(); i++) {
            if (Character.isWhitespace(prefix.charAt(i))) return prefix.substring(0, i);
        }
        return prefix;
    }

    /**
     * Returns whether a pattern is still relevant given the head word the user
     * has already typed and what they are currently typing for the next token.
     *
     * @param pattern    the pattern to test
     * @param firstWord  the first word the user typed on this line, may be empty
     * @param partial    the trailing identifier under the cursor
     * @param lockedHead whether the user already has a complete first word followed by more input
     * @return whether to surface this pattern
     */
    private static boolean relevant(@NotNull Pattern pattern, @NotNull String firstWord, @NotNull String partial, boolean lockedHead) {
        if (lockedHead) {
            return matchesAnyHead(pattern.parts(), firstWord);
        }
        if (partial.isEmpty()) return true;
        for (String head : collectHeads(pattern.parts())) {
            if (head.toLowerCase().startsWith(partial.toLowerCase())) return true;
        }
        return false;
    }

    /**
     * Returns whether any of the pattern's possible head literals equals the
     * given word ignoring case.
     *
     * @param parts the pattern parts
     * @param word  the user's first word
     * @return whether any head matches
     */
    private static boolean matchesAnyHead(@NotNull List<PatternPart> parts, @NotNull String word) {
        for (String head : collectHeads(parts)) {
            if (head.equalsIgnoreCase(word)) return true;
        }
        return false;
    }

    /**
     * Collects every possible literal that could appear at the head of the
     * given pattern parts, descending one level into required and optional
     * groups so that all alternatives of a leading group are returned.
     *
     * @param parts the parts to scan
     * @return the head literals, possibly empty when the pattern starts with a placeholder
     */
    private static @NotNull List<String> collectHeads(@NotNull List<PatternPart> parts) {
        List<String> heads = new ArrayList<>();
        if (parts.isEmpty()) return heads;
        PatternPart head = parts.get(0);
        if (head instanceof PatternPart.Literal lit) {
            heads.add(lit.text());
            return heads;
        }
        if (head instanceof PatternPart.FlexLiteral flex) {
            heads.addAll(flex.forms());
            return heads;
        }
        if (head instanceof PatternPart.Group group) {
            for (List<PatternPart> alt : group.alternatives()) {
                heads.addAll(collectHeads(alt));
            }
            if (!group.required() && parts.size() > 1) {
                heads.addAll(collectHeads(parts.subList(1, parts.size())));
            }
        }
        return heads;
    }

    /**
     * Returns the env to use when looking up variables, taken from the line
     * preceding the cursor, falling back to the latest available line above.
     *
     * @param result     the analysis of the document
     * @param lineNumber the 1-based line number under the cursor
     * @return the env, or {@code null} when no prior line was analysed
     */
    private static @Nullable TypeEnv entryEnv(@NotNull AnalysisResult result, int lineNumber) {
        for (int i = lineNumber - 1; i >= 1; i--) {
            LineAnalysis prev = result.line(i);
            if (prev != null && prev.afterEnv() != null) return prev.afterEnv();
        }
        return null;
    }

    /**
     * Returns the segment of the line text that lies before the cursor column.
     *
     * @param line the full line text
     * @param col  the 0-based cursor column
     * @return the prefix
     */
    private static @NotNull String prefixSlice(@NotNull String line, int col) {
        int safe = Math.min(col, line.length());
        return line.substring(0, safe);
    }

    /**
     * Returns the trailing identifier characters of the given prefix used for
     * filtering candidates against what the user has typed so far.
     *
     * @param prefix the slice of line text in front of the cursor
     * @return the trailing identifier, possibly empty
     */
    private static @NotNull String trailingIdent(@NotNull String prefix) {
        int i = prefix.length();
        while (i > 0) {
            char c = prefix.charAt(i - 1);
            if (Character.isLetterOrDigit(c) || c == '_') i--;
            else break;
        }
        return prefix.substring(i);
    }

    /**
     * Adds an unconditional pattern completion to the output list.
     *
     * @param out     the output list
     * @param pattern the pattern to expose
     * @param kind    the LSP completion kind
     * @param detail  short label shown next to the entry
     */
    private static void addPattern(@NotNull List<CompletionItem> out, @NotNull Pattern pattern, @NotNull CompletionItemKind kind, @NotNull String detail) {
        out.add(buildPatternItem(pattern, kind, detail));
    }


    /**
     * Builds a completion item for the given pattern, using the raw pattern
     * string as both the insert text and the visible label.
     *
     * @param pattern the pattern
     * @param kind    the LSP completion kind
     * @param detail  short label shown next to the entry
     * @return the completion item
     */
    private static @NotNull CompletionItem buildPatternItem(@NotNull Pattern pattern, @NotNull CompletionItemKind kind, @NotNull String detail) {
        CompletionItem item = new CompletionItem(pattern.raw());
        item.setKind(kind);
        item.setDetail(detail);
        item.setInsertText(pattern.raw());
        return item;
    }

    /**
     * Adds a completion entry for the given variable name.
     *
     * @param out  the output list
     * @param name the variable source name
     * @param ref  the resolved var ref, or {@code null} when only the name is known
     */
    private static void addVariable(@NotNull List<CompletionItem> out, @NotNull String name, @Nullable VarRef ref) {
        CompletionItem item = new CompletionItem(name);
        item.setKind(CompletionItemKind.Variable);
        if (ref != null) item.setDetail(ref.type().displayName());
        out.add(item);
    }
}
