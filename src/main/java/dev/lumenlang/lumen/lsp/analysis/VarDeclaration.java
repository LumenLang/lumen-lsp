package dev.lumenlang.lumen.lsp.analysis;

import org.jetbrains.annotations.NotNull;

/**
 * Represents a variable declaration found during analysis.
 *
 * @param name     the variable name
 * @param type     the inferred Java type
 * @param line     the 1-based line where it was declared
 * @param provided whether this variable is provided by a block context (event var, loop var, etc.)
 */
public record VarDeclaration(
        @NotNull String name,
        @NotNull String type,
        int line,
        boolean provided
) {
}
