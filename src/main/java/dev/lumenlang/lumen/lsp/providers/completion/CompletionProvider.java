package dev.lumenlang.lumen.lsp.providers.completion;

import dev.lumenlang.lumen.api.codegen.TypeEnv;
import dev.lumenlang.lumen.api.codegen.TypeEnv.VarHandle;
import dev.lumenlang.lumen.api.language.SemanticKind;
import dev.lumenlang.lumen.api.language.Suggestion;
import dev.lumenlang.lumen.api.type.LumenType;
import dev.lumenlang.lumen.lsp.analysis.AnalysisResult;
import dev.lumenlang.lumen.lsp.analysis.LineAnalysis;
import dev.lumenlang.lumen.lsp.bootstrap.LumenBootstrap;
import dev.lumenlang.lumen.pipeline.codegen.TypeEnvImpl;
import dev.lumenlang.lumen.pipeline.language.TypeBinding;
import dev.lumenlang.lumen.pipeline.language.compile.PatternCompiler;
import dev.lumenlang.lumen.pipeline.language.pattern.Pattern;
import dev.lumenlang.lumen.pipeline.language.pattern.PatternPart;
import dev.lumenlang.lumen.pipeline.language.pattern.PatternRegistry;
import dev.lumenlang.lumen.pipeline.language.pattern.Placeholder;
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredBlock;
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredExpression;
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredPattern;
import dev.lumenlang.lumen.pipeline.typebinding.TypeRegistry;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds context aware completion items. Pattern matches are emitted as
 * tabstop snippets so accepting them places the cursor on the first
 * placeholder. Binding driven suggestions resolve in scope variables,
 * registered events, and type names directly from the pipeline registries.
 */
public final class CompletionProvider {

    private CompletionProvider() {
    }

    /**
     * Returns the completion items for the given cursor position.
     *
     * @param bootstrap the populated bootstrap
     * @param result    the analysis of the document
     * @param source    the full document text, used to slice the partial line in front of the cursor
     * @param position  the cursor position
     * @return the completion items, possibly empty
     */
    public static @NotNull List<CompletionItem> complete(@NotNull LumenBootstrap bootstrap, @NotNull AnalysisResult result, @NotNull String source, @NotNull Position position) {
        String[] lines = source.split("\\r?\\n", -1);
        int row = position.getLine();
        int col = position.getCharacter();
        if (row >= lines.length) return List.of();
        String rawLine = lines[row];
        boolean topLevel = leadingIndent(rawLine, col) == 0;
        String prefix = prefixSlice(rawLine, col).stripLeading();
        TypeEnv env = entryEnv(result, position.getLine() + 1);
        List<CompletionItem> out = new ArrayList<>();
        PatternRegistry registry = bootstrap.patterns();
        TypeRegistry types = bootstrap.types();
        String firstWord = firstWordOf(prefix);
        String partial = trailingIdent(prefix);
        boolean lockedHead = !firstWord.isEmpty() && !firstWord.equals(partial);

        Set<String> seenInsert = new HashSet<>();
        Slot slot = detectSlot(prefix, firstWord, registry, env, types);
        if (slot != null) {
            emitBindingSuggestions(out, seenInsert, slot, env);
            if (!out.isEmpty()) return out;
        }

        if (!topLevel) {
            for (RegisteredPattern rp : registry.getStatements()) {
                if (relevant(rp.pattern(), firstWord, partial, lockedHead)) {
                    addPattern(out, seenInsert, rp.pattern(), CompletionItemKind.Function, "statement");
                }
            }
        }
        for (RegisteredBlock rb : registry.getBlocks()) {
            if (topLevel ? !rb.supportsRootLevel() : !rb.supportsBlock()) continue;
            if (relevant(rb.pattern(), firstWord, partial, lockedHead)) {
                addPattern(out, seenInsert, rb.pattern(), CompletionItemKind.Class, "block");
            }
        }
        if (!topLevel) {
            for (RegisteredExpression re : registry.getExpressions()) {
                if (relevant(re.pattern(), firstWord, partial, lockedHead)) {
                    addPattern(out, seenInsert, re.pattern(), CompletionItemKind.Value, "expression");
                }
            }
            if (env instanceof TypeEnvImpl impl) {
                for (String name : impl.allVisibleVarNames()) {
                    if (partial.isEmpty() || name.toLowerCase().startsWith(partial.toLowerCase())) {
                        if (seenInsert.add(name)) addVariable(out, name, impl.lookupVar(name));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Returns the count of leading whitespace columns on the cursor's line up
     * to the cursor position. Zero means the cursor sits on a top level line.
     */
    private static int leadingIndent(@NotNull String line, int col) {
        int safe = Math.min(col, line.length());
        int i = 0;
        while (i < safe && Character.isWhitespace(line.charAt(i))) i++;
        return i;
    }

    /**
     * Walks the binding's suggestions and appends them as LSP completion items.
     * Suggestions that carry a pattern raw form (statement, block, expression,
     * condition) are expanded the same way as the registry sourced patterns so
     * required-group alternatives split into separate entries and optional
     * groups disappear.
     */
    private static void emitBindingSuggestions(@NotNull List<CompletionItem> out, @NotNull Set<String> seenInsert, @NotNull Slot slot, @Nullable TypeEnv env) {
        TypeEnvImpl envImpl = env instanceof TypeEnvImpl i ? i : new TypeEnvImpl();
        for (Suggestion s : slot.binding.suggestions(envImpl, slot.expectedType)) {
            if (isPatternShape(s)) {
                Pattern compiled = tryCompile(s.insertText());
                if (compiled != null) {
                    addPattern(out, seenInsert, compiled, kindOf(s.kind()), s.detail());
                    continue;
                }
            }
            if (!seenInsert.add(s.insertText())) continue;
            out.add(toLspItem(s));
        }
    }

    /**
     * Returns whether the suggestion's text is shaped like a registered pattern
     * raw form, in which case it deserves snippet expansion rather than literal
     * insertion.
     */
    private static boolean isPatternShape(@NotNull Suggestion s) {
        String text = s.insertText();
        return text.indexOf('%') >= 0 || text.indexOf('(') >= 0 || text.indexOf('[') >= 0;
    }

    /**
     * Compiles a raw pattern string to a {@link Pattern}, returning {@code null}
     * when the binding emitted plain text we should leave alone.
     */
    private static @Nullable Pattern tryCompile(@NotNull String raw) {
        try {
            return PatternCompiler.compile(raw);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Returns the placeholder slot the cursor sits in. Walks every pattern
     * whose head literal matches the user's first word, advancing literal by
     * literal against the typed prefix until the next part is a placeholder
     * with no token left to consume. The first such match wins.
     *
     * @return the slot, or {@code null} when no pattern's prefix shape lines up
     */
    private static @Nullable Slot detectSlot(@NotNull String prefix, @NotNull String firstWord, @NotNull PatternRegistry registry, @Nullable TypeEnv env, @NotNull TypeRegistry types) {
        if (firstWord.isEmpty()) return null;
        List<String> words = splitWords(prefix);
        boolean cursorOnNewWord = endsInWhitespace(prefix);
        int consumedTarget = cursorOnNewWord ? words.size() : words.size() - 1;
        if (consumedTarget < 1) return null;

        return scanPatterns(streamHeads(registry), words, consumedTarget, env, types);
    }

    /**
     * Returns every registered pattern as a single iterable, so {@link #detectSlot}
     * can walk statements, blocks, and expressions in one pass.
     */
    private static @NotNull List<Pattern> streamHeads(@NotNull PatternRegistry registry) {
        List<Pattern> all = new ArrayList<>();
        for (RegisteredPattern rp : registry.getStatements()) all.add(rp.pattern());
        for (RegisteredBlock rb : registry.getBlocks()) all.add(rb.pattern());
        for (RegisteredExpression re : registry.getExpressions()) all.add(re.pattern());
        return all;
    }

    /**
     * Walks every candidate pattern. For each, advances literal by literal
     * against the user's typed words and returns the slot when the cursor
     * lands on a placeholder boundary.
     */
    private static @Nullable Slot scanPatterns(@NotNull List<Pattern> patterns, @NotNull List<String> words, int consumedTarget, @Nullable TypeEnv env, @NotNull TypeRegistry types) {
        for (Pattern p : patterns) {
            Slot slot = walk(p.parts(), words, 0, consumedTarget, env, types, words);
            if (slot != null) return slot;
        }
        return null;
    }

    /**
     * Recursive part walker. Maintains a token cursor {@code idx} into
     * {@code words}; literals consume one token, placeholders consume one
     * token unless they are the slot under the cursor in which case the slot
     * is returned. Optional groups try to enter then fall through; required
     * groups try the first matching alternative.
     */
    private static @Nullable Slot walk(@NotNull List<PatternPart> parts, @NotNull List<String> words, int idx, int consumedTarget, @Nullable TypeEnv env, @NotNull TypeRegistry types, @NotNull List<String> wordsAll) {
        for (PatternPart part : parts) {
            if (idx >= consumedTarget) {
                if (part instanceof PatternPart.PlaceholderPart ph) {
                    return slotFor(ph.ph(), env, types, wordsAll, idx);
                }
                return null;
            }
            String word = words.get(idx);
            if (part instanceof PatternPart.Literal lit) {
                if (!lit.text().equalsIgnoreCase(word)) return null;
                idx++;
            } else if (part instanceof PatternPart.FlexLiteral flex) {
                if (!flex.forms().contains(word.toLowerCase())) return null;
                idx++;
            } else if (part instanceof PatternPart.PlaceholderPart) {
                idx++;
            } else if (part instanceof PatternPart.Group group) {
                int saved = idx;
                Integer matched = null;
                for (List<PatternPart> alt : group.alternatives()) {
                    Integer end = walkConsumes(alt, words, saved, consumedTarget);
                    if (end != null) {
                        matched = end;
                        break;
                    }
                }
                if (matched == null) {
                    if (group.required()) return null;
                } else {
                    idx = matched;
                }
            }
        }
        return null;
    }

    /**
     * Returns the new token index after walking the alternative for literal
     * tracking only. Used by {@link #walk} when descending into a group's
     * alternatives to figure out how many tokens an alternative ate.
     */
    private static @Nullable Integer walkConsumes(@NotNull List<PatternPart> parts, @NotNull List<String> words, int idx, int consumedTarget) {
        for (PatternPart part : parts) {
            if (idx >= consumedTarget) return idx;
            String word = words.get(idx);
            if (part instanceof PatternPart.Literal lit) {
                if (!lit.text().equalsIgnoreCase(word)) return null;
                idx++;
            } else if (part instanceof PatternPart.FlexLiteral flex) {
                if (!flex.forms().contains(word.toLowerCase())) return null;
                idx++;
            } else if (part instanceof PatternPart.PlaceholderPart) {
                idx++;
            } else if (part instanceof PatternPart.Group group) {
                int saved = idx;
                Integer matched = null;
                for (List<PatternPart> alt : group.alternatives()) {
                    Integer end = walkConsumes(alt, words, saved, consumedTarget);
                    if (end != null) {
                        matched = end;
                        break;
                    }
                }
                if (matched == null) {
                    if (group.required()) return null;
                } else {
                    idx = matched;
                }
            }
        }
        return idx;
    }

    /**
     * Builds the slot for the placeholder under the cursor, looking up its
     * binding by id and inferring an expected type from a well known
     * containing pattern such as {@code set %name:IDENT% to %val:EXPR%}.
     */
    private static @Nullable Slot slotFor(@NotNull Placeholder ph, @Nullable TypeEnv env, @NotNull TypeRegistry types, @NotNull List<String> wordsAll, int placeholderIdx) {
        TypeBinding binding = types.get(ph.typeId());
        if (binding == null) return null;
        LumenType expected = inferExpectedType(ph, wordsAll, placeholderIdx, env);
        return new Slot(binding, expected);
    }

    /**
     * Returns a narrowing type for the placeholder when the surrounding
     * statement shape is recognised. Currently covers
     * {@code set %name:IDENT% to %val:EXPR%}: when the slot is the
     * {@code val} placeholder, the declared type of the {@code name} variable
     * is used.
     */
    private static @Nullable LumenType inferExpectedType(@NotNull Placeholder ph, @NotNull List<String> words, int placeholderIdx, @Nullable TypeEnv env) {
        if (!"val".equals(ph.name())) return null;
        if (!(env instanceof TypeEnvImpl impl)) return null;
        if (placeholderIdx < 3) return null;
        if (!words.get(0).equalsIgnoreCase("set")) return null;
        if (!words.get(2).equalsIgnoreCase("to")) return null;
        VarHandle handle = impl.lookupVar(words.get(1));
        return handle != null ? handle.type() : null;
    }

    /**
     * Splits the prefix into whitespace separated words. Empty trailing
     * whitespace is dropped; callers detect "cursor on a new word" via
     * {@link #endsInWhitespace}.
     */
    private static @NotNull List<String> splitWords(@NotNull String prefix) {
        List<String> out = new ArrayList<>();
        int len = prefix.length();
        int i = 0;
        while (i < len) {
            while (i < len && Character.isWhitespace(prefix.charAt(i))) i++;
            int start = i;
            while (i < len && !Character.isWhitespace(prefix.charAt(i))) i++;
            if (i > start) out.add(prefix.substring(start, i));
        }
        return out;
    }

    /**
     * Returns whether the prefix ends in a whitespace character, which means
     * the cursor sits at the start of a fresh word.
     */
    private static boolean endsInWhitespace(@NotNull String prefix) {
        return !prefix.isEmpty() && Character.isWhitespace(prefix.charAt(prefix.length() - 1));
    }

    /**
     * Translates a binding {@link Suggestion} into an LSP completion item.
     */
    private static @NotNull CompletionItem toLspItem(@NotNull Suggestion s) {
        CompletionItem item = new CompletionItem(s.insertText());
        item.setInsertText(s.insertText());
        item.setDetail(s.detail());
        item.setKind(kindOf(s.kind()));
        item.setData(resolveKey(s.detail(), s.insertText()));
        return item;
    }

    /**
     * Builds the {@code data} payload the editor echoes back on resolve. Pairs
     * the suggestion's category with the inserted text so the resolver can
     * route the lookup to the right registry.
     */
    private static @NotNull java.util.Map<String, String> resolveKey(@NotNull String category, @NotNull String key) {
        java.util.Map<String, String> data = new java.util.HashMap<>();
        data.put("category", category);
        data.put("key", key);
        return data;
    }

    /**
     * Maps a binding semantic kind to the LSP completion icon kind that best
     * matches it.
     */
    private static @NotNull CompletionItemKind kindOf(@NotNull SemanticKind kind) {
        return switch (kind) {
            case VARIABLE, PASSTHROUGH -> CompletionItemKind.Variable;
            case PARAMETER -> CompletionItemKind.Variable;
            case PROPERTY -> CompletionItemKind.Property;
            case FUNCTION -> CompletionItemKind.Function;
            case TYPE -> CompletionItemKind.Class;
            case EVENT -> CompletionItemKind.Event;
            case NUMBER -> CompletionItemKind.Value;
            case STRING -> CompletionItemKind.Value;
            case NAMESPACE -> CompletionItemKind.Module;
            case KEYWORD -> CompletionItemKind.Keyword;
        };
    }

    /**
     * Returns the first whitespace separated token of the trimmed prefix.
     *
     * @param prefix the trimmed line slice in front of the cursor
     * @return the first word, possibly empty
     */
    private static @NotNull String firstWordOf(@NotNull String prefix) {
        for (int i = 0; i < prefix.length(); i++) {
            if (Character.isWhitespace(prefix.charAt(i))) return prefix.substring(0, i);
        }
        return prefix;
    }

    /**
     * Returns whether a pattern is still relevant given the head word the user
     * has already typed and what they are currently typing for the next token.
     *
     * @param pattern    the pattern to test
     * @param firstWord  the first word the user typed on this line, may be empty
     * @param partial    the trailing identifier under the cursor
     * @param lockedHead whether the user already has a complete first word followed by more input
     * @return whether to surface this pattern
     */
    private static boolean relevant(@NotNull Pattern pattern, @NotNull String firstWord, @NotNull String partial, boolean lockedHead) {
        if (lockedHead) {
            return matchesAnyHead(pattern.parts(), firstWord);
        }
        if (partial.isEmpty()) return true;
        for (String head : collectHeads(pattern.parts())) {
            if (head.toLowerCase().startsWith(partial.toLowerCase())) return true;
        }
        return false;
    }

    /**
     * Returns whether any of the pattern's possible head literals equals the
     * given word ignoring case.
     *
     * @param parts the pattern parts
     * @param word  the user's first word
     * @return whether any head matches
     */
    private static boolean matchesAnyHead(@NotNull List<PatternPart> parts, @NotNull String word) {
        for (String head : collectHeads(parts)) {
            if (head.equalsIgnoreCase(word)) return true;
        }
        return false;
    }

    /**
     * Collects every possible literal that could appear at the head of the
     * given pattern parts, descending one level into required and optional
     * groups so that all alternatives of a leading group are returned.
     *
     * @param parts the parts to scan
     * @return the head literals, possibly empty when the pattern starts with a placeholder
     */
    private static @NotNull List<String> collectHeads(@NotNull List<PatternPart> parts) {
        List<String> heads = new ArrayList<>();
        if (parts.isEmpty()) return heads;
        PatternPart head = parts.get(0);
        if (head instanceof PatternPart.Literal lit) {
            heads.add(lit.text());
            return heads;
        }
        if (head instanceof PatternPart.FlexLiteral flex) {
            heads.addAll(flex.forms());
            return heads;
        }
        if (head instanceof PatternPart.Group group) {
            for (List<PatternPart> alt : group.alternatives()) {
                heads.addAll(collectHeads(alt));
            }
            if (!group.required() && parts.size() > 1) {
                heads.addAll(collectHeads(parts.subList(1, parts.size())));
            }
        }
        return heads;
    }

    /**
     * Returns the env to use when looking up variables, taken from the line
     * preceding the cursor, falling back to the latest available line above.
     *
     * @param result     the analysis of the document
     * @param lineNumber the 1-based line number under the cursor
     * @return the env, or {@code null} when no prior line was analysed
     */
    private static @Nullable TypeEnv entryEnv(@NotNull AnalysisResult result, int lineNumber) {
        for (int i = lineNumber - 1; i >= 1; i--) {
            LineAnalysis prev = result.line(i);
            if (prev != null && prev.afterEnv() != null) return prev.afterEnv();
        }
        return null;
    }

    /**
     * Returns the segment of the line text that lies before the cursor column.
     *
     * @param line the full line text
     * @param col  the 0-based cursor column
     * @return the prefix
     */
    private static @NotNull String prefixSlice(@NotNull String line, int col) {
        int safe = Math.min(col, line.length());
        return line.substring(0, safe);
    }

    /**
     * Returns the trailing identifier characters of the given prefix used for
     * filtering candidates against what the user has typed so far.
     *
     * @param prefix the slice of line text in front of the cursor
     * @return the trailing identifier, possibly empty
     */
    private static @NotNull String trailingIdent(@NotNull String prefix) {
        int i = prefix.length();
        while (i > 0) {
            char c = prefix.charAt(i - 1);
            if (Character.isLetterOrDigit(c) || c == '_') i--;
            else break;
        }
        return prefix.substring(i);
    }

    /**
     * Expands the pattern's required-group alternatives into separate entries
     * (one per choice) and drops every optional group. The result is a flat
     * snippet per concrete form, so {@code (perform|run|execute) command %x%}
     * surfaces as three items rather than one with raw group syntax.
     *
     * @param out         the output list
     * @param seenInsert  cross-list dedupe set keyed on the snippet text
     * @param pattern     the pattern to expose
     * @param kind        the LSP completion kind
     * @param detail      short label shown next to the entry
     */
    private static void addPattern(@NotNull List<CompletionItem> out, @NotNull Set<String> seenInsert, @NotNull Pattern pattern, @NotNull CompletionItemKind kind, @NotNull String detail) {
        for (List<PatternPart> form : expandForms(pattern.parts())) {
            int[] tab = {1};
            StringBuilder sb = new StringBuilder();
            StringBuilder label = new StringBuilder();
            appendForm(sb, label, form, tab);
            sb.append("$0");
            String insert = sb.toString();
            String labelText = label.toString();
            if (!seenInsert.add(insert)) continue;
            CompletionItem item = new CompletionItem(labelText);
            item.setKind(kind);
            item.setDetail(detail);
            item.setInsertText(insert);
            item.setInsertTextFormat(InsertTextFormat.Snippet);
            item.setFilterText(labelText);
            item.setData(resolveKey(detail, pattern.raw()));
            out.add(item);
        }
    }

    /**
     * Returns every concrete form of the pattern: each required group becomes
     * a separate emitted alternative, optional groups are dropped entirely,
     * and flex literals collapse to their primary form.
     */
    private static @NotNull List<List<PatternPart>> expandForms(@NotNull List<PatternPart> parts) {
        List<List<PatternPart>> forms = new ArrayList<>();
        forms.add(new ArrayList<>());
        for (PatternPart part : parts) {
            if (part instanceof PatternPart.Group group) {
                if (!group.required()) continue;
                if (group.alternatives().isEmpty()) continue;
                List<List<PatternPart>> next = new ArrayList<>();
                for (List<PatternPart> alt : group.alternatives()) {
                    for (List<PatternPart> altExpanded : expandForms(alt)) {
                        for (List<PatternPart> base : forms) {
                            List<PatternPart> merged = new ArrayList<>(base);
                            merged.addAll(altExpanded);
                            next.add(merged);
                        }
                    }
                }
                forms = next;
            } else {
                for (List<PatternPart> base : forms) {
                    base.add(part);
                }
            }
        }
        return forms;
    }

    /**
     * Walks a flat (already expanded) form, appending its literals to the
     * label and writing snippet text with numbered tabstops for placeholders.
     */
    private static void appendForm(@NotNull StringBuilder snippet, @NotNull StringBuilder label, @NotNull List<PatternPart> parts, int[] tab) {
        boolean first = true;
        for (PatternPart part : parts) {
            if (part instanceof PatternPart.Literal lit) {
                if (!first) {
                    snippet.append(' ');
                    label.append(' ');
                }
                snippet.append(escapeSnippet(lit.text()));
                label.append(lit.text());
                first = false;
            } else if (part instanceof PatternPart.FlexLiteral flex) {
                String form = flex.forms().isEmpty() ? "" : flex.forms().get(0);
                if (!first) {
                    snippet.append(' ');
                    label.append(' ');
                }
                snippet.append(escapeSnippet(form));
                label.append(form);
                first = false;
            } else if (part instanceof PatternPart.PlaceholderPart ph) {
                Placeholder p = ph.ph();
                if (!first) {
                    snippet.append(' ');
                    label.append(' ');
                }
                snippet.append("${").append(tab[0]++).append(':').append(escapeSnippet(p.name())).append('}');
                label.append('<').append(p.typeId().toLowerCase()).append('>');
                first = false;
            }
        }
    }

    /**
     * Escapes the LSP snippet syntax meta characters {@code $}, {@code }},
     * and {@code \} so literal pattern text is inserted verbatim.
     */
    private static @NotNull String escapeSnippet(@NotNull String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '$' || c == '}' || c == '\\') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Adds a completion entry for the given variable name.
     *
     * @param out  the output list
     * @param name the variable source name
     * @param ref  the resolved var ref, or {@code null} when only the name is known
     */
    private static void addVariable(@NotNull List<CompletionItem> out, @NotNull String name, @Nullable VarHandle ref) {
        CompletionItem item = new CompletionItem(name);
        item.setKind(CompletionItemKind.Variable);
        if (ref != null) item.setDetail(ref.type().displayName());
        item.setData(resolveKey("variable", name));
        out.add(item);
    }

    /**
     * Carries the placeholder binding the cursor is currently sitting in,
     * along with any expected type narrowing the surrounding statement
     * declares for the slot.
     */
    private record Slot(@NotNull TypeBinding binding, @Nullable LumenType expectedType) {
    }
}
