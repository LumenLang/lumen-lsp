package dev.lumenlang.lumen.lsp.entries.documentation.variables;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Represents a variable that a block provides to its child statements.
 *
 * @param name        the variable name accessible in script child statements
 * @param type        a human readable type string (e.g. "Player", "World")
 * @param nullable    whether the variable can be null
 * @param description a human readable description, or null
 * @param metadata    optional metadata map, or null if absent
 * @param refType     the ref type identifier for pattern matching (e.g. "PLAYER"), or null
 */
public record BlockVariable(
        @NotNull String name,
        @NotNull String type,
        boolean nullable,
        @Nullable String description,
        @Nullable Map<String, String> metadata,
        @Nullable String refType
) {
}
