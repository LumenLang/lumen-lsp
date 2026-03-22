package dev.lumenlang.lumen.lsp.analysis;

import dev.lumenlang.lumen.pipeline.var.RefType;
import org.jetbrains.annotations.Nullable;

/**
 * Holds the inferred return type information for an expression variable declaration,
 * including the pipeline ref type and the Java type name.
 *
 * @param refType  the inferred pipeline ref type, or null if unknown
 * @param javaType the inferred Java type name, or null if unknown
 */
public record ExpressionReturn(@Nullable RefType refType, @Nullable String javaType) {
}
