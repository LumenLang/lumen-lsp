package dev.lumenlang.lumen.lsp.entries.documentation.variables;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Represents a variable exposed to child statements by an event.
 *
 * @param name        the variable name accessible in script child statements
 * @param javaType    the fully qualified Java type
 * @param refTypeId   the type binding ID (e.g. "PLAYER"), or null
 * @param description a human readable description, or null
 * @param nullable    whether this variable can be null
 * @param metadata    optional metadata entries, or null if absent
 */
public record EventVariable(
        @NotNull String name,
        @Nullable String javaType,
        @Nullable String refTypeId,
        @Nullable String description,
        boolean nullable,
        @Nullable Map<String, String> metadata
) {
}
