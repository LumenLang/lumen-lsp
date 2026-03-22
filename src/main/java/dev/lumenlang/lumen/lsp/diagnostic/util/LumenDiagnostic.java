package dev.lumenlang.lumen.lsp.diagnostic.util;

import org.jetbrains.annotations.NotNull;

/**
 * Represents a single diagnostic message produced during script analysis.
 *
 * @param line        the 1-based line number where the diagnostic applies
 * @param startColumn the start column offset (relative to the line content, excluding indentation)
 * @param endColumn   the end column offset
 * @param message     the human readable diagnostic message
 * @param severity    the severity level
 */
public record LumenDiagnostic(
        int line,
        int startColumn,
        int endColumn,
        @NotNull String message,
        @NotNull LumenSeverity severity
) {
}
