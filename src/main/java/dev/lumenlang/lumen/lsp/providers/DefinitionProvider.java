package dev.lumenlang.lumen.lsp.providers;

import dev.lumenlang.lumen.lsp.analysis.AnalysisResult;
import dev.lumenlang.lumen.lsp.analysis.VarDeclaration;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Provides go-to-definition support for Lumen scripts.
 * Resolves variable references back to their declaration site.
 */
public final class DefinitionProvider {

    /**
     * Returns definition locations for the token at the given position.
     *
     * @param params   the definition request parameters
     * @param analysis the analysis result (may be null)
     * @param source   the document source text
     * @param uri      the document URI
     * @return the list of definition locations
     */
    public @NotNull List<Location> definition(@NotNull DefinitionParams params, @Nullable AnalysisResult analysis, @Nullable String source, @NotNull String uri) {
        if (analysis == null || source == null) return List.of();

        String word = wordAt(source, params.getPosition().getLine(), params.getPosition().getCharacter());
        if (word == null) return List.of();

        VarDeclaration var = analysis.variables().get(word);
        if (var == null) return List.of();

        int targetLine = Math.max(0, var.line() - 1);
        int targetCol = findVarColumn(source, var.line(), word);

        Range range = new Range(
                new Position(targetLine, targetCol),
                new Position(targetLine, targetCol + word.length())
        );
        return List.of(new Location(uri, range));
    }

    /**
     * Extracts the identifier word at the given cursor position in the source.
     *
     * @param source the full document text
     * @param line   the 0-based line index
     * @param col    the 0-based column index
     * @return the word under the cursor, or null if not found
     */
    private @Nullable String wordAt(@NotNull String source, int line, int col) {
        String[] lines = source.split("\\r?\\n", -1);
        if (line >= lines.length) return null;
        String text = lines[line];
        if (col >= text.length()) return null;

        int start = col;
        while (start > 0 && isIdentChar(text.charAt(start - 1))) start--;
        int end = col;
        while (end < text.length() && isIdentChar(text.charAt(end))) end++;

        if (start >= end) return null;
        return text.substring(start, end);
    }

    /**
     * Finds the column index of a variable name on the given 1-based line number.
     *
     * @param source  the full document text
     * @param lineNum the 1-based line number
     * @param varName the variable name to locate
     * @return the column index, or 0 if not found
     */
    private int findVarColumn(@NotNull String source, int lineNum, @NotNull String varName) {
        String[] lines = source.split("\\r?\\n", -1);
        if (lineNum < 1 || lineNum > lines.length) return 0;
        String text = lines[lineNum - 1];
        int idx = text.indexOf(varName);
        return Math.max(idx, 0);
    }

    /**
     * Returns true if the given character is valid within an identifier.
     *
     * @param c the character to test
     * @return true if letter, digit, underscore, or apostrophe
     */
    private boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '\'';
    }
}
