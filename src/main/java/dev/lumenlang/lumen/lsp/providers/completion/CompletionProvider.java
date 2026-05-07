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
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredPattern;
import dev.lumenlang.lumen.pipeline.typebinding.TypeRegistry;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the completion list for one cursor position.
 */
public final class CompletionProvider {

    private CompletionProvider() {
    }

    /**
     * Builds the completion list for the given cursor position. The returned
     * list is always {@code isIncomplete}, which makes the editor re-query on
     * every keystroke so the server stays the source of truth for what is
     * relevant.
     */
    public static @NotNull CompletionList complete(@NotNull LumenBootstrap bootstrap, @NotNull AnalysisResult result, @NotNull String source, @NotNull Position position) {
        String[] lines = source.split("\\r?\\n", -1);
        int row = position.getLine();
        int col = position.getCharacter();
        if (row >= lines.length) return new CompletionList(true, List.of());
        String rawLine = lines[row];
        boolean topLevel = leadingIndent(rawLine, col) == 0;
        int safe = Math.min(col, rawLine.length());
        String prefix = rawLine.substring(0, safe).stripLeading();
        TypeEnv env = entryEnv(result, position.getLine() + 1);
        List<CompletionItem> out = new ArrayList<>();
        PatternRegistry registry = bootstrap.patterns();
        TypeRegistry types = bootstrap.types();
        List<String> typedWords = splitWords(prefix);
        boolean cursorOnNewWord = endsInWhitespace(prefix);

        Set<String> seenInsert = new HashSet<>();
        Slot slot = detectSlot(typedWords, cursorOnNewWord, env, registry, types);
        if (slot != null) {
            emitBindingSuggestions(out, seenInsert, slot, env, prefix);
            if (!out.isEmpty()) return new CompletionList(true, out);
        }

        if (!topLevel) {
            for (RegisteredPattern rp : registry.getStatements()) {
                if (matchesPrefix(rp.pattern(), typedWords, cursorOnNewWord)) {
                    addPattern(out, seenInsert, rp.pattern(), CompletionItemKind.Function, "statement", prefix);
                }
            }
        }
        for (RegisteredBlock rb : registry.getBlocks()) {
            if (topLevel ? !rb.supportsRootLevel() : !rb.supportsBlock()) continue;
            if (matchesPrefix(rb.pattern(), typedWords, cursorOnNewWord)) {
                addPattern(out, seenInsert, rb.pattern(), CompletionItemKind.Class, "block", prefix);
            }
        }
        if (!topLevel && env instanceof TypeEnvImpl impl) {
            String partial = trailingIdent(prefix);
            String partialLower = partial.toLowerCase();
            for (String name : impl.allVisibleVarNames()) {
                if (!partial.isEmpty() && !name.toLowerCase().startsWith(partialLower)) continue;
                if (seenInsert.add(name)) addVariable(out, name, impl.lookupVar(name), prefix);
            }
        }
        return new CompletionList(true, out);
    }

    /**
     * Returns the placeholder slot the cursor sits in, or {@code null} when
     * the typed prefix does not line up with any pattern's literal sequence.
     * The first match wins; this keeps the cost low and lets the binding own
     * what to suggest from there.
     */
    private static @Nullable Slot detectSlot(@NotNull List<String> words, boolean cursorOnNewWord, @Nullable TypeEnv env, @NotNull PatternRegistry registry, @NotNull TypeRegistry types) {
        if (words.isEmpty()) return null;
        int target = cursorOnNewWord ? words.size() : words.size() - 1;
        if (target < 1) return null;
        for (RegisteredPattern rp : registry.getStatements()) {
            Slot s = walkSlot(rp.pattern().parts(), words, 0, target, env, types);
            if (s != null) return s;
        }
        for (RegisteredBlock rb : registry.getBlocks()) {
            Slot s = walkSlot(rb.pattern().parts(), words, 0, target, env, types);
            if (s != null) return s;
        }
        return null;
    }

    /**
     * Walks the pattern parts against the typed words. Literals must match
     * the corresponding word, placeholders consume one word, and groups try
     * their alternatives in order. Returns a {@link Slot} as soon as the
     * cursor sits at a placeholder boundary, otherwise {@code null}.
     */
    private static @Nullable Slot walkSlot(@NotNull List<PatternPart> parts, @NotNull List<String> words, int idx, int target, @Nullable TypeEnv env, @NotNull TypeRegistry types) {
        for (PatternPart part : parts) {
            if (idx >= target) {
                if (part instanceof PatternPart.PlaceholderPart ph) return slotFor(ph.ph(), words, idx, env, types);
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
                Integer end = consumeGroup(group, words, idx, target);
                if (end == null && group.required()) return null;
                if (end != null) idx = end;
            }
        }
        return null;
    }

    /**
     * Returns the cursor index after walking through the group's first
     * matching alternative, or {@code null} when none of the alternatives
     * could accept the typed words at the current position.
     */
    private static @Nullable Integer consumeGroup(@NotNull PatternPart.Group group, @NotNull List<String> words, int idx, int target) {
        for (List<PatternPart> alt : group.alternatives()) {
            Integer end = consumeAlt(alt, words, idx, target);
            if (end != null) return end;
        }
        return null;
    }

    /**
     * Returns the cursor index after walking the given alternative's parts,
     * or {@code null} when the alternative cannot accept the typed words.
     */
    private static @Nullable Integer consumeAlt(@NotNull List<PatternPart> parts, @NotNull List<String> words, int idx, int target) {
        for (PatternPart part : parts) {
            if (idx >= target) return idx;
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
                Integer end = consumeGroup(group, words, idx, target);
                if (end == null && group.required()) return null;
                if (end != null) idx = end;
            }
        }
        return idx;
    }

    /**
     * Returns whether the typed words could be a prefix of any concrete form
     * of the pattern. The trailing partial word is matched as a prefix when
     * the cursor sits inside it, otherwise the cursor is on a fresh slot.
     */
    private static boolean matchesPrefix(@NotNull Pattern pattern, @NotNull List<String> typed, boolean cursorOnNewWord) {
        if (typed.isEmpty()) return true;
        int complete = cursorOnNewWord ? typed.size() : typed.size() - 1;
        String partial = cursorOnNewWord ? "" : typed.get(typed.size() - 1).toLowerCase();
        for (List<PatternPart> form : expandForms(pattern.parts())) {
            if (formAccepts(form, typed, complete, partial)) return true;
        }
        return false;
    }

    private static boolean formAccepts(@NotNull List<PatternPart> form, @NotNull List<String> typed, int complete, @NotNull String partial) {
        int idx = 0;
        for (PatternPart part : form) {
            if (idx < complete) {
                if (!partAcceptsCommitted(part, typed.get(idx))) return false;
                idx++;
            } else if (idx == complete && !partial.isEmpty()) {
                return partAcceptsPartial(part, partial);
            } else {
                return true;
            }
        }
        return idx >= complete && partial.isEmpty();
    }

    private static boolean partAcceptsCommitted(@NotNull PatternPart part, @NotNull String word) {
        if (part instanceof PatternPart.Literal lit) return lit.text().equalsIgnoreCase(word);
        if (part instanceof PatternPart.FlexLiteral flex) return flex.forms().contains(word.toLowerCase());
        return part instanceof PatternPart.PlaceholderPart;
    }

    private static boolean partAcceptsPartial(@NotNull PatternPart part, @NotNull String partial) {
        if (part instanceof PatternPart.Literal lit) return lit.text().toLowerCase().startsWith(partial);
        if (part instanceof PatternPart.FlexLiteral flex) {
            for (String form : flex.forms()) if (form.toLowerCase().startsWith(partial)) return true;
            return false;
        }
        return true;
    }

    /**
     * Emits suggestions contributed by the binding under the cursor.
     * Suggestions whose text reads like a raw pattern are recompiled and
     * expanded so required-group alternatives split into separate entries.
     */
    private static void emitBindingSuggestions(@NotNull List<CompletionItem> out, @NotNull Set<String> seenInsert, @NotNull Slot slot, @Nullable TypeEnv env, @NotNull String filter) {
        TypeEnvImpl envImpl = env instanceof TypeEnvImpl i ? i : new TypeEnvImpl();
        for (Suggestion s : slot.binding.suggestions(envImpl, slot.expectedType)) {
            if (looksLikePattern(s.insertText())) {
                Pattern compiled = tryCompile(s.insertText());
                if (compiled != null) {
                    addPattern(out, seenInsert, compiled, kindOf(s.kind()), s.detail(), filter);
                    continue;
                }
            }
            if (!seenInsert.add(s.insertText())) continue;
            CompletionItem item = new CompletionItem(s.insertText());
            item.setInsertText(s.insertText());
            item.setDetail(s.detail());
            item.setKind(kindOf(s.kind()));
            item.setFilterText(filter);
            item.setSortText(sortPrefix(s.detail()) + s.insertText().toLowerCase());
            item.setData(resolveKey(s.detail(), s.insertText()));
            out.add(item);
        }
    }

    private static boolean looksLikePattern(@NotNull String text) {
        return text.indexOf('%') >= 0 || text.indexOf('(') >= 0 || text.indexOf('[') >= 0;
    }

    private static @Nullable Pattern tryCompile(@NotNull String raw) {
        try {
            return PatternCompiler.compile(raw);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Builds the slot for the placeholder at the cursor, looking up its
     * binding by id. The expected type is derived only when the surrounding
     * shape is a known assignment form.
     */
    private static @Nullable Slot slotFor(@NotNull Placeholder ph, @NotNull List<String> words, int placeholderIdx, @Nullable TypeEnv env, @NotNull TypeRegistry types) {
        TypeBinding binding = types.get(ph.typeId());
        if (binding == null) return null;
        return new Slot(binding, expectedTypeForSet(ph, words, placeholderIdx, env));
    }

    /**
     * Returns the declared type of the variable named on the left-hand side
     * of {@code set <name> to <val>} so the binding can narrow its
     * suggestions, or {@code null} when the shape does not match.
     */
    private static @Nullable LumenType expectedTypeForSet(@NotNull Placeholder ph, @NotNull List<String> words, int placeholderIdx, @Nullable TypeEnv env) {
        if (!"val".equals(ph.name())) return null;
        if (!(env instanceof TypeEnvImpl impl)) return null;
        if (placeholderIdx < 3 || words.size() < 3) return null;
        if (!words.get(0).equalsIgnoreCase("set")) return null;
        if (!words.get(2).equalsIgnoreCase("to")) return null;
        VarHandle handle = impl.lookupVar(words.get(1));
        return handle != null ? handle.type() : null;
    }

    /**
     * Adds one completion item per concrete form of the pattern. Required
     * groups split into separate entries; optional groups are dropped so the
     * label only ever shows what the user actually has to type.
     */
    private static void addPattern(@NotNull List<CompletionItem> out, @NotNull Set<String> seenInsert, @NotNull Pattern pattern, @NotNull CompletionItemKind kind, @NotNull String detail, @NotNull String filter) {
        for (List<PatternPart> form : expandForms(pattern.parts())) {
            int[] tab = {1};
            StringBuilder snippet = new StringBuilder();
            StringBuilder label = new StringBuilder();
            renderForm(snippet, label, form, tab);
            snippet.append("$0");
            String insert = snippet.toString();
            if (!seenInsert.add(insert)) continue;
            String labelText = label.toString();
            CompletionItem item = new CompletionItem(labelText);
            item.setKind(kind);
            item.setDetail(detail);
            item.setInsertText(insert);
            item.setInsertTextFormat(InsertTextFormat.Snippet);
            item.setFilterText(filter);
            item.setSortText(sortPrefix(detail) + labelText.toLowerCase());
            item.setData(resolveKey(detail, pattern.raw()));
            out.add(item);
        }
    }

    /**
     * Returns every concrete realisation of the pattern: required groups fan
     * out into their alternatives, optional groups are dropped, and flex
     * literals collapse to their first form.
     */
    private static @NotNull List<List<PatternPart>> expandForms(@NotNull List<PatternPart> parts) {
        List<List<PatternPart>> forms = new ArrayList<>();
        forms.add(new ArrayList<>());
        for (PatternPart part : parts) {
            if (part instanceof PatternPart.Group group) {
                if (!group.required() || group.alternatives().isEmpty()) continue;
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
                for (List<PatternPart> base : forms) base.add(part);
            }
        }
        return forms;
    }

    /**
     * Writes one form into both the snippet payload and the visible label.
     * Each placeholder becomes a numbered tabstop in the snippet and a
     * lowercased {@code <typeid>} in the label.
     */
    private static void renderForm(@NotNull StringBuilder snippet, @NotNull StringBuilder label, @NotNull List<PatternPart> parts, int[] tab) {
        boolean first = true;
        for (PatternPart part : parts) {
            String labelText;
            String snippetText;
            if (part instanceof PatternPart.Literal lit) {
                labelText = lit.text();
                snippetText = escapeSnippet(lit.text());
            } else if (part instanceof PatternPart.FlexLiteral flex) {
                labelText = flex.forms().isEmpty() ? "" : flex.forms().get(0);
                snippetText = escapeSnippet(labelText);
            } else if (part instanceof PatternPart.PlaceholderPart ph) {
                Placeholder p = ph.ph();
                labelText = "<" + p.typeId().toLowerCase() + ">";
                snippetText = "${" + (tab[0]++) + ":" + escapeSnippet(p.name()) + "}";
            } else {
                continue;
            }
            if (!first) {
                snippet.append(' ');
                label.append(' ');
            }
            snippet.append(snippetText);
            label.append(labelText);
            first = false;
        }
    }

    private static @NotNull String escapeSnippet(@NotNull String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '$' || c == '}' || c == '\\') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    private static void addVariable(@NotNull List<CompletionItem> out, @NotNull String name, @Nullable VarHandle ref, @NotNull String filter) {
        CompletionItem item = new CompletionItem(name);
        item.setKind(CompletionItemKind.Variable);
        if (ref != null) item.setDetail(ref.type().displayName());
        item.setFilterText(filter);
        item.setSortText(sortPrefix("variable") + name.toLowerCase());
        item.setData(resolveKey("variable", name));
        out.add(item);
    }

    /**
     * Returns the env at the cursor line, taken from the latest analysed line
     * above the cursor. Used so binding suggestions and variable completion
     * see the in-scope variables for the current block.
     */
    private static @Nullable TypeEnv entryEnv(@NotNull AnalysisResult result, int lineNumber) {
        for (int i = lineNumber - 1; i >= 1; i--) {
            LineAnalysis prev = result.line(i);
            if (prev != null && prev.afterEnv() != null) return prev.afterEnv();
        }
        return null;
    }

    /**
     * Returns a sort prefix that ranks pattern entries above bare variables
     * inside the popup so the user sees actionable forms first.
     */
    private static @NotNull String sortPrefix(@NotNull String category) {
        return switch (category) {
            case "statement", "block" -> "1_";
            case "expression", "condition" -> "2_";
            case "event" -> "3_";
            case "variable" -> "5_";
            default -> "4_";
        };
    }

    private static @NotNull Map<String, String> resolveKey(@NotNull String category, @NotNull String key) {
        Map<String, String> data = new HashMap<>();
        data.put("category", category);
        data.put("key", key);
        return data;
    }

    private static @NotNull CompletionItemKind kindOf(@NotNull SemanticKind kind) {
        return switch (kind) {
            case VARIABLE, PASSTHROUGH, PARAMETER -> CompletionItemKind.Variable;
            case PROPERTY -> CompletionItemKind.Property;
            case FUNCTION -> CompletionItemKind.Function;
            case TYPE -> CompletionItemKind.Class;
            case EVENT -> CompletionItemKind.Event;
            case NUMBER, STRING -> CompletionItemKind.Value;
            case NAMESPACE -> CompletionItemKind.Module;
            case KEYWORD -> CompletionItemKind.Keyword;
        };
    }

    private static int leadingIndent(@NotNull String line, int col) {
        int safe = Math.min(col, line.length());
        int i = 0;
        while (i < safe && Character.isWhitespace(line.charAt(i))) i++;
        return i;
    }

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

    private static boolean endsInWhitespace(@NotNull String prefix) {
        return !prefix.isEmpty() && Character.isWhitespace(prefix.charAt(prefix.length() - 1));
    }

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
     * Pairs the binding under the cursor with any narrowing type the
     * surrounding statement declared, so the binding can filter what it
     * returns.
     */
    private record Slot(@NotNull TypeBinding binding, @Nullable LumenType expectedType) {
    }
}
