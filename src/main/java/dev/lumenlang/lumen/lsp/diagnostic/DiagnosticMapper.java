package dev.lumenlang.lumen.lsp.diagnostic;

import dev.lumenlang.lumen.api.diagnostic.LumenDiagnostic;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a Lumen pipeline {@link LumenDiagnostic} into one or more LSP
 * {@link Diagnostic} entries, splitting the primary highlight and every sub
 * highlight into separate squiggles so each issue gets its own precise column
 * range and label rather than a single line wide underline with prose buried
 * in the message.
 */
public final class DiagnosticMapper {

    private DiagnosticMapper() {
    }

    /**
     * Returns every LSP diagnostic produced from the given Lumen diagnostic,
     * with the primary highlight first and sub highlights afterwards in
     * source order. Column ranges are shifted by the line's indent since the
     * pipeline reports columns relative to the stripped content while LSP
     * positions are relative to the full source line.
     *
     * @param d      the Lumen diagnostic to convert
     * @param indent the count of leading whitespace characters on the line
     * @return the LSP diagnostics, never empty
     */
    public static @NotNull List<Diagnostic> map(@NotNull LumenDiagnostic d, int indent, @Nullable String suggestion) {
        List<Diagnostic> out = new ArrayList<>();
        DiagnosticSeverity severity = d.severity() == LumenDiagnostic.Severity.ERROR ? DiagnosticSeverity.Error : DiagnosticSeverity.Warning;
        int line = Math.max(0, d.line() - 1);
        out.add(primary(d, line, indent, severity, suggestion));
        for (LumenDiagnostic.SubHighlight sh : DiagnosticAccess.subHighlights(d)) {
            out.add(secondary(line, indent, severity, sh));
        }
        return out;
    }

    /**
     * Builds the diagnostic for the primary underline range, attaching the
     * title plus the optional underline label and any extracted note or help
     * lines so the tooltip carries every actionable hint at the offending
     * tokens.
     *
     * @param d        the Lumen diagnostic
     * @param line     the 0-based source line
     * @param severity the LSP severity
     * @return the primary LSP diagnostic
     */
    private static @NotNull Diagnostic primary(@NotNull LumenDiagnostic d, int line, int indent, @NotNull DiagnosticSeverity severity, @Nullable String suggestion) {
        Diagnostic result = new Diagnostic();
        result.setRange(rangeAt(line, DiagnosticAccess.columnStart(d) + indent, DiagnosticAccess.columnEnd(d) + indent));
        result.setSeverity(severity);
        result.setSource("lumen");
        result.setMessage(buildPrimaryMessage(d));
        if (suggestion != null) {
            Map<String, Object> data = new HashMap<>();
            data.put("suggestion", suggestion);
            result.setData(data);
        }
        return result;
    }

    /**
     * Builds an additional diagnostic for a sub highlight, mirroring its
     * label as the message and its column range as the LSP range so each
     * issue inside the line lights up independently.
     *
     * @param line     the 0-based source line
     * @param severity the LSP severity
     * @param sh       the sub highlight
     * @return the LSP diagnostic
     */
    private static @NotNull Diagnostic secondary(int line, int indent, @NotNull DiagnosticSeverity severity, @NotNull LumenDiagnostic.SubHighlight sh) {
        Diagnostic result = new Diagnostic();
        result.setRange(rangeAt(line, sh.columnStart() + indent, sh.columnEnd() + indent));
        result.setSeverity(severity);
        result.setSource("lumen");
        result.setMessage(sh.label() != null ? sh.label() : "issue here");
        return result;
    }

    /**
     * Returns an LSP range on the given line spanning the requested columns,
     * widened to at least one character so the squiggle is always visible.
     *
     * @param line  the 0-based line
     * @param start the 0-based start column
     * @param end   the 0-based end column, exclusive
     * @return the range
     */
    private static @NotNull Range rangeAt(int line, int start, int end) {
        int safeStart = Math.max(0, start);
        int safeEnd = Math.max(safeStart + 1, end);
        return new Range(new Position(line, safeStart), new Position(line, safeEnd));
    }

    /**
     * Builds the message body for the primary diagnostic by combining the
     * title, the optional underline label, and every note or help body
     * extracted from the upstream Rust style format, dropping confidence
     * scores and pattern listings that get their own lens.
     *
     * @param d the diagnostic
     * @return the message body
     */
    private static @NotNull String buildPrimaryMessage(@NotNull LumenDiagnostic d) {
        StringBuilder sb = new StringBuilder();
        sb.append(d.title());
        String label = DiagnosticAccess.underlineLabel(d);
        if (label != null) sb.append(", ").append(label);
        for (String reason : reasons(d)) {
            sb.append('\n').append(reason);
        }
        return sb.toString();
    }

    /**
     * Pulls every actionable note and help body from the upstream format,
     * dropping confidence scores and pattern listings that would otherwise
     * read like console output rather than guidance.
     *
     * @param d the diagnostic
     * @return the reason strings in source order, possibly empty
     */
    private static @NotNull List<String> reasons(@NotNull LumenDiagnostic d) {
        List<String> out = new ArrayList<>();
        for (String line : d.format().split("\n")) {
            String trimmed = line.stripLeading();
            String body;
            if (trimmed.startsWith("= note: ")) {
                body = trimmed.substring("= note: ".length());
            } else if (trimmed.startsWith("= help: ")) {
                body = trimmed.substring("= help: ".length());
            } else {
                continue;
            }
            if (body.startsWith("confidence:")) continue;
            if (body.startsWith("closest pattern:")) continue;
            if (body.startsWith("also consider:")) continue;
            out.add(body);
        }
        return out;
    }
}
