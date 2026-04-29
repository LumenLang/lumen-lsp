package dev.lumenlang.lumen.lsp.analysis.util;

import dev.lumenlang.lumen.pipeline.codegen.BlockContext;
import dev.lumenlang.lumen.pipeline.codegen.TypeEnv;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reflection based deep copy for the Lumen TypeEnv and BlockContext.
 *
 * <p>The pipeline does not expose a public deep copy method, so this class walks
 * every declared field of TypeEnv and BlockContext and rebuilds an equivalent
 * graph that can later be swapped back when a line fails or when a cached
 * after state is replayed.
 *
 * <p>This class must be replaced by a TypeEnv.deepCopy() method in Lumen 1.3.0
 * once available upstream.
 */
public final class EnvCopier {

    private static final @NotNull List<Field> TYPE_ENV_FIELDS = fields(TypeEnv.class);
    private static final @NotNull List<Field> BLOCK_CONTEXT_FIELDS = fields(BlockContext.class);

    private EnvCopier() {
    }

    /**
     * Returns a deep copy of the given environment, independent of the source.
     *
     * @param source the environment to copy
     * @return the copy
     */
    public static @NotNull TypeEnv copy(@NotNull TypeEnv source) {
        TypeEnv target = new TypeEnv();
        copyFields(source, target, TYPE_ENV_FIELDS, new IdentityHashMap<>());
        return target;
    }

    /**
     * Overwrites the destination in place with the contents of the snapshot,
     * keeping the destination instance identity for callers already holding it.
     *
     * @param destination the live environment to restore
     * @param snapshot    a deep copy taken earlier
     */
    public static void restore(@NotNull TypeEnv destination, @NotNull TypeEnv snapshot) {
        copyFields(snapshot, destination, TYPE_ENV_FIELDS, new IdentityHashMap<>());
    }

    private static void copyFields(@NotNull Object source, @NotNull Object target, @NotNull List<Field> fields, @NotNull IdentityHashMap<Object, Object> seen) {
        for (Field field : fields) {
            try {
                Object cloned = clone(field.get(source), seen);
                Object existing = field.get(target);
                if (existing instanceof Collection<?> destCol && cloned instanceof Collection<?> repCol) {
                    refill(destCol, repCol);
                } else if (existing instanceof Map<?, ?> destMap && cloned instanceof Map<?, ?> repMap) {
                    refill(destMap, repMap);
                } else {
                    field.set(target, cloned);
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Failed to copy field " + field.getName() + " on " + source.getClass().getSimpleName(), e);
            }
        }
    }

    private static @Nullable Object clone(@Nullable Object value, @NotNull IdentityHashMap<Object, Object> seen) {
        if (value == null) return null;
        Object cached = seen.get(value);
        if (cached != null) return cached;
        if (value instanceof BlockContext block) return cloneBlockContext(block, seen);
        if (value instanceof Map<?, ?> map) return cloneMap(map, seen);
        if (value instanceof Set<?> set) return cloneSet(set, seen);
        if (value instanceof List<?> list) return cloneList(list, seen);
        return value;
    }

    private static @NotNull BlockContext cloneBlockContext(@NotNull BlockContext source, @NotNull IdentityHashMap<Object, Object> seen) {
        BlockContext parentClone = source.parent() == null ? null : cloneBlockContext(source.parent(), seen);
        BlockContext clone = new BlockContext(source.node(), parentClone, source.siblings(), source.index());
        seen.put(source, clone);
        copyFields(source, clone, BLOCK_CONTEXT_FIELDS, seen);
        return clone;
    }

    private static @NotNull Map<Object, Object> cloneMap(@NotNull Map<?, ?> source, @NotNull IdentityHashMap<Object, Object> seen) {
        Map.Entry<?, ?>[] snapshot;
        synchronized (source) {
            snapshot = source.entrySet().toArray(new Map.Entry<?, ?>[0]);
        }
        Map<Object, Object> result = new HashMap<>(snapshot.length);
        seen.put(source, result);
        for (Map.Entry<?, ?> entry : snapshot) {
            result.put(clone(entry.getKey(), seen), clone(entry.getValue(), seen));
        }
        return result;
    }

    private static @NotNull Set<Object> cloneSet(@NotNull Set<?> source, @NotNull IdentityHashMap<Object, Object> seen) {
        Object[] snapshot;
        synchronized (source) {
            snapshot = source.toArray();
        }
        Set<Object> result = new HashSet<>(snapshot.length);
        seen.put(source, result);
        for (Object element : snapshot) {
            result.add(clone(element, seen));
        }
        return result;
    }

    private static @NotNull List<Object> cloneList(@NotNull List<?> source, @NotNull IdentityHashMap<Object, Object> seen) {
        Object[] snapshot;
        synchronized (source) {
            snapshot = source.toArray();
        }
        List<Object> result = new ArrayList<>(snapshot.length);
        seen.put(source, result);
        for (Object element : snapshot) {
            result.add(clone(element, seen));
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void refill(@NotNull Collection target, @NotNull Collection source) {
        target.clear();
        target.addAll(source);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void refill(@NotNull Map target, @NotNull Map source) {
        target.clear();
        target.putAll(source);
    }

    private static @NotNull List<Field> fields(@NotNull Class<?> type) {
        List<Field> result = new ArrayList<>();
        Class<?> cursor = type;
        while (cursor != null && cursor != Object.class) {
            for (Field field : cursor.getDeclaredFields()) {
                if ((field.getModifiers() & Modifier.STATIC) != 0) continue;
                field.setAccessible(true);
                result.add(field);
            }
            cursor = cursor.getSuperclass();
        }
        return result;
    }
}
