package dev.lumenlang.lumen.lsp.providers.hover;

import dev.lumenlang.lumen.api.pattern.PatternMeta;
import dev.lumenlang.lumen.lsp.analysis.AnalysisResult;
import dev.lumenlang.lumen.lsp.analysis.LineAnalysis;
import dev.lumenlang.lumen.lsp.analysis.MetaKeys;
import dev.lumenlang.lumen.lsp.bootstrap.LumenBootstrap;
import dev.lumenlang.lumen.lsp.providers.util.TokenAt;
import dev.lumenlang.lumen.api.codegen.TypeEnv;
import dev.lumenlang.lumen.pipeline.codegen.TypeEnvImpl;
import dev.lumenlang.lumen.pipeline.language.match.BoundValue;
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredBlockMatch;
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredExpressionMatch;
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredPatternMatch;
import dev.lumenlang.lumen.pipeline.language.tokenization.Token;
import dev.lumenlang.lumen.api.codegen.TypeEnv.VarHandle;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Builds hover content for a position in a Lumen document.
 */
public final class HoverProvider {

    /**
     * The raw pattern of the variable declaration form, special cased so the
     * name token shows the introduced variable while every other token shows
     * the assigned expression.
     */
    private static final @NotNull String SET_PATTERN = "set %name:IDENT% to %val:EXPR%";

    private HoverProvider() {
    }

    /**
     * Returns the hover for the given cursor position, or {@code null} when
     * there is nothing meaningful to show.
     *
     * @param bootstrap the populated bootstrap, used to resolve nested expression matches
     * @param result    the analysis of the document
     * @param position  the cursor position
     * @return the hover, or {@code null}
     */
    public static @Nullable Hover hover(@NotNull LumenBootstrap bootstrap, @NotNull AnalysisResult result, @NotNull Position position) {
        int lineNumber = position.getLine() + 1;
        int column = position.getCharacter();
        LineAnalysis line = result.line(lineNumber);
        if (line == null) return null;
        Token token = TokenAt.at(line.tokens(), line.indent(), column);
        if (token == null) return null;
        RegisteredPatternMatch sm = line.meta(MetaKeys.STATEMENT_MATCH, RegisteredPatternMatch.class);
        if (sm != null && SET_PATTERN.equals(sm.reg().pattern().raw())) {
            return setHover(bootstrap, line, token, sm);
        }
        RegisteredBlockMatch bm = line.meta(MetaKeys.BLOCK_MATCH, RegisteredBlockMatch.class);
        BoundValue bound = sm != null ? findBinding(sm.match().values(), token) : (bm != null ? findBinding(bm.match().values(), token) : null);
        if (bound != null && "IDENT".equals(bound.binding().id())) {
            VarHandle ref = lookupVar(line, token.text());
            if (ref != null) return variableHover(ref);
        }
        if (sm != null) return patternHover(sm.reg().pattern().raw(), sm.reg().meta());
        if (bm != null) return patternHover(bm.reg().pattern().raw(), bm.reg().meta());
        return null;
    }

    /**
     * Returns the hover used on a variable declaration line, branching by
     * whether the cursor sits on the name token or anywhere else.
     *
     * @param bootstrap the populated bootstrap
     * @param line      the line analysis
     * @param token     the token under the cursor
     * @param sm        the matched set pattern
     * @return the hover
     */
    private static @NotNull Hover setHover(@NotNull LumenBootstrap bootstrap, @NotNull LineAnalysis line, @NotNull Token token, @NotNull RegisteredPatternMatch sm) {
        int[] range = line.meta(MetaKeys.VAR_DECL_RANGE, int[].class);
        String varName = line.meta(MetaKeys.VAR_DECL_NAME, String.class);
        if (range != null && varName != null && token.start() == range[0] && token.end() == range[1]) {
            return varDeclHover(line, varName);
        }
        BoundValue val = sm.match().values().get("val");
        return valueHover(bootstrap, line, varName, val);
    }

    /**
     * Returns the bound value whose consumed tokens contain the cursor token.
     *
     * @param values the bound values of a match
     * @param token  the token under the cursor
     * @return the matching bound value, or {@code null}
     */
    private static @Nullable BoundValue findBinding(@NotNull Map<String, BoundValue> values, @NotNull Token token) {
        for (BoundValue bv : values.values()) {
            for (Token t : bv.tokens()) {
                if (t.line() == token.line() && t.start() == token.start() && t.end() == token.end()) return bv;
            }
        }
        return null;
    }

    /**
     * Builds the hover shown over the name token of a variable declaration.
     *
     * @param line    the line analysis carrying the after env
     * @param varName the declared variable name
     * @return the hover
     */
    private static @NotNull Hover varDeclHover(@NotNull LineAnalysis line, @NotNull String varName) {
        TypeEnv after = line.afterEnv();
        VarHandle ref = after != null ? after.lookupVar(varName) : null;
        StringBuilder sb = new StringBuilder();
        sb.append("```lumen\n");
        sb.append(varName);
        if (ref != null) sb.append(": ").append(ref.type().displayName());
        sb.append("\n```\n\nVariable declaration");
        return markdown(sb.toString());
    }

    /**
     * Builds the hover shown over a non name token on a variable declaration
     * line, preferring the matched expression pattern docs when one exists
     * and falling back to a plain value rendering otherwise.
     *
     * @param bootstrap the populated bootstrap, used to look up the expression match
     * @param line      the line analysis
     * @param varName   the declared variable name, or {@code null} when unavailable
     * @param val       the bound value for the assigned expression, or {@code null}
     * @return the hover
     */
    private static @NotNull Hover valueHover(@NotNull LumenBootstrap bootstrap, @NotNull LineAnalysis line, @Nullable String varName, @Nullable BoundValue val) {
        TypeEnv after = line.afterEnv();
        if (val != null && after != null && val.tokens().size() > 1) {
            RegisteredExpressionMatch em = bootstrap.patterns().matchExpression(val.tokens(), (TypeEnvImpl) after);
            if (em != null) return patternHover(em.reg().pattern().raw(), em.reg().meta());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("```lumen\n");
        sb.append(joinTokens(val));
        VarHandle ref = after != null && varName != null ? after.lookupVar(varName) : null;
        if (ref != null) sb.append("\n: ").append(ref.type().displayName());
        sb.append("\n```\n\nAssigned value");
        return markdown(sb.toString());
    }

    /**
     * Returns the textual form of a bound value by joining its consumed token
     * texts with single spaces.
     *
     * @param bv the bound value, possibly null
     * @return the joined text, possibly empty
     */
    private static @NotNull String joinTokens(@Nullable BoundValue bv) {
        if (bv == null || bv.tokens().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bv.tokens().size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(bv.tokens().get(i).text());
        }
        return sb.toString();
    }

    /**
     * Builds the hover shown over an identifier that resolves to a variable in
     * scope.
     *
     * @param ref the resolved variable
     * @return the hover
     */
    private static @NotNull Hover variableHover(@NotNull VarHandle ref) {
        return markdown("```lumen\n" + ref.name() + ": " + ref.type().displayName() + "\n```");
    }

    /**
     * Builds the hover shown over a token that belongs to a matched pattern.
     *
     * @param raw  the raw pattern string
     * @param meta the pattern documentation
     * @return the hover
     */
    private static @NotNull Hover patternHover(@NotNull String raw, @NotNull PatternMeta meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("```lumen\n").append(raw).append("\n```");
        if (meta.description() != null) {
            sb.append("\n\n").append(meta.description());
        }
        if (meta.since() != null) {
            sb.append("\n\nSince ").append(meta.since());
        }
        if (meta.deprecated()) {
            sb.append("\n\nDeprecated");
        }
        if (!meta.examples().isEmpty()) {
            sb.append("\n\nExamples\n```lumen\n");
            for (String example : meta.examples()) {
                sb.append(example).append('\n');
            }
            sb.append("```");
        }
        return markdown(sb.toString());
    }

    /**
     * Looks up a variable name against the env recorded after this line ran.
     *
     * @param line the line analysis
     * @param name the identifier text
     * @return the matching var ref, or {@code null}
     */
    private static @Nullable VarHandle lookupVar(@NotNull LineAnalysis line, @NotNull String name) {
        TypeEnv env = line.afterEnv();
        if (env == null) return null;
        return env.lookupVar(name);
    }

    /**
     * Wraps the given markdown string in an LSP {@link Hover}.
     *
     * @param value the markdown body
     * @return the hover
     */
    private static @NotNull Hover markdown(@NotNull String value) {
        return new Hover(new MarkupContent(MarkupKind.MARKDOWN, value));
    }
}
