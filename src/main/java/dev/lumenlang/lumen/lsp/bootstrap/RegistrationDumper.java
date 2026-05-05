package dev.lumenlang.lumen.lsp.bootstrap;

import dev.lumenlang.lumen.api.annotations.Registration;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Standalone JVM tool that walks the active classpath, finds every class
 * annotated with {@link Registration} under a given base package, and prints
 * one fully qualified class name per line to stdout. Output is consumed at
 * build time to produce {@code lumen-defaults.txt}, which the AOT friendly
 * {@link StaticRegistrationScannerBackend} then reads at runtime in place of
 * doing its own jar walking.
 *
 * <p>Usage: {@code java -cp ... RegistrationDumper [basePackage]}.
 */
public final class RegistrationDumper {

    private static final @NotNull String DEFAULT_PACKAGE = "dev.lumenlang.lumen.plugin.defaults";

    private RegistrationDumper() {
    }

    /**
     * Entry point. Resolves every {@link Registration} annotated class under
     * {@code basePackage} and prints the names sorted alphabetically.
     *
     * @param args optional single argument overriding the default base package
     * @throws Exception when classpath enumeration or class loading fails
     */
    public static void main(@NotNull String[] args) throws Exception {
        String basePackage = args.length > 0 ? args[0] : DEFAULT_PACKAGE;
        List<String> classes = collect(basePackage);
        Collections.sort(classes);
        for (String name : classes) {
            System.out.println(name);
        }
    }

    /**
     * Walks the protection domain of the {@link Registration} annotation,
     * which lives in {@code lumen-api}, then walks every other classpath jar
     * the system classloader can see. Both jar entries and exploded class
     * directories are scanned.
     *
     * @param basePackage the base package to filter, dotted form
     * @return the discovered fully qualified class names, may contain duplicates removed by the caller
     * @throws IOException when classpath enumeration fails
     */
    private static @NotNull List<String> collect(@NotNull String basePackage) throws IOException {
        String basePath = basePackage.replace('.', '/');
        List<String> out = new ArrayList<>();
        for (Path path : classpathRoots()) {
            if (Files.isDirectory(path)) {
                collectFromDirectory(path.resolve(basePath), basePackage, out);
            } else if (Files.isRegularFile(path)) {
                collectFromJar(path, basePath, out);
            }
        }
        return filterRegistrationAnnotated(out);
    }

    /**
     * Returns the classpath roots: every entry in {@code java.class.path}
     * resolved to an absolute path.
     *
     * @return the root paths, as filesystem paths
     */
    private static @NotNull List<Path> classpathRoots() {
        String classpath = System.getProperty("java.class.path", "");
        String separator = System.getProperty("path.separator", ":");
        List<Path> out = new ArrayList<>();
        for (String entry : classpath.split(Pattern.quote(separator))) {
            if (entry.isBlank()) continue;
            try {
                out.add(Paths.get(entry).toAbsolutePath());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /**
     * Adds every {@code .class} entry inside {@code jarPath} whose internal
     * name starts with {@code basePath} to {@code out}.
     *
     * @param jarPath  the jar path
     * @param basePath the slash separated base path prefix
     * @param out      the accumulator for fully qualified class names
     * @throws IOException when reading the jar fails
     */
    private static void collectFromJar(@NotNull Path jarPath, @NotNull String basePath, @NotNull List<String> out) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            jar.stream()
                    .filter(entry -> entry.getName().startsWith(basePath) && entry.getName().endsWith(".class"))
                    .forEach(entry -> {
                        String name = entry.getName().replace('/', '.');
                        out.add(name.substring(0, name.length() - 6));
                    });
        }
    }

    /**
     * Adds every {@code .class} file under {@code dir} to {@code out}, using
     * {@code basePackage} as the name prefix.
     *
     * @param dir         the directory to walk
     * @param basePackage the dotted base package prefix
     * @param out         the accumulator for fully qualified class names
     * @throws IOException when walking the directory fails
     */
    private static void collectFromDirectory(@NotNull Path dir, @NotNull String basePackage, @NotNull List<String> out) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> stream = Files.walk(dir)) {
            String separator = FileSystems.getDefault().getSeparator();
            stream.filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> {
                        String relative = dir.relativize(p).toString()
                                .replace(separator, ".")
                                .replace(".class", "");
                        out.add(basePackage + "." + relative);
                    });
        }
    }

    /**
     * Loads each candidate name and keeps only the classes carrying the
     * {@link Registration} annotation. Inner classes and unreadable entries
     * are skipped silently.
     *
     * @param names the candidate fully qualified class names
     * @return the filtered list, deduplicated
     */
    private static @NotNull List<String> filterRegistrationAnnotated(@NotNull List<String> names) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            if (!seen.add(name)) continue;
            try {
                Class<?> clazz = Class.forName(name, false, Thread.currentThread().getContextClassLoader());
                if (clazz.isAnnotationPresent(Registration.class)) out.add(name);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }
}
