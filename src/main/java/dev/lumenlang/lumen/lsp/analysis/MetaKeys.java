package dev.lumenlang.lumen.lsp.analysis;

import dev.lumenlang.lumen.api.emit.BlockFormHandler;
import org.jetbrains.annotations.NotNull;

/**
 * Keys used by the analyser to stash extra per line data on a {@link LineAnalysis}.
 */
public final class MetaKeys {

    /**
     * The match produced when a statement line matched.
     */
    public static final @NotNull String STATEMENT_MATCH = "statementMatch";

    /**
     * The match produced when a block header line matched.
     */
    public static final @NotNull String BLOCK_MATCH = "blockMatch";

    /**
     * The 0-based column range of the variable name introduced by a set style
     * declaration, stored as {@code int[] {start, end}}.
     */
    public static final @NotNull String VAR_DECL_RANGE = "varDeclRange";

    /**
     * The variable name introduced on this line.
     */
    public static final @NotNull String VAR_DECL_NAME = "varDeclName";

    /**
     * The leading head literal of a block handled by a {@link BlockFormHandler}.
     */
    public static final @NotNull String BLOCK_FORM_NAME = "blockFormName";

    /**
     * Pattern simulator suggestions recorded for a line that failed to match,
     * stored as {@code List<PatternSimulator.Suggestion>}.
     */
    public static final @NotNull String SUGGESTIONS = "suggestions";

    private MetaKeys() {
    }
}
