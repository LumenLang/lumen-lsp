package dev.lumenlang.lumen.lsp.analysis.util;

import dev.lumenlang.lumen.api.codegen.JavaOutput;
import org.jetbrains.annotations.NotNull;

/**
 * A {@link JavaOutput} that drops every line written to it. Used when running
 * Lumen handlers solely for the side effects they have on the type
 * environment, since the LSP never needs the generated Java source.
 */
public final class NoopJavaOutput implements JavaOutput {

    /**
     * The shared singleton instance.
     */
    public static final @NotNull NoopJavaOutput INSTANCE = new NoopJavaOutput();

    private NoopJavaOutput() {
    }

    @Override
    public void line(@NotNull String code) {
    }

    @Override
    public int lineNum() {
        return 0;
    }

    @Override
    public void insertLine(int index, @NotNull String code) {
    }
}
