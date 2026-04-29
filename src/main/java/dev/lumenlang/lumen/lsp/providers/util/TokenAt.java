package dev.lumenlang.lumen.lsp.providers.util;

import dev.lumenlang.lumen.pipeline.language.tokenization.Token;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Locates the token under a given source position, accounting for the
 * leading whitespace the tokeniser stripped from each line so editor
 * absolute columns map correctly to the content relative token columns.
 */
public final class TokenAt {

    private TokenAt() {
    }

    /**
     * Returns the token whose column range contains the given column, or
     * {@code null} when no token spans that column.
     *
     * @param tokens the tokens of the line, must be in source order
     * @param indent the leading whitespace count of the line, in characters
     * @param column the 0-based cursor column in the LSP absolute space
     * @return the matching token, or {@code null}
     */
    public static @Nullable Token at(@NotNull List<Token> tokens, int indent, int column) {
        int contentCol = column - indent;
        for (Token t : tokens) {
            if (contentCol >= t.start() && contentCol < t.end()) return t;
        }
        return null;
    }
}
