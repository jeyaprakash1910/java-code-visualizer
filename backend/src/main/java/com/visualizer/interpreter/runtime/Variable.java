package com.visualizer.interpreter.runtime;

import java.util.Objects;

/**
 * A single named slot in a {@link StackFrame}: a fixed name and declared
 * {@link ValueType}, plus a mutable current {@link Value}.
 *
 * <p>The declared type never changes after construction. Every {@link #assign}
 * is type-checked, so a variable declared {@code int} can only ever hold an
 * {@code int} value — this is the model's core type-safety guarantee.</p>
 */
public final class Variable {

    private final String name;
    private final ValueType declaredType;
    private Value value;

    /**
     * @param name         the variable's identifier
     * @param declaredType the type it was declared with; the value must match it
     * @param initialValue the value assigned at declaration
     * @throws TypeMismatchException if {@code initialValue} is not of {@code declaredType}
     */
    public Variable(String name, ValueType declaredType, Value initialValue) {
        this.name = Objects.requireNonNull(name, "name");
        this.declaredType = Objects.requireNonNull(declaredType, "declaredType");
        assign(initialValue);
    }

    public String name() {
        return name;
    }

    public ValueType declaredType() {
        return declaredType;
    }

    public Value value() {
        return value;
    }

    /**
     * Replace the current value, enforcing that it matches the declared type.
     *
     * @throws TypeMismatchException if {@code newValue}'s type differs from the
     *                               declared type
     */
    public void assign(Value newValue) {
        Objects.requireNonNull(newValue, "value");
        if (newValue.type() != declaredType) {
            throw new TypeMismatchException(name, declaredType, newValue.type());
        }
        this.value = newValue;
    }

    @Override
    public String toString() {
        return declaredType.javaName() + " " + name + " = " + value.display();
    }
}
