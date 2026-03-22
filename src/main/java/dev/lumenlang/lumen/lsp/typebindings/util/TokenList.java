package dev.lumenlang.lumen.lsp.typebindings.util;

import dev.lumenlang.lumen.pipeline.language.tokenization.Token;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A thin wrapper around a list of tokens that provides a human readable
 * {@link #toString()} returning the joined raw text of its tokens.
 *
 * @param tokens the original tokens
 */
public record TokenList(@NotNull List<Token> tokens) {

    @Override
    public @NotNull String toString() {
        return tokens.stream().map(Token::text).collect(Collectors.joining(" "));
    }
}
