package dev.lumenlang.lumen.lsp.analysis.line;

import dev.lumenlang.lumen.api.pattern.PatternMeta;
import dev.lumenlang.lumen.lsp.entries.documentation.EventEntry;
import dev.lumenlang.lumen.pipeline.language.tokenization.Token;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Stores classification and metadata for a single analyzed line.
 *
 * @param line   the 1-based line number
 * @param tokens the tokens on this line
 * @param kind   the classification kind
 * @param meta   optional pattern metadata (for matched patterns)
 * @param event  optional event entry (for event blocks)
 */
public record LineInfo(
        int line,
        @NotNull List<Token> tokens,
        @NotNull LineKind kind,
        @Nullable PatternMeta meta,
        @Nullable EventEntry event
) {
}
