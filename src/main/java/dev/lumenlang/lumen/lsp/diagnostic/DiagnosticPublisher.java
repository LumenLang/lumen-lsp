package dev.lumenlang.lumen.lsp.diagnostic;

import dev.lumenlang.lumen.api.diagnostic.LumenDiagnostic;
import dev.lumenlang.lumen.lsp.analysis.AnalysisResult;
import dev.lumenlang.lumen.lsp.analysis.LineAnalysis;
import dev.lumenlang.lumen.lsp.analysis.MetaKeys;
import dev.lumenlang.lumen.pipeline.language.resolve.PatternSimulator;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Pushes analyser diagnostics to the LSP client, attaching the closest
 * pattern simulator suggestion as the diagnostic data payload so editor
 * extensions can render the suggested shape inline without issuing another
 * round trip.
 */
public final class DiagnosticPublisher {

    private DiagnosticPublisher() {
    }

    /**
     * Publishes the diagnostics for the given analysis. An empty list is sent
     * when no diagnostics were produced so the client clears any prior ones.
     *
     * @param client the LSP client
     * @param result the analysis to publish
     */
    public static void publish(@NotNull LanguageClient client, @NotNull AnalysisResult result) {
        List<Diagnostic> mapped = new ArrayList<>();
        for (LumenDiagnostic d : result.diagnostics()) {
            LineAnalysis line = result.line(d.line());
            int indent = line != null ? line.indent() : 0;
            String suggestion = topSuggestion(line);
            mapped.addAll(DiagnosticMapper.map(d, indent, suggestion));
        }
        client.publishDiagnostics(new PublishDiagnosticsParams(result.uri(), mapped));
    }

    /**
     * Returns the raw pattern of the highest confidence simulator suggestion
     * recorded on the line, or {@code null} when none was stored.
     *
     * @param line the line analysis, possibly null
     * @return the suggestion pattern raw, or {@code null}
     */
    private static @Nullable String topSuggestion(@Nullable LineAnalysis line) {
        if (line == null) return null;
        @SuppressWarnings("unchecked")
        List<PatternSimulator.Suggestion> suggestions = (List<PatternSimulator.Suggestion>) line.metadata().get(MetaKeys.SUGGESTIONS);
        if (suggestions == null || suggestions.isEmpty()) return null;
        return suggestions.get(0).pattern().raw();
    }
}
