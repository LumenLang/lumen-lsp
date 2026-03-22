package dev.lumenlang.lumen.lsp.documentation;

import dev.lumenlang.lumen.lsp.entries.documentation.BlockEntry;
import dev.lumenlang.lumen.lsp.entries.documentation.EventEntry;
import dev.lumenlang.lumen.lsp.entries.documentation.PatternEntry;
import dev.lumenlang.lumen.lsp.entries.documentation.TypeBindingEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Top level container for all documentation entries loaded from documentation.json.
 *
 * @param version      the documentation version string
 * @param statements   registered statement patterns
 * @param expressions  registered expression patterns
 * @param conditions   registered condition patterns
 * @param blocks       registered block patterns (may provide variables)
 * @param loopSources  registered loop source patterns
 * @param events       registered events
 * @param typeBindings registered type bindings
 */
public record DocumentationData(
        @Nullable String version,
        @NotNull List<PatternEntry> statements,
        @NotNull List<PatternEntry> expressions,
        @NotNull List<PatternEntry> conditions,
        @NotNull List<BlockEntry> blocks,
        @NotNull List<PatternEntry> loopSources,
        @NotNull List<EventEntry> events,
        @NotNull List<TypeBindingEntry> typeBindings
) {

    public static final DocumentationData EMPTY = new DocumentationData(
            null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
    );
}
