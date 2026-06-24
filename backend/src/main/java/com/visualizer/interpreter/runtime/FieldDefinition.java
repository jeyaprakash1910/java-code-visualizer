package com.visualizer.interpreter.runtime;

import java.util.Objects;

/**
 * The schema of a single field declared on a class (Phase 2B): its {@code name},
 * resolved {@link ValueType}, and the source-level {@code declaredTypeName} (used
 * to build a typed {@code null} reference default for reference-typed fields).
 *
 * <p>Pure data — it carries no value. A {@link HeapObject} turns each definition
 * into a default-initialized field slot at allocation time.</p>
 */
public record FieldDefinition(String name, ValueType type, String declaredTypeName) {

    public FieldDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(declaredTypeName, "declaredTypeName");
    }
}
