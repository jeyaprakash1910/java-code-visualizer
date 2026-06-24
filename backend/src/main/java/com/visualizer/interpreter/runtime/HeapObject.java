package com.visualizer.interpreter.runtime;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A single object living on the {@link Heap}: an identity ({@code objectId}), the
 * {@code className} it was allocated from, and its mutable field slots (Phase 2B).
 *
 * <p>Each field is a {@link Variable}, so it carries its own declared type and
 * enforces type-safe assignment exactly like a local variable. Fields are created
 * default-initialized from the class's {@link FieldDefinition}s at allocation and
 * keep declaration order. No methods, constructors, or inheritance yet.</p>
 */
public final class HeapObject implements HeapEntity {

    private final int objectId;
    private final String className;
    private final Map<String, Variable> fields = new LinkedHashMap<>();

    public HeapObject(int objectId, String className, List<FieldDefinition> fieldDefinitions) {
        this.objectId = objectId;
        this.className = Objects.requireNonNull(className, "className");
        for (FieldDefinition def : fieldDefinitions) {
            fields.put(def.name(),
                    new Variable(def.name(), def.type(),
                            Value.defaultFor(def.type(), def.declaredTypeName())));
        }
    }

    public int objectId() {
        return objectId;
    }

    public String className() {
        return className;
    }

    public boolean hasField(String name) {
        return fields.containsKey(name);
    }

    /**
     * The field slot for {@code name}.
     *
     * @throws RuntimeModelException if this object has no such field
     */
    public Variable field(String name) {
        Variable field = fields.get(name);
        if (field == null) {
            throw new RuntimeModelException(
                    "Object " + objectId + " (" + className + ") has no field '" + name + "'");
        }
        return field;
    }

    /** Fields in declaration order; unmodifiable. */
    public Collection<Variable> fields() {
        return java.util.Collections.unmodifiableCollection(fields.values());
    }
}
