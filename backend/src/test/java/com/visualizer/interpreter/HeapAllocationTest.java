package com.visualizer.interpreter;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.visualizer.api.dto.ExecutionStepDto;
import com.visualizer.api.dto.ExecutionTrace;
import com.visualizer.api.dto.HeapObjectDto;
import com.visualizer.api.dto.SimulateRequest;
import com.visualizer.api.SimulationController;
import com.visualizer.interpreter.engine.InterpretationResult;
import com.visualizer.interpreter.engine.ProgramInterpreter;
import com.visualizer.interpreter.runtime.Heap;
import com.visualizer.interpreter.runtime.ReferenceValue;
import com.visualizer.interpreter.runtime.Value;
import com.visualizer.parser.JavaCodeParser;
import com.visualizer.parser.ParseOutcome;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2A: heap infrastructure and object allocation. Covers allocation,
 * multiple allocations with distinct ids, immutable heap snapshots, the relaxed
 * validation rules, and the end-to-end simulate path.
 */
class HeapAllocationTest {

    private final ProgramInterpreter interpreter = new ProgramInterpreter();
    private final JavaCodeParser parser = new JavaCodeParser();

    private InterpretationResult run(String source) {
        CompilationUnit cu = new JavaParser().parse(source).getResult().orElseThrow();
        return interpreter.run(cu);
    }

    private static final String PERSON_PROGRAM = """
            class Person {
            }

            public class Main {
                public static void main(String[] args) {
                    Person p = new Person();
                }
            }
            """;

    @Nested
    class Allocation {

        @Test
        void allocatesOneObjectAndPointsVariableAtIt() {
            InterpretationResult r = run(PERSON_PROGRAM);

            Value pValue = r.variable("p");
            assertThat(pValue).isInstanceOf(ReferenceValue.class);
            ReferenceValue ref = (ReferenceValue) pValue;
            assertThat(ref.objectId()).isEqualTo(Heap.FIRST_OBJECT_ID);
            assertThat(ref.className()).isEqualTo("Person");
        }

        @Test
        void multipleAllocationsGetDistinctIds() {
            InterpretationResult r = run("""
                    class Person {
                    }

                    public class Main {
                        public static void main(String[] args) {
                            Person a = new Person();
                            Person b = new Person();
                        }
                    }
                    """);

            int idA = ((ReferenceValue) r.variable("a")).objectId();
            int idB = ((ReferenceValue) r.variable("b")).objectId();
            assertThat(idA).isEqualTo(1001);
            assertThat(idB).isEqualTo(1002);
            assertThat(idA).isNotEqualTo(idB);
        }
    }

    @Nested
    class HeapInternals {

        @Test
        void heapAllocatesSequentialIdsAndLooksThemUp() {
            Heap heap = new Heap();
            var first = heap.allocate("Person");
            var second = heap.allocate("Person");

            assertThat(first.objectId()).isEqualTo(1001);
            assertThat(second.objectId()).isEqualTo(1002);
            assertThat(heap.get(1001)).isSameAs(first);
            assertThat(heap.allObjects()).containsExactly(first, second);
        }

        @Test
        void lookupOfUnknownIdFails() {
            assertThatThrownBy(() -> new Heap().get(9999))
                    .hasMessageContaining("9999");
        }
    }

    @Nested
    class HeapSnapshots {

        @Test
        void traceContainsHeapObjectForAllocation() {
            ExecutionTrace trace = interpreter.run(
                    new JavaParser().parse(PERSON_PROGRAM).getResult().orElseThrow()).trace();

            ExecutionStepDto lastStep = trace.steps().get(trace.steps().size() - 1);
            assertThat(lastStep.heap()).hasSize(1);
            HeapObjectDto object = lastStep.heap().get(0);
            assertThat(object.id()).isEqualTo(1001);
            assertThat(object.type()).isEqualTo("Person");
            assertThat(object.category()).isEqualTo("OBJECT");

            // Stack reference points at the heap id.
            var p = lastStep.callStack().get(0).variables().get(0);
            assertThat(p.name()).isEqualTo("p");
            assertThat(p.kind()).isEqualTo("REFERENCE");
            assertThat(p.ref()).isEqualTo(1001);
        }

        @Test
        void heapSnapshotsAreImmutable() {
            ExecutionTrace trace = interpreter.run(
                    new JavaParser().parse(PERSON_PROGRAM).getResult().orElseThrow()).trace();
            List<HeapObjectDto> heap = trace.steps().get(trace.steps().size() - 1).heap();

            assertThatThrownBy(() -> heap.add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class Validation {

        @Test
        void objectCreationIsNowAllowed() {
            ParseOutcome outcome = parser.parseAndValidate(PERSON_PROGRAM);
            assertThat(outcome.isValid()).isTrue();
        }

        @Test
        void multiDimensionalArraysAreStillRejected() {
            ParseOutcome outcome = parser.parseAndValidate("""
                    public class Main {
                        public static void main(String[] args) {
                            int[][] xs = new int[3][3];
                        }
                    }
                    """);
            assertThat(outcome.isValid()).isFalse();
        }

        @Test
        void constructorArgumentsAreRejected() {
            ParseOutcome outcome = parser.parseAndValidate("""
                    class Person {
                    }

                    public class Main {
                        public static void main(String[] args) {
                            Person p = new Person(1);
                        }
                    }
                    """);
            assertThat(outcome.isValid()).isFalse();
        }

        @Test
        void instantiatingUnknownClassIsRejected() {
            ParseOutcome outcome = parser.parseAndValidate("""
                    public class Main {
                        public static void main(String[] args) {
                            Object o = new Object();
                        }
                    }
                    """);
            assertThat(outcome.isValid()).isFalse();
        }
    }

    @Nested
    class Integration {

        @Test
        void simulateReturnsStackReferencesAndHeapObjects() {
            SimulationController controller =
                    new SimulationController(new Interpreter(parser));
            ExecutionTrace trace = controller.simulate(new SimulateRequest(PERSON_PROGRAM, null));

            assertThat(trace.status()).isEqualTo("OK");
            ExecutionStepDto last = trace.steps().get(trace.steps().size() - 1);
            assertThat(last.heap()).extracting(HeapObjectDto::type).containsExactly("Person");
            assertThat(last.callStack().get(0).variables().get(0).ref()).isEqualTo(1001);
        }
    }
}
