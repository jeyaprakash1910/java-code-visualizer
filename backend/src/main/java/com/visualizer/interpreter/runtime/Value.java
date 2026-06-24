package com.visualizer.interpreter.runtime;

/**
 * An immutable runtime value of one of the four supported {@link ValueType}s.
 *
 * <p>Modeled as a sealed hierarchy so the interpreter can pattern-match
 * exhaustively and so an {@code int} value can never be silently read as a
 * {@code boolean}. Construct via the static factories; read via the typed
 * accessors, which throw if the value is not of the requested type.</p>
 */
public sealed interface Value
        permits Value.IntValue, Value.DoubleValue, Value.BoolValue, Value.StringValue, ReferenceValue {

    ValueType type();

    /** Textual form used for display / trace output (e.g. {@code "42"}, {@code "true"}). */
    String display();

    // ---- Factories -----------------------------------------------------------

    static Value of(int v) {
        return new IntValue(v);
    }

    static Value of(double v) {
        return new DoubleValue(v);
    }

    static Value of(boolean v) {
        return new BoolValue(v);
    }

    /** A String value. {@code null} represents the Java {@code null} reference. */
    static Value of(String v) {
        return new StringValue(v);
    }

    /** A heap reference to the object with the given id and class (Phase 2A). */
    static Value reference(int objectId, String className) {
        return new ReferenceValue(objectId, className);
    }

    /** The {@code null} reference for a declared reference type. */
    static Value nullReference(String className) {
        return new ReferenceValue(null, className);
    }

    /**
     * The Java default value for a freshly declared slot of {@code type}: {@code 0},
     * {@code 0.0}, {@code false}, the {@code null} String, or a {@code null}
     * reference of {@code declaredTypeName}.
     */
    static Value defaultFor(ValueType type, String declaredTypeName) {
        return switch (type) {
            case INT -> of(0);
            case DOUBLE -> of(0.0);
            case BOOLEAN -> of(false);
            case STRING -> of((String) null);
            case REFERENCE -> nullReference(declaredTypeName);
        };
    }

    // ---- Typed accessors -----------------------------------------------------

    default int asInt() {
        if (this instanceof IntValue i) {
            return i.value();
        }
        throw new IllegalStateException("Value is " + type().javaName() + ", not int");
    }

    default double asDouble() {
        if (this instanceof DoubleValue d) {
            return d.value();
        }
        throw new IllegalStateException("Value is " + type().javaName() + ", not double");
    }

    default boolean asBoolean() {
        if (this instanceof BoolValue b) {
            return b.value();
        }
        throw new IllegalStateException("Value is " + type().javaName() + ", not boolean");
    }

    default String asString() {
        if (this instanceof StringValue s) {
            return s.value();
        }
        throw new IllegalStateException("Value is " + type().javaName() + ", not String");
    }

    // ---- Implementations -----------------------------------------------------

    record IntValue(int value) implements Value {
        @Override public ValueType type() { return ValueType.INT; }
        @Override public String display() { return Integer.toString(value); }
    }

    record DoubleValue(double value) implements Value {
        @Override public ValueType type() { return ValueType.DOUBLE; }
        @Override public String display() { return Double.toString(value); }
    }

    record BoolValue(boolean value) implements Value {
        @Override public ValueType type() { return ValueType.BOOLEAN; }
        @Override public String display() { return Boolean.toString(value); }
    }

    record StringValue(String value) implements Value {
        @Override public ValueType type() { return ValueType.STRING; }
        @Override public String display() { return value == null ? "null" : value; }
    }
}
