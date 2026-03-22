package dev.lumenlang.lumen.lsp.entries.minicolorize;

import org.jetbrains.annotations.NotNull;

/**
 * A named color entry for MiniColorize tag completion, containing
 * the tag name, hex value, description, and a usage example.
 *
 * @param name        the MiniColorize color name (e.g. "red")
 * @param hex         the hex color code (e.g. "#FF5555")
 * @param description a short human readable description
 * @param example     an example usage string
 */
public record MiniColorEntry(
        @NotNull String name,
        @NotNull String hex,
        @NotNull String description,
        @NotNull String example
) {
}
