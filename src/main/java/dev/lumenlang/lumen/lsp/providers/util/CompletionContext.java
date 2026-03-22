package dev.lumenlang.lumen.lsp.providers.util;

/**
 * Classifies the cursor context when generating completions,
 * determining which set of completion items should be offered.
 */
public enum CompletionContext {
    EMPTY,
    EVENT,
    VARIABLE_EXPR,
    CONDITION,
    LOOP_SOURCE,
    STRING_INTERPOLATION,
    BLOCK_HEAD,
    STATEMENT,
    PLACEHOLDER_TYPE
}
