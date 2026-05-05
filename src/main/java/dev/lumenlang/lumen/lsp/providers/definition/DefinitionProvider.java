package dev.lumenlang.lumen.lsp.providers.definition;

import dev.lumenlang.lumen.lsp.analysis.AnalysisResult;
import dev.lumenlang.lumen.lsp.analysis.LineAnalysis;
import dev.lumenlang.lumen.lsp.providers.util.TokenAt;
import dev.lumenlang.lumen.api.codegen.TypeEnv;
import dev.lumenlang.lumen.pipeline.codegen.TypeEnvImpl;
import dev.lumenlang.lumen.pipeline.language.tokenization.Token;
import dev.lumenlang.lumen.pipeline.language.tokenization.TokenKind;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Resolves go to definition requests against a document analysis. Currently
 * supports variables, jumping to the line that first declared the name.
 */
public final class DefinitionProvider {

    private DefinitionProvider() {
    }

    /**
     * Returns the locations to navigate to for the given cursor position.
     *
     * @param uri      the document URI
     * @param result   the analysis of the document
     * @param position the cursor position
     * @return the matching locations, possibly empty
     */
    public static @NotNull List<Location> definition(@NotNull String uri, @NotNull AnalysisResult result, @NotNull Position position) {
        int lineNumber = position.getLine() + 1;
        int column = position.getCharacter();
        LineAnalysis line = result.line(lineNumber);
        if (line == null) return List.of();
        Token token = TokenAt.at(line.tokens(), line.indent(), column);
        if (token == null || token.kind() != TokenKind.IDENT) return List.of();
        TypeEnv env = line.afterEnv();
        if (!(env instanceof TypeEnvImpl impl)) return List.of();
        TypeEnvImpl.DeclarationInfo info = impl.declarationInfo(token.text());
        if (info == null) return List.of();
        int declLine = Math.max(0, info.firstLine() - 1);
        return List.of(new Location(uri, new Range(new Position(declLine, 0), new Position(declLine, Integer.MAX_VALUE))));
    }
}
