package dev.lumenlang.lumen.lsp.providers;

import dev.lumenlang.lumen.lsp.analysis.AnalysisResult;
import dev.lumenlang.lumen.lsp.analysis.line.LineInfo;
import dev.lumenlang.lumen.lsp.documentation.DocumentationData;
import dev.lumenlang.lumen.lsp.entries.documentation.BlockEntry;
import dev.lumenlang.lumen.pipeline.language.nodes.BlockNode;
import dev.lumenlang.lumen.pipeline.language.nodes.Node;
import dev.lumenlang.lumen.pipeline.language.nodes.StatementNode;
import dev.lumenlang.lumen.pipeline.language.tokenization.Token;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides document symbols (outline) for Lumen scripts.
 */
public final class DocumentSymbolProvider {

    /**
     * Generates document symbols for the analyzed document.
     *
     * @param analysis the analysis result (may be null)
     * @param docs     the documentation data
     * @return the list of document symbols
     */
    public @NotNull List<Either<SymbolInformation, DocumentSymbol>> symbols(@Nullable AnalysisResult analysis, @NotNull DocumentationData docs) {
        if (analysis == null) return List.of();

        List<Either<SymbolInformation, DocumentSymbol>> result = new ArrayList<>();
        collectSymbols(analysis.root().children(), analysis, docs, result);
        return result;
    }

    /**
     * Recursively collects document symbol entries from a list of AST nodes.
     *
     * @param nodes    the nodes to collect symbols from
     * @param analysis the analysis result
     * @param docs     the documentation data
     * @param result   the list to append collected symbols to
     */
    private void collectSymbols(@NotNull List<Node> nodes,
                                @NotNull AnalysisResult analysis,
                                @NotNull DocumentationData docs,
                                @NotNull List<Either<SymbolInformation, DocumentSymbol>> result) {
        for (Node node : nodes) {
            if (node instanceof BlockNode block) {
                DocumentSymbol symbol = blockSymbol(block, docs);
                if (symbol != null) {
                    List<Either<SymbolInformation, DocumentSymbol>> children = new ArrayList<>();
                    collectSymbols(block.children(), analysis, docs, children);
                    List<DocumentSymbol> childSymbols = new ArrayList<>();
                    for (Either<SymbolInformation, DocumentSymbol> child : children) {
                        if (child.isRight()) childSymbols.add(child.getRight());
                    }
                    symbol.setChildren(childSymbols);
                    result.add(Either.forRight(symbol));
                }
            } else if (node instanceof StatementNode stmt) {
                DocumentSymbol symbol = statementSymbol(stmt, analysis);
                if (symbol != null) {
                    result.add(Either.forRight(symbol));
                }
            }
        }
    }

    /**
     * Builds a document symbol for a block node. The symbol kind is determined
     * from the block's documentation category.
     *
     * @param block the block node
     * @param docs  the documentation data
     * @return the document symbol, or null if the block has no header
     */
    private @Nullable DocumentSymbol blockSymbol(@NotNull BlockNode block, @NotNull DocumentationData docs) {
        List<Token> head = block.head();
        if (head.isEmpty()) return null;

        String firstWord = head.get(0).text().toLowerCase();
        String name;
        SymbolKind kind;

        switch (firstWord) {
            case "if", "else" -> {
                name = block.raw().trim();
                if (name.endsWith(":")) name = name.substring(0, name.length() - 1);
                kind = SymbolKind.Boolean;
            }
            case "loop" -> {
                name = "loop " + tokenText(head);
                kind = SymbolKind.Array;
            }
            case "java" -> {
                name = "java";
                kind = SymbolKind.Object;
            }
            case "data" -> {
                name = "data " + (head.size() >= 2 ? head.get(1).text() : "?");
                kind = SymbolKind.Class;
            }
            default -> {
                BlockEntry entry = blockEntry(docs, firstWord);
                if (entry != null) {
                    String rest = tokenText(head);
                    name = rest.isEmpty() ? firstWord : firstWord + " " + rest;
                    kind = categoryKind();
                } else {
                    name = block.raw().trim();
                    if (name.endsWith(":")) name = name.substring(0, name.length() - 1);
                    kind = SymbolKind.Object;
                }
            }
        }

        int line = Math.max(0, block.line() - 1);
        int lastLine = Math.max(line, lastLine(block));
        int rawLen = block.raw() != null ? block.raw().length() : 0;

        return new DocumentSymbol(name, kind,
                new Range(new Position(line, 0), new Position(lastLine, lastLine > line ? 0 : rawLen)),
                new Range(new Position(line, 0), new Position(line, rawLen)));
    }

    /**
     * Builds a document symbol for a statement node if it represents a variable declaration.
     *
     * @param stmt     the statement node
     * @param analysis the analysis result
     * @return the document symbol, or null if not a tracked declaration kind
     */
    private @Nullable DocumentSymbol statementSymbol(@NotNull StatementNode stmt,
                                                     @NotNull AnalysisResult analysis) {
        LineInfo info = analysis.lineInfo().get(stmt.line());
        if (info == null) return null;

        switch (info.kind()) {
            case VAR_DECL, EXPR_VAR_DECL -> {
                String varName = extractVarName(stmt);
                if (varName == null) return null;
                int line = Math.max(0, stmt.line() - 1);
                Range range = new Range(new Position(line, 0), new Position(line, stmt.raw().length()));
                return new DocumentSymbol("var " + varName, SymbolKind.Variable, range, range);
            }
            case GLOBAL_VAR -> {
                String varName = extractGlobalVarName(stmt);
                if (varName == null) return null;
                int line = Math.max(0, stmt.line() - 1);
                Range range = new Range(new Position(line, 0), new Position(line, stmt.raw().length()));
                DocumentSymbol sym = new DocumentSymbol("global " + varName, SymbolKind.Variable, range, range);
                sym.setDetail("global");
                return sym;
            }
            case STORE_VAR -> {
                String varName = extractStoreVarName(stmt);
                if (varName == null) return null;
                int line = Math.max(0, stmt.line() - 1);
                Range range = new Range(new Position(line, 0), new Position(line, stmt.raw().length()));
                DocumentSymbol sym = new DocumentSymbol("stored " + varName, SymbolKind.Variable, range, range);
                sym.setDetail("stored");
                return sym;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * Extracts the declared variable name from a var-declaration statement.
     *
     * @param stmt the statement node to search
     * @return the variable name, or null if not found
     */
    private @Nullable String extractVarName(@NotNull StatementNode stmt) {
        List<Token> tokens = stmt.head();
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).text().equalsIgnoreCase("var") && i + 1 < tokens.size()) {
                return tokens.get(i + 1).text();
            }
        }
        return null;
    }

    /**
     * Extracts the declared variable name from a global var-declaration statement.
     *
     * @param stmt the statement node to search
     * @return the variable name, or null if not found
     */
    private @Nullable String extractGlobalVarName(@NotNull StatementNode stmt) {
        List<Token> tokens = stmt.head();
        for (int i = 0; i < tokens.size() - 1; i++) {
            if (tokens.get(i).text().equalsIgnoreCase("var")) {
                return tokens.get(i + 1).text();
            }
            if (tokens.get(i).text().equalsIgnoreCase("global") && i + 1 < tokens.size()
                    && !tokens.get(i + 1).text().equalsIgnoreCase("var")
                    && !tokens.get(i + 1).text().equalsIgnoreCase("stored")) {
                return tokens.get(i + 1).text();
            }
        }
        return null;
    }

    /**
     * Extracts the declared variable name from a stored var-declaration statement.
     *
     * @param stmt the statement node to search
     * @return the variable name, or null if not found
     */
    private @Nullable String extractStoreVarName(@NotNull StatementNode stmt) {
        List<Token> tokens = stmt.head();
        for (int i = 0; i < tokens.size() - 1; i++) {
            if (tokens.get(i).text().equalsIgnoreCase("var") || tokens.get(i).text().equalsIgnoreCase("store")) {
                if (i + 1 < tokens.size() && !tokens.get(i + 1).text().equalsIgnoreCase("var")) {
                    return tokens.get(i + 1).text();
                }
                if (i + 2 < tokens.size()) return tokens.get(i + 2).text();
            }
        }
        return null;
    }

    /**
     * Joins all tokens from the start index into a single space-separated string.
     *
     * @param tokens the token list
     * @return the concatenated token text
     */
    private @NotNull String tokenText(@NotNull List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < tokens.size(); i++) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(tokens.get(i).text());
        }
        return sb.toString();
    }

    /**
     * Recursively finds the last line number within a block and its children.
     *
     * @param block the block node to scan
     * @return the 0-based last line number
     */
    private int lastLine(@NotNull BlockNode block) {
        int max = Math.max(0, block.line() - 1);
        for (Node child : block.children()) {
            if (child instanceof BlockNode b) {
                max = Math.max(max, lastLine(b));
            } else {
                max = Math.max(max, Math.max(0, child.line() - 1));
            }
        }
        return max;
    }

    /**
     * Finds a block entry from documentation whose first pattern starts with the given keyword.
     *
     * @param docs    the documentation data
     * @param keyword the lowercase block keyword to search for
     * @return the matching block entry, or null if not found
     */
    private @Nullable BlockEntry blockEntry(@NotNull DocumentationData docs, @NotNull String keyword) {
        for (BlockEntry entry : docs.blocks()) {
            for (String pattern : entry.patterns()) {
                String first = pattern.split("\\s")[0].toLowerCase();
                if (first.equals(keyword)) return entry;
            }
        }
        return null;
    }

    /**
     * Returns a generic symbol kind for documentation blocks.
     *
     * @return the symbol kind
     */
    private @NotNull SymbolKind categoryKind() {
        return SymbolKind.Object;
    }
}
