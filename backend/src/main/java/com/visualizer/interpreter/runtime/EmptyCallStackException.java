package com.visualizer.interpreter.runtime;

/** Raised when the current frame is requested (or popped) from an empty call stack. */
public class EmptyCallStackException extends RuntimeModelException {
    public EmptyCallStackException() {
        super("The call stack is empty.");
    }
}
