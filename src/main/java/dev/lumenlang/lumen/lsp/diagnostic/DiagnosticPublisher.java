package dev.lumenlang.lumen.lsp.diagnostic;

import dev.lumenlang.lumen.lsp.analysis.AnalysisResult;
import dev.lumenlang.lumen.lsp.diagnostic.util.LumenDiagnostic;
import dev.lumenlang.lumen.lsp.diagnostic.util.LumenSeverity;
import dev.lumenlang.lumen.lsp.server.LumenLanguageServer;
import dev.lumenlang.lumen.pipeline.language.tokenization.Line;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts internal diagnostics to LSP diagnostics and publishes them to the client.
 */
public final class DiagnosticPublisher {

    /**
     * Converts an internal diagnostic severity to the LSP diagnostic severity.
     *
     * @param s the internal severity
     * @return the corresponding LSP severity
     */
    private static DiagnosticSeverity severity(@NotNull LumenSeverity s) {
        return switch (s) {
            case ERROR -> DiagnosticSeverity.Error;
            case WARNING -> DiagnosticSeverity.Warning;
            case INFORMATION -> DiagnosticSeverity.Information;
            case HINT -> DiagnosticSeverity.Hint;
        };
    }

    /**
     * Publishes diagnostics for an analyzed document.
     *
     * @param server the language server
     * @param uri    the document URI
     * @param result the analysis result containing diagnostics
     */
    public void publish(@NotNull LumenLanguageServer server, @NotNull String uri, @NotNull AnalysisResult result) {
        if (server.client() == null) return;

        // map each line number to its indent so we can offset diagnostic columns
        Map<Integer, Integer> indentByLine = new HashMap<>();
        for (Line l : result.lines()) {
            indentByLine.put(l.lineNumber(), l.indent());
        }

        List<Diagnostic> lspDiags = new ArrayList<>();
        for (LumenDiagnostic d : result.diagnostics()) {
            if (server.errorsDisabled() && d.severity() == LumenSeverity.ERROR) continue;
            // convert 1-based line to 0-based, and shift columns by indent width
            int line = Math.max(0, d.line() - 1);
            int indent = indentByLine.getOrDefault(d.line(), 0);
            int startCol = d.startColumn() + indent;
            int endCol = d.endColumn() + indent;
            Range range = new Range(
                    new Position(line, startCol),
                    new Position(line, Math.max(endCol, startCol + 1))
            );

            Diagnostic lspDiag = new Diagnostic(
                    range, d.message()
            );
            lspDiag.setSeverity(severity(d.severity()));
            lspDiag.setSource("lumen");
            lspDiags.add(lspDiag);
        }

        server.client().publishDiagnostics(new PublishDiagnosticsParams(uri, lspDiags));
    }

    /**
     * Clears diagnostics for a document.
     *
     * @param server the language server
     * @param uri    the document URI
     */
    public void clear(@NotNull LumenLanguageServer server, @NotNull String uri) {
        if (server.client() == null) return;
        server.client().publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
    }
}
