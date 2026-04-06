package dev.lumenlang.lumen.lsp.analysis;

import dev.lumenlang.lumen.lsp.analysis.line.LineInfo;
import dev.lumenlang.lumen.lsp.diagnostic.util.LumenDiagnostic;
import dev.lumenlang.lumen.lsp.documentation.DocumentationData;
import dev.lumenlang.lumen.pipeline.codegen.TypeEnv;
import dev.lumenlang.lumen.pipeline.data.DataSchema;
import dev.lumenlang.lumen.pipeline.language.pattern.PatternRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable container for all state accumulated during a single script analysis pass.
 * Groups together the pattern registry, type environment, diagnostics, line info,
 * variable scopes, and data schemas so they can be passed as a single argument
 * instead of repeating 10+ parameters on every method.
 */
public record AnalysisState(PatternRegistry registry, TypeEnv env, DocumentationData docs,
                            List<LumenDiagnostic> diagnostics, Map<Integer, LineInfo> lineInfo,
                            Map<String, VarDeclaration> variables, Map<String, VarDeclaration> allVariables,
                            Map<String, DataSchema> dataSchemas,
                            Map<Integer, Map<String, VarDeclaration>> scopeByLine) {

    /**
     * Creates a new analysis state with the given registry, environment, and documentation.
     *
     * @param registry     the pattern registry
     * @param env          the type environment
     * @param docs         the documentation data
     * @param diagnostics  the mutable diagnostics list
     * @param lineInfo     the mutable line info map
     * @param variables    the mutable current scope variable map
     * @param allVariables the mutable all-variables map
     * @param dataSchemas  the mutable data schemas map
     * @param scopeByLine  the mutable scope-by-line map
     */
    public AnalysisState(@NotNull PatternRegistry registry, @NotNull TypeEnv env, @NotNull DocumentationData docs, @NotNull List<LumenDiagnostic> diagnostics, @NotNull Map<Integer, LineInfo> lineInfo, @NotNull Map<String, VarDeclaration> variables, @NotNull Map<String, VarDeclaration> allVariables, @NotNull Map<String, DataSchema> dataSchemas, @NotNull Map<Integer, Map<String, VarDeclaration>> scopeByLine) {
        this.registry = registry;
        this.env = env;
        this.docs = docs;
        this.diagnostics = diagnostics;
        this.lineInfo = lineInfo;
        this.variables = variables;
        this.allVariables = allVariables;
        this.dataSchemas = dataSchemas;
        this.scopeByLine = scopeByLine;
    }

    /**
     * @return the pattern registry
     */
    @Override
    public @NotNull PatternRegistry registry() {
        return registry;
    }

    /**
     * @return the type environment
     */
    @Override
    public @NotNull TypeEnv env() {
        return env;
    }

    /**
     * @return the documentation data
     */
    @Override
    public @NotNull DocumentationData docs() {
        return docs;
    }

    /**
     * @return the mutable diagnostics list
     */
    @Override
    public @NotNull List<LumenDiagnostic> diagnostics() {
        return diagnostics;
    }

    /**
     * @return the mutable line info map
     */
    @Override
    public @NotNull Map<Integer, LineInfo> lineInfo() {
        return lineInfo;
    }

    /**
     * @return the mutable current scope variable map
     */
    @Override
    public @NotNull Map<String, VarDeclaration> variables() {
        return variables;
    }

    /**
     * @return the mutable all-variables map
     */
    @Override
    public @NotNull Map<String, VarDeclaration> allVariables() {
        return allVariables;
    }

    /**
     * @return the mutable data schemas map
     */
    @Override
    public @NotNull Map<String, DataSchema> dataSchemas() {
        return dataSchemas;
    }

    /**
     * @return the mutable scope-by-line map
     */
    @Override
    public @NotNull Map<Integer, Map<String, VarDeclaration>> scopeByLine() {
        return scopeByLine;
    }

    /**
     * Takes a snapshot of the current variable scope and records it for the given line.
     *
     * @param line the 1-based line number
     */
    public void snapshot(int line) {
        scopeByLine.put(line, new HashMap<>(variables));
    }

    /**
     * Records a line classification entry in the line info map.
     *
     * @param info the line info to record
     */
    public void record(@NotNull LineInfo info) {
        lineInfo.put(info.line(), info);
    }

    /**
     * Appends a diagnostic to the diagnostics list.
     *
     * @param diagnostic the diagnostic to add
     */
    public void report(@NotNull LumenDiagnostic diagnostic) {
        diagnostics.add(diagnostic);
    }
}
