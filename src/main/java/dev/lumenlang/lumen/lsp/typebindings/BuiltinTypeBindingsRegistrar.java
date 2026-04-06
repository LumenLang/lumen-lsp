package dev.lumenlang.lumen.lsp.typebindings;

import dev.lumenlang.lumen.api.LumenAPI;
import dev.lumenlang.lumen.pipeline.addon.LumenAPIImpl;
import dev.lumenlang.lumen.pipeline.addon.ScriptBinderManager;
import dev.lumenlang.lumen.pipeline.language.emit.EmitRegistry;
import dev.lumenlang.lumen.pipeline.language.emit.TransformerRegistry;
import dev.lumenlang.lumen.pipeline.language.pattern.PatternRegistry;
import dev.lumenlang.lumen.pipeline.typebinding.TypeRegistry;
import org.jetbrains.annotations.NotNull;

/**
 * Registers the built-in type bindings into the LSP's type registry.
 */
public final class BuiltinTypeBindingsRegistrar {

    private BuiltinTypeBindingsRegistrar() {
    }

    /**
     * Registers all built-in type bindings on the given registries.
     *
     * @param registry the pattern registry
     * @param types    the type registry to populate
     */
    public static void register(@NotNull PatternRegistry registry, @NotNull TypeRegistry types) {
        LumenAPI api = new LumenAPIImpl(registry, types, new EmitRegistry(), new TransformerRegistry(), new ScriptBinderManager());
        BuiltinTypeBindings.register(types);
        DefaultTypeBindings.register(api);
    }
}
