package dev.lumenlang.lumen.lsp.analysis.util;

import dev.lumenlang.lumen.api.emit.ScriptLine;
import dev.lumenlang.lumen.api.emit.ScriptToken;
import dev.lumenlang.lumen.pipeline.language.nodes.Node;
import dev.lumenlang.lumen.pipeline.language.nodes.StatementNode;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/**
 * Adapts a pipeline node to the public ScriptLine surface so block form
 * handlers see the same shape they do during normal compilation.
 */
public final class SimpleScriptLine implements ScriptLine {

    private final int lineNumber;
    private final @NotNull String raw;
    private final @NotNull List<? extends ScriptToken> tokens;

    /**
     * Wraps the given node, exposing its head tokens for statement nodes and
     * an empty token list otherwise.
     *
     * @param node the source node
     */
    public SimpleScriptLine(@NotNull Node node) {
        this.lineNumber = node.line();
        this.raw = node.raw();
        this.tokens = node instanceof StatementNode sn ? Collections.unmodifiableList(sn.head()) : Collections.emptyList();
    }

    @Override
    public int lineNumber() {
        return lineNumber;
    }

    @Override
    public @NotNull String raw() {
        return raw;
    }

    @Override
    public @NotNull List<? extends ScriptToken> tokens() {
        return tokens;
    }
}
