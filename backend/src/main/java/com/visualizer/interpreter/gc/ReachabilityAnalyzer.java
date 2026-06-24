package com.visualizer.interpreter.gc;

import com.visualizer.interpreter.runtime.ArrayHeapObject;
import com.visualizer.interpreter.runtime.CallStack;
import com.visualizer.interpreter.runtime.Heap;
import com.visualizer.interpreter.runtime.HeapEntity;
import com.visualizer.interpreter.runtime.HeapObject;
import com.visualizer.interpreter.runtime.ReferenceValue;
import com.visualizer.interpreter.runtime.StackFrame;
import com.visualizer.interpreter.runtime.Value;
import com.visualizer.interpreter.runtime.Variable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Educational reachability analysis (Phase 2F): which heap entities are still
 * reachable from the GC roots?
 *
 * <p>GC roots are the reference-typed variables in every active stack frame
 * (locals and parameters); primitives are ignored. From those roots it walks the
 * object graph — object fields and array elements — marking every entity it can
 * reach. This is a teaching model of reachability, deliberately not a real JVM
 * collector (no generations, no mark-sweep internals, no weak/soft refs).</p>
 *
 * <p>Its own component, with no knowledge of DTOs or trace steps — callers turn the
 * returned id set into {@code gcState} labels.</p>
 */
public final class ReachabilityAnalyzer {

    /** The ids of all heap entities reachable from the current GC roots. */
    public Set<Integer> reachable(CallStack callStack, Heap heap) {
        Set<Integer> reachable = new LinkedHashSet<>();
        Deque<Integer> worklist = new ArrayDeque<>();

        // Roots: every reference held by a variable in any active frame.
        for (StackFrame frame : callStack.frames()) {
            for (Variable variable : frame.variables()) {
                follow(variable.value(), reachable, worklist);
            }
        }

        // Transitive closure through object fields and array elements.
        while (!worklist.isEmpty()) {
            HeapEntity entity = heap.get(worklist.pop());
            if (entity instanceof HeapObject object) {
                for (Variable field : object.fields()) {
                    follow(field.value(), reachable, worklist);
                }
            } else if (entity instanceof ArrayHeapObject array) {
                for (Value element : array.elements()) {
                    follow(element, reachable, worklist);
                }
            }
        }
        return reachable;
    }

    private void follow(Value value, Set<Integer> reachable, Deque<Integer> worklist) {
        if (value instanceof ReferenceValue ref && !ref.isNull() && reachable.add(ref.objectId())) {
            worklist.push(ref.objectId());
        }
    }
}
