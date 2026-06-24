package com.visualizer.interpreter.runtime;

/** Raised when code reads or assigns a variable that was never declared in scope. */
public class UndefinedVariableException extends RuntimeModelException {
    public UndefinedVariableException(String name) {
        super("Variable '" + name + "' is not defined.");
    }
}
