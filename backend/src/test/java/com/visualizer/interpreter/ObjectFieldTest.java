package com.visualizer.interpreter;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.visualizer.api.SimulationController;
import com.visualizer.api.dto.ExecutionStepDto;
import com.visualizer.api.dto.ExecutionTrace;
import com.visualizer.api.dto.HeapObjectDto;
import com.visualizer.api.dto.SimulateRequest;
import com.visualizer.api.dto.VariableDto;
import com.visualizer.interpreter.engine.InterpretationResult;
import com.visualizer.interpreter.engine.ProgramInterpreter;
import com.visualizer.interpreter.runtime.ReferenceValue;
import com.visualizer.parser.JavaCodeParser;
import com.visualizer.parser.ParseOutcome;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2B: object fields and heap mutation. Default initialization, field
 * assignment, multiple fields/objects, heap-snapshot correctness, and the
 * controller integration path.
 */
class ObjectFieldTest {

    private final ProgramInterpreter interpreter = new ProgramInterpreter();
    private final JavaCodeParser parser = new JavaCodeParser();

    private InterpretationResult run(String source) {
        CompilationUnit cu = new JavaParser().parse(source).getResult().orElseThrow();
        return interpreter.run(cu);
    }

    private ExecutionTrace trace(String source) {
        return interpreter.run(new JavaParser().parse(source).getResult().orElseThrow()).trace();
    }

    private static final String SUCCESS_PROGRAM = """
            class Person {
                String name;
                int age;
            }

            public class Main {
                public static void main(String[] args) {
                    Person p = new Person();
                    p.name = "John";
                    p.age = 25;
                }
            }
            """;

    @Nested
    class DefaultInitialization {

        @Test
        void fieldsStartAtJavaDefaults() {
            ExecutionTrace t = trace("""
                    class Box {
                        int count;
                        double ratio;
                        boolean flag;
                        String label;
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Box b = new Box();
                        }
                    }
                    """);
            HeapObjectDto box = lastHeap(t).get(0);
            // A null String renders via its display text ("null"); it stays a
            // PRIMITIVE String slot (Strings are scalars in this runtime).
            assertThat(box.fields()).extracting(VariableDto::name, VariableDto::value, VariableDto::ref)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("count", "0", null),
                            org.assertj.core.groups.Tuple.tuple("ratio", "0.0", null),
                            org.assertj.core.groups.Tuple.tuple("flag", "false", null),
                            org.assertj.core.groups.Tuple.tuple("label", "null", null));
            VariableDto label = box.fields().get(3);
            assertThat(label.kind()).isEqualTo("PRIMITIVE");
            assertThat(label.declaredType()).isEqualTo("String");
        }
    }

    @Nested
    class Assignment {

        @Test
        void assignsAndReadsBackFields() {
            InterpretationResult r = run(SUCCESS_PROGRAM);
            // Read the fields back through the interpreter to confirm mutation.
            assertThat(((ReferenceValue) r.variable("p")).objectId()).isEqualTo(1001);

            HeapObjectDto person = lastHeap(r.trace()).get(0);
            assertThat(person.type()).isEqualTo("Person");
            assertThat(person.fields()).extracting(VariableDto::name, VariableDto::value)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("name", "John"),
                            org.assertj.core.groups.Tuple.tuple("age", "25"));
        }

        @Test
        void emitsAssignStepsForFieldUpdates() {
            ExecutionTrace t = trace(SUCCESS_PROGRAM);
            assertThat(t.steps()).anyMatch(s -> s.event().equals("ASSIGN")
                    && s.description().equals("Assign value John to p.name"));
            assertThat(t.steps()).anyMatch(s -> s.event().equals("ASSIGN")
                    && s.description().equals("Assign value 25 to p.age"));
        }

        @Test
        void heapStateChangesAcrossSteps() {
            ExecutionTrace t = trace(SUCCESS_PROGRAM);
            // First ASSIGN step: name set, age still default 0.
            ExecutionStepDto afterName = t.steps().stream()
                    .filter(s -> s.description().equals("Assign value John to p.name"))
                    .findFirst().orElseThrow();
            assertThat(afterName.heap().get(0).fields().get(0).value()).isEqualTo("John");
            assertThat(afterName.heap().get(0).fields().get(1).value()).isEqualTo("0");

            // Final step: both fields set — earlier snapshot must be untouched.
            HeapObjectDto finalPerson = lastHeap(t).get(0);
            assertThat(finalPerson.fields().get(1).value()).isEqualTo("25");
            assertThat(afterName.heap().get(0).fields().get(1).value()).isEqualTo("0");
        }
    }

    @Nested
    class MultipleInstances {

        @Test
        void distinctObjectsCarryIndependentFieldState() {
            ExecutionTrace t = trace("""
                    class Person {
                        int age;
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Person a = new Person();
                            Person b = new Person();
                            a.age = 10;
                            b.age = 20;
                        }
                    }
                    """);
            var heap = lastHeap(t);
            assertThat(heap).hasSize(2);
            assertThat(heap.get(0).id()).isEqualTo(1001);
            assertThat(heap.get(0).fields().get(0).value()).isEqualTo("10");
            assertThat(heap.get(1).id()).isEqualTo(1002);
            assertThat(heap.get(1).fields().get(0).value()).isEqualTo("20");
        }
    }

    @Nested
    class Validation {

        @Test
        void fieldDeclarationsAndAssignmentsAreAllowed() {
            ParseOutcome outcome = parser.parseAndValidate(SUCCESS_PROGRAM);
            assertThat(outcome.isValid()).isTrue();
        }
    }

    @Nested
    class Integration {

        @Test
        void simulateReturnsHeapWithFieldState() {
            SimulationController controller = new SimulationController(new Interpreter(parser));
            ExecutionTrace t = controller.simulate(new SimulateRequest(SUCCESS_PROGRAM, null));

            assertThat(t.status()).isEqualTo("OK");
            HeapObjectDto person = lastHeap(t).get(0);
            assertThat(person.fields()).extracting(VariableDto::name, VariableDto::value)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("name", "John"),
                            org.assertj.core.groups.Tuple.tuple("age", "25"));
        }
    }

    private static java.util.List<HeapObjectDto> lastHeap(ExecutionTrace trace) {
        return trace.steps().get(trace.steps().size() - 1).heap();
    }
}
