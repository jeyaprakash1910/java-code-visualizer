package com.visualizer.interpreter.runtime;

/**
 * Raised when a variable name is declared twice within the same frame. Prevents
 * shadowing/redeclaration in a single scope, matching the Java compiler's rule.
 */
public class VariableAlreadyDeclaredException extends RuntimeModelException {
    public VariableAlreadyDeclaredException(String name) {
        super("Variable '" + name + "' is already declared in this scope.");
    }
}
