package com.visualizer.interpreter.runtime;

/**
 * The first non-primitive runtime {@link Value} (Phase 2A): a reference to a
 * heap-allocated object, identified by its {@code objectId}.
 *
 * <p>It also carries the object's {@code className}, which is the reference's
 * Java source type (e.g. {@code Person}). The runtime model has no single
 * {@link ValueType} that can express a user-defined class, so the class name
 * travels on the value itself; the enum only marks it as {@link ValueType#REFERENCE}.</p>
 *
 * <p>A {@code null} {@code objectId} represents the Java {@code null} reference.</p>
 */
public record ReferenceValue(Integer objectId, String className) implements Value {

    @Override
    public ValueType type() {
        return ValueType.REFERENCE;
    }

    /** {@code true} when this is the {@code null} reference. */
    public boolean isNull() {
        return objectId == null;
    }

    @Override
    public String display() {
        return objectId == null ? "null" : Integer.toString(objectId);
    }
}
