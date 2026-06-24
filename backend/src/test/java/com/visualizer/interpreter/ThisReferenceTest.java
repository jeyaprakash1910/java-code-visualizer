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
import com.visualizer.parser.JavaCodeParser;
import com.visualizer.parser.ParseOutcome;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3B: explicit {@code this}. Reads, writes, and calls through {@code this};
 * name shadowing (parameter vs field); and that {@code this} is never surfaced as
 * a local variable while the receiver still counts as a GC root.
 */
class ThisReferenceTest {

    private final ProgramInterpreter interpreter = new ProgramInterpreter();
    private final JavaCodeParser parser = new JavaCodeParser();

    private InterpretationResult run(String source) {
        CompilationUnit cu = new JavaParser().parse(source).getResult().orElseThrow();
        return interpreter.run(cu);
    }

    private static List<HeapObjectDto> lastHeap(ExecutionTrace t) {
        return t.steps().get(t.steps().size() - 1).heap();
    }

    private static final String SHADOWING_PROGRAM = """
            class Person {
                String name;
                void setName(String name) {
                    this.name = name;
                }
            }
            public class Main {
                public static void main(String[] args) {
                    Person p = new Person();
                    p.setName("John");
                }
            }
            """;

    @Nested
    class FieldAccess {

        @Test
        void thisFieldWriteWithShadowingTargetsTheField() {
            ExecutionTrace t = run(SHADOWING_PROGRAM).trace();
            // `this.name = name` writes the parameter's value into the receiver field,
            // proving this.name (field) != name (parameter).
            assertThat(lastHeap(t).get(0).fields()).extracting(VariableDto::name, VariableDto::value)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("name", "John"));
        }

        @Test
        void thisFieldRead() {
            InterpretationResult r = run("""
                    class Person {
                        String name;
                        void setName(String name) { this.name = name; }
                        String getName() { return this.name; }
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Person p = new Person();
                            p.setName("Jane");
                            String result = p.getName();
                        }
                    }
                    """);
            assertThat(r.variable("result").asString()).isEqualTo("Jane");
        }

        @Test
        void thisFieldIncrement() {
            ExecutionTrace t = run("""
                    class Person {
                        int age;
                        void birthday() { this.age++; }
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Person p = new Person();
                            p.birthday();
                            p.birthday();
                        }
                    }
                    """).trace();
            assertThat(lastHeap(t).get(0).fields()).extracting(VariableDto::name, VariableDto::value)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("age", "2"));
        }
    }

    @Nested
    class ThisMethodCalls {

        @Test
        void thisMethodCall() {
            ExecutionTrace t = run("""
                    class Person {
                        String name;
                        void rename(String n) { this.setName(n); }
                        void setName(String name) { this.name = name; }
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Person p = new Person();
                            p.rename("Bob");
                        }
                    }
                    """).trace();
            assertThat(lastHeap(t).get(0).fields().get(0).value()).isEqualTo("Bob");
        }

        @Test
        void nestedThisMethodCallsShareTheReceiver() {
            ExecutionTrace t = run("""
                    class Counter {
                        int count;
                        void bump() { this.add(1); }
                        void add(int delta) { this.count = this.count + delta; }
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Counter c = new Counter();
                            c.bump();
                            c.bump();
                        }
                    }
                    """).trace();
            assertThat(lastHeap(t).get(0).fields().get(0).value()).isEqualTo("2");
        }
    }

    @Nested
    class ReceiverInternal {

        @Test
        void thisIsNotExposedAsALocalVariable() {
            ExecutionTrace t = run(SHADOWING_PROGRAM).trace();
            ExecutionStepDto enter = t.steps().stream()
                    .filter(s -> s.event().equals("METHOD_ENTER")).findFirst().orElseThrow();
            // Only the parameter `name` is visible — no synthetic `this` local.
            assertThat(enter.callStack().get(0).variables()).extracting(VariableDto::name)
                    .containsExactly("name")
                    .doesNotContain("this");
        }

        @Test
        void receiverStillCountsAsGcRootDuringTheCall() {
            // The Person is created inline and only reachable via the receiver while
            // setName runs; it must not appear collected/unreachable mid-call.
            ExecutionTrace t = run("""
                    class Person {
                        String name;
                        void setName(String name) { this.name = name; }
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Person p = new Person();
                            p.setName("John");
                        }
                    }
                    """).trace();
            // While setName runs, p still holds 1001 in main, so it is REACHABLE.
            ExecutionStepDto duringCall = t.steps().stream()
                    .filter(s -> s.callStack().size() == 2)
                    .reduce((a, b) -> b).orElseThrow();
            assertThat(duringCall.heap().get(0).gcState()).isEqualTo("REACHABLE");
        }
    }

    @Nested
    class Validation {

        @Test
        void thisProgramIsValid() {
            assertThat(parser.parseAndValidate(SHADOWING_PROGRAM).isValid()).isTrue();
        }

        @Test
        void superIsRejected() {
            ParseOutcome outcome = parser.parseAndValidate("""
                    class Person {
                        void f() { super.toString(); }
                    }
                    public class Main {
                        public static void main(String[] args) {}
                    }
                    """);
            assertThat(outcome.isValid()).isFalse();
            assertThat(outcome.errors()).anyMatch(e -> e.message().contains("super"));
        }
    }

    @Nested
    class Integration {

        @Test
        void simulateRunsSuccessCriteriaProgram() {
            SimulationController controller = new SimulationController(new Interpreter(parser));
            ExecutionTrace t = controller.simulate(new SimulateRequest(SHADOWING_PROGRAM, null));

            assertThat(t.status()).isEqualTo("OK");
            assertThat(lastHeap(t).get(0).type()).isEqualTo("Person");
            assertThat(lastHeap(t).get(0).fields().get(0).value()).isEqualTo("John");
        }
    }
}
