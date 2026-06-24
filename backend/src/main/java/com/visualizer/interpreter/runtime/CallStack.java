package com.visualizer.interpreter.runtime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The simulated call stack: a LIFO of {@link StackFrame}s with the most recently
 * pushed frame as the "current" one.
 *
 * <p>Variable operations delegate to the current frame only. Java uses lexical,
 * not dynamic, scoping, so a method never sees its caller's locals — this model
 * deliberately does <em>not</em> search down the stack for names.</p>
 */
public final class CallStack {

    private final Deque<StackFrame> frames = new ArrayDeque<>();

    /** Push a new frame and make it current. */
    public StackFrame push(StackFrame frame) {
        frames.push(frame);
        return frame;
    }

    /** Convenience: build and push a frame for {@code className.methodName}. */
    public StackFrame push(String className, String methodName) {
        return push(new StackFrame(className, methodName));
    }

    /**
     * Remove and return the current frame.
     *
     * @throws EmptyCallStackException if the stack is empty
     */
    public StackFrame pop() {
        requireNonEmpty();
        return frames.pop();
    }

    /**
     * The current (top) frame without removing it.
     *
     * @throws EmptyCallStackException if the stack is empty
     */
    public StackFrame current() {
        requireNonEmpty();
        return frames.peek();
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    public int depth() {
        return frames.size();
    }

    /** Frames from top (current) to bottom; unmodifiable. */
    public List<StackFrame> frames() {
        return List.copyOf(frames);
    }

    // ---- Convenience delegates to the current frame --------------------------

    public Variable declare(String name, ValueType type, Value initialValue) {
        return current().declare(name, type, initialValue);
    }

    public Variable lookup(String name) {
        return current().lookup(name);
    }

    public Value read(String name) {
        return current().read(name);
    }

    public void assign(String name, Value value) {
        current().assign(name, value);
    }

    private void requireNonEmpty() {
        if (frames.isEmpty()) {
            throw new EmptyCallStackException();
        }
    }
}
