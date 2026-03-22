package dev.lumenlang.lumen.lsp.entries.documentation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Represents a type binding loaded from the documentation.
 *
 * @param id          the unique identifier for this type binding
 * @param description a human readable description, or null if unavailable
 * @param javaType    the corresponding Java type name, or null if unavailable
 * @param examples    example usages of this type
 * @param since       the version this type was introduced, or null if unknown
 * @param deprecated  whether this type binding is deprecated
 */
public record TypeBindingEntry(
        @NotNull String id,
        @Nullable String description,
        @Nullable String javaType,
        @NotNull List<String> examples,
        @Nullable String since,
        boolean deprecated
) {
}
