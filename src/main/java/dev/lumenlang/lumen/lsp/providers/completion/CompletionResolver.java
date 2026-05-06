package dev.lumenlang.lumen.lsp.providers.completion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.lumenlang.lumen.api.event.AdvancedEventDefinition;
import dev.lumenlang.lumen.api.event.EventDefinition;
import dev.lumenlang.lumen.api.pattern.PatternMeta;
import dev.lumenlang.lumen.lsp.bootstrap.LumenBootstrap;
import dev.lumenlang.lumen.pipeline.events.EventDefRegistry;
import dev.lumenlang.lumen.pipeline.language.pattern.PatternRegistry;
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredBlock;
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredExpression;
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredPattern;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Lazy enrichment of {@link CompletionItem} entries. The completion provider
 * stamps each item with a small {@code data} routing payload, the editor
 * echoes that back on selection via {@code completionItem/resolve}, and this
 * class fills in the {@code documentation} field by reading the relevant
 * registry.
 */
public final class CompletionResolver {

    private CompletionResolver() {
    }

    /**
     * Resolves the given completion item by populating its
     * {@link CompletionItem#getDocumentation() documentation} field with
     * markdown rendered from the matching registry entry. Items without a
     * recognised data payload are returned unchanged.
     *
     * @param bootstrap the populated bootstrap whose registries supply the metadata
     * @param item      the completion item to resolve
     * @return the same item, possibly with documentation attached
     */
    public static @NotNull CompletionItem resolve(@NotNull LumenBootstrap bootstrap, @NotNull CompletionItem item) {
        Routing routing = readRouting(item.getData());
        if (routing == null) return item;
        String markdown = render(bootstrap, routing);
        if (markdown == null) return item;
        MarkupContent content = new MarkupContent();
        content.setKind(MarkupKind.MARKDOWN);
        content.setValue(markdown);
        item.setDocumentation(content);
        return item;
    }

    private static @Nullable String render(@NotNull LumenBootstrap bootstrap, @NotNull Routing routing) {
        return switch (routing.category) {
            case "event" -> renderEvent(routing.key);
            case "statement" -> renderStatement(bootstrap.patterns(), routing.key);
            case "block" -> renderBlock(bootstrap.patterns(), routing.key);
            case "expression" -> renderExpression(bootstrap.patterns(), routing.key);
            default -> null;
        };
    }

    private static @Nullable String renderEvent(@NotNull String name) {
        EventDefinition def = EventDefRegistry.apiDefinitions().get(name);
        if (def != null) return renderEventDefinition(def);
        AdvancedEventDefinition adv = EventDefRegistry.advancedDefs().get(name);
        if (adv != null) return renderAdvancedEvent(adv);
        return null;
    }

    private static @NotNull String renderEventDefinition(@NotNull EventDefinition def) {
        StringBuilder sb = new StringBuilder();
        sb.append("**event** `").append(def.name()).append("`");
        if (def.deprecated()) sb.append(" · deprecated");
        if (def.cancellable()) sb.append(" · cancellable");
        if (def.category() != null) sb.append(" · ").append(def.category());
        sb.append("\n\n");
        if (def.description() != null) sb.append(def.description()).append("\n\n");
        if (!def.vars().isEmpty()) {
            sb.append("**Variables**\n\n");
            for (Map.Entry<String, EventDefinition.VarEntry> e : def.vars().entrySet()) {
                EventDefinition.VarEntry v = e.getValue();
                sb.append("- `").append(e.getKey()).append("`: `").append(v.type().displayName()).append("`");
                if (v.description() != null) sb.append(" — ").append(v.description());
                sb.append('\n');
            }
            sb.append('\n');
        }
        appendExamples(sb, def.examples());
        appendSinceBy(sb, def.since(), def.by());
        return sb.toString();
    }

    private static @NotNull String renderAdvancedEvent(@NotNull AdvancedEventDefinition def) {
        StringBuilder sb = new StringBuilder();
        sb.append("**event** `").append(def.name()).append("`");
        if (def.deprecated()) sb.append(" · deprecated");
        if (def.cancellable()) sb.append(" · cancellable");
        if (def.category() != null) sb.append(" · ").append(def.category());
        sb.append("\n\n");
        if (def.description() != null) sb.append(def.description()).append("\n\n");
        if (!def.vars().isEmpty()) {
            sb.append("**Variables**\n\n");
            for (Map.Entry<String, EventDefinition.VarEntry> e : def.vars().entrySet()) {
                EventDefinition.VarEntry v = e.getValue();
                sb.append("- `").append(e.getKey()).append("`: `").append(v.type().displayName()).append("`");
                if (v.description() != null) sb.append(" — ").append(v.description());
                sb.append('\n');
            }
            sb.append('\n');
        }
        appendExamples(sb, def.examples());
        appendSinceBy(sb, def.since(), def.by());
        return sb.toString();
    }

    private static @Nullable String renderStatement(@NotNull PatternRegistry registry, @NotNull String raw) {
        for (RegisteredPattern rp : registry.getStatements()) {
            if (rp.pattern().raw().equals(raw)) return renderPatternMeta("statement", raw, rp.meta());
        }
        return null;
    }

    private static @Nullable String renderBlock(@NotNull PatternRegistry registry, @NotNull String raw) {
        for (RegisteredBlock rb : registry.getBlocks()) {
            if (rb.pattern().raw().equals(raw)) return renderPatternMeta("block", raw, rb.meta());
        }
        return null;
    }

    private static @Nullable String renderExpression(@NotNull PatternRegistry registry, @NotNull String raw) {
        for (RegisteredExpression re : registry.getExpressions()) {
            if (re.pattern().raw().equals(raw)) return renderPatternMeta("expression", raw, re.meta());
        }
        return null;
    }

    private static @NotNull String renderPatternMeta(@NotNull String kind, @NotNull String raw, @NotNull PatternMeta meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(kind).append("**");
        if (meta.deprecated()) sb.append(" · deprecated");
        if (meta.category() != null) sb.append(" · ").append(meta.category().name().toLowerCase());
        sb.append("\n\n```lumen\n").append(raw).append("\n```\n\n");
        if (meta.description() != null) sb.append(meta.description()).append("\n\n");
        appendExamples(sb, meta.examples());
        appendSinceBy(sb, meta.since(), meta.by());
        return sb.toString();
    }

    private static void appendExamples(@NotNull StringBuilder sb, @NotNull List<String> examples) {
        if (examples.isEmpty()) return;
        sb.append("**Examples**\n\n");
        for (String ex : examples) {
            sb.append("```lumen\n").append(ex).append("\n```\n\n");
        }
    }

    private static void appendSinceBy(@NotNull StringBuilder sb, @Nullable String since, @Nullable String by) {
        if (since == null && by == null) return;
        sb.append("\n_");
        boolean any = false;
        if (since != null) {
            sb.append("since ").append(since);
            any = true;
        }
        if (by != null) {
            if (any) sb.append(" · ");
            sb.append("by ").append(by);
        }
        sb.append("_");
    }

    private static @Nullable Routing readRouting(@Nullable Object data) {
        if (data instanceof JsonObject json) {
            JsonElement c = json.get("category");
            JsonElement k = json.get("key");
            if (c == null || k == null) return null;
            return new Routing(c.getAsString(), k.getAsString());
        }
        if (data instanceof Map<?, ?> map) {
            Object c = map.get("category");
            Object k = map.get("key");
            if (c == null || k == null) return null;
            return new Routing(c.toString(), k.toString());
        }
        return null;
    }

    private record Routing(@NotNull String category, @NotNull String key) {
    }
}
