package dev.lumenlang.lumen.lsp.analysis.line;

/**
 * Classification kinds for analyzed lines, indicating what type of
 * construct each line represents in a Lumen script.
 */
public enum LineKind {
    STATEMENT,
    EXPRESSION,
    VAR_DECL,
    EXPR_VAR_DECL,
    STORE_VAR,
    GLOBAL_VAR,
    ERROR,
    EVENT_BLOCK,
    CONDITIONAL,
    LOOP_BLOCK,
    PATTERN_BLOCK,
    UNKNOWN_BLOCK,
    RAW_BLOCK,
    DATA_BLOCK,
    DATA_FIELD,
    CONFIG_BLOCK,
    CONFIG_ENTRY
}
