package dev.lumenlang.lumen.lsp.providers.inlay;

import dev.lumenlang.lumen.lsp.analysis.AnalysisResult;
import dev.lumenlang.lumen.lsp.analysis.LineAnalysis;
import dev.lumenlang.lumen.lsp.analysis.MetaKeys;
import dev.lumenlang.lumen.api.codegen.TypeEnv;
import dev.lumenlang.lumen.api.codegen.TypeEnv.VarHandle;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.InlayHintLabelPart;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds inlay hints for a document analysis. Limited to inferred variable
 * types after declaration name tokens, since diagnostic content lives on
 * code lenses to avoid crowding the line the user is editing.
 */
public final class InlayHintProvider {

    private InlayHintProvider() {
    }

    /**
     * Returns every hint that overlaps the requested range.
     *
     * @param result the analysis of the document
     * @param range  the range the editor is currently looking at
     * @return the hints, possibly empty
     */
    public static @NotNull List<InlayHint> hints(@NotNull AnalysisResult result, @NotNull Range range) {
        List<InlayHint> out = new ArrayList<>();
        int from = range.getStart().getLine() + 1;
        int to = range.getEnd().getLine() + 1;
        for (LineAnalysis line : result.lines()) {
            if (line.lineNumber() < from || line.lineNumber() > to) continue;
            addVarDeclHint(line, out);
        }
        return out;
    }

    /**
     * Appends an inferred type hint after the name token of a variable
     * declaration when the post line environment recorded a binding for it.
     *
     * @param line the line analysis
     * @param out  the hint accumulator
     */
    private static void addVarDeclHint(@NotNull LineAnalysis line, @NotNull List<InlayHint> out) {
        String name = line.meta(MetaKeys.VAR_DECL_NAME, String.class);
        int[] range = line.meta(MetaKeys.VAR_DECL_RANGE, int[].class);
        TypeEnv env = line.afterEnv();
        if (name == null || range == null || env == null) return;
        VarHandle ref = env.lookupVar(name);
        if (ref == null) return;
        InlayHint hint = new InlayHint(positionAt(line, range[1] + line.indent()), forLeft(": " + ref.type().displayName()));
        hint.setKind(InlayHintKind.Type);
        hint.setPaddingLeft(false);
        hint.setPaddingRight(true);
        out.add(hint);
    }

    /**
     * Builds an LSP position for the given column on the analysed line.
     *
     * @param line the line analysis providing the row
     * @param col  the 0-based column
     * @return the position
     */
    private static @NotNull Position positionAt(@NotNull LineAnalysis line, int col) {
        return new Position(Math.max(0, line.lineNumber() - 1), Math.max(0, col));
    }

    /**
     * Wraps the given text into the {@code Either<String, List<...>>} form
     * required by the LSP inlay hint label field, picking the simpler string
     * branch.
     *
     * @param text the hint text
     * @return the label wrapper
     */
    private static @NotNull Either<String, List<InlayHintLabelPart>> forLeft(@NotNull String text) {
        return Either.forLeft(text);
    }
}
