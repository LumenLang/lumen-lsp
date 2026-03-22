package dev.lumenlang.lumen.lsp.providers;

import dev.lumenlang.lumen.lsp.analysis.AnalysisResult;
import dev.lumenlang.lumen.lsp.analysis.VarDeclaration;
import dev.lumenlang.lumen.lsp.analysis.line.LineInfo;
import dev.lumenlang.lumen.lsp.analysis.line.LineKind;
import dev.lumenlang.lumen.lsp.documentation.DocumentationData;
import dev.lumenlang.lumen.lsp.entries.documentation.BlockEntry;
import dev.lumenlang.lumen.lsp.entries.documentation.EventEntry;
import dev.lumenlang.lumen.lsp.entries.documentation.variables.BlockVariable;
import dev.lumenlang.lumen.lsp.entries.documentation.variables.EventVariable;
import dev.lumenlang.lumen.pipeline.data.DataSchema;
import dev.lumenlang.lumen.pipeline.language.tokenization.Token;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Provides hover information for Lumen scripts.
 */
public final class HoverProvider {

    /**
     * Returns hover information for the given position.
     *
     * @param params   the hover request parameters
     * @param analysis the analysis result (may be null)
     * @param docs     the documentation data
     * @param source   the raw source text (may be null)
     * @return the hover information, or null
     */
    public @Nullable Hover hover(@NotNull HoverParams params,
                                 @Nullable AnalysisResult analysis,
                                 @NotNull DocumentationData docs,
                                 @Nullable String source) {
        if (analysis == null) return null;

        int line = params.getPosition().getLine() + 1;
        int col = params.getPosition().getCharacter();

        int indent = 0;
        if (source != null) {
            String[] lines = source.split("\\r?\\n", -1);
            int lineIdx = line - 1;
            if (lineIdx >= 0 && lineIdx < lines.length) {
                indent = lines[lineIdx].length() - lines[lineIdx].stripLeading().length();
            }
        }
        LineInfo info = analysis.lineInfo().get(line);
        if (info == null) return null;

        String hoverText = buildHover(info, Math.max(0, col - indent), analysis, docs);
        if (hoverText == null) return null;

        return new Hover(new MarkupContent(MarkupKind.MARKDOWN, hoverText));
    }

    /**
     * Builds the hover text string for the given line info, delegating to type-specific hover builders.
     *
     * @param info     the classification info for the hovered line
     * @param col      the adjusted column position within the line
     * @param analysis the analysis result
     * @param docs     the documentation data
     * @return the Markdown hover string, or null if no hover is available
     */
    private @Nullable String buildHover(@NotNull LineInfo info, int col,
                                        @NotNull AnalysisResult analysis,
                                        @NotNull DocumentationData docs) {
        Token hoveredToken = tokenAt(info.tokens(), col);

        switch (info.kind()) {
            case STATEMENT, EXPRESSION, EXPR_VAR_DECL -> {
                if (info.meta() != null) return patternHover(info);
            }
            case EVENT_BLOCK -> {
                if (info.event() != null) return eventHover(info.event());
            }
            case VAR_DECL -> {
                return varDeclHover(info, analysis);
            }
            case GLOBAL_VAR -> {
                return "**Global Variable**\n\nA global variable accessible across all scopes.";
            }
            case STORE_VAR -> {
                return "**Stored Variable**\n\nA persistent variable that survives server restarts.";
            }
            case LOOP_BLOCK -> {
                return "**Loop Block**\n\nIterates over a collection.";
            }
            case DATA_BLOCK -> {
                return dataBlockHover(info, analysis);
            }
            case DATA_FIELD -> {
                return dataFieldHover(info);
            }
            case CONFIG_BLOCK -> {
                return configBlockHover(info, analysis);
            }
            case CONFIG_ENTRY -> {
                return configEntryHover(info);
            }
            case CONDITIONAL -> {
                return "**Conditional Block**\n\nExecutes content based on a condition.";
            }
            case PATTERN_BLOCK -> {
                if (info.meta() != null) return patternHover(info);
                return blockHover(info, docs);
            }
            case RAW_BLOCK -> {
                return "**Java Block**\n\nRaw Java code block. Content is inserted directly into the generated class.";
            }
            default -> {
            }
        }

        if (hoveredToken != null) {
            return variableHover(hoveredToken.text(), info.line(), analysis);
        }

        return null;
    }

    /**
     * Returns the token that covers the given column position, or null if none matches.
     *
     * @param tokens the tokens to search
     * @param col    the column position
     * @return the matching token, or null
     */
    private @Nullable Token tokenAt(@NotNull List<Token> tokens, int col) {
        for (Token t : tokens) {
            if (col >= t.start() && col < t.end()) return t;
        }
        return null;
    }

    /**
     * Builds a hover string for a variable reference by looking up the declaration in the scope.
     *
     * @param name     the variable name
     * @param line     the 1-based line number
     * @param analysis the analysis result
     * @return the Markdown hover string, or null if not found
     */
    private @Nullable String variableHover(@NotNull String name, int line,
                                           @NotNull AnalysisResult analysis) {
        Map<String, VarDeclaration> scope = analysis.scopeByLine().get(line);
        if (scope == null) return null;
        VarDeclaration var = scope.get(name);
        if (var == null) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("**Variable:** `").append(var.name()).append("`\n\n");
        sb.append("**Type:** `").append(var.type()).append("`\n\n");
        sb.append("**Declared at:** line ").append(var.line()).append("\n\n");
        if (var.provided()) {
            sb.append("*Context variable (provided by the enclosing block)*\n");
        }
        return sb.toString();
    }

    /**
     * Builds a hover string for a matched pattern line using its metadata.
     * Check if line info's metadata is not null before calling this.
     *
     * @param info the line info carrying pattern metadata
     * @return the Markdown hover string
     */
    @SuppressWarnings("DataFlowIssue")
    private @NotNull String patternHover(@NotNull LineInfo info) {
        StringBuilder sb = new StringBuilder();
        if (info.meta().description() != null) {
            sb.append(info.meta().description()).append("\n\n");
        }
        if (info.meta().category() != null) {
            sb.append("**Category:** ").append(info.meta().category().name()).append("\n\n");
        }
        if (info.meta().by() != null) {
            sb.append("**By:** ").append(info.meta().by()).append("\n\n");
        }
        if (info.meta().since() != null) {
            sb.append("**Since:** ").append(info.meta().since()).append("\n\n");
        }
        if (!info.meta().examples().isEmpty()) {
            sb.append("**Examples:**\n```luma\n");
            for (String ex : info.meta().examples()) {
                sb.append(ex).append('\n');
            }
            sb.append("```\n");
        }
        if (info.meta().deprecated()) {
            sb.append("\n**@deprecated**\n");
        }
        return sb.toString();
    }

    /**
     * Builds a hover string for an event block header.
     *
     * @param event the event entry
     * @return the Markdown hover string
     */
    private @NotNull String eventHover(@NotNull EventEntry event) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Event:** `").append(event.name()).append("`\n\n");
        if (event.description() != null) {
            sb.append(event.description()).append("\n\n");
        }
        if (event.className() != null) {
            sb.append("**Class:** `").append(event.className()).append("`\n\n");
        }
        if (event.category() != null) {
            sb.append("**Category:** ").append(event.category()).append("\n\n");
        }
        if (!event.variables().isEmpty()) {
            sb.append("**Available Variables:**\n");
            for (EventVariable var : event.variables()) {
                sb.append("- `").append(var.name()).append("` : `").append(simpleType(var.javaType() != null ? var.javaType() : "Object")).append('`');
                if (var.nullable()) sb.append(" `nullable`");
                if (var.description() != null) sb.append(" ").append(var.description());
                sb.append('\n');
            }
            sb.append('\n');
        }
        if (event.since() != null) {
            sb.append("**Since:** ").append(event.since()).append("\n\n");
        }
        if (event.deprecated()) {
            sb.append("**@deprecated**\n");
        }
        return sb.toString();
    }

    /**
     * Builds a hover string for a block by looking up the block entry from documentation.
     *
     * @param info the line info for the block
     * @param docs the documentation data
     * @return the Markdown hover string, or null if no matching block is found
     */
    private @Nullable String blockHover(@NotNull LineInfo info, @NotNull DocumentationData docs) {
        if (info.tokens().isEmpty()) return null;
        String keyword = info.tokens().get(0).text();
        BlockEntry block = findBlock(docs, keyword);
        if (block == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(capitalize(keyword)).append(" Block**\n\n");
        if (block.description() != null) {
            sb.append(block.description()).append("\n\n");
        }
        if (block.variables() != null && !block.variables().isEmpty()) {
            sb.append("**Available Variables:**\n");
            for (BlockVariable var : block.variables()) {
                sb.append("- `").append(var.name()).append("` : `").append(var.type()).append('`');
                if (var.nullable()) sb.append(" `nullable`");
                if (var.description() != null) sb.append(" ").append(var.description());
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Finds a block entry from documentation whose first pattern starts with the given keyword.
     *
     * @param docs    the documentation data
     * @param keyword the block keyword to search for
     * @return the matching block entry, or null if not found
     */
    private @Nullable BlockEntry findBlock(@NotNull DocumentationData docs, @NotNull String keyword) {
        String lower = keyword.toLowerCase();
        for (BlockEntry entry : docs.blocks()) {
            for (String pattern : entry.patterns()) {
                if (pattern.toLowerCase().startsWith(lower + " ") || pattern.toLowerCase().equals(lower)) {
                    return entry;
                }
            }
        }
        return null;
    }

    /**
     * Builds a hover string for a variable declaration line.
     *
     * @param info     the line info for the declaration
     * @param analysis the analysis result
     * @return the Markdown hover string, or a generic string if no declaration is found
     */
    private @NotNull String varDeclHover(@NotNull LineInfo info, @NotNull AnalysisResult analysis) {
        for (Token t : info.tokens()) {
            VarDeclaration var = analysis.variables().get(t.text());
            if (var != null && var.line() == info.line()) {
                return "**Variable Declaration:** `" + var.name() + "`\n\n" +
                        "**Type:** `" + var.type() + "`";
            }
        }
        return "**Variable Declaration**";
    }

    /**
     * Extracts the simple (unqualified) class name from a fully qualified class name.
     *
     * @param fqcn the fully qualified class name
     * @return the simple class name
     */
    private @NotNull String simpleType(@NotNull String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }

    /**
     * Capitalizes the first character of a string.
     *
     * @param s the string to capitalize
     * @return the string with the first character uppercased
     */
    private @NotNull String capitalize(@NotNull String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Builds a hover string for a data block, including its field list if a schema was resolved.
     *
     * @param info     the line info for the data block
     * @param analysis the analysis result
     * @return the Markdown hover string
     */
    private @NotNull String dataBlockHover(@NotNull LineInfo info, @NotNull AnalysisResult analysis) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Data Class**\n\n");
        String typeName = info.tokens().size() >= 2 ? info.tokens().get(1).text() : "unknown";
        sb.append("Defines a value type `").append(typeName).append("`\n\n");

        DataSchema schema = analysis.dataSchemas().get(typeName.toLowerCase());
        if (schema != null && !schema.fields().isEmpty()) {
            sb.append("**Fields:**\n");
            for (var entry : schema.fields().entrySet()) {
                sb.append("- `").append(entry.getKey()).append("` : `").append(entry.getValue().javaType()).append("`\n");
            }
            sb.append('\n');
        }

        sb.append("**Usage:**\n```luma\nset var to new ").append(typeName);
        if (schema != null && !schema.fields().isEmpty()) {
            sb.append(" with");
            for (String field : schema.fields().keySet()) {
                sb.append(" ").append(field).append(" <value>");
            }
        }
        sb.append("\nvar f = get field \"<name>\" of var\n```\n");
        return sb.toString();
    }

    /**
     * Builds a hover string for a single field inside a data block.
     *
     * @param info the line info for the data field
     * @return the Markdown hover string
     */
    private @NotNull String dataFieldHover(@NotNull LineInfo info) {
        List<Token> tokens = info.tokens();
        if (tokens.size() < 2) return "**Data Field**";

        String fieldName = tokens.get(0).text();
        String fieldTypeName = tokens.get(1).text();

        StringBuilder sb = new StringBuilder();
        sb.append("**Data Field:** `").append(fieldName).append("`\n\n");
        sb.append("**Type:** `").append(fieldTypeName).append("`\n");

        try {
            DataSchema.FieldType ft = DataSchema.FieldType.fromName(fieldTypeName);
            sb.append("\n**Java Type:** `").append(ft.javaType()).append("`\n");
        } catch (IllegalArgumentException ignored) {
        }

        return sb.toString();
    }

    /**
     * Builds a hover string for a config block header, listing all defined config entries.
     *
     * @param info     the line info for the config block
     * @param analysis the analysis result
     * @return the Markdown hover string
     */
    private @NotNull String configBlockHover(@NotNull LineInfo info, @NotNull AnalysisResult analysis) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Config Block**\n\n");
        sb.append("Defines variables accessible from all blocks in the script.\n\n");

        boolean hasEntries = false;
        for (int line = info.line() + 1; ; line++) {
            LineInfo lineInfo = analysis.lineInfo().get(line);
            if (lineInfo == null || lineInfo.kind() != LineKind.CONFIG_ENTRY) break;
            if (!hasEntries) {
                sb.append("**Entries:**\n");
                hasEntries = true;
            }
            if (!lineInfo.tokens().isEmpty()) {
                sb.append("- `").append(lineInfo.tokens().get(0).text()).append("`\n");
            }
        }

        return sb.toString();
    }

    /**
     * Builds a hover string for a config entry line showing its name.
     *
     * @param info the line info for the config entry
     * @return the Markdown hover string
     */
    private @NotNull String configEntryHover(@NotNull LineInfo info) {
        List<Token> tokens = info.tokens();
        if (tokens.isEmpty()) return "**Config Entry**";
        String name = tokens.get(0).text();
        return "**Config Variable:** `" + name + "`\n\n*Available in all blocks throughout the script.*";
    }
}
