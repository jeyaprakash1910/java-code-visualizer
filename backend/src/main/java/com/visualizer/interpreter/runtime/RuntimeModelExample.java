package com.visualizer.interpreter.runtime;

/**
 * Standalone, runnable demonstration of how the interpreter will drive the
 * runtime model. It is <em>not</em> wired into Spring or the controller — it
 * exists purely as living documentation of the intended usage.
 *
 * <p>Simulates the runtime effects of:</p>
 * <pre>{@code
 * class Demo {
 *     static void main(String[] args) {
 *         int count = 0;
 *         double avg = 1.5;
 *         boolean done = false;
 *         String label = "count=";
 *         count = count + 5;   // reassignment
 *         done = true;
 *     }
 * }
 * }</pre>
 */
public final class RuntimeModelExample {

    private RuntimeModelExample() {
    }

    public static void main(String[] args) {
        CallStack stack = new CallStack();

        // Entering main(): a fresh frame becomes current.
        stack.push("Demo", "main");

        // Variable declarations (each must match its declared type).
        stack.declare("count", ValueType.INT, Value.of(0));
        stack.declare("avg", ValueType.DOUBLE, Value.of(1.5));
        stack.declare("done", ValueType.BOOLEAN, Value.of(false));
        stack.declare("label", ValueType.STRING, Value.of("count="));

        // Variable lookup + reassignment: count = count + 5.
        int current = stack.read("count").asInt();
        stack.assign("count", Value.of(current + 5));

        // Another assignment.
        stack.assign("done", Value.of(true));

        // Inspect the frame.
        StackFrame frame = stack.current();
        System.out.println("Frame: " + frame.className() + "." + frame.methodName());
        for (Variable v : frame.variables()) {
            System.out.println("  " + v);
        }

        // Leaving main().
        stack.pop();
        System.out.println("Stack empty after return: " + stack.isEmpty());
    }
}
