package dev.lumenlang.lumen.lsp.analysis;

import dev.lumenlang.lumen.lsp.analysis.line.LineInfo;
import dev.lumenlang.lumen.lsp.diagnostic.util.LumenDiagnostic;
import dev.lumenlang.lumen.pipeline.data.DataSchema;
import dev.lumenlang.lumen.pipeline.language.nodes.BlockNode;
import dev.lumenlang.lumen.pipeline.language.tokenization.Line;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * The result of analyzing a Lumen script, containing the AST,
 * diagnostics, per-line info, variable declarations, and data schemas.
 *
 * @param root        the parsed AST root
 * @param lines       the tokenized lines
 * @param diagnostics all collected diagnostics
 * @param lineInfo    per-line classification info, keyed by 1-based line number
 * @param variables   all variable declarations found, keyed by name
 * @param dataSchemas all data class schemas found, keyed by lowercase type name
 * @param scopeByLine per-line variable scope snapshots, keyed by 1-based line number
 */
public record AnalysisResult(
        @NotNull BlockNode root,
        @NotNull List<Line> lines,
        @NotNull List<LumenDiagnostic> diagnostics,
        @NotNull Map<Integer, LineInfo> lineInfo,
        @NotNull Map<String, VarDeclaration> variables,
        @NotNull Map<String, DataSchema> dataSchemas,
        @NotNull Map<Integer, Map<String, VarDeclaration>> scopeByLine
) {
}
