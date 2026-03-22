package dev.lumenlang.lumen.lsp.providers;

import dev.lumenlang.lumen.lsp.analysis.AnalysisResult;
import dev.lumenlang.lumen.lsp.analysis.VarDeclaration;
import dev.lumenlang.lumen.lsp.analysis.line.LineInfo;
import dev.lumenlang.lumen.lsp.analysis.line.LineKind;
import dev.lumenlang.lumen.lsp.documentation.DocumentationData;
import dev.lumenlang.lumen.lsp.entries.documentation.BlockEntry;
import dev.lumenlang.lumen.lsp.entries.documentation.EventEntry;
import dev.lumenlang.lumen.lsp.entries.documentation.PatternEntry;
import dev.lumenlang.lumen.lsp.entries.documentation.TypeBindingEntry;
import dev.lumenlang.lumen.lsp.entries.documentation.variables.BlockVariable;
import dev.lumenlang.lumen.lsp.entries.documentation.variables.EventVariable;
import dev.lumenlang.lumen.lsp.entries.minicolorize.FieldTypeEntry;
import dev.lumenlang.lumen.lsp.entries.minicolorize.MiniColorEntry;
import dev.lumenlang.lumen.lsp.entries.minicolorize.MiniDecorationEntry;
import dev.lumenlang.lumen.lsp.entries.minicolorize.MiniSpecialEntry;
import dev.lumenlang.lumen.lsp.providers.util.CompletionContext;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionItemTag;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides intelligent completions for Lumen scripts based on context.
 */
public final class CompletionProvider {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("%([^%:]+)(?::([^%]+))?%");

    /**
     * Checks whether a label string matches a filter by prefix, word-start, containment,
     * or by matching the literal words of a partially typed pattern.
     *
     * @param label  the label to test
     * @param filter the lowercase filter string
     * @return true if the label matches the filter
     */
    private static boolean matches(@NotNull String label, @NotNull String filter) {
        String lower = label.toLowerCase();
        if (lower.startsWith(filter)) return true;
        for (String word : lower.split("\\s+")) {
            if (word.startsWith(filter)) return true;
        }
        if (lower.contains(filter)) return true;
        return literalPrefixMatches(lower, filter);
    }

    /**
     * Checks whether the literal (non-placeholder) words of a label match the literal
     * (non-quoted, non-numeric) words of the filter as a prefix sequence.
     *
     * @param label  the lowercase label string
     * @param filter the lowercase filter string
     * @return true if the filter's literal words are a prefix of the label's literal words
     */
    private static boolean literalPrefixMatches(@NotNull String label, @NotNull String filter) {
        // extract non-placeholder words from the label
        List<String> labelWords = new ArrayList<>();
        for (String w : label.split("\\s+")) {
            if (!w.startsWith("<") && !w.endsWith(">")) labelWords.add(w);
        }
        // strip quoted strings and numbers from the filter to get only literal words
        String stripped = filter.replaceAll("\"[^\"]*\"", " ").replaceAll("'[^']*'", " ");
        List<String> filterWords = new ArrayList<>();
        for (String w : stripped.split("\\s+")) {
            if (w.isEmpty()) continue;
            if (w.matches("\\d+(\\.\\d+)?")) continue;
            filterWords.add(w);
        }
        if (filterWords.isEmpty() || labelWords.isEmpty()) return false;
        // check if filter words appear as a prefix subsequence of label words
        int fi = 0;
        for (String lw : labelWords) {
            if (fi >= filterWords.size()) return true;
            if (lw.equals(filterWords.get(fi))) fi++;
        }
        return fi >= filterWords.size();
    }

    /**
     * Counts the number of occurrences of a character in a string.
     *
     * @param s the string to search
     * @param c the character to count
     * @return the number of occurrences
     */
    private static int countChar(@NotNull String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }

    /**
     * Generates completion items based on the cursor context.
     *
     * @param params   the completion request parameters
     * @param source   the document source text
     * @param docs     the documentation data
     * @param analysis the analysis result (may be null)
     * @return the list of completion items
     */
    public @NotNull List<CompletionItem> complete(@NotNull CompletionParams params, @Nullable String source, @NotNull DocumentationData docs, @Nullable AnalysisResult analysis) {
        if (source == null) return List.of();

        int line = params.getPosition().getLine();
        int character = params.getPosition().getCharacter();

        String[] lines = source.split("\\r?\\n", -1);
        if (line >= lines.length) return List.of();

        String currentLine = lines[line];
        String prefix = character <= currentLine.length() ? currentLine.substring(0, character) : currentLine;
        String content = prefix.stripLeading();
        boolean atRoot = prefix.length() == content.length();

        Collection<VarDeclaration> scopedVars = resolveScope(analysis, line + 1);

        List<CompletionItem> items = new ArrayList<>();

        if (isInsideDataBlock(line, lines, analysis)) {
            completeDataFieldTypes(content, items);
            if (analysis != null) {
                completeDataSchemaFields(analysis, items);
            }
            return items;
        }

        if (isInsideConfigBlock(line, lines, analysis)) {
            return items;
        }

        if (isInsideMiniTag(prefix)) {
            String tagFilter = extractMiniTagFilter(prefix);
            completeMiniColorize(items, tagFilter);
            return items;
        }

        CompletionContext ctx = classifyContext(content, prefix);

        String filterText = extractFilter(content, ctx);

        switch (ctx) {
            case EVENT -> completeEvents(docs, items, filterText);
            case VARIABLE_EXPR -> {
                completeExpressions(docs, items, filterText);
                completeVariables(scopedVars, items, filterText);
            }
            case CONDITION -> {
                String afterKeyword = content.replaceFirst("(?i)(if|else if)\\s+", "");
                completeConditions(docs, items, afterKeyword);
            }
            case LOOP_SOURCE -> completeLoopSources(docs, items, filterText);
            case STRING_INTERPOLATION -> completeInterpolation(scopedVars, items, filterText);
            case BLOCK_HEAD -> {
                completeBlocks(docs, items, filterText);
                completeStatements(docs, items, filterText);
            }
            case EMPTY -> {
                if (atRoot) {
                    completeTopLevel(docs, items, "", true);
                } else {
                    completeStatements(docs, items, "");
                    completeTopLevel(docs, items, "", false);
                    completeVariables(scopedVars, items, "");
                }
            }
            case STATEMENT -> {
                if (atRoot) {
                    completeTopLevel(docs, items, content, true);
                } else {
                    completeStatements(docs, items, content);
                    completeTopLevel(docs, items, content, false);
                    completeVariables(scopedVars, items, content);
                    completeExpressions(docs, items, content);
                }
            }
            case PLACEHOLDER_TYPE -> completeTypeBindings(docs, items, filterText);
        }

        return items;
    }

    /**
     * Resolves the variable declarations visible at a given line number by walking backwards
     * through the scope-by-line map.
     *
     * @param analysis the analysis result, may be null
     * @param lineNum  the 1-based line number to resolve scope for
     * @return the collection of variable declarations in scope
     */
    private @NotNull Collection<VarDeclaration> resolveScope(@Nullable AnalysisResult analysis, int lineNum) {
        if (analysis == null) return Collections.emptyList();
        Map<Integer, Map<String, VarDeclaration>> scopeByLine = analysis.scopeByLine();
        if (scopeByLine.isEmpty()) return analysis.variables().values();
        for (int l = lineNum; l >= 1; l--) {
            Map<String, VarDeclaration> scope = scopeByLine.get(l);
            if (scope != null) return scope.values();
        }
        return Collections.emptyList();
    }

    /**
     * Determines whether the cursor is currently inside an opening MiniColorize tag.
     *
     * @param prefix the text up to the cursor position
     * @return true if the cursor is between an unclosed opening angle bracket inside a string
     */
    private boolean isInsideMiniTag(@NotNull String prefix) {
        int lastOpen = prefix.lastIndexOf('<');
        if (lastOpen < 0) return false;
        int lastClose = prefix.lastIndexOf('>');
        // if there's a '>' after the last '<', the tag is already closed
        if (lastClose > lastOpen) return false;
        // the '<' must be inside a string, so check for odd number of quotes before it
        int quotesBefore = 0;
        for (int i = 0; i < lastOpen; i++) {
            if (prefix.charAt(i) == '"') quotesBefore++;
        }
        return quotesBefore % 2 != 0;
    }

    /**
     * Extracts the partial tag name typed after the opening angle bracket for MiniColorize tag filtering.
     *
     * @param prefix the text up to the cursor position
     * @return the partial tag filter string
     */
    private @NotNull String extractMiniTagFilter(@NotNull String prefix) {
        int lastOpen = prefix.lastIndexOf('<');
        if (lastOpen < 0) return "";
        String after = prefix.substring(lastOpen + 1);
        if (after.startsWith("/") || after.startsWith("!")) {
            after = after.substring(1);
        }
        return after;
    }

    /**
     * Extracts the text fragment to use as a filter for the given completion context.
     *
     * @param content the stripped line content
     * @param ctx     the classified completion context
     * @return the filter string
     */
    private @NotNull String extractFilter(@NotNull String content, @NotNull CompletionContext ctx) {
        return switch (ctx) {
            case EVENT -> content.startsWith("on ") || content.startsWith("On ") ? content.substring(3) : content;
            case VARIABLE_EXPR -> {
                int eq = content.indexOf('=');
                yield eq >= 0 ? content.substring(eq + 1).trim() : "";
            }
            case CONDITION -> content.replaceFirst("(?i)(if|else if)\\s+", "");
            case LOOP_SOURCE -> {
                int in = content.toLowerCase().lastIndexOf(" in ");
                yield in >= 0 ? content.substring(in + 4).trim() : "";
            }
            case STRING_INTERPOLATION -> {
                int brace = content.lastIndexOf('{');
                yield brace >= 0 ? content.substring(brace + 1) : "";
            }
            case PLACEHOLDER_TYPE -> {
                int pct = content.lastIndexOf('%');
                yield pct >= 0 ? content.substring(pct + 1) : "";
            }
            default -> content;
        };
    }

    /**
     * Classifies the current line content into a {@link CompletionContext} that determines which
     * completion list is offered.
     *
     * @param content the whitespace-stripped line content
     * @param prefix  the raw text up to the cursor
     * @return the completion context
     */
    private @NotNull CompletionContext classifyContext(@NotNull String content, @NotNull String prefix) {
        if (content.isEmpty()) return CompletionContext.EMPTY;
        if (content.toLowerCase().startsWith("on ")) return CompletionContext.EVENT;
        if (content.matches("(?i)var\\s+\\w+\\s*=\\s*.*")) return CompletionContext.VARIABLE_EXPR;
        if (content.matches("(?i)(if|else if)\\s+.*") || content.matches("(?i)(if|else if)\\s*"))
            return CompletionContext.CONDITION;
        if (content.matches("(?i)loop\\s+\\w+\\s+(\\w+\\s+)?in\\s+.*")) return CompletionContext.LOOP_SOURCE;
        // unclosed brace means we are inside a string interpolation expression
        if (prefix.contains("{") && !prefix.contains("}")) return CompletionContext.STRING_INTERPOLATION;
        // odd number of '%' means we are between placeholder delimiters
        if (prefix.contains("%") && countChar(prefix, '%') % 2 != 0) return CompletionContext.PLACEHOLDER_TYPE;
        if (content.endsWith(":")) return CompletionContext.BLOCK_HEAD;
        return CompletionContext.STATEMENT;
    }

    /**
     * Appends event completion items filtered by the given prefix string.
     *
     * @param docs   the documentation data
     * @param items  the list to append items to
     * @param filter the filter prefix
     */
    private void completeEvents(@NotNull DocumentationData docs, @NotNull List<CompletionItem> items, @NotNull String filter) {
        String lower = filter.toLowerCase();
        for (EventEntry event : docs.events()) {
            if (!lower.isEmpty() && !event.name().toLowerCase().startsWith(lower)) continue;
            CompletionItem item = new CompletionItem(event.name());
            item.setKind(CompletionItemKind.Event);
            item.setDetail(event.category() != null ? event.category() : "Event");
            if (event.description() != null) {
                item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, eventDoc(event)));
            }
            item.setInsertText(event.name() + ":");
            item.setInsertTextFormat(InsertTextFormat.PlainText);
            if (event.deprecated()) item.setTags(List.of(CompletionItemTag.Deprecated));
            items.add(item);
        }
    }

    /**
     * Appends statement completion items filtered by the given prefix string.
     *
     * @param docs   the documentation data
     * @param items  the list to append items to
     * @param filter the filter prefix
     */
    private void completeStatements(@NotNull DocumentationData docs, @NotNull List<CompletionItem> items,
                                    @NotNull String filter) {
        String lower = filter.toLowerCase();
        Set<String> seen = new HashSet<>();
        for (PatternEntry entry : docs.statements()) {
            for (String pattern : entry.patterns()) {
                String label = patternLabel(pattern);
                if (!seen.add(label)) continue;
                if (!lower.isEmpty() && !matches(label, lower)) continue;

                CompletionItem item = new CompletionItem(label);
                item.setKind(CompletionItemKind.Function);
                item.setDetail(entry.category() != null ? entry.category() : "Statement");
                if (entry.description() != null) {
                    item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, patternDoc(entry)));
                }
                item.setInsertText(patternSnippet(pattern));
                item.setInsertTextFormat(InsertTextFormat.Snippet);
                if (entry.deprecated()) item.setTags(List.of(CompletionItemTag.Deprecated));
                items.add(item);
            }
        }
    }

    /**
     * Appends expression completion items filtered by the given prefix string.
     *
     * @param docs   the documentation data
     * @param items  the list to append items to
     * @param filter the filter prefix
     */
    private void completeExpressions(@NotNull DocumentationData docs, @NotNull List<CompletionItem> items,
                                     @NotNull String filter) {
        String lower = filter.toLowerCase();
        Set<String> seen = new HashSet<>();
        for (PatternEntry entry : docs.expressions()) {
            for (String pattern : entry.patterns()) {
                String label = patternLabel(pattern);
                if (!seen.add(label)) continue;
                if (!lower.isEmpty() && !matches(label, lower)) continue;

                CompletionItem item = new CompletionItem(label);
                item.setKind(CompletionItemKind.Value);
                item.setDetail(entry.category() != null ? entry.category() : "Expression");
                if (entry.description() != null) {
                    item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, patternDoc(entry)));
                }
                item.setInsertText(patternSnippet(pattern));
                item.setInsertTextFormat(InsertTextFormat.Snippet);
                if (entry.deprecated()) item.setTags(List.of(CompletionItemTag.Deprecated));
                items.add(item);
            }
        }
    }

    /**
     * Appends condition completion items filtered by the given prefix string.
     *
     * @param docs   the documentation data
     * @param items  the list to append items to
     * @param filter the filter prefix
     */
    private void completeConditions(@NotNull DocumentationData docs, @NotNull List<CompletionItem> items,
                                    @NotNull String filter) {
        String lower = filter.toLowerCase();
        Set<String> seen = new HashSet<>();
        for (PatternEntry entry : docs.conditions()) {
            for (String pattern : entry.patterns()) {
                String label = patternLabel(pattern);
                if (!seen.add(label)) continue;
                if (!lower.isEmpty() && !matches(label, lower)) continue;

                CompletionItem item = new CompletionItem(label);
                item.setKind(CompletionItemKind.Keyword);
                item.setDetail(entry.category() != null ? entry.category() : "Condition");
                if (entry.description() != null) {
                    item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, patternDoc(entry)));
                }
                item.setInsertText(patternSnippet(pattern));
                item.setInsertTextFormat(InsertTextFormat.Snippet);
                if (entry.deprecated()) item.setTags(List.of(CompletionItemTag.Deprecated));
                items.add(item);
            }
        }
    }

    /**
     * Appends loop source completion items filtered by the given prefix string.
     *
     * @param docs   the documentation data
     * @param items  the list to append items to
     * @param filter the filter prefix
     */
    private void completeLoopSources(@NotNull DocumentationData docs, @NotNull List<CompletionItem> items,
                                     @NotNull String filter) {
        String lower = filter.toLowerCase();
        Set<String> seen = new HashSet<>();
        for (PatternEntry entry : docs.loopSources()) {
            for (String pattern : entry.patterns()) {
                String label = patternLabel(pattern);
                if (!seen.add(label)) continue;
                if (!lower.isEmpty() && !matches(label, lower)) continue;

                CompletionItem item = new CompletionItem(label);
                item.setKind(CompletionItemKind.Enum);
                item.setDetail(entry.category() != null ? entry.category() : "Loop Source");
                if (entry.description() != null) {
                    item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, patternDoc(entry)));
                }
                item.setInsertText(patternSnippet(pattern));
                item.setInsertTextFormat(InsertTextFormat.Snippet);
                if (entry.deprecated()) item.setTags(List.of(CompletionItemTag.Deprecated));
                items.add(item);
            }
        }
    }

    /**
     * Appends block pattern completion items filtered by the given prefix string.
     *
     * @param docs   the documentation data
     * @param items  the list to append items to
     * @param filter the filter prefix
     */
    private void completeBlocks(@NotNull DocumentationData docs, @NotNull List<CompletionItem> items,
                                @NotNull String filter) {
        String lower = filter.toLowerCase();
        Set<String> seen = new HashSet<>();
        for (BlockEntry entry : docs.blocks()) {
            if (!entry.supportsBlock()) continue;
            for (String pattern : entry.patterns()) {
                String label = patternLabel(pattern);
                if (!seen.add(label)) continue;
                if (!lower.isEmpty() && !matches(label, lower)) continue;

                CompletionItem item = new CompletionItem(label);
                item.setKind(CompletionItemKind.Struct);
                item.setDetail(entry.category() != null ? entry.category() : "Block");
                if (entry.description() != null) {
                    item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, blockDoc(entry)));
                }
                item.setInsertText(patternSnippet(pattern) + ":\n\t$0");
                item.setInsertTextFormat(InsertTextFormat.Snippet);
                if (entry.deprecated()) item.setTags(List.of(CompletionItemTag.Deprecated));
                items.add(item);
            }
        }
    }

    /**
     * Appends variable completion items from the current scope, filtered by the given prefix.
     *
     * @param scopedVars the variable declarations in scope
     * @param items      the list to append items to
     * @param filter     the filter prefix
     */
    private void completeVariables(@NotNull Collection<VarDeclaration> scopedVars,
                                   @NotNull List<CompletionItem> items,
                                   @NotNull String filter) {
        String lower = filter.toLowerCase();
        for (VarDeclaration var : scopedVars) {
            if (!lower.isEmpty() && !var.name().toLowerCase().startsWith(lower)) continue;
            CompletionItem item = new CompletionItem(var.name());
            item.setKind(CompletionItemKind.Variable);
            item.setDetail(var.type());
            items.add(item);
        }
    }

    /**
     * Appends variable and property interpolation completion items for use inside string
     * interpolation expressions.
     *
     * @param scopedVars the variable declarations in scope
     * @param items      the list to append items to
     * @param filter     the filter prefix
     */
    private void completeInterpolation(@NotNull Collection<VarDeclaration> scopedVars,
                                       @NotNull List<CompletionItem> items,
                                       @NotNull String filter) {
        String lower = filter.toLowerCase();
        for (VarDeclaration var : scopedVars) {
            if (!lower.isEmpty() && !var.name().toLowerCase().startsWith(lower)) continue;
            CompletionItem item = new CompletionItem(var.name());
            item.setKind(CompletionItemKind.Variable);
            item.setDetail(var.type());
            item.setInsertText(var.name());
            items.add(item);

            String propName = var.name() + "_name";
            if (!lower.isEmpty() && !propName.toLowerCase().startsWith(lower)) continue;
            CompletionItem propItem = new CompletionItem(propName);
            propItem.setKind(CompletionItemKind.Property);
            propItem.setDetail("Property of " + var.name());
            items.add(propItem);
        }
    }

    /**
     * Appends MiniColorize tag completion items including named colors, decorations, and special tags.
     *
     * @param items  the list to append items to
     * @param filter the partial tag text typed so far
     */
    private void completeMiniColorize(@NotNull List<CompletionItem> items,
                                      @NotNull String filter) {
        String lower = filter.toLowerCase();
        completeMiniColors(items, lower);
        completeMiniDecorations(items, lower);
        completeMiniSpecial(items, lower);
    }

    /**
     * Appends named color completion items.
     *
     * @param items  the list to append items to
     * @param filter the lowercase filter
     */
    private void completeMiniColors(@NotNull List<CompletionItem> items, @NotNull String filter) {
        List<MiniColorEntry> colors = List.of(
                new MiniColorEntry("red", "#FF5555", "Red color", "\"<red>This text is red</red>\""),
                new MiniColorEntry("green", "#55FF55", "Green color", "\"<green>This text is green</green>\""),
                new MiniColorEntry("blue", "#5555FF", "Blue color", "\"<blue>This text is blue</blue>\""),
                new MiniColorEntry("yellow", "#FFFF55", "Yellow color", "\"<yellow>Warning!</yellow>\""),
                new MiniColorEntry("gold", "#FFAA00", "Gold color", "\"<gold>Achievement unlocked</gold>\""),
                new MiniColorEntry("aqua", "#55FFFF", "Aqua/cyan color", "\"<aqua>Info message</aqua>\""),
                new MiniColorEntry("white", "#FFFFFF", "White color", "\"<white>Default text</white>\""),
                new MiniColorEntry("black", "#000000", "Black color", "\"<black>Dark text</black>\""),
                new MiniColorEntry("gray", "#AAAAAA", "Gray color", "\"<gray>Muted text</gray>\""),
                new MiniColorEntry("dark_red", "#AA0000", "Dark red color", "\"<dark_red>Error!</dark_red>\""),
                new MiniColorEntry("dark_green", "#00AA00", "Dark green color", "\"<dark_green>Success</dark_green>\""),
                new MiniColorEntry("dark_blue", "#0000AA", "Dark blue color", "\"<dark_blue>Link</dark_blue>\""),
                new MiniColorEntry("dark_aqua", "#00AAAA", "Dark aqua color", "\"<dark_aqua>Info</dark_aqua>\""),
                new MiniColorEntry("dark_purple", "#AA00AA", "Dark purple color", "\"<dark_purple>Rare item</dark_purple>\""),
                new MiniColorEntry("dark_gray", "#555555", "Dark gray color", "\"<dark_gray>Disabled</dark_gray>\""),
                new MiniColorEntry("light_purple", "#FF55FF", "Light purple/pink color", "\"<light_purple>Special</light_purple>\"")
        );
        for (MiniColorEntry entry : colors) {
            if (!filter.isEmpty() && !entry.name().startsWith(filter)) continue;
            CompletionItem item = new CompletionItem(entry.name());
            item.setKind(CompletionItemKind.Color);
            item.setDetail(entry.hex());
            item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, """
                    **%s** (`%s`)
                    
                    Applies %s color to the enclosed text.
                    
                    **Examples:**
                    ```luma
                    message player %s
                    message player "<%s>colored <bold>and bold</bold></%s>"
                    ```
                    
                    Close with `</%s>` or use `<reset>` to clear.""".formatted(
                    entry.description(), entry.hex(), entry.name(), entry.example(),
                    entry.name(), entry.name(), entry.name())));
            item.setInsertText(entry.name() + ">");
            item.setInsertTextFormat(InsertTextFormat.PlainText);
            items.add(item);
        }
    }

    /**
     * Appends decoration tag completion items.
     *
     * @param items  the list to append items to
     * @param filter the lowercase filter
     */
    private void completeMiniDecorations(@NotNull List<CompletionItem> items, @NotNull String filter) {
        List<MiniDecorationEntry> decorations = List.of(
                new MiniDecorationEntry("bold", "b", "Bold text", "Makes text **bold**"),
                new MiniDecorationEntry("italic", "i", "Italic text", "Makes text *italic*"),
                new MiniDecorationEntry("underlined", "u", "Underlined text", "Adds underline decoration"),
                new MiniDecorationEntry("strikethrough", "st", "Strikethrough text", "Adds ~~strikethrough~~ decoration"),
                new MiniDecorationEntry("obfuscated", "obf", "Obfuscated text", "Makes text randomize/scramble continuously")
        );
        for (MiniDecorationEntry entry : decorations) {
            if (!filter.isEmpty() && !entry.name().startsWith(filter) && !entry.alias().startsWith(filter)) continue;
            CompletionItem item = new CompletionItem(entry.name());
            item.setKind(CompletionItemKind.Keyword);
            item.setDetail(entry.label());
            item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, """
                    **%s** (alias: `<%s>`)
                    
                    %s
                    
                    **Examples:**
                    ```luma
                    message player "<%s>important text</%s>"
                    message player "<%s>shorthand</%s>"
                    message player "<red><%s>colored and styled</%s></red>"
                    ```
                    
                    Negate with `<!%s>` or `<%s:false>`.""".formatted(
                    entry.label(), entry.alias(), entry.description(),
                    entry.name(), entry.name(), entry.alias(), entry.alias(),
                    entry.name(), entry.name(), entry.name(), entry.name())));
            item.setInsertText(entry.name() + ">");
            item.setInsertTextFormat(InsertTextFormat.PlainText);
            items.add(item);
        }
    }

    /**
     * Appends special MiniColorize tag completion items (reset, rainbow, gradient, etc.).
     *
     * @param items  the list to append items to
     * @param filter the lowercase filter
     */
    private void completeMiniSpecial(@NotNull List<CompletionItem> items, @NotNull String filter) {
        List<MiniSpecialEntry> special = List.of(
                new MiniSpecialEntry("reset", "Clear all formatting", """
                        **Reset Tag**
                        
                        Clears all active formatting (colors, decorations, events).
                        
                        **Examples:**
                        ```luma
                        message player "<red><bold>styled<reset> normal text"
                        message player "<gradient:red:blue>gradient<reset> plain"
                        ```
                        
                        Alias: `<r>`"""),
                new MiniSpecialEntry("rainbow", "Rainbow colored text", """
                        **Rainbow Tag**
                        
                        Applies a rainbow color gradient across the enclosed text.
                        
                        **Examples:**
                        ```luma
                        message player "<rainbow>All the colors!</rainbow>"
                        message player "<rainbow:2>faster cycle</rainbow>"
                        message player "<rainbow:!>reversed rainbow</rainbow>"
                        ```
                        
                        Optional phase parameter controls the starting point."""),
                new MiniSpecialEntry("gradient:", "Gradient between colors", """
                        **Gradient Tag**
                        
                        Creates a smooth color gradient between two or more colors.
                        
                        **Examples:**
                        ```luma
                        message player "<gradient:red:blue>smooth blend</gradient>"
                        message player "<gradient:red:gold:green>multi stop</gradient>"
                        message player "<gradient:#FF0000:#00FF00>hex gradient</gradient>"
                        ```
                        
                        Supports named colors and hex codes. Use `:!` suffix to reverse."""),
                new MiniSpecialEntry("click:", "Click event", """
                        **Click Tag**
                        
                        Adds a click action to the enclosed text.
                        
                        **Actions:** `run_command`, `suggest_command`, `open_url`, `copy_to_clipboard`
                        
                        **Examples:**
                        ```luma
                        message player "<click:run_command:/help>Click for help</click>"
                        message player "<click:open_url:https://example.com>Visit site</click>"
                        message player "<click:suggest_command:/msg >Click to message</click>"
                        message player "<click:copy_to_clipboard:text>Copy me</click>"
                        ```"""),
                new MiniSpecialEntry("hover:", "Hover event", """
                        **Hover Tag**
                        
                        Shows a tooltip when the player hovers over the enclosed text.
                        
                        **Actions:** `show_text`, `show_item`, `show_entity`
                        
                        **Examples:**
                        ```luma
                        message player "<hover:show_text:'Extra info here'>Hover me</hover>"
                        message player "<hover:show_text:'<red>Red tooltip'>Hover</hover>"
                        ```
                        
                        Tooltip text supports nested MiniColorize formatting."""),
                new MiniSpecialEntry("key:", "Keybind display", """
                        **Keybind Tag**
                        
                        Displays the player's configured keybind for the given action.
                        
                        **Examples:**
                        ```luma
                        message player "Press <key:key.jump> to jump"
                        message player "Use <key:key.inventory> to open inventory"
                        message player "Sprint: <key:key.sprint>"
                        ```
                        
                        Shows the actual key the player has bound, not a hardcoded key name."""),
                new MiniSpecialEntry("lang:", "Translatable text", """
                        **Translatable Tag**
                        
                        Inserts a client-side translated string using Minecraft's language files.
                        
                        **Examples:**
                        ```luma
                        message player "<lang:block.minecraft.stone>"
                        message player "<lang:item.minecraft.diamond_sword>"
                        message player "You mined <lang:block.minecraft.diamond_ore>"
                        ```
                        
                        Text is translated based on the player's language setting."""),
                new MiniSpecialEntry("insert:", "Shift click insertion", """
                        **Insertion Tag**
                        
                        Inserts text into the chat box when the player shift-clicks the message.
                        
                        **Examples:**
                        ```luma
                        message player "<insert:Hello!>Shift click me</insert>"
                        message player "<insert:/tp ~ ~ ~>Teleport command</insert>"
                        ```"""),
                new MiniSpecialEntry("#", "Hex color", """
                        **Hex Color Tag**
                        
                        Applies a custom hex color to the enclosed text.
                        
                        **Examples:**
                        ```luma
                        message player "<#FF5555>Custom red</#FF5555>"
                        message player "<#00AAFF>Custom blue</#00AAFF>"
                        message player "<#FFD700>Golden text</#FFD700>"
                        ```
                        
                        Format: `#RRGGBB` (6 digit hex). Use the color picker for easy selection."""),
                new MiniSpecialEntry("color:", "Color by name or hex", """
                        **Color Prefix Tag**
                        
                        Alternative syntax for applying colors using a prefix.
                        
                        **Examples:**
                        ```luma
                        message player "<color:red>Red text</color:red>"
                        message player "<color:#FF5555>Hex color</color:#FF5555>"
                        message player "<c:gold>Short prefix</c:gold>"
                        ```
                        
                        Aliases: `<colour:...>`, `<c:...>`""")
        );
        for (MiniSpecialEntry entry : special) {
            if (!filter.isEmpty() && !entry.name().startsWith(filter)) continue;
            CompletionItem item = new CompletionItem(entry.name());
            item.setKind(CompletionItemKind.Keyword);
            item.setDetail(entry.detail());
            item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, entry.documentation()));
            item.setInsertText(entry.name().endsWith(":") ? entry.name() : entry.name() + ">");
            item.setInsertTextFormat(InsertTextFormat.PlainText);
            items.add(item);
        }
    }

    /**
     * Appends type binding completion items filtered by the given prefix string.
     *
     * @param docs   the documentation data
     * @param items  the list to append items to
     * @param filter the filter prefix
     */
    private void completeTypeBindings(@NotNull DocumentationData docs, @NotNull List<CompletionItem> items,
                                      @NotNull String filter) {
        String lower = filter.toLowerCase();
        for (TypeBindingEntry tb : docs.typeBindings()) {
            if (!lower.isEmpty() && !tb.id().toLowerCase().startsWith(lower)) continue;
            CompletionItem item = new CompletionItem(tb.id());
            item.setKind(CompletionItemKind.TypeParameter);
            item.setDetail(tb.javaType() != null ? tb.javaType() : "Type");
            if (tb.description() != null) {
                item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
                        "**" + tb.id() + "**\n\n" + tb.description()));
            }
            if (tb.deprecated()) item.setTags(List.of(CompletionItemTag.Deprecated));
            items.add(item);
        }
    }

    /**
     * Appends block and keyword completion items filtered by the given prefix.
     *
     * @param docs   the documentation data
     * @param items  the list to append items to
     * @param filter the filter prefix
     * @param root   true when completing at the top level, false when inside a block
     */
    private void completeTopLevel(@NotNull DocumentationData docs,
                                  @NotNull List<CompletionItem> items,
                                  @NotNull String filter,
                                  boolean root) {
        String lower = filter.toLowerCase();
        Set<String> seen = new HashSet<>();

        for (BlockEntry entry : docs.blocks()) {
            if (root && !entry.supportsRootLevel()) continue;
            if (!root && !entry.supportsBlock()) continue;
            for (String pattern : entry.patterns()) {
                String label = patternLabel(pattern);
                if (!seen.add(label)) continue;
                if (!lower.isEmpty() && !matches(label, lower)) continue;
                CompletionItem item = new CompletionItem(label);
                item.setKind(CompletionItemKind.Keyword);
                item.setDetail(entry.category() != null ? entry.category() : "Block");
                if (entry.description() != null) {
                    item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, blockDoc(entry)));
                }
                item.setInsertText(patternSnippet(pattern) + ":\n\t$0");
                item.setInsertTextFormat(InsertTextFormat.Snippet);
                if (entry.deprecated()) item.setTags(List.of(CompletionItemTag.Deprecated));
                items.add(item);
                break;
            }
        }

        addKeyword(items, lower, "var", "Variable declaration", "var $1 = $0");
        addKeyword(items, lower, "global var", "Global variable", "global var $1 default $0");
        if (root) {
            addKeyword(items, lower, "data", "Data class definition", "data $1:\n\t$0");
            addKeyword(items, lower, "config", "Config block", "config:\n\t$0");
        }
    }

    /**
     * Adds a single keyword completion item if it matches the filter.
     *
     * @param items   the list to append to
     * @param filter  the lowercase filter prefix
     * @param label   the keyword label
     * @param detail  the detail text
     * @param snippet the insert snippet
     */
    private void addKeyword(@NotNull List<CompletionItem> items,
                            @NotNull String filter,
                            @NotNull String label,
                            @NotNull String detail,
                            @NotNull String snippet) {
        if (!label.toLowerCase().startsWith(filter)) return;
        CompletionItem item = new CompletionItem(label);
        item.setKind(CompletionItemKind.Keyword);
        item.setDetail(detail);
        item.setInsertText(snippet);
        item.setInsertTextFormat(InsertTextFormat.Snippet);
        items.add(item);
    }

    /**
     * Converts a pattern string into a human-readable label by stripping placeholders.
     */
    private @NotNull String patternLabel(@NotNull String pattern) {
        StringBuilder sb = new StringBuilder();
        Matcher m = PLACEHOLDER_PATTERN.matcher(pattern);
        int last = 0;
        while (m.find()) {
            sb.append(pattern, last, m.start());
            sb.append('<').append(m.group(1));
            if (m.group(2) != null) sb.append(':').append(m.group(2).toLowerCase());
            sb.append('>');
            last = m.end();
        }
        sb.append(pattern.substring(last));
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    /**
     * Converts a pattern string into a snippet with tab stops for each placeholder.
     */
    private @NotNull String patternSnippet(@NotNull String pattern) {
        StringBuilder sb = new StringBuilder();
        Matcher m = PLACEHOLDER_PATTERN.matcher(pattern);
        int last = 0;
        int tabIndex = 1;
        while (m.find()) {
            sb.append(pattern, last, m.start());
            sb.append("${").append(tabIndex++).append(':').append(m.group(1)).append('}');
            last = m.end();
        }
        sb.append(pattern.substring(last));
        return cleanBrackets(sb.toString());
    }

    /**
     * Removes redundant bracket alternatives from a pattern snippet string.
     *
     * @param s the snippet string to clean
     * @return the cleaned snippet
     */
    private @NotNull String cleanBrackets(@NotNull String s) {
        return s.replaceAll("\\(([^|)]+)\\|[^)]*\\)", "$1")
                .replaceAll("\\[([^|\\]]+)\\|[^]]*]", "$1")
                .replaceAll("\\[([^]]*)]", "$1");
    }

    /**
     * Builds a Markdown documentation string for a pattern entry.
     *
     * @param entry the pattern entry to document
     * @return the Markdown documentation string
     */
    private @NotNull String patternDoc(@NotNull PatternEntry entry) {
        StringBuilder sb = new StringBuilder();
        if (entry.description() != null) {
            sb.append(entry.description()).append("\n\n");
        }
        if (entry.category() != null) {
            sb.append("**Category:** ").append(entry.category()).append("\n\n");
        }
        if (entry.since() != null) {
            sb.append("**Since:** ").append(entry.since()).append("\n\n");
        }
        if (!entry.examples().isEmpty()) {
            sb.append("**Examples:**\n```luma\n");
            for (String ex : entry.examples()) {
                sb.append(ex).append('\n');
            }
            sb.append("```\n");
        }
        if (entry.deprecated()) {
            sb.append("\n**@deprecated**\n");
        }
        return sb.toString();
    }

    /**
     * Builds a Markdown documentation string for a block entry.
     *
     * @param entry the block entry to document
     * @return the Markdown documentation string
     */
    private @NotNull String blockDoc(@NotNull BlockEntry entry) {
        StringBuilder sb = new StringBuilder();
        if (entry.description() != null) {
            sb.append(entry.description()).append("\n\n");
        }
        if (entry.category() != null) {
            sb.append("**Category:** ").append(entry.category()).append("\n\n");
        }
        if (entry.variables() != null && !entry.variables().isEmpty()) {
            sb.append("**Provided Variables:**\n");
            for (BlockVariable var : entry.variables()) {
                sb.append("- `").append(var.name()).append("` : `").append(var.type()).append('`');
                if (var.nullable()) sb.append(" `nullable`");
                if (var.description() != null) sb.append(" ").append(var.description());
                sb.append('\n');
            }
            sb.append('\n');
        }
        if (entry.since() != null) {
            sb.append("**Since:** ").append(entry.since()).append("\n\n");
        }
        if (!entry.examples().isEmpty()) {
            sb.append("**Examples:**\n```luma\n");
            for (String ex : entry.examples()) {
                sb.append(ex).append('\n');
            }
            sb.append("```\n");
        }
        if (entry.deprecated()) {
            sb.append("\n**@deprecated**\n");
        }
        return sb.toString();
    }

    /**
     * Builds a Markdown documentation string for an event entry.
     *
     * @param event the event entry to document
     * @return the Markdown documentation string
     */
    private @NotNull String eventDoc(@NotNull EventEntry event) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Event:** ").append(event.name()).append("\n\n");
        if (event.description() != null) {
            sb.append(event.description()).append("\n\n");
        }
        if (event.className() != null) {
            sb.append("**Class:** `").append(event.className()).append("`\n\n");
        }
        if (!event.variables().isEmpty()) {
            sb.append("**Variables:**\n");
            for (EventVariable var : event.variables()) {
                sb.append("- `").append(var.name()).append("` : `").append(var.javaType() != null ? var.javaType() : "Object").append('`');
                if (var.nullable()) sb.append(" `nullable`");
                if (var.description() != null) sb.append(" ").append(var.description());
                sb.append('\n');
            }
            sb.append('\n');
        }
        if (!event.examples().isEmpty()) {
            sb.append("**Examples:**\n```luma\n");
            for (String ex : event.examples()) {
                sb.append(ex).append('\n');
            }
            sb.append("```\n");
        }
        return sb.toString();
    }

    /**
     * Determines whether the current cursor position is inside a data block, either via
     * analysis data or by scanning backwards through the raw source lines.
     *
     * @param lineIndex the 0-based index of the current line
     * @param lines     all source lines
     * @param analysis  the analysis result, may be null
     * @return true if the cursor is inside a data block
     */
    private boolean isInsideDataBlock(int lineIndex, @NotNull String[] lines,
                                      @Nullable AnalysisResult analysis) {
        if (analysis != null) {
            LineInfo info = analysis.lineInfo().get(lineIndex + 1);
            if (info != null && info.kind() == LineKind.DATA_FIELD) return true;
        }
        String currentLine = lines[lineIndex];
        if (currentLine.equals(currentLine.stripLeading())) return false;
        for (int i = lineIndex - 1; i >= 0; i--) {
            String prev = lines[i].stripLeading();
            if (prev.isEmpty()) continue;
            if (prev.toLowerCase().startsWith("data ") && prev.endsWith(":")) return true;
            if (lines[i].equals(lines[i].stripLeading())) return false;
        }
        return false;
    }

    /**
     * Determines whether the current cursor position is inside a config block, either via
     * analysis data or by scanning backwards through the raw source lines.
     *
     * @param lineIndex the 0-based index of the current line
     * @param lines     all source lines
     * @param analysis  the analysis result, may be null
     * @return true if the cursor is inside a config block
     */
    private boolean isInsideConfigBlock(int lineIndex, @NotNull String[] lines,
                                        @Nullable AnalysisResult analysis) {
        if (analysis != null) {
            LineInfo info = analysis.lineInfo().get(lineIndex + 1);
            if (info != null && info.kind() == LineKind.CONFIG_ENTRY) return true;
        }
        String currentLine = lines[lineIndex];
        if (currentLine.equals(currentLine.stripLeading())) return false;
        for (int i = lineIndex - 1; i >= 0; i--) {
            String prev = lines[i].stripLeading();
            if (prev.isEmpty()) continue;
            if (prev.equalsIgnoreCase("config:")) return true;
            if (lines[i].equals(lines[i].stripLeading())) return false;
        }
        return false;
    }

    /**
     * Appends data field type keyword completions for use inside a data block body.
     *
     * @param content the whitespace-stripped current line content
     * @param items   the list to append items to
     */
    private void completeDataFieldTypes(@NotNull String content, @NotNull List<CompletionItem> items) {
        String[] parts = content.trim().split("\\s+");
        if (parts.length >= 2) return;
        String filter = content.trim().toLowerCase();

        List<FieldTypeEntry> types = List.of(
                new FieldTypeEntry("text", "String", "A text/string value"),
                new FieldTypeEntry("number", "double", "A decimal number"),
                new FieldTypeEntry("integer", "int", "A whole number"),
                new FieldTypeEntry("boolean", "boolean", "A true/false value"),
                new FieldTypeEntry("any", "Object", "Any value type")
        );
        for (FieldTypeEntry type : types) {
            if (!filter.isEmpty() && !type.name().startsWith(filter)) continue;
            CompletionItem item = new CompletionItem(type.name());
            item.setKind(CompletionItemKind.TypeParameter);
            item.setDetail(type.javaType());
            item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, type.description()));
            items.add(item);
        }
    }

    /**
     * Appends existing data schema field completions from previously analyzed data blocks.
     *
     * @param analysis the analysis result containing data schemas
     * @param items    the list to append items to
     */
    private void completeDataSchemaFields(@NotNull AnalysisResult analysis, @NotNull List<CompletionItem> items) {
        for (var schema : analysis.dataSchemas().values()) {
            for (var entry : schema.fields().entrySet()) {
                String fieldCompletion = entry.getKey() + " " + entry.getValue().name().toLowerCase();
                CompletionItem item = new CompletionItem(fieldCompletion);
                item.setKind(CompletionItemKind.Field);
                item.setDetail("Field of " + schema.name());
                items.add(item);
            }
        }
    }

}
