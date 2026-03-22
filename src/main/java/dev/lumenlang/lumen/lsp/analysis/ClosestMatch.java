package dev.lumenlang.lumen.lsp.analysis;

import dev.lumenlang.lumen.lsp.entries.documentation.PatternEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents the closest matching pattern found during structural matching when
 * a statement could not be classified by the pipeline. Includes the matched
 * pattern text, the documentation entry, and a confidence score.
 *
 * @param pattern    the matched pattern string
 * @param entry      the documentation entry that owns the matched pattern, or null
 * @param confidence the confidence score between 0.0 and 1.0
 */
public record ClosestMatch(
        @NotNull String pattern,
        @Nullable PatternEntry entry,
        double confidence
) {
}
