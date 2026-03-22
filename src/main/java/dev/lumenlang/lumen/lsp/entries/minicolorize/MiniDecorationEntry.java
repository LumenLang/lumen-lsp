package dev.lumenlang.lumen.lsp.entries.minicolorize;

import org.jetbrains.annotations.NotNull;

/**
 * A text decoration entry for MiniColorize tag completion, containing
 * the full tag name, its short alias, a label, and a description.
 *
 * @param name        the full decoration name (e.g. "bold")
 * @param alias       the short alias (e.g. "b")
 * @param label       a short human readable label
 * @param description a description of what the decoration does
 */
public record MiniDecorationEntry(
        @NotNull String name,
        @NotNull String alias,
        @NotNull String label,
        @NotNull String description
) {
}
