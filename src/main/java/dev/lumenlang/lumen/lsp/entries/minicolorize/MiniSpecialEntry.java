package dev.lumenlang.lumen.lsp.entries.minicolorize;

import org.jetbrains.annotations.NotNull;

/**
 * A special MiniColorize tag entry for completion, such as reset,
 * rainbow, gradient, click events, and other non-color tags.
 *
 * @param name          the tag name or prefix (e.g. "gradient:")
 * @param detail        a short detail label
 * @param documentation the full Markdown documentation string
 */
public record MiniSpecialEntry(
        @NotNull String name,
        @NotNull String detail,
        @NotNull String documentation
) {
}
