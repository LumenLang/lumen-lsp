package dev.lumenlang.lumen.lsp.typebindings;

import dev.lumenlang.lumen.api.LumenAPI;
import dev.lumenlang.lumen.api.annotations.Call;
import dev.lumenlang.lumen.api.annotations.Registration;
import dev.lumenlang.lumen.api.codegen.CodegenAccess;
import dev.lumenlang.lumen.api.codegen.EnvironmentAccess;
import dev.lumenlang.lumen.api.codegen.EnvironmentAccess.VarHandle;
import dev.lumenlang.lumen.api.exceptions.ParseFailureException;
import dev.lumenlang.lumen.api.type.AddonTypeBinding;
import dev.lumenlang.lumen.api.type.EnumTypeBinding;
import dev.lumenlang.lumen.api.type.RegistryTypeBinding;
import dev.lumenlang.lumen.api.type.TypeBindingMeta;
import dev.lumenlang.lumen.api.type.Types;
import dev.lumenlang.lumen.pipeline.logger.LumenLogger;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * Registers all default type bindings from the Lumen language into the LSP type registry.
 *
 * <p>This is the LSP counterpart of the runtime DefaultTypeBindings. It provides the same
 * type identifiers and logic so pattern matcher can work properly.
 * Note, it does remove some type bindings, those related to the Bukkit registry, to prevent showing outdated registries or more.
 */
@Registration(order = -1000)
@SuppressWarnings({"unused", "DataFlowIssue"})
public final class DefaultTypeBindings {

    /**
     * Attempts to register an enum type binding, logging a warning on failure.
     *
     * @param api       the Lumen API to register with
     * @param typeId    the type identifier
     * @param enumClass the enum class to bind
     * @param fqcn      the fully qualified class name of the enum
     * @param <E>       the enum type
     */
    private static <E extends Enum<E>> void tryRegisterEnum(
            @NotNull LumenAPI api,
            @NotNull String typeId,
            @NotNull Class<E> enumClass,
            @NotNull String fqcn) {
        try {
            api.types().register(EnumTypeBinding.of(typeId, enumClass, fqcn));
        } catch (Exception e) {
            LumenLogger.warning("Skipping enum type binding '" + typeId + "' (" + fqcn + "): " + e.getMessage());
        }
    }

    /**
     * Attempts to register a registry type binding from static fields, logging a warning on failure.
     *
     * @param api    the Lumen API to register with
     * @param typeId the type identifier
     * @param clazz  the class containing static fields to bind
     * @param fqcn   the fully qualified class name
     */
    private static void tryRegisterRegistryType(
            @NotNull LumenAPI api,
            @NotNull String typeId,
            @NotNull Class<?> clazz,
            @NotNull String fqcn) {
        try {
            api.types().register(RegistryTypeBinding.fromStaticFields(typeId, clazz, fqcn));
        } catch (Exception e) {
            LumenLogger.warning("Skipping registry type binding '" + typeId + "' (" + fqcn + "): " + e.getMessage());
        }
    }

    /**
     * Formats a double value for clean Java source output.
     *
     * <p>
     * Whole numbers produce a trailing {@code .0} (e.g. {@code 20.0}).
     * Fractional values are rendered with up to 6 significant decimal digits,
     * with trailing zeros stripped.
     *
     * @param d the double value
     * @return a clean representation suitable for Java source code
     */
    private static @NotNull String formatDouble(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) {
            long whole = (long) d;
            return whole + ".0";
        }
        String formatted = String.format(Locale.ROOT, "%.6f", d);
        formatted = formatted.replaceAll("0+$", "");
        if (formatted.endsWith(".")) {
            formatted += "0";
        }
        return formatted;
    }

    /**
     * Registers all default type bindings for the Lumen language.
     *
     * @param api the Lumen API to register types with
     */
    @Call
    public static void register(@NotNull LumenAPI api) {
        registerInt(api);
        registerLong(api);
        registerDouble(api);
        registerNumber(api);
        registerBoolean(api);
        registerPlayer(api);
        registerOfflinePlayer(api);
        registerCond(api);
        registerOp(api);
        registerEntity(api);
        registerItemStack(api);
        registerLocation(api);
        registerWorld(api);
        registerList(api);
        registerMap(api);
        registerData(api);
        registerBlock(api);
    }

    /**
     * Registers the {@code int} type binding and its expressions.
     *
     * @param api the Lumen API
     */
    private static void registerInt(@NotNull LumenAPI api) {
        api.types().register(new AddonTypeBinding() {
            @Override
            public @NotNull String id() {
                return "INT";
            }

            @Override
            public @NotNull TypeBindingMeta meta() {
                return new TypeBindingMeta(
                        "Parses an integer number from a single token or a variable reference, supporting modulo with %.",
                        "int",
                        List.of("give player diamond %amt:INT%"),
                        "1.0.0",
                        false);
            }

            @Override
            public int consumeCount(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                if (tokens.isEmpty())
                    throw new ParseFailureException("INT requires at least one token");
                if (tokens.size() >= 2 && tokens.get(1).equals("%"))
                    return 2;
                return 1;
            }

            @Override
            public Object parse(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                String text = tokens.get(0);
                try {
                    return Integer.parseInt(text);
                } catch (NumberFormatException e) {
                    VarHandle ref = env.lookupVar(text);
                    if (ref != null)
                        return ref;
                    throw new ParseFailureException("Not a valid integer: " + text);
                }
            }

            @Override
            public @NotNull String toJava(Object value, @NotNull CodegenAccess ctx,
                                          @NotNull EnvironmentAccess env) {
                if (value instanceof VarHandle ref) {
                    return "Coerce.toInt(" + ref.java() + ")";
                }
                return value.toString();
            }
        });
    }

    /**
     * Registers the {@code long} type binding and its expressions.
     *
     * @param api the Lumen API
     */
    private static void registerLong(@NotNull LumenAPI api) {
        api.types().register(new AddonTypeBinding() {
            @Override
            public @NotNull String id() {
                return "LONG";
            }

            @Override
            public @NotNull TypeBindingMeta meta() {
                return new TypeBindingMeta(
                        "Parses a long integer from a single token or a variable reference. Useful for large numeric values that exceed the int range.",
                        "long",
                        List.of("set %var:EXPR% to %val:LONG%"),
                        "1.0.0",
                        false);
            }

            @Override
            public Object parse(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                String text = tokens.get(0);
                if (text.endsWith("L") || text.endsWith("l")) {
                    text = text.substring(0, text.length() - 1);
                }
                try {
                    return Long.parseLong(text);
                } catch (NumberFormatException e) {
                    VarHandle ref = env.lookupVar(tokens.get(0));
                    if (ref != null)
                        return ref;
                    throw e;
                }
            }

            @Override
            public @NotNull String toJava(Object value, @NotNull CodegenAccess ctx,
                                          @NotNull EnvironmentAccess env) {
                if (value instanceof VarHandle ref) {
                    return "((long) Coerce.toDouble(" + ref.java() + "))";
                }
                return value + "L";
            }
        });
    }

    /**
     * Registers the {@code double} type binding and its expressions.
     *
     * @param api the Lumen API
     */
    private static void registerDouble(@NotNull LumenAPI api) {
        api.types().register(new AddonTypeBinding() {
            @Override
            public @NotNull String id() {
                return "DOUBLE";
            }

            @Override
            public @NotNull TypeBindingMeta meta() {
                return new TypeBindingMeta(
                        "Parses a decimal number from a single token or a variable reference. Trailing zeros are stripped for cleaner output (e.g. 20.50 becomes 20.5).",
                        "double",
                        List.of("set %e:ENTITY% max_health [to] %val:DOUBLE%"),
                        "1.0.0",
                        false);
            }

            @Override
            public Object parse(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                String text = tokens.get(0);
                try {
                    return Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    VarHandle ref = env.lookupVar(text);
                    if (ref != null)
                        return ref;
                    throw e;
                }
            }

            @Override
            public @NotNull String toJava(Object value, @NotNull CodegenAccess ctx,
                                          @NotNull EnvironmentAccess env) {
                if (value instanceof VarHandle ref) {
                    return "Coerce.toDouble(" + ref.java() + ")";
                }
                return formatDouble((Double) value);
            }
        });
    }

    /**
     * Registers the {@code number} type binding and its expressions.
     *
     * @param api the Lumen API
     */
    private static void registerNumber(@NotNull LumenAPI api) {
        api.types().register(new AddonTypeBinding() {
            @Override
            public @NotNull String id() {
                return "NUMBER";
            }

            @Override
            public @NotNull TypeBindingMeta meta() {
                return new TypeBindingMeta(
                        "Parses any numeric literal (int, long, or double) from a single token or a variable reference. Automatically detects the appropriate numeric type based on the token content.",
                        "Number",
                        List.of("set %var:EXPR% to %val:NUMBER%"),
                        "1.0.0",
                        false);
            }

            @Override
            public Object parse(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                String text = tokens.get(0);
                if (text.endsWith("L") || text.endsWith("l")) {
                    try {
                        return Long.parseLong(text.substring(0, text.length() - 1));
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (text.contains(".")) {
                    try {
                        return Double.parseDouble(text);
                    } catch (NumberFormatException ignored) {
                    }
                }
                try {
                    return Integer.parseInt(text);
                } catch (NumberFormatException e) {
                    try {
                        return Long.parseLong(text);
                    } catch (NumberFormatException e2) {
                        VarHandle ref = env.lookupVar(text);
                        if (ref != null)
                            return ref;
                        throw new ParseFailureException("Not a valid number: " + text);
                    }
                }
            }

            @Override
            public @NotNull String toJava(Object value, @NotNull CodegenAccess ctx,
                                          @NotNull EnvironmentAccess env) {
                if (value instanceof VarHandle ref) {
                    return "Coerce.toDouble(" + ref.java() + ")";
                }
                if (value instanceof Double d) {
                    return formatDouble(d);
                }
                if (value instanceof Long l) {
                    return l + "L";
                }
                return value.toString();
            }
        });
    }

    /**
     * Registers the {@code boolean} type binding and its expressions.
     *
     * @param api the Lumen API
     */
    private static void registerBoolean(@NotNull LumenAPI api) {
        api.types().register(new AddonTypeBinding() {
            @Override
            public @NotNull String id() {
                return "BOOLEAN";
            }

            @Override
            public @NotNull TypeBindingMeta meta() {
                return new TypeBindingMeta(
                        "Parses a boolean from a single token using truthiness: true/yes/on map to true, and false/no/off map to false.",
                        "boolean",
                        List.of("set %e:ENTITY% gravity [to] %val:BOOLEAN%"),
                        "1.0.0",
                        false);
            }

            @Override
            public int consumeCount(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                if (tokens.isEmpty())
                    throw new ParseFailureException("BOOLEAN requires at least one token");
                String raw = tokens.get(0).replace("\"", "").toLowerCase(Locale.ROOT);
                if (raw.equals("true") || raw.equals("yes") || raw.equals("on"))
                    return 1;
                if (raw.equals("false") || raw.equals("no") || raw.equals("off"))
                    return 1;
                if (env.lookupVar(tokens.get(0)) != null)
                    return 1;
                throw new ParseFailureException("Unknown boolean value: " + tokens.get(0));
            }

            @Override
            public Object parse(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                String raw = tokens.get(0).replace("\"", "").toLowerCase(Locale.ROOT);
                if (raw.equals("true") || raw.equals("yes") || raw.equals("on"))
                    return Boolean.TRUE;
                if (raw.equals("false") || raw.equals("no") || raw.equals("off"))
                    return Boolean.FALSE;
                VarHandle ref = env.lookupVar(tokens.get(0));
                if (ref != null)
                    return ref;
                throw new ParseFailureException("Unknown boolean value: " + tokens.get(0));
            }

            @Override
            public @NotNull String toJava(Object value, @NotNull CodegenAccess ctx,
                                          @NotNull EnvironmentAccess env) {
                if (value instanceof VarHandle ref) {
                    return "Boolean.parseBoolean(String.valueOf(" + ref.java() + "))";
                }
                return value.toString();
            }
        });
    }

    /**
     * Registers the {@code player} type binding and its expressions.
     *
     * @param api the Lumen API
     */
    private static void registerPlayer(@NotNull LumenAPI api) {
        api.types().register(new AddonTypeBinding() {
            @Override
            public @NotNull String id() {
                return "PLAYER";
            }

            @Override
            public @NotNull TypeBindingMeta meta() {
                return new TypeBindingMeta(
                        "Resolves a player reference from a variable name. Does not accept possessive syntax.",
                        "org.bukkit.entity.Player",
                        List.of("message %who:PLAYER% \"Hello!\"", "kill player"),
                        "1.0.0",
                        false);
            }

            @Override
            public Object parse(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                String raw = tokens.get(0);
                if (raw.endsWith("'s"))
                    throw new ParseFailureException("PLAYER does not accept possessive form: " + raw);

                VarHandle ref = env.lookupVar(raw);
                if (ref == null)
                    throw new ParseFailureException("Unknown player reference: " + raw);
                if (!isPlayer(ref))
                    throw new ParseFailureException(raw + " is not a player");
                return ref;
            }

            @Override
            public @NotNull String toJava(Object v, @NotNull CodegenAccess ctx,
                                          @NotNull EnvironmentAccess env) {
                if (v == null)
                    throw new RuntimeException("Cannot generate Java for null player reference");
                return ((VarHandle) v).java();
            }

            private boolean isPlayer(@NotNull VarHandle ref) {
                return ref.type() != null && ref.type().id().equals(Types.PLAYER.id());
            }
        });

        api.types().register(new AddonTypeBinding() {
            @Override
            public @NotNull String id() {
                return "PLAYER_POSSESSIVE";
            }

            @Override
            public @NotNull TypeBindingMeta meta() {
                return new TypeBindingMeta(
                        "Resolves a player reference from a possessive token (e.g. player's). The token must end with 's.",
                        "org.bukkit.entity.Player",
                        List.of("%who:PLAYER_POSSESSIVE% name", "%who:PLAYER_POSSESSIVE% health"),
                        "1.0.0",
                        false);
            }

            @Override
            public Object parse(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                String raw = tokens.get(0);
                if (!raw.endsWith("'s"))
                    throw new ParseFailureException("PLAYER_POSSESSIVE requires possessive form (e.g. player's): " + raw);
                String name = raw.substring(0, raw.length() - 2);

                VarHandle ref = env.lookupVar(name);
                if (ref == null)
                    throw new ParseFailureException("Unknown player reference: " + name);
                if (!isPlayer(ref))
                    throw new ParseFailureException(name + " is not a player");
                return ref;
            }

            @Override
            public @NotNull String toJava(Object v, @NotNull CodegenAccess ctx,
                                          @NotNull EnvironmentAccess env) {
                if (v == null)
                    throw new RuntimeException("Cannot generate Java for null player reference");
                return ((VarHandle) v).java();
            }

            private boolean isPlayer(@NotNull VarHandle ref) {
                return ref.type() != null && ref.type().id().equals(Types.PLAYER.id());
            }
        });
    }

    /**
     * Registers the {@code offlineplayer} type binding and its expressions.
     *
     * @param api the Lumen API
     */
    private static void registerOfflinePlayer(@NotNull LumenAPI api) {
        api.types().register(new AddonTypeBinding() {
            @Override
            public @NotNull String id() {
                return "OFFLINE_PLAYER";
            }

            @Override
            public @NotNull TypeBindingMeta meta() {
                return new TypeBindingMeta(
                        "Resolves an offline player reference. Does not accept possessive syntax.",
                        "org.bukkit.OfflinePlayer",
                        List.of("ban %target:OFFLINE_PLAYER%"),
                        "1.0.0",
                        false);
            }

            @Override
            public Object parse(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                String raw = tokens.get(0);
                if (raw.endsWith("'s"))
                    throw new ParseFailureException("OFFLINE_PLAYER does not accept possessive form: " + raw);

                VarHandle ref = env.lookupVar(raw);
                if (ref == null)
                    throw new ParseFailureException("Unknown offline player reference: " + raw);
                if (!isOfflinePlayer(ref))
                    throw new ParseFailureException(raw + " is not an offline player");
                return ref;
            }

            @Override
            public @NotNull String toJava(Object v, @NotNull CodegenAccess ctx,
                                          @NotNull EnvironmentAccess env) {
                if (v == null)
                    throw new RuntimeException("Cannot generate Java for null offline player reference");
                return ((VarHandle) v).java();
            }

            private boolean isOfflinePlayer(@NotNull VarHandle ref) {
                return ref.type() != null && ref.type().id().equals(Types.OFFLINE_PLAYER.id());
            }
        });

        api.types().register(new AddonTypeBinding() {
            @Override
            public @NotNull String id() {
                return "OFFLINE_PLAYER_POSSESSIVE";
            }

            @Override
            public @NotNull TypeBindingMeta meta() {
                return new TypeBindingMeta(
                        "Resolves an offline player reference from a possessive token (e.g. offlinePlayer's). The token must end with 's.",
                        "org.bukkit.OfflinePlayer",
                        List.of("%who:OFFLINE_PLAYER_POSSESSIVE% name"),
                        "1.0.0",
                        false);
            }

            @Override
            public Object parse(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                String raw = tokens.get(0);
                if (!raw.endsWith("'s"))
                    throw new ParseFailureException("OFFLINE_PLAYER_POSSESSIVE requires possessive form (e.g. player's): " + raw);
                String name = raw.substring(0, raw.length() - 2);

                VarHandle ref = env.lookupVar(name);
                if (ref == null)
                    throw new ParseFailureException("Unknown offline player reference: " + name);
                if (!isOfflinePlayer(ref))
                    throw new ParseFailureException(name + " is not an offline player");
                return ref;
            }

            @Override
            public @NotNull String toJava(Object v, @NotNull CodegenAccess ctx,
                                          @NotNull EnvironmentAccess env) {
                if (v == null)
                    throw new RuntimeException("Cannot generate Java for null offline player reference");
                return ((VarHandle) v).java();
            }

            private boolean isOfflinePlayer(@NotNull VarHandle ref) {
                return ref.type() != null && ref.type().id().equals(Types.OFFLINE_PLAYER.id());
            }
        });
    }

    /**
     * Registers the {@code cond} (condition) type binding and its expressions.
     *
     * @param api the Lumen API
     */
    private static void registerCond(@NotNull LumenAPI api) {
        api.types().register(new AddonTypeBinding() {
            @Override
            public @NotNull String id() {
                return "COND";
            }

            @Override
            public @NotNull TypeBindingMeta meta() {
                return new TypeBindingMeta(
                        "Captures all remaining tokens as a raw condition string. Used internally for conditional expressions.",
                        "String",
                        List.of(),
                        "1.0.0",
                        false);
            }

            @Override
            public Object parse(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                return String.join(" ", tokens);
            }

            @Override
            public int consumeCount(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                return -1;
            }

            @Override
            public @NotNull String toJava(Object v, @NotNull CodegenAccess ctx,
                                          @NotNull EnvironmentAccess env) {
                return (String) v;
            }
        });
    }

    /**
     * Registers the {@code op} (operator) type binding and its expressions.
     *
     * @param api the Lumen API
     */
    private static void registerOp(@NotNull LumenAPI api) {
        api.types().register(new AddonTypeBinding() {
            @Override
            public @NotNull String id() {
                return "OP";
            }

            @Override
            public @NotNull TypeBindingMeta meta() {
                return new TypeBindingMeta(
                        "Parses a comparison operator from natural language (e.g. 'greater than', 'is', '==') into a Java operator string.",
                        "String",
                        List.of("%a:EXPR% %op:OP% %b:EXPR%"),
                        "1.0.0",
                        false);
            }

            @Override
            public int consumeCount(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                if (tokens.isEmpty())
                    throw new ParseFailureException("OP requires at least one token");
                String first = tokens.get(0).toLowerCase(Locale.ROOT);
                if (tokens.size() >= 3) {
                    String second = tokens.get(1).toLowerCase(Locale.ROOT);
                    String third = tokens.get(2).toLowerCase(Locale.ROOT);
                    if (first.equals("is") && second.equals("greater") && third.equals("than")) {
                        if (tokens.size() >= 6
                                && tokens.get(3).toLowerCase(Locale.ROOT).equals("or")
                                && tokens.get(4).toLowerCase(Locale.ROOT).equals("equal")
                                && tokens.get(5).toLowerCase(Locale.ROOT).equals("to"))
                            return 6;
                        return 3;
                    }
                    if (first.equals("is") && second.equals("less") && third.equals("than")) {
                        if (tokens.size() >= 6
                                && tokens.get(3).toLowerCase(Locale.ROOT).equals("or")
                                && tokens.get(4).toLowerCase(Locale.ROOT).equals("equal")
                                && tokens.get(5).toLowerCase(Locale.ROOT).equals("to"))
                            return 6;
                        return 3;
                    }
                    if (first.equals("greater") && second.equals("than") && third.equals("or") && tokens.size() >= 5) {
                        String fourth = tokens.get(3).toLowerCase(Locale.ROOT);
                        if (fourth.equals("equal") && tokens.get(4).toLowerCase(Locale.ROOT).equals("to"))
                            return 5;
                    }
                    if (first.equals("less") && second.equals("than") && third.equals("or") && tokens.size() >= 5) {
                        String fourth = tokens.get(3).toLowerCase(Locale.ROOT);
                        if (fourth.equals("equal") && tokens.get(4).toLowerCase(Locale.ROOT).equals("to"))
                            return 5;
                    }
                    if (first.equals("not") && second.equals("equal") && third.equals("to"))
                        return 3;
                    if (first.equals("is") && second.equals("not") && third.equals("equal")) {
                        if (tokens.size() >= 4 && tokens.get(3).toLowerCase(Locale.ROOT).equals("to"))
                            return 4;
                    }
                }
                if (tokens.size() >= 2) {
                    String second = tokens.get(1).toLowerCase(Locale.ROOT);
                    if (first.equals("greater") && second.equals("than"))
                        return 2;
                    if (first.equals("less") && second.equals("than"))
                        return 2;
                    if (first.equals("equal") && second.equals("to"))
                        return 2;
                    if (first.equals("not") && second.equals("equal"))
                        return 2;
                    if (first.equals("is") && second.equals("not"))
                        return 2;
                    if (second.equals("=")
                            && (first.equals(">") || first.equals("<") || first.equals("=") || first.equals("!")))
                        return 2;
                }
                return 1;
            }

            @Override
            public Object parse(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                if (tokens.size() == 2) {
                    String combined = tokens.get(0) + tokens.get(1);
                    if (combined.equals(">=") || combined.equals("<=") || combined.equals("==")
                            || combined.equals("!="))
                        return combined;
                }
                String joined = String.join(" ", tokens).toLowerCase(Locale.ROOT);
                return switch (joined) {
                    case "==", "equals", "is", "equal to" -> "==";
                    case "!=", "not equal", "not equal to", "is not", "is not equal to" -> "!=";
                    case "<", "less than", "is less than" -> "<";
                    case ">", "greater than", "is greater than" -> ">";
                    case "<=", "less than or equal to", "is less than or equal to" -> "<=";
                    case ">=", "greater than or equal to", "is greater than or equal to" -> ">=";
                    default -> {
                        String op = tokens.get(0);
                        yield switch (op) {
                            case "==", "!=", "<", ">", "<=", ">=" -> op;
                            default -> throw new ParseFailureException("Unknown operator: " + joined);
                        };
                    }
                };
            }

            @Override
            public @NotNull String toJava(Object v, @NotNull CodegenAccess ctx,
                                          @NotNull EnvironmentAccess env) {
                return (String) v;
            }
        });
    }

    /**
     * Registers the {@code entity} type binding and its expressions.
     *
     * @param api the Lumen API
     */
    private static void registerEntity(@NotNull LumenAPI api) {
        api.types().register(new AddonTypeBinding() {
            @Override
            public @NotNull String id() {
                return "ENTITY";
            }

            @Override
            public @NotNull TypeBindingMeta meta() {
                return new TypeBindingMeta(
                        "Resolves an entity reference from a variable name. Does not accept possessive syntax.",
                        "org.bukkit.entity.Entity",
                        List.of("kill %e:ENTITY%", "teleport entity to location"),
                        "1.0.0",
                        false);
            }

            @Override
            public Object parse(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                String raw = tokens.get(0);
                if (raw.endsWith("'s"))
                    throw new ParseFailureException("ENTITY does not accept possessive form: " + raw);

                VarHandle ref = env.lookupVar(raw);
                if (ref == null)
                    throw new ParseFailureException("Unknown entity reference: " + raw);
                if (!isEntity(ref))
                    throw new ParseFailureException(raw + " is not an entity");
                return ref;
            }

            @Override
            public @NotNull String toJava(Object v, @NotNull CodegenAccess ctx,
                                          @NotNull EnvironmentAccess env) {
                if (v == null)
                    throw new RuntimeException("Cannot generate Java for null entity reference");
                return ((VarHandle) v).java();
            }

            private boolean isEntity(@NotNull VarHandle ref) {
                if (ref.type() == null)
                    return false;
                String id = ref.type().id();
                return id.equals(Types.ENTITY.id()) || id.equals(Types.PLAYER.id());
            }
        });

        api.types().register(new AddonTypeBinding() {
            @Override
            public @NotNull String id() {
                return "ENTITY_POSSESSIVE";
            }

            @Override
            public @NotNull TypeBindingMeta meta() {
                return new TypeBindingMeta(
                        "Resolves an entity reference from a possessive token (e.g. entity's). The token must end with 's.",
                        "org.bukkit.entity.Entity",
                        List.of("%e:ENTITY_POSSESSIVE% health"),
                        "1.0.0",
                        false);
            }

            @Override
            public Object parse(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                String raw = tokens.get(0);
                if (!raw.endsWith("'s"))
                    throw new ParseFailureException("ENTITY_POSSESSIVE requires possessive form (e.g. entity's): " + raw);
                String name = raw.substring(0, raw.length() - 2);

                VarHandle ref = env.lookupVar(name);
                if (ref == null)
                    throw new ParseFailureException("Unknown entity reference: " + name);
                if (!isEntity(ref))
                    throw new ParseFailureException(name + " is not an entity");
                return ref;
            }

            @Override
            public @NotNull String toJava(Object v, @NotNull CodegenAccess ctx,
                                          @NotNull EnvironmentAccess env) {
                if (v == null)
                    throw new RuntimeException("Cannot generate Java for null entity reference");
                return ((VarHandle) v).java();
            }

            private boolean isEntity(@NotNull VarHandle ref) {
                if (ref.type() == null)
                    return false;
                String id = ref.type().id();
                return id.equals(Types.ENTITY.id()) || id.equals(Types.PLAYER.id());
            }
        });
    }

    /**
     * Registers the {@code itemstack} type binding and its expressions.
     *
     * @param api the Lumen API
     */
    private static void registerItemStack(@NotNull LumenAPI api) {
        api.types().register(new AddonTypeBinding() {
            @Override
            public @NotNull String id() {
                return "ITEMSTACK";
            }

            @Override
            public @NotNull TypeBindingMeta meta() {
                return new TypeBindingMeta(
                        "Resolves an ItemStack reference from a variable name. Does not accept possessive syntax.",
                        "org.bukkit.inventory.ItemStack",
                        List.of("set %i:ITEMSTACK% amount to 5"),
                        "1.0.0",
                        false);
            }

            @Override
            public Object parse(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                String raw = tokens.get(0);
                if (raw.endsWith("'s"))
                    throw new ParseFailureException("ITEMSTACK does not accept possessive form: " + raw);
                VarHandle ref = env.lookupVar(raw);
                if (ref == null)
                    throw new ParseFailureException("Unknown item stack reference: " + raw);
                if (!isItemStack(ref))
                    throw new ParseFailureException(raw + " is not an item stack");
                return ref;
            }

            @Override
            public @NotNull String toJava(Object v, @NotNull CodegenAccess ctx,
                                          @NotNull EnvironmentAccess env) {
                if (v == null)
                    throw new RuntimeException("Cannot generate Java for null item stack reference");
                return ((VarHandle) v).java();
            }

            private boolean isItemStack(@NotNull VarHandle ref) {
                return ref.type() != null && ref.type().id().equals(Types.ITEMSTACK.id());
            }
        });

        api.types().register(new AddonTypeBinding() {
            @Override
            public @NotNull String id() {
                return "ITEMSTACK_POSSESSIVE";
            }

            @Override
            public @NotNull TypeBindingMeta meta() {
                return new TypeBindingMeta(
                        "Resolves an ItemStack reference from a possessive token (e.g. item's). The token must end with 's.",
                        "org.bukkit.inventory.ItemStack",
                        List.of("%i:ITEMSTACK_POSSESSIVE% display name"),
                        "1.0.0",
                        false);
            }

            @Override
            public Object parse(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                String raw = tokens.get(0);
                if (!raw.endsWith("'s"))
                    throw new ParseFailureException("ITEMSTACK_POSSESSIVE requires possessive form (e.g. item's): " + raw);
                String name = raw.substring(0, raw.length() - 2);
                VarHandle ref = env.lookupVar(name);
                if (ref == null)
                    throw new ParseFailureException("Unknown item stack reference: " + name);
                if (!isItemStack(ref))
                    throw new ParseFailureException(name + " is not an item stack");
                return ref;
            }

            @Override
            public @NotNull String toJava(Object v, @NotNull CodegenAccess ctx,
                                          @NotNull EnvironmentAccess env) {
                if (v == null)
                    throw new RuntimeException("Cannot generate Java for null item stack reference");
                return ((VarHandle) v).java();
            }

            private boolean isItemStack(@NotNull VarHandle ref) {
                return ref.type() != null && ref.type().id().equals(Types.ITEMSTACK.id());
            }
        });
    }

    /**
     * Registers the {@code world} type binding and its expressions.
     *
     * @param api the Lumen API
     */
    private static void registerWorld(@NotNull LumenAPI api) {
        api.types().register(new AddonTypeBinding() {
            @Override
            public @NotNull String id() {
                return "WORLD";
            }

            @Override
            public @NotNull TypeBindingMeta meta() {
                return new TypeBindingMeta(
                        "Resolves a world reference from a variable name.",
                        "org.bukkit.World",
                        List.of("set time in %w:WORLD% to 0"),
                        "1.0.0",
                        false);
            }

            @Override
            public Object parse(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                String name = tokens.get(0);
                VarHandle ref = env.lookupVar(name);
                if (ref == null)
                    throw new ParseFailureException("Unknown world reference: " + name);
                if (!isWorld(ref))
                    throw new ParseFailureException(name + " is not a world");
                return ref;
            }

            @Override
            public @NotNull String toJava(Object v, @NotNull CodegenAccess ctx,
                                          @NotNull EnvironmentAccess env) {
                return ((VarHandle) v).java();
            }

            private boolean isWorld(@NotNull VarHandle ref) {
                return ref.type() != null && ref.type().id().equals(Types.WORLD.id());
            }
        });
    }

    /**
     * Registers the {@code location} type binding and its expressions.
     *
     * @param api the Lumen API
     */
    private static void registerLocation(@NotNull LumenAPI api) {
        api.types().register(new AddonTypeBinding() {
            @Override
            public @NotNull String id() {
                return "LOCATION";
            }

            @Override
            public @NotNull TypeBindingMeta meta() {
                return new TypeBindingMeta(
                        "Resolves a location reference from a variable name.",
                        "org.bukkit.Location",
                        List.of("teleport player to %loc:LOCATION%"),
                        "1.0.0",
                        false);
            }

            @Override
            public Object parse(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                String name = tokens.get(0);
                VarHandle ref = env.lookupVar(name);
                if (ref == null)
                    throw new ParseFailureException("Unknown location reference: " + name);
                if (!isLocation(ref))
                    throw new ParseFailureException(name + " is not a location");
                return ref;
            }

            @Override
            public @NotNull String toJava(Object v, @NotNull CodegenAccess ctx,
                                          @NotNull EnvironmentAccess env) {
                return ((VarHandle) v).java();
            }

            private boolean isLocation(@NotNull VarHandle ref) {
                return ref.type() != null && ref.type().id().equals(Types.LOCATION.id());
            }
        });
    }

    /**
     * Registers the {@code list} type binding and its expressions.
     *
     * @param api the Lumen API
     */
    private static void registerList(@NotNull LumenAPI api) {
        api.types().register(new AddonTypeBinding() {
            @Override
            public @NotNull String id() {
                return "LIST";
            }

            @Override
            public @NotNull TypeBindingMeta meta() {
                return new TypeBindingMeta(
                        "Resolves a list variable reference. The variable must have been declared as a list type.",
                        "java.util.List",
                        List.of("add \"hello\" to %list:LIST%"),
                        "1.0.0",
                        false);
            }

            @Override
            public int consumeCount(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                if (tokens.isEmpty())
                    throw new ParseFailureException("LIST requires at least one token");
                String name = tokens.get(0);
                VarHandle ref = env.lookupVar(name);
                if (ref != null && isList(ref))
                    return 1;
                throw new ParseFailureException("Expected a list variable, got '" + name + "'");
            }

            @Override
            public Object parse(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                String name = tokens.get(0);
                VarHandle ref = env.lookupVar(name);
                if (ref != null) {
                    if (!isList(ref))
                        throw new ParseFailureException(name + " is not a list");
                    return ref;
                }
                throw new ParseFailureException("Unknown list variable: " + name);
            }

            @Override
            public @NotNull String toJava(Object v, @NotNull CodegenAccess ctx,
                                          @NotNull EnvironmentAccess env) {
                if (v == null)
                    throw new RuntimeException("Cannot generate Java for null list reference");
                return ((VarHandle) v).java();
            }

            private boolean isList(@NotNull VarHandle ref) {
                return ref.type() != null && ref.type().id().equals(Types.LIST.id());
            }
        });
    }

    /**
     * Registers the {@code map} type binding and its expressions.
     *
     * @param api the Lumen API
     */
    private static void registerMap(@NotNull LumenAPI api) {
        api.types().register(new AddonTypeBinding() {
            @Override
            public @NotNull String id() {
                return "MAP";
            }

            @Override
            public @NotNull TypeBindingMeta meta() {
                return new TypeBindingMeta(
                        "Resolves a map variable reference. The variable must have been declared as a map type.",
                        "java.util.Map",
                        List.of("set %map:MAP% at key \"name\" to \"value\""),
                        "1.0.0",
                        false);
            }

            @Override
            public int consumeCount(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                if (tokens.isEmpty())
                    throw new ParseFailureException("MAP requires at least one token");
                String name = tokens.get(0);
                VarHandle ref = env.lookupVar(name);
                if (ref != null && isMap(ref))
                    return 1;
                throw new ParseFailureException("Expected a map variable, got '" + name + "'");
            }

            @Override
            public Object parse(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                String name = tokens.get(0);
                VarHandle ref = env.lookupVar(name);
                if (ref != null) {
                    if (!isMap(ref))
                        throw new ParseFailureException(name + " is not a map");
                    return ref;
                }
                throw new ParseFailureException("Unknown map variable: " + name);
            }

            @Override
            public @NotNull String toJava(Object v, @NotNull CodegenAccess ctx,
                                          @NotNull EnvironmentAccess env) {
                if (v == null)
                    throw new RuntimeException("Cannot generate Java for null map reference");
                return ((VarHandle) v).java();
            }

            private boolean isMap(@NotNull VarHandle ref) {
                return ref.type() != null && ref.type().id().equals(Types.MAP.id());
            }
        });
    }

    /**
     * Registers the {@code data} type binding and its expressions.
     *
     * @param api the Lumen API
     */
    private static void registerData(@NotNull LumenAPI api) {
        api.types().register(new AddonTypeBinding() {
            @Override
            public @NotNull String id() {
                return "DATA";
            }

            @Override
            public @NotNull TypeBindingMeta meta() {
                return new TypeBindingMeta(
                        "Resolves a data instance variable reference. The variable must have been declared as a data type.",
                        "dev.lumenlang.lumen.pipeline.java.compiled.DataInstance",
                        List.of("get field \"name\" of %obj:DATA%"),
                        "1.0.0",
                        false);
            }

            @Override
            public int consumeCount(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                if (tokens.isEmpty())
                    throw new ParseFailureException("DATA requires at least one token");
                String name = tokens.get(0);
                VarHandle ref = env.lookupVar(name);
                if (ref != null && isData(ref))
                    return 1;
                throw new ParseFailureException("Expected a data variable, got '" + name + "'");
            }

            @Override
            public Object parse(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                String name = tokens.get(0);
                VarHandle ref = env.lookupVar(name);
                if (ref != null) {
                    if (!isData(ref))
                        throw new ParseFailureException(name + " is not a data instance");
                    return ref;
                }
                throw new ParseFailureException("Unknown data variable: " + name);
            }

            @Override
            public @NotNull String toJava(Object v, @NotNull CodegenAccess ctx,
                                          @NotNull EnvironmentAccess env) {
                if (v == null)
                    throw new RuntimeException("Cannot generate Java for null data reference");
                return ((VarHandle) v).java();
            }

            private boolean isData(@NotNull VarHandle ref) {
                return ref.type() != null && ref.type().id().equals(Types.DATA.id());
            }
        });
    }

    /**
     * Registers the {@code block} type binding and its expressions.
     *
     * @param api the Lumen API
     */
    private static void registerBlock(@NotNull LumenAPI api) {
        api.types().register(new AddonTypeBinding() {
            @Override
            public @NotNull String id() {
                return "BLOCK";
            }

            @Override
            public @NotNull TypeBindingMeta meta() {
                return new TypeBindingMeta(
                        "Resolves a block reference from a variable name.",
                        "org.bukkit.block.Block",
                        List.of("set %b:BLOCK% type to stone", "break block naturally"),
                        "1.0.0",
                        false);
            }

            @Override
            public Object parse(@NotNull List<String> tokens, @NotNull EnvironmentAccess env) {
                String name = tokens.get(0);
                VarHandle ref = env.lookupVar(name);
                if (ref == null)
                    throw new ParseFailureException("Unknown block reference: " + name);
                if (!isBlock(ref))
                    throw new ParseFailureException(name + " is not a block");
                return ref;
            }

            @Override
            public @NotNull String toJava(Object v, @NotNull CodegenAccess ctx,
                                          @NotNull EnvironmentAccess env) {
                if (v == null)
                    throw new RuntimeException("Cannot generate Java for null block reference");
                return ((VarHandle) v).java();
            }

            private boolean isBlock(@NotNull VarHandle ref) {
                return ref.type() != null && ref.type().id().equals(Types.BLOCK.id());
            }
        });
    }
}