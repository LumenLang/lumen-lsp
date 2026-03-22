package dev.lumenlang.lumen.lsp.entries.documentation;

import dev.lumenlang.lumen.lsp.entries.documentation.variables.EventVariable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Represents a Bukkit event entry from the documentation.
 *
 * @param name        the event name as written in scripts
 * @param by          the author or plugin source
 * @param className   the fully qualified Java class name, or null
 * @param description a human readable description, or null
 * @param examples    example code snippets
 * @param since       the version this event was introduced, or null
 * @param category    the category this event belongs to, or null
 * @param deprecated  whether this event is deprecated
 * @param advanced    whether this event is considered advanced
 * @param variables   the variables exposed to child statements
 * @param interfaces  the interfaces implemented by the event class
 * @param fields      the fields exposed by the event class
 */
public record EventEntry(
        @NotNull String name,
        @Nullable String by,
        @Nullable String className,
        @Nullable String description,
        @NotNull List<String> examples,
        @Nullable String since,
        @Nullable String category,
        boolean deprecated,
        boolean advanced,
        @NotNull List<EventVariable> variables,
        @NotNull List<String> interfaces,
        @NotNull List<String> fields
) {
}
