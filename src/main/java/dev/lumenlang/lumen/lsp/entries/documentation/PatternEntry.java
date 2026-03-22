package dev.lumenlang.lumen.lsp.entries.documentation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Represents a pattern entry from the documentation, such as a statement, expression, or condition.
 *
 * @param patterns    the pattern strings that match this entry
 * @param by          the author or source of this entry
 * @param description a human readable description
 * @param examples    example code snippets
 * @param since       the version this entry was introduced
 * @param category    the category this entry belongs to
 * @param deprecated  whether this entry is deprecated
 */
public record PatternEntry(
        @NotNull List<String> patterns,
        @Nullable String by,
        @Nullable String description,
        @NotNull List<String> examples,
        @Nullable String since,
        @Nullable String category,
        boolean deprecated,
        @Nullable String returnRefTypeId,
        @Nullable String returnJavaType
) {
}
