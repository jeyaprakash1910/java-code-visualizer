package com.visualizer.interpreter.runtime;

/**
 * The four scalar types the Phase 1 interpreter understands. Mirrors the
 * validation subset in {@code com.visualizer.parser}. {@code STRING} is treated
 * as a scalar here because the runtime model has no heap yet.
 */
public enum ValueType {
    INT("int"),
    DOUBLE("double"),
    BOOLEAN("boolean"),
    STRING("String"),
    /**
     * A non-primitive heap reference (Phase 2A). Unlike the scalar types, the
     * Java source name of a reference is the object's class (e.g. {@code Person}),
     * which is carried on the {@link Value} itself, not by this enum.
     */
    REFERENCE("reference");

    private final String javaName;

    ValueType(String javaName) {
        this.javaName = javaName;
    }

    /** The name as it appears in Java source, e.g. {@code "int"}, {@code "String"}. */
    public String javaName() {
        return javaName;
    }

    /** Resolve a declared-type token from source into a {@link ValueType}. */
    public static ValueType fromJavaName(String name) {
        for (ValueType type : values()) {
            if (type.javaName.equals(name)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported type: " + name);
    }

    /**
     * Resolve a declared-type token, treating any non-scalar name as a
     * {@link #REFERENCE} to a user-defined class (e.g. {@code Person}). Unlike
     * {@link #fromJavaName} this never throws — callers rely on validation having
     * already confirmed the name is a known class.
     */
    public static ValueType resolveDeclared(String name) {
        return switch (name) {
            case "int" -> INT;
            case "double" -> DOUBLE;
            case "boolean" -> BOOLEAN;
            case "String" -> STRING;
            default -> REFERENCE;
        };
    }
}
