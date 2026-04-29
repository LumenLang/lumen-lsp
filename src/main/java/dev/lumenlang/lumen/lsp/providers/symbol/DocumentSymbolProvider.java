package dev.lumenlang.lumen.lsp.providers.symbol;

import dev.lumenlang.lumen.lsp.analysis.AnalysisResult;
import dev.lumenlang.lumen.lsp.analysis.LineAnalysis;
import dev.lumenlang.lumen.lsp.analysis.MetaKeys;
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredBlockMatch;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the symbol outline for a document. Currently emits a flat list of top
 * level matched blocks plus variable declarations.
 */
public final class DocumentSymbolProvider {

    private DocumentSymbolProvider() {
    }

    /**
     * Returns the document symbols for the given analysis.
     *
     * @param result the analysis of the document
     * @return the list of symbols, possibly empty
     */
    public static @NotNull List<DocumentSymbol> symbols(@NotNull AnalysisResult result) {
        List<DocumentSymbol> out = new ArrayList<>();
        for (LineAnalysis line : result.lines()) {
            RegisteredBlockMatch bm = line.meta(MetaKeys.BLOCK_MATCH, RegisteredBlockMatch.class);
            if (bm != null) {
                out.add(symbol(line, bm.reg().pattern().raw(), SymbolKind.Class));
                continue;
            }
            String varName = line.meta(MetaKeys.VAR_DECL_NAME, String.class);
            if (varName != null) {
                out.add(symbol(line, varName, SymbolKind.Variable));
            }
        }
        return out;
    }

    /**
     * Builds a single document symbol entry covering the given line.
     *
     * @param line  the line analysis providing line number and tokens
     * @param name  the symbol display name
     * @param kind  the LSP symbol kind
     * @return the symbol
     */
    private static @NotNull DocumentSymbol symbol(@NotNull LineAnalysis line, @NotNull String name, @NotNull SymbolKind kind) {
        int row = Math.max(0, line.lineNumber() - 1);
        Range range = new Range(new Position(row, 0), new Position(row, Integer.MAX_VALUE));
        DocumentSymbol sym = new DocumentSymbol();
        sym.setName(name);
        sym.setKind(kind);
        sym.setRange(range);
        sym.setSelectionRange(range);
        sym.setChildren(List.of());
        return sym;
    }
}
