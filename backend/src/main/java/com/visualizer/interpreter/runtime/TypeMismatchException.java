package com.visualizer.interpreter.runtime;

/** Raised when a value of the wrong {@link ValueType} is assigned to a variable. */
public class TypeMismatchException extends RuntimeModelException {
    public TypeMismatchException(String name, ValueType expected, ValueType actual) {
        super("Cannot assign " + actual.javaName() + " to variable '" + name
                + "' of type " + expected.javaName() + ".");
    }
}
