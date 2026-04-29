package dev.lumenlang.lumen.lsp.analysis;

import dev.lumenlang.lumen.api.diagnostic.LumenDiagnostic;
import dev.lumenlang.lumen.pipeline.codegen.TypeEnv;
import dev.lumenlang.lumen.pipeline.language.tokenization.Token;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The analysis output for a single source line. Carries the source location,
 * tokens, the diagnostic produced when matching failed, and deep copies of
 * the environment before and after this line ran so the incremental analyser
 * can resume from any line. Built in analysers and addons may stash arbitrary
 * data on the open metadata map.
 *
 * <p>Token columns are relative to the source line text after leading
 * whitespace was stripped by the tokeniser. {@link #indent} carries that
 * stripped width so providers can translate token columns back to the LSP
 * absolute column space when needed.
 *
 * @param lineNumber the 1-based line number this analysis applies to
 * @param indent     the count of leading whitespace characters that the tokeniser stripped from the original line
 * @param tokens     the tokens that were matched against patterns
 * @param diagnostic the diagnostic from this line, or {@code null} when the line ran without producing one
 * @param beforeEnv  a deep copy of the environment before this line ran, used as the resume point during incremental reparse
 * @param afterEnv   a deep copy of the environment after this line ran, or {@code null} when the line did not produce a final env (e.g. failed before mutation)
 * @param metadata   open metadata map keyed by namespaced string. Built ins use keys under {@code lumen.*}. Addons should use their own prefix
 */
public record LineAnalysis(int lineNumber, int indent, @NotNull List<Token> tokens, @Nullable LumenDiagnostic diagnostic, @NotNull TypeEnv beforeEnv, @Nullable TypeEnv afterEnv, @NotNull Map<String, Object> metadata) {

    /**
     * Returns the metadata value stored under the given key, cast to the
     * requested type when present and assignable.
     *
     * @param key  the namespaced metadata key
     * @param type the type to cast the value to
     * @param <T>  the requested value type
     * @return the value, or {@code null} when absent or of the wrong type
     */
    public <T> @Nullable T meta(@NotNull String key, @NotNull Class<T> type) {
        Object value = metadata.get(key);
        if (type.isInstance(value)) return type.cast(value);
        return null;
    }
}
