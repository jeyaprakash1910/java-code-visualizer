package com.visualizer.interpreter;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.visualizer.api.SimulationController;
import com.visualizer.api.dto.ExecutionStepDto;
import com.visualizer.api.dto.ExecutionTrace;
import com.visualizer.api.dto.HeapObjectDto;
import com.visualizer.api.dto.SimulateRequest;
import com.visualizer.api.dto.StackFrameDto;
import com.visualizer.api.dto.VariableDto;
import com.visualizer.interpreter.engine.InterpretationResult;
import com.visualizer.interpreter.engine.ProgramInterpreter;
import com.visualizer.interpreter.runtime.ReferenceValue;
import com.visualizer.parser.JavaCodeParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2C: reference aliasing. Demonstrates that Java copies references, not
 * objects — {@code Person b = a} makes both variables point at the same heap id,
 * mutation through one alias is observed through the other, and no extra object
 * is allocated.
 */
class ReferenceAliasingTest {

    private final ProgramInterpreter interpreter = new ProgramInterpreter();
    private final JavaCodeParser parser = new JavaCodeParser();

    private InterpretationResult run(String source) {
        CompilationUnit cu = new JavaParser().parse(source).getResult().orElseThrow();
        return interpreter.run(cu);
    }

    private ExecutionTrace trace(String source) {
        return run(source).trace();
    }

    private static final String SUCCESS_PROGRAM = """
            class Person {
                String name;
            }

            public class Main {
                public static void main(String[] args) {
                    Person a = new Person();
                    Person b = a;
                    b.name = "John";
                }
            }
            """;

    @Nested
    class ReferenceAssignment {

        @Test
        void aliasPointsAtSameObjectId() {
            InterpretationResult r = run(SUCCESS_PROGRAM);
            int idA = ((ReferenceValue) r.variable("a")).objectId();
            int idB = ((ReferenceValue) r.variable("b")).objectId();
            assertThat(idA).isEqualTo(1001);
            assertThat(idB).isEqualTo(1001);
        }

        @Test
        void multipleAliasesShareOneId() {
            InterpretationResult r = run("""
                    class Person {
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Person a = new Person();
                            Person b = a;
                            Person c = b;
                        }
                    }
                    """);
            assertThat(((ReferenceValue) r.variable("a")).objectId()).isEqualTo(1001);
            assertThat(((ReferenceValue) r.variable("b")).objectId()).isEqualTo(1001);
            assertThat(((ReferenceValue) r.variable("c")).objectId()).isEqualTo(1001);
        }
    }

    @Nested
    class SharedState {

        @Test
        void heapHoldsExactlyOneObjectDespiteAliasing() {
            ExecutionTrace t = trace(SUCCESS_PROGRAM);
            assertThat(lastHeap(t)).hasSize(1);
            assertThat(lastHeap(t).get(0).id()).isEqualTo(1001);
        }

        @Test
        void mutationThroughAliasIsObservedByBothReferences() {
            ExecutionTrace t = trace(SUCCESS_PROGRAM);
            ExecutionStepDto last = t.steps().get(t.steps().size() - 1);

            // One heap object whose field is now "John".
            HeapObjectDto person = last.heap().get(0);
            assertThat(person.fields()).extracting(VariableDto::name, VariableDto::value)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("name", "John"));

            // Both stack variables reference that same id.
            StackFrameDto frame = last.callStack().get(0);
            assertThat(frame.variables()).extracting(VariableDto::name, VariableDto::ref)
                    .contains(
                            org.assertj.core.groups.Tuple.tuple("a", 1001),
                            org.assertj.core.groups.Tuple.tuple("b", 1001));
        }
    }

    @Nested
    class TraceCorrectness {

        @Test
        void referenceCopyIsDescribedAsReference() {
            ExecutionTrace t = trace(SUCCESS_PROGRAM);
            assertThat(t.steps()).anyMatch(s -> s.event().equals("DECLARE")
                    && s.description().equals("Declare variable b = reference 1001"));
        }

        @Test
        void plainReferenceAssignmentIsDescribedAsReference() {
            ExecutionTrace t = trace("""
                    class Person {
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Person a = new Person();
                            Person b = new Person();
                            b = a;
                        }
                    }
                    """);
            assertThat(t.steps()).anyMatch(s -> s.event().equals("ASSIGN")
                    && s.description().equals("Assign reference 1001 to b"));
        }

        @Test
        void aliasingDeclarationDoesNotChangeHeap() {
            ExecutionTrace t = trace(SUCCESS_PROGRAM);
            // The step that declares the alias `b` must show the heap unchanged:
            // still exactly one object, and no new id allocated.
            ExecutionStepDto aliasStep = t.steps().stream()
                    .filter(s -> s.description().equals("Declare variable b = reference 1001"))
                    .findFirst().orElseThrow();
            assertThat(aliasStep.heap()).hasSize(1);
            assertThat(aliasStep.heap().get(0).id()).isEqualTo(1001);
        }
    }

    @Nested
    class Integration {

        @Test
        void simulateShowsTwoReferencesToOneSharedObject() {
            SimulationController controller = new SimulationController(new Interpreter(parser));
            ExecutionTrace t = controller.simulate(new SimulateRequest(SUCCESS_PROGRAM, null));

            assertThat(t.status()).isEqualTo("OK");
            ExecutionStepDto last = t.steps().get(t.steps().size() - 1);
            assertThat(last.heap()).hasSize(1);
            assertThat(last.callStack().get(0).variables())
                    .extracting(VariableDto::ref)
                    .containsExactly(1001, 1001);
            assertThat(last.heap().get(0).fields().get(0).value()).isEqualTo("John");
        }
    }

    private static List<HeapObjectDto> lastHeap(ExecutionTrace trace) {
        return trace.steps().get(trace.steps().size() - 1).heap();
    }
}
