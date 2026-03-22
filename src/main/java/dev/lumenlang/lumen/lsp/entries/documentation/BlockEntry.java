package dev.lumenlang.lumen.lsp.entries.documentation;

import dev.lumenlang.lumen.lsp.entries.documentation.variables.BlockVariable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Represents a block pattern entry from the documentation.
 *
 * @param patterns          the pattern strings that match this block
 * @param by                the author or source of this block
 * @param description       a human readable description
 * @param examples          example code snippets
 * @param since             the version this block was introduced
 * @param category          the category this block belongs to
 * @param deprecated        whether this block is deprecated
 * @param variables         variables provided to child statements, or null if none
 * @param supportsRootLevel whether this block can appear at the top level of a script
 * @param supportsBlock     whether this block can appear nested inside another block
 */
public record BlockEntry(
        @NotNull List<String> patterns,
        @Nullable String by,
        @Nullable String description,
        @NotNull List<String> examples,
        @Nullable String since,
        @Nullable String category,
        boolean deprecated,
        @Nullable List<BlockVariable> variables,
        boolean supportsRootLevel,
        boolean supportsBlock
) {
}
