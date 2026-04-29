package dev.lumenlang.lumen.lsp.providers.semantic;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Token type and modifier legend used by the semantic tokens provider.
 */
public final class SemanticLegend {

    /**
     * The ordered list of token type names sent to the client. Indices are
     * referenced from emitted token arrays.
     */
    public static final @NotNull List<String> TYPES = List.of("keyword", "variable", "function", "number", "string", "operator", "comment", "type", "parameter", "property", "event", "namespace");

    /**
     * The ordered list of token modifier names. Modifier values are bitmasks
     * built from these indices.
     */
    public static final @NotNull List<String> MODIFIERS = List.of("declaration", "definition", "readonly", "deprecated", "modification", "documentation");

    /**
     * Index of the {@code keyword} type.
     */
    public static final int TYPE_KEYWORD = 0;

    /**
     * Index of the {@code variable} type.
     */
    public static final int TYPE_VARIABLE = 1;

    /**
     * Index of the {@code function} type.
     */
    public static final int TYPE_FUNCTION = 2;

    /**
     * Index of the {@code number} type.
     */
    public static final int TYPE_NUMBER = 3;

    /**
     * Index of the {@code string} type.
     */
    public static final int TYPE_STRING = 4;

    /**
     * Index of the {@code operator} type.
     */
    public static final int TYPE_OPERATOR = 5;

    /**
     * Index of the {@code comment} type.
     */
    public static final int TYPE_COMMENT = 6;

    /**
     * Index of the {@code type} type.
     */
    public static final int TYPE_TYPE = 7;

    /**
     * Index of the {@code parameter} type.
     */
    public static final int TYPE_PARAMETER = 8;

    /**
     * Index of the {@code property} type.
     */
    public static final int TYPE_PROPERTY = 9;

    /**
     * Bitmask of the {@code declaration} modifier.
     */
    public static final int MOD_DECLARATION = 1;

    private SemanticLegend() {
    }
}
