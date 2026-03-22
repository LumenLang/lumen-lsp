package dev.lumenlang.lumen.lsp.entries.minicolorize;

import org.jetbrains.annotations.NotNull;

/**
 * A data field type entry used for completion inside data block bodies,
 * mapping a Lumen type keyword to its Java equivalent and a description.
 *
 * @param name        the Lumen type keyword (e.g. "text", "number")
 * @param javaType    the corresponding Java type name (e.g. "String", "double")
 * @param description a short human readable description
 */
public record FieldTypeEntry(
        @NotNull String name,
        @NotNull String javaType,
        @NotNull String description
) {
}
