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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and parses a documentation.json file into structured {@link DocumentationData}.
 */
public final class DocumentationLoader {

    private DocumentationLoader() {
    }

    /**
     * Loads documentation data from a file at the given path.
     *
     * @param path the path to the JSON file
     * @return the parsed documentation data
     * @throws IOException if the file cannot be read
     */
    public static @NotNull DocumentationData load(@NotNull Path path) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        return parse(json);
    }

    /**
     * Parses the full documentation data from a raw JSON string.
     *
     * @param json the raw JSON content
     * @return the parsed documentation data
     */
    private static @NotNull DocumentationData parse(@NotNull String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        String version = string(root, "version");

        List<PatternEntry> statements = patternEntries(root, "statements");
        List<PatternEntry> expressions = patternEntries(root, "expressions");
        List<PatternEntry> conditions = patternEntries(root, "conditions");
        List<BlockEntry> blocks = blockEntries(root);
        List<PatternEntry> loopSources = patternEntries(root, "loopSources");
        List<EventEntry> events = eventEntries(root);
        List<TypeBindingEntry> typeBindings = typeBindingEntries(root);

        return new DocumentationData(version, statements, expressions, conditions, blocks, loopSources, events, typeBindings);
    }

    /**
     * Reads a list of pattern entries from a named array within the root JSON object.
     *
     * @param root the root JSON object
     * @param key  the array key to read from
     * @return the list of parsed pattern entries
     */
    private static @NotNull List<PatternEntry> patternEntries(@NotNull JsonObject root, @NotNull String key) {
        List<PatternEntry> result = new ArrayList<>();
        JsonArray arr = root.getAsJsonArray(key);
        if (arr == null) return result;
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            result.add(new PatternEntry(
                    stringList(obj, "patterns"),
                    string(obj, "by"),
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
     * @param root the root JSON object
     * @return the list of parsed block entries
     */
    private static @NotNull List<BlockEntry> blockEntries(@NotNull JsonObject root) {
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

            result.add(new BlockEntry(
                    stringList(obj, "patterns"),
                    string(obj, "by"),
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
     * @param root the root JSON object
     * @return the list of parsed event entries
     */
    private static @NotNull List<EventEntry> eventEntries(@NotNull JsonObject root) {
        List<EventEntry> result = new ArrayList<>();
        JsonArray arr = root.getAsJsonArray("events");
        if (arr == null) return result;
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            String name = string(obj, "name");
            if (name == null) continue;
            String by = string(obj, "by");
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
