package dev.lumenlang.lumen.lsp.server.tokens;

import java.util.List;

/**
 * Constants for semantic token types and modifiers used by the LSP.
 */
public final class LumenSemanticTokens {

    public static final List<String> TOKEN_TYPES = List.of(
            "keyword",
            "variable",
            "function",
            "number",
            "string",
            "operator",
            "comment",
            "type",
            "parameter",
            "property",
            "event",
            "namespace"
    );
    public static final List<String> TOKEN_MODIFIERS = List.of(
            "declaration",
            "definition",
            "readonly",
            "deprecated",
            "modification",
            "documentation"
    );
    public static final int TYPE_KEYWORD = 0;
    public static final int TYPE_VARIABLE = 1;
    public static final int TYPE_FUNCTION = 2;
    public static final int TYPE_NUMBER = 3;
    public static final int TYPE_STRING = 4;
    public static final int TYPE_OPERATOR = 5;
    public static final int TYPE_COMMENT = 6;
    public static final int TYPE_TYPE = 7;
    public static final int TYPE_PARAMETER = 8;
    public static final int TYPE_PROPERTY = 9;
    public static final int TYPE_EVENT = 10;
    public static final int TYPE_NAMESPACE = 11;
    public static final int MOD_DECLARATION = 1;
    public static final int MOD_DEFINITION = 2;
    public static final int MOD_READONLY = 4;
    public static final int MOD_DEPRECATED = 8;

    private LumenSemanticTokens() {
    }
}
