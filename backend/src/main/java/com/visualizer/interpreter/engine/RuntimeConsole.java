package com.visualizer.interpreter.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures everything the program writes via {@code System.out.print/println},
 * in order. Keeps both the flat accumulated text (for assertions / display) and
 * the discrete entries (so the trace can build cumulative {@code ConsoleOutputDto}
 * snapshots). No real I/O happens.
 */
public final class RuntimeConsole {

    /** A single write, in emission order. {@code newline} mirrors print vs println. */
    public record Entry(String text, boolean newline) {}

    private final StringBuilder buffer = new StringBuilder();
    private final List<Entry> entries = new ArrayList<>();

    /** Append text with no trailing newline (mirrors {@code System.out.print}). */
    public void print(String text) {
        buffer.append(text);
        entries.add(new Entry(text, false));
    }

    /** Append text followed by a newline (mirrors {@code System.out.println}). */
    public void println(String text) {
        buffer.append(text).append('\n');
        entries.add(new Entry(text, true));
    }

    /** Append just a newline (mirrors no-arg {@code System.out.println()}). */
    public void println() {
        buffer.append('\n');
        entries.add(new Entry("", true));
    }

    /** The full accumulated output, including embedded newlines. */
    public String output() {
        return buffer.toString();
    }

    /** Discrete writes in emission order; unmodifiable. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public boolean isEmpty() {
        return buffer.length() == 0;
    }
}
