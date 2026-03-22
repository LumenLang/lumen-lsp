package dev.lumenlang.lumen.lsp.typebindings.util;

import dev.lumenlang.lumen.api.codegen.BindingAccess;
import dev.lumenlang.lumen.api.codegen.JavaOutput;
import dev.lumenlang.lumen.api.handler.BlockHandler;
import dev.lumenlang.lumen.api.handler.ConditionHandler;
import dev.lumenlang.lumen.api.handler.ExpressionHandler;
import dev.lumenlang.lumen.api.handler.LoopHandler;
import dev.lumenlang.lumen.api.handler.StatementHandler;
import dev.lumenlang.lumen.api.pattern.Categories;
import dev.lumenlang.lumen.api.pattern.PatternMeta;
import dev.lumenlang.lumen.api.type.TypeBindingMeta;
import dev.lumenlang.lumen.lsp.documentation.DocumentationData;
import dev.lumenlang.lumen.lsp.entries.documentation.BlockEntry;
import dev.lumenlang.lumen.lsp.entries.documentation.PatternEntry;
import dev.lumenlang.lumen.lsp.entries.documentation.TypeBindingEntry;
import dev.lumenlang.lumen.pipeline.codegen.CodegenContext;
import dev.lumenlang.lumen.pipeline.codegen.TypeEnv;
import dev.lumenlang.lumen.pipeline.language.TypeBinding;
import dev.lumenlang.lumen.pipeline.language.pattern.PatternRegistry;
import dev.lumenlang.lumen.pipeline.language.tokenization.Token;
import dev.lumenlang.lumen.pipeline.typebinding.TypeRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Populates the pipeline's {@link PatternRegistry} and {@link TypeRegistry}
 * from the loaded {@link DocumentationData}.
 */
@SuppressWarnings("DataFlowIssue")
public final class RegistryBuilder {

    private static final StatementHandler NOOP_STATEMENT = (line, ctx, out) -> {
    };
    private static final ExpressionHandler NOOP_EXPRESSION = ctx -> new ExpressionHandler.ExpressionResult("null", null);
    private static final ConditionHandler NOOP_CONDITION = (match, env, ctx) -> "false";
    private static final BlockHandler NOOP_BLOCK = new BlockHandler() {
        @Override
        public void begin(@NotNull BindingAccess ctx, @NotNull JavaOutput out) {
        }

        @Override
        public void end(@NotNull BindingAccess ctx, @NotNull JavaOutput out) {
        }
    };
    private static final LoopHandler NOOP_LOOP = ctx -> new LoopHandler.LoopResult("java.util.Collections.emptyList()", null);

    private RegistryBuilder() {
    }

    /**
     * Populates the given registries with all entries from the documentation data.
     *
     * @param registry the pattern registry to populate
     * @param types    the type registry to populate
     * @param data     the loaded documentation
     */
    public static void populate(@NotNull PatternRegistry registry,
                                @NotNull TypeRegistry types,
                                @NotNull DocumentationData data) {
        for (TypeBindingEntry tb : data.typeBindings()) {
            registerTypeBinding(types, tb);
        }

        for (PatternEntry entry : data.statements()) {
            registerStatement(registry, entry);
        }
        for (PatternEntry entry : data.expressions()) {
            registerExpression(registry, entry);
        }
        for (PatternEntry entry : data.conditions()) {
            registerCondition(registry, entry);
        }
        for (BlockEntry entry : data.blocks()) {
            registerBlock(registry, entry);
        }
        for (PatternEntry entry : data.loopSources()) {
            registerLoop(registry, entry);
        }
    }

    /**
     * Registers a single type binding from a documentation entry, if not already present.
     *
     * @param types the type registry to register into
     * @param entry the type binding documentation entry
     */
    private static void registerTypeBinding(@NotNull TypeRegistry types,
                                            @NotNull TypeBindingEntry entry) {
        if (types.get(entry.id()) != null) return;

        types.register(new TypeBinding() {
            @Override
            public @NotNull String id() {
                return entry.id();
            }

            @Override
            public Object parse(@NotNull List<Token> tokens, @NotNull TypeEnv env) {
                return tokens.stream()
                        .map(Token::text)
                        .reduce((a, b) -> a + " " + b)
                        .orElse("");
            }

            @Override
            public @NotNull String toJava(Object value, @NotNull CodegenContext ctx, @NotNull TypeEnv env) {
                return String.valueOf(value);
            }

            @Override
            public int consumeCount(@NotNull List<Token> tokens, @NotNull TypeEnv env) {
                return 1;
            }
        });

        types.registerMeta(entry.id(), new TypeBindingMeta(
                entry.description(),
                entry.javaType(),
                entry.examples(),
                entry.since(),
                entry.deprecated()
        ));
    }

    /**
     * Registers a statement pattern from a documentation entry into the given registry.
     *
     * @param registry the pattern registry to register into
     * @param entry    the pattern entry describing the statement
     */
    private static void registerStatement(@NotNull PatternRegistry registry,
                                          @NotNull PatternEntry entry) {
        PatternMeta meta = meta(entry);
        registry.statement(b -> {
            b.by(meta.by());
            for (String p : entry.patterns()) {
                b.pattern(p);
            }
            if (meta.description() != null) b.description(meta.description());
            for (String ex : entry.examples()) b.example(ex);
            if (meta.since() != null) b.since(meta.since());
            if (meta.category() != null) b.category(meta.category());
            if (meta.deprecated()) b.deprecated(true);
            b.handler(NOOP_STATEMENT);
        });
    }

    /**
     * Registers an expression pattern from a documentation entry into the given registry.
     *
     * @param registry the pattern registry to register into
     * @param entry    the pattern entry describing the expression
     */
    private static void registerExpression(@NotNull PatternRegistry registry,
                                           @NotNull PatternEntry entry) {
        PatternMeta meta = meta(entry);
        registry.expression(b -> {
            b.by(meta.by());
            for (String p : entry.patterns()) {
                b.pattern(p);
            }
            if (meta.description() != null) b.description(meta.description());
            for (String ex : entry.examples()) b.example(ex);
            if (meta.since() != null) b.since(meta.since());
            if (meta.category() != null) b.category(meta.category());
            if (meta.deprecated()) b.deprecated(true);
            b.handler(NOOP_EXPRESSION);
        });
    }

    /**
     * Registers a condition pattern from a documentation entry into the given registry.
     *
     * @param registry the pattern registry to register into
     * @param entry    the pattern entry describing the condition
     */
    private static void registerCondition(@NotNull PatternRegistry registry,
                                          @NotNull PatternEntry entry) {
        PatternMeta meta = meta(entry);
        registry.condition(b -> {
            b.by(meta.by());
            for (String p : entry.patterns()) {
                b.pattern(p);
            }
            if (meta.description() != null) b.description(meta.description());
            for (String ex : entry.examples()) b.example(ex);
            if (meta.since() != null) b.since(meta.since());
            if (meta.category() != null) b.category(meta.category());
            if (meta.deprecated()) b.deprecated(true);
            b.handler(NOOP_CONDITION);
        });
    }

    /**
     * Registers a block pattern from a documentation entry into the given registry.
     *
     * @param registry the pattern registry to register into
     * @param entry    the block entry describing the block
     */
    private static void registerBlock(@NotNull PatternRegistry registry,
                                      @NotNull BlockEntry entry) {
        PatternMeta meta = meta(entry);
        registry.block(b -> {
            b.by(meta.by());
            for (String p : entry.patterns()) {
                b.pattern(p);
            }
            if (meta.description() != null) b.description(meta.description());
            for (String ex : entry.examples()) b.example(ex);
            if (meta.since() != null) b.since(meta.since());
            if (meta.category() != null) b.category(meta.category());
            if (meta.deprecated()) b.deprecated(true);
            b.handler(NOOP_BLOCK);
        });
    }

    /**
     * Registers a loop source pattern from a documentation entry into the given registry.
     *
     * @param registry the pattern registry to register into
     * @param entry    the pattern entry describing the loop source
     */
    private static void registerLoop(@NotNull PatternRegistry registry,
                                     @NotNull PatternEntry entry) {
        PatternMeta meta = meta(entry);
        registry.loop(b -> {
            b.by(meta.by());
            for (String p : entry.patterns()) {
                b.pattern(p);
            }
            if (meta.description() != null) b.description(meta.description());
            for (String ex : entry.examples()) b.example(ex);
            if (meta.since() != null) b.since(meta.since());
            if (meta.category() != null) b.category(meta.category());
            if (meta.deprecated()) b.deprecated(true);
            b.handler(NOOP_LOOP);
        });
    }

    /**
     * Constructs a {@link PatternMeta} from a pattern documentation entry.
     *
     * @param entry the pattern entry to convert
     * @return the constructed pattern metadata
     */
    private static @NotNull PatternMeta meta(@NotNull PatternEntry entry) {
        return new PatternMeta(
                entry.by(),
                entry.description(),
                entry.examples(),
                entry.since(),
                entry.category() != null ? Categories.createOrGet(entry.category()) : null,
                entry.deprecated()
        );
    }

    /**
     * Constructs a {@link PatternMeta} from a block documentation entry.
     *
     * @param entry the block entry to convert
     * @return the constructed pattern metadata
     */
    private static @NotNull PatternMeta meta(@NotNull BlockEntry entry) {
        return new PatternMeta(
                entry.by(),
                entry.description(),
                entry.examples(),
                entry.since(),
                entry.category() != null ? Categories.createOrGet(entry.category()) : null,
                entry.deprecated()
        );
    }
}
