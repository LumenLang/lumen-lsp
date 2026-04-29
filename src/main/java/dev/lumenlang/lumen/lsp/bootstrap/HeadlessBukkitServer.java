package dev.lumenlang.lumen.lsp.bootstrap;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.UnsafeValues;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.util.logging.Logger;

/**
 * Installs a stub Bukkit Server implementation so that the Lumen pipeline can
 * complete its registration phase without an actual Minecraft server running.
 */
public final class HeadlessBukkitServer {

    private static final @NotNull Logger LOGGER = Logger.getLogger("LumenLSP-HeadlessBukkit");

    private HeadlessBukkitServer() {
    }

    /**
     * Installs the headless server stub. Safe to call once per JVM.
     */
    public static void install() {
        if (Bukkit.getServer() != null) return;
        Server server = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> defaults(method.getReturnType())
        );
        Bukkit.setServer(server);
    }

    /**
     * Returns the no-op default value for a given return type.
     *
     * @param type the method return type
     * @return zero, false, an empty array or {@code null} as appropriate
     */
    private static @Nullable Object defaults(@NotNull Class<?> type) {
        if (type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        if (type == char.class) return '\0';
        if (type == String.class) return "";
        if (type == Logger.class) return LOGGER;
        if (type.isArray()) return Array.newInstance(type.getComponentType(), 0);
        return null;
    }
}
