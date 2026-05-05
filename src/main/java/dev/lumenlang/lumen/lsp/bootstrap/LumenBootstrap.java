package dev.lumenlang.lumen.lsp.bootstrap;

import dev.lumenlang.lumen.api.LumenAPI;
import dev.lumenlang.lumen.api.LumenProvider;
import dev.lumenlang.lumen.api.scanner.RegistrationScanner;
import dev.lumenlang.lumen.api.type.BuiltinLumenTypes;
import dev.lumenlang.lumen.api.type.MinecraftTypes;
import dev.lumenlang.lumen.api.version.MinecraftVersion;
import dev.lumenlang.lumen.pipeline.addon.AddonManager;
import dev.lumenlang.lumen.pipeline.addon.LumenAPIImpl;
import dev.lumenlang.lumen.pipeline.addon.ScriptBinderManager;
import dev.lumenlang.lumen.pipeline.binder.ScriptBinder;
import dev.lumenlang.lumen.pipeline.bus.LumenEventBus;
import dev.lumenlang.lumen.pipeline.inject.InjectableHandlers;
import dev.lumenlang.lumen.pipeline.language.emit.CodeEmitter;
import dev.lumenlang.lumen.pipeline.language.emit.EmitRegistry;
import dev.lumenlang.lumen.pipeline.language.emit.TransformerRegistry;
import dev.lumenlang.lumen.pipeline.language.pattern.PatternRegistry;
import dev.lumenlang.lumen.pipeline.logger.LumenLogger;
import dev.lumenlang.lumen.pipeline.typebinding.TypeRegistry;
import dev.lumenlang.lumen.plugin.defaults.type.BuiltinTypeBindings;
import dev.lumenlang.lumen.plugin.inject.InjectableHandlerFactoryImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.logging.Logger;

/**
 * Initialises the full Lumen registration system in headless mode for the LSP.
 * Mirrors the plugin onLoad init without scripts, persistence, or a real server.
 */
public final class LumenBootstrap {

    private static final @NotNull Logger LOGGER = Logger.getLogger("LumenLSP-Bootstrap");
    private static @Nullable LumenBootstrap instance;

    private final @NotNull PatternRegistry patternRegistry;
    private final @NotNull TypeRegistry typeRegistry;
    private final @NotNull EmitRegistry emitRegistry;
    private final @NotNull LumenAPI api;
    private final @NotNull AddonManager addonManager;
    private final @NotNull LumenEventBus eventBus;

    private LumenBootstrap() {
        HeadlessBukkitServer.install();
        LumenLogger.init(LOGGER);
        CodeEmitter.setParallelParseThreads(0);
        MinecraftVersion.detect("1.21");

        MinecraftTypes.registerAll();
        BuiltinLumenTypes.registerAll();
        InjectableHandlers.factory(new InjectableHandlerFactoryImpl());

        this.typeRegistry = new TypeRegistry();
        BuiltinTypeBindings.register(typeRegistry);

        this.patternRegistry = new PatternRegistry(typeRegistry);
        PatternRegistry.instance(patternRegistry);

        this.emitRegistry = new EmitRegistry();
        EmitRegistry.instance(emitRegistry);

        TransformerRegistry transformerRegistry = new TransformerRegistry();
        TransformerRegistry.instance(transformerRegistry);

        ScriptBinderManager binderManager = new ScriptBinderManager();
        ScriptBinder.init(binderManager);

        this.api = new LumenAPIImpl(patternRegistry, typeRegistry, emitRegistry, transformerRegistry, binderManager);

        this.addonManager = new AddonManager();
        LumenProvider.init(api, addonManager::registerAddon);
        this.eventBus = new LumenEventBus();
        LumenProvider.initBus(eventBus);

        RegistrationScanner.init(new StaticRegistrationScannerBackend(api));
        RegistrationScanner.scan("dev.lumenlang.lumen.plugin.defaults");
        patternRegistry.warmup();
    }

    /**
     * Returns the singleton bootstrap, building it on first call.
     *
     * @return the active bootstrap
     */
    public static @NotNull LumenBootstrap get() {
        LumenBootstrap b = instance;
        if (b != null) return b;
        synchronized (LumenBootstrap.class) {
            if (instance == null) instance = new LumenBootstrap();
            return instance;
        }
    }

    /**
     * Returns the populated pattern registry.
     *
     * @return the pattern registry
     */
    public @NotNull PatternRegistry patterns() {
        return patternRegistry;
    }

    /**
     * Returns the populated type registry.
     *
     * @return the type registry
     */
    public @NotNull TypeRegistry types() {
        return typeRegistry;
    }

    /**
     * Returns the emit registry, holding block form handlers and validators.
     *
     * @return the emit registry
     */
    public @NotNull EmitRegistry emit() {
        return emitRegistry;
    }

    /**
     * Returns the Lumen API surface.
     *
     * @return the api
     */
    public @NotNull LumenAPI api() {
        return api;
    }

    /**
     * Returns the addon manager.
     *
     * @return the addon manager
     */
    public @NotNull AddonManager addons() {
        return addonManager;
    }

    /**
     * Returns the event bus.
     *
     * @return the event bus
     */
    public @NotNull LumenEventBus events() {
        return eventBus;
    }
}
