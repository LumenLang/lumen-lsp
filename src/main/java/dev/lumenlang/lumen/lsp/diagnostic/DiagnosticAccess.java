package dev.lumenlang.lumen.lsp.diagnostic;

import dev.lumenlang.lumen.api.diagnostic.LumenDiagnostic;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Reflection based reader for the private positioning fields on
 * {@link LumenDiagnostic}, used so the LSP can render exact column ranges
 * and sub highlight labels rather than only the title and notes the public
 * surface exposes.
 *
 * <p>This class must be replaced by accessor methods on
 * {@code LumenDiagnostic} once available upstream.
 */
public final class DiagnosticAccess {

    private static final @NotNull Field COLUMN_START = field("columnStart");
    private static final @NotNull Field COLUMN_END = field("columnEnd");
    private static final @NotNull Field UNDERLINE_LABEL = field("underlineLabel");
    private static final @NotNull Field SUB_HIGHLIGHTS = field("subHighlights");

    private DiagnosticAccess() {
    }

    /**
     * Returns the 0-based start column of the primary highlight.
     *
     * @param d the diagnostic
     * @return the start column
     */
    public static int columnStart(@NotNull LumenDiagnostic d) {
        return readInt(COLUMN_START, d);
    }

    /**
     * Returns the 0-based end column of the primary highlight, exclusive.
     *
     * @param d the diagnostic
     * @return the end column
     */
    public static int columnEnd(@NotNull LumenDiagnostic d) {
        return readInt(COLUMN_END, d);
    }

    /**
     * Returns the optional label attached to the primary highlight, or
     * {@code null} when none was set.
     *
     * @param d the diagnostic
     * @return the label, or {@code null}
     */
    public static @Nullable String underlineLabel(@NotNull LumenDiagnostic d) {
        return (String) read(UNDERLINE_LABEL, d);
    }

    /**
     * Returns the sub highlight list attached to the diagnostic.
     *
     * @param d the diagnostic
     * @return the sub highlights, possibly empty
     */
    @SuppressWarnings("unchecked")
    public static @NotNull List<LumenDiagnostic.SubHighlight> subHighlights(@NotNull LumenDiagnostic d) {
        Object value = read(SUB_HIGHLIGHTS, d);
        if (value == null) return List.of();
        return (List<LumenDiagnostic.SubHighlight>) value;
    }

    /**
     * Reads the named field from the diagnostic class via reflection,
     * propagating any access failure as a runtime error since the pipeline
     * version is part of the build contract.
     *
     * @param field the field to read
     * @param d     the diagnostic instance
     * @return the field value
     */
    private static @Nullable Object read(@NotNull Field field, @NotNull LumenDiagnostic d) {
        try {
            return field.get(d);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to read " + field.getName() + " on LumenDiagnostic", e);
        }
    }

    /**
     * Reads an int field from the diagnostic, treating a null result as zero.
     *
     * @param field the field to read
     * @param d     the diagnostic instance
     * @return the int value
     */
    private static int readInt(@NotNull Field field, @NotNull LumenDiagnostic d) {
        Object value = read(field, d);
        return value == null ? 0 : (int) value;
    }

    /**
     * Resolves a private field on {@link LumenDiagnostic} for repeated use,
     * making it accessible at class load time.
     *
     * @param name the field name
     * @return the field
     */
    private static @NotNull Field field(@NotNull String name) {
        try {
            Field f = LumenDiagnostic.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("LumenDiagnostic has no field '" + name + "'", e);
        }
    }
}
