package dev.lumenlang.lumen.lsp.analysis;

import dev.lumenlang.lumen.api.diagnostic.LumenDiagnostic;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The full output of analysing a single document. Carries every diagnostic in
 * source order, the per line analysis snapshots, and the original document
 * text so providers that need to scan beyond what the tokeniser kept (such as
 * comments) have access without re-reading the file.
 *
 * @param uri         the document URI that was analysed
 * @param source      the raw document text
 * @param diagnostics every diagnostic, in source order
 * @param lines       per line analysis, in source order, indexed by 1-based line number using {@link #line(int)}
 */
public record AnalysisResult(@NotNull String uri, @NotNull String source, @NotNull List<LumenDiagnostic> diagnostics, @NotNull List<LineAnalysis> lines) {

    /**
     * Returns the line analysis for the given 1-based line number, or
     * {@code null} when no analysis exists for that line.
     *
     * @param lineNumber the 1-based line number
     * @return the analysis, or {@code null}
     */
    public @Nullable LineAnalysis line(int lineNumber) {
        for (LineAnalysis line : lines) {
            if (line.lineNumber() == lineNumber) return line;
        }
        return null;
    }
}
