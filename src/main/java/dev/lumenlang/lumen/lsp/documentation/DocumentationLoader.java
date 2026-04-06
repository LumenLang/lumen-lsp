package dev.lumenlang.lumen.lsp.documentation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lumenlang.lumen.lsp.entries.documentation.BlockEntry;
import dev.lumenlang.lumen.lsp.entries.documentation.EventEntry;
import dev.lumenlang.lumen.lsp.entries.documentation.PatternEntry;
import dev.lumenlang.lumen.lsp.entries.documentation.TypeBindingEntry;
import dev.lumenlang.lumen.lsp.entries.documentation.variables.BlockVariable;
import dev.lumenlang.lumen.lsp.entries.documentation.variables.EventVariable;
import dev.lumenlang.lumen.pipeline.documentation.LumenDoc;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and parses documentation files ({@code .ldoc})
 * into structured {@link DocumentationData}.
 */
public final class DocumentationLoader {

    private DocumentationLoader() {
    }

    /**
     * Extracts the addon name from a documentation filename.
     * For example, {@code "Lumen-documentation.ldoc"} returns {@code "Lumen"}.
     *
     * @param filename the filename to extract from
     * @return the addon name, or the filename itself if the pattern does not match
     */
    public static @NotNull String addonName(@NotNull String filename) {
        String name = filename;
        if (name.endsWith(LumenDoc.EXTENSION)) {
            name = name.substring(0, name.length() - LumenDoc.EXTENSION.length());
        }
        if (name.endsWith("-documentation")) {
            name = name.substring(0, name.length() - "-documentation".length());
        }
        return name.isEmpty() ? filename : name;
    }

    /**
     * Loads documentation data from an {@code .ldoc} file, inferring the addon name from the filename.
     *
     * @param path the path to the documentation file
     * @param by   the addon name to assign as the author of all entries
     * @return the parsed documentation data
     * @throws IOException if the file cannot be read
     */
    public static @NotNull DocumentationData load(@NotNull Path path, @NotNull String by) throws IOException {
        return parse(LumenDoc.readCompressed(path), by);
    }

    /**
     * Merges two documentation data instances into one, concatenating all entry lists.
     *
     * @param a the first documentation data
     * @param b the second documentation data
     * @return the merged documentation data
     */
    public static @NotNull DocumentationData merge(@NotNull DocumentationData a, @NotNull DocumentationData b) {
        List<PatternEntry> statements = new ArrayList<>(a.statements());
        statements.addAll(b.statements());
        List<PatternEntry> expressions = new ArrayList<>(a.expressions());
        expressions.addAll(b.expressions());
        List<PatternEntry> conditions = new ArrayList<>(a.conditions());
        conditions.addAll(b.conditions());
        List<BlockEntry> blocks = new ArrayList<>(a.blocks());
        blocks.addAll(b.blocks());
        List<PatternEntry> loopSources = new ArrayList<>(a.loopSources());
        loopSources.addAll(b.loopSources());
        List<EventEntry> events = new ArrayList<>(a.events());
        events.addAll(b.events());
        List<TypeBindingEntry> typeBindings = new ArrayList<>(a.typeBindings());
        typeBindings.addAll(b.typeBindings());
        String version = b.version() != null ? b.version() : a.version();
        return new DocumentationData(version, statements, expressions, conditions,
                blocks, loopSources, events, typeBindings);
    }

    /**
     * Parses the full documentation data from a raw JSON string.
     *
     * @param json the raw JSON content
     * @param by   the addon name used as the default author
     * @return the parsed documentation data
     */
    private static @NotNull DocumentationData parse(@NotNull String json, @NotNull String by) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        String version = string(root, "version");

        List<PatternEntry> statements = patternEntries(root, "statements", by);
        List<PatternEntry> expressions = patternEntries(root, "expressions", by);
        List<PatternEntry> conditions = patternEntries(root, "conditions", by);
        List<BlockEntry> blocks = blockEntries(root, by);
        List<PatternEntry> loopSources = patternEntries(root, "loopSources", by);
        List<EventEntry> events = eventEntries(root, by);
        List<TypeBindingEntry> typeBindings = typeBindingEntries(root);

        return new DocumentationData(version, statements, expressions, conditions, blocks, loopSources, events, typeBindings);
    }

    /**
     * Reads a list of pattern entries from a named array within the root JSON object.
     *
     * @param root      the root JSON object
     * @param key       the array key to read from
     * @param defaultBy the default author if not present in the entry
     * @return the list of parsed pattern entries
     */
    private static @NotNull List<PatternEntry> patternEntries(@NotNull JsonObject root, @NotNull String key, @NotNull String defaultBy) {
        List<PatternEntry> result = new ArrayList<>();
        JsonArray arr = root.getAsJsonArray(key);
        if (arr == null) return result;
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            String by = string(obj, "by");
            if (by == null) by = defaultBy;
            result.add(new PatternEntry(
                    stringList(obj, "patterns"),
                    by,
                    string(obj, "description"),
                    stringList(obj, "examples"),
                    string(obj, "since"),
                    string(obj, "category"),
                    bool(obj, "deprecated"),
                    string(obj, "returnRefTypeId"),
                    string(obj, "returnJavaType")
            ));
        }
        return result;
    }

    /**
     * Reads all block entries from the root JSON object, including any block-provided variables.
     *
     * @param root      the root JSON object
     * @param defaultBy the default author if not present in the entry
     * @return the list of parsed block entries
     */
    private static @NotNull List<BlockEntry> blockEntries(@NotNull JsonObject root, @NotNull String defaultBy) {
        List<BlockEntry> result = new ArrayList<>();
        JsonArray arr = root.getAsJsonArray("blocks");
        if (arr == null) return result;
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();

            List<BlockVariable> blockVars = null;
            JsonArray bvArr = obj.getAsJsonArray("variables");
            if (bvArr != null) {
                blockVars = new ArrayList<>();
                for (JsonElement bvEl : bvArr) {
                    JsonObject bvo = bvEl.getAsJsonObject();
                    String bvName = string(bvo, "name");
                    if (bvName == null) continue;
                    String bvType = string(bvo, "type");
                    if (bvType == null) bvType = "Object";
                    boolean bvNullable = bool(bvo, "nullable");
                    String bvDesc = string(bvo, "description");
                    Map<String, String> bvMeta = stringMap(bvo, "metadata");
                    String bvRefType = string(bvo, "refType");
                    blockVars.add(new BlockVariable(bvName, bvType, bvNullable, bvDesc, bvMeta, bvRefType));
                }
            }

            String by = string(obj, "by");
            if (by == null) by = defaultBy;

            result.add(new BlockEntry(
                    stringList(obj, "patterns"),
                    by,
                    string(obj, "description"),
                    stringList(obj, "examples"),
                    string(obj, "since"),
                    string(obj, "category"),
                    bool(obj, "deprecated"),
                    blockVars,
                    obj.has("supportsRootLevel") && bool(obj, "supportsRootLevel"),
                    !obj.has("supportsBlock") || bool(obj, "supportsBlock")
            ));
        }
        return result;
    }

    /**
     * Reads all event entries from the root JSON object.
     *
     * @param root      the root JSON object
     * @param defaultBy the default author if not present in the entry
     * @return the list of parsed event entries
     */
    private static @NotNull List<EventEntry> eventEntries(@NotNull JsonObject root, @NotNull String defaultBy) {
        List<EventEntry> result = new ArrayList<>();
        JsonArray arr = root.getAsJsonArray("events");
        if (arr == null) return result;
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            String name = string(obj, "name");
            if (name == null) continue;
            String by = string(obj, "by");
            if (by == null) by = defaultBy;
            String className = string(obj, "className");
            String description = string(obj, "description");
            List<String> examples = stringList(obj, "examples");
            String since = string(obj, "since");
            String category = string(obj, "category");
            boolean deprecated = bool(obj, "deprecated");
            boolean advanced = bool(obj, "advanced");

            List<EventVariable> variables = new ArrayList<>();
            JsonArray vars = obj.getAsJsonArray("variables");
            if (vars != null) {
                for (JsonElement v : vars) {
                    JsonObject vo = v.getAsJsonObject();
                    String vName = string(vo, "name");
                    if (vName == null) continue;
                    String vDesc = string(vo, "description");
                    boolean vNullable = bool(vo, "nullable");
                    Map<String, String> vMeta = stringMap(vo, "metadata");
                    variables.add(new EventVariable(
                            vName, string(vo, "javaType"), string(vo, "refTypeId"),
                            vDesc, vNullable, vMeta));
                }
            }

            List<String> interfaces = stringList(obj, "interfaces");
            List<String> fields = stringList(obj, "fields");

            result.add(new EventEntry(name, by, className, description, examples, since, category, deprecated, advanced, variables, interfaces, fields));
        }
        return result;
    }

    /**
     * Reads all type binding entries from the root JSON object.
     *
     * @param root the root JSON object
     * @return the list of parsed type binding entries
     */
    private static @NotNull List<TypeBindingEntry> typeBindingEntries(@NotNull JsonObject root) {
        List<TypeBindingEntry> result = new ArrayList<>();
        JsonArray arr = root.getAsJsonArray("typeBindings");
        if (arr == null) return result;
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            String id = string(obj, "id");
            if (id == null) continue;
            result.add(new TypeBindingEntry(
                    id, string(obj, "description"), string(obj, "javaType"),
                    stringList(obj, "examples"), string(obj, "since"), bool(obj, "deprecated")
            ));
        }
        return result;
    }

    /**
     * Reads a nullable string value from a JSON object by key.
     *
     * @param obj the JSON object to read from
     * @param key the key to look up
     * @return the string value, or null if absent or null
     */
    private static @Nullable String string(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        return el.getAsString();
    }

    /**
     * Reads a list of strings from a JSON array by key, returning an empty list if absent.
     *
     * @param obj the JSON object to read from
     * @param key the key to look up
     * @return the list of string values
     */
    private static @NotNull List<String> stringList(@NotNull JsonObject obj, @NotNull String key) {
        List<String> list = new ArrayList<>();
        JsonArray arr = obj.getAsJsonArray(key);
        if (arr == null) return list;
        for (JsonElement el : arr) {
            if (!el.isJsonNull()) list.add(el.getAsString());
        }
        return list;
    }

    /**
     * Reads a boolean value from a JSON object by key, defaulting to false if absent.
     *
     * @param obj the JSON object to read from
     * @param key the key to look up
     * @return the boolean value, or false if absent or null
     */
    private static boolean bool(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return false;
        return el.getAsBoolean();
    }

    /**
     * Reads a string to string map from a JSON object by key, returning null if absent.
     *
     * @param obj the JSON object to read from
     * @param key the key to look up
     * @return the string map, or null if absent
     */
    private static @Nullable Map<String, String> stringMap(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonObject()) return null;
        JsonObject mapObj = el.getAsJsonObject();
        Map<String, String> map = new HashMap<>();
        for (var entry : mapObj.entrySet()) {
            if (!entry.getValue().isJsonNull()) {
                map.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return map;
    }
}
