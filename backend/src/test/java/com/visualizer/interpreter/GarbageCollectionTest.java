package com.visualizer.interpreter;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.visualizer.api.SimulationController;
import com.visualizer.api.dto.ExecutionStepDto;
import com.visualizer.api.dto.ExecutionTrace;
import com.visualizer.api.dto.HeapObjectDto;
import com.visualizer.api.dto.SimulateRequest;
import com.visualizer.interpreter.engine.ProgramInterpreter;
import com.visualizer.parser.JavaCodeParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2F: educational garbage-collection visualization. Verifies reachability
 * (REACHABLE / UNREACHABLE / COLLECTED) through stack roots, object fields, array
 * elements, and method frames, plus the GC trace events.
 */
class GarbageCollectionTest {

    private final ProgramInterpreter interpreter = new ProgramInterpreter();
    private final JavaCodeParser parser = new JavaCodeParser();

    private ExecutionTrace trace(String source) {
        CompilationUnit cu = new JavaParser().parse(source).getResult().orElseThrow();
        return interpreter.run(cu).trace();
    }

    private static List<HeapObjectDto> lastHeap(ExecutionTrace t) {
        return t.steps().get(t.steps().size() - 1).heap();
    }

    /** gcState of the entity with the given id, in the given heap snapshot. */
    private static String stateOf(List<HeapObjectDto> heap, int id) {
        return heap.stream().filter(o -> o.id() == id).findFirst().orElseThrow().gcState();
    }

    /** The non-GC step whose heap best reflects pre-collection reachability. */
    private static ExecutionStepDto lastNonCollectStep(ExecutionTrace t) {
        return t.steps().stream()
                .filter(s -> !s.event().equals("GC_COLLECT"))
                .reduce((a, b) -> b)
                .orElseThrow();
    }

    @Nested
    class LostReference {

        @Test
        void reassignedObjectBecomesUnreachable() {
            ExecutionTrace t = trace("""
                    class Person {
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Person p = new Person();
                            p = new Person();
                        }
                    }
                    """);
            // Before collection: 1001 lost, 1002 live.
            ExecutionStepDto step = lastNonCollectStep(t);
            assertThat(stateOf(step.heap(), 1001)).isEqualTo("UNREACHABLE");
            assertThat(stateOf(step.heap(), 1002)).isEqualTo("REACHABLE");
        }

        @Test
        void emitsGcMarkAndCollectEvents() {
            ExecutionTrace t = trace("""
                    class Person {
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Person p = new Person();
                            p = new Person();
                        }
                    }
                    """);
            assertThat(t.steps()).anyMatch(s -> s.event().equals("GC_MARK")
                    && s.description().equals("Object 1001 became unreachable"));
            assertThat(t.steps()).anyMatch(s -> s.event().equals("GC_COLLECT")
                    && s.description().equals("Collected object 1001"));
            // After collection 1001 is COLLECTED but still present; 1002 survives.
            assertThat(stateOf(lastHeap(t), 1001)).isEqualTo("COLLECTED");
            assertThat(stateOf(lastHeap(t), 1002)).isEqualTo("REACHABLE");
        }
    }

    @Nested
    class StaysReachable {

        @Test
        void aliasedObjectRemainsReachable() {
            ExecutionTrace t = trace("""
                    class Person {
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Person a = new Person();
                            Person b = a;
                        }
                    }
                    """);
            // One object, reachable via both a and b; nothing collected.
            assertThat(lastHeap(t)).hasSize(1);
            assertThat(stateOf(lastHeap(t), 1001)).isEqualTo("REACHABLE");
            assertThat(t.steps()).noneMatch(s -> s.event().equals("GC_COLLECT"));
        }

        @Test
        void nestedObjectGraphKeepsBothReachable() {
            ExecutionTrace t = trace("""
                    class Node {
                        Node next;
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Node a = new Node();
                            Node b = new Node();
                            a.next = b;
                        }
                    }
                    """);
            // b is reachable only through a.next — traversal must follow the field.
            assertThat(stateOf(lastHeap(t), 1001)).isEqualTo("REACHABLE");
            assertThat(stateOf(lastHeap(t), 1002)).isEqualTo("REACHABLE");
        }

        @Test
        void objectReachableThroughArrayElement() {
            ExecutionTrace t = trace("""
                    class Person {
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Person[] people = new Person[2];
                            Person p = new Person();
                            people[0] = p;
                        }
                    }
                    """);
            // Array 1001 (root via `people`), Person 1002 reachable via element and `p`.
            assertThat(stateOf(lastHeap(t), 1001)).isEqualTo("REACHABLE");
            assertThat(stateOf(lastHeap(t), 1002)).isEqualTo("REACHABLE");
        }

        @Test
        void objectStillReachableThroughArrayAfterLocalCleared() {
            // Even if the direct local is overwritten, the array keeps it alive.
            ExecutionTrace t = trace("""
                    class Person {
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Person[] people = new Person[1];
                            Person p = new Person();
                            people[0] = p;
                            p = new Person();
                        }
                    }
                    """);
            // 1001 array (root), 1002 reachable via array element, 1003 via p.
            assertThat(stateOf(lastHeap(t), 1002)).isEqualTo("REACHABLE");
            assertThat(stateOf(lastHeap(t), 1003)).isEqualTo("REACHABLE");
        }
    }

    @Nested
    class MethodFrames {

        @Test
        void objectOnlyInACompletedFrameBecomesUnreachable() {
            ExecutionTrace t = trace("""
                    class Person {
                    }
                    public class Main {
                        public static Person leak() {
                            Person local = new Person();
                            return local;
                        }
                        public static void main(String[] args) {
                            leak();
                        }
                    }
                    """);
            // The returned Person is not stored by main, so after the frame pops it
            // is unreachable (and ultimately collected).
            assertThat(stateOf(lastNonCollectStep(t).heap(), 1001)).isEqualTo("UNREACHABLE");
            assertThat(stateOf(lastHeap(t), 1001)).isEqualTo("COLLECTED");
        }

        @Test
        void objectReturnedAndStoredStaysReachable() {
            ExecutionTrace t = trace("""
                    class Person {
                    }
                    public class Main {
                        public static Person make() {
                            return new Person();
                        }
                        public static void main(String[] args) {
                            Person p = make();
                        }
                    }
                    """);
            assertThat(stateOf(lastHeap(t), 1001)).isEqualTo("REACHABLE");
            assertThat(t.steps()).noneMatch(s -> s.event().equals("GC_COLLECT"));
        }
    }

    @Nested
    class Snapshots {

        @Test
        void heapSnapshotCarriesIdTypeAndGcState() {
            ExecutionTrace t = trace("""
                    class Person {
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Person p = new Person();
                            p = new Person();
                        }
                    }
                    """);
            HeapObjectDto lost = lastNonCollectStep(t).heap().stream()
                    .filter(o -> o.id() == 1001).findFirst().orElseThrow();
            assertThat(lost.id()).isEqualTo(1001);
            assertThat(lost.type()).isEqualTo("Person");
            assertThat(lost.gcState()).isEqualTo("UNREACHABLE");
        }
    }

    @Nested
    class Integration {

        @Test
        void simulateShowsReachableUnreachableAndCollected() {
            SimulationController controller = new SimulationController(new Interpreter(parser));
            ExecutionTrace t = controller.simulate(new SimulateRequest("""
                    class Person {
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Person p = new Person();
                            p = new Person();
                        }
                    }
                    """, null));

            assertThat(t.status()).isEqualTo("OK");
            assertThat(t.steps()).anyMatch(s -> s.event().equals("GC_MARK"));
            assertThat(t.steps()).anyMatch(s -> s.event().equals("GC_COLLECT"));
            // Final snapshot: 1001 collected (still visible), 1002 reachable.
            assertThat(stateOf(lastHeap(t), 1001)).isEqualTo("COLLECTED");
            assertThat(stateOf(lastHeap(t), 1002)).isEqualTo("REACHABLE");
            assertThat(lastHeap(t)).hasSize(2); // collected object is preserved in history
        }
    }
}
