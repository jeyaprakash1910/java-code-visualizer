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
import com.visualizer.parser.JavaCodeParser;
import com.visualizer.parser.ParseOutcome;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3C: constructors. Object initialization through default and parameterized
 * constructors, the constructor call stack, multiple instances, and validation.
 */
class ConstructorTest {

    private final ProgramInterpreter interpreter = new ProgramInterpreter();
    private final JavaCodeParser parser = new JavaCodeParser();

    private InterpretationResult run(String source) {
        CompilationUnit cu = new JavaParser().parse(source).getResult().orElseThrow();
        return interpreter.run(cu);
    }

    private static List<HeapObjectDto> lastHeap(ExecutionTrace t) {
        return t.steps().get(t.steps().size() - 1).heap();
    }

    private static final String PARAM_CTOR_PROGRAM = """
            class Person {
                String name;
                Person(String name) {
                    this.name = name;
                }
            }
            public class Main {
                public static void main(String[] args) {
                    Person p = new Person("John");
                }
            }
            """;

    @Nested
    class Initialization {

        @Test
        void defaultConstructorInitializesFields() {
            ExecutionTrace t = run("""
                    class Person {
                        String name;
                        Person() {
                            this.name = "Unknown";
                        }
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Person p = new Person();
                        }
                    }
                    """).trace();
            assertThat(lastHeap(t).get(0).fields()).extracting(VariableDto::name, VariableDto::value)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("name", "Unknown"));
        }

        @Test
        void parameterizedConstructorInitializesFields() {
            ExecutionTrace t = run(PARAM_CTOR_PROGRAM).trace();
            assertThat(lastHeap(t).get(0).fields()).extracting(VariableDto::name, VariableDto::value)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("name", "John"));
        }

        @Test
        void classWithNoConstructorStillUsesDefaults() {
            ExecutionTrace t = run("""
                    class Person {
                        int age;
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Person p = new Person();
                        }
                    }
                    """).trace();
            assertThat(lastHeap(t).get(0).fields().get(0).value()).isEqualTo("0");
            // No explicit constructor → no constructor events.
            assertThat(t.steps()).noneMatch(s -> s.event().equals("CONSTRUCTOR_ENTER"));
        }

        @Test
        void multipleInstancesAreIndependent() {
            ExecutionTrace t = run("""
                    class Person {
                        String name;
                        Person(String name) { this.name = name; }
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Person a = new Person("Alice");
                            Person b = new Person("Bob");
                        }
                    }
                    """).trace();
            assertThat(lastHeap(t)).hasSize(2);
            assertThat(lastHeap(t).get(0).fields().get(0).value()).isEqualTo("Alice");
            assertThat(lastHeap(t).get(1).fields().get(0).value()).isEqualTo("Bob");
        }
    }

    @Nested
    class CallStackAndTrace {

        @Test
        void constructorCallPushesAFrameWithParameters() {
            ExecutionTrace t = run(PARAM_CTOR_PROGRAM).trace();

            assertThat(t.steps()).anyMatch(s -> s.event().equals("CONSTRUCTOR_ENTER")
                    && s.description().equals("Enter constructor Person"));
            assertThat(t.steps()).anyMatch(s -> s.event().equals("CONSTRUCTOR_EXIT")
                    && s.description().equals("Exit constructor Person"));

            ExecutionStepDto enter = t.steps().stream()
                    .filter(s -> s.event().equals("CONSTRUCTOR_ENTER")).findFirst().orElseThrow();
            assertThat(enter.callStack()).hasSize(2);
            StackFrameDto top = enter.callStack().get(0);
            assertThat(top.methodName()).isEqualTo("Person");
            assertThat(top.className()).isEqualTo("Person");
            assertThat(top.variables()).extracting(VariableDto::name, VariableDto::value)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("name", "John"));
            // `this` is never a local variable.
            assertThat(top.variables()).extracting(VariableDto::name).doesNotContain("this");
            assertThat(enter.callStack().get(1).methodName()).isEqualTo("main");
        }

        @Test
        void framePopsAfterConstructorReturns() {
            ExecutionTrace t = run(PARAM_CTOR_PROGRAM).trace();
            ExecutionStepDto last = t.steps().get(t.steps().size() - 1);
            assertThat(last.callStack()).hasSize(1);
            assertThat(last.callStack().get(0).methodName()).isEqualTo("main");
            // The created object reference reached the local `p`.
            assertThat(last.callStack().get(0).variables().get(0).ref()).isEqualTo(1001);
        }
    }

    @Nested
    class Validation {

        @Test
        void constructorProgramIsValid() {
            assertThat(parser.parseAndValidate(PARAM_CTOR_PROGRAM).isValid()).isTrue();
        }

        @Test
        void overloadedConstructorsAreRejected() {
            ParseOutcome outcome = parser.parseAndValidate("""
                    class Person {
                        String name;
                        Person() {}
                        Person(String name) { this.name = name; }
                    }
                    public class Main {
                        public static void main(String[] args) {}
                    }
                    """);
            assertThat(outcome.isValid()).isFalse();
            assertThat(outcome.errors()).anyMatch(e -> e.message().contains("constructors"));
        }

        @Test
        void constructorDelegationIsRejected() {
            ParseOutcome outcome = parser.parseAndValidate("""
                    class Person {
                        String name;
                        Person() { this("X"); }
                        Person(String n) { this.name = n; }
                    }
                    public class Main {
                        public static void main(String[] args) {}
                    }
                    """);
            assertThat(outcome.isValid()).isFalse();
        }

        @Test
        void argumentsWithoutAConstructorAreRejected() {
            ParseOutcome outcome = parser.parseAndValidate("""
                    class Person {
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Person p = new Person("oops");
                        }
                    }
                    """);
            assertThat(outcome.isValid()).isFalse();
        }
    }

    @Nested
    class Integration {

        @Test
        void simulateRunsSuccessCriteriaProgram() {
            SimulationController controller = new SimulationController(new Interpreter(parser));
            ExecutionTrace t = controller.simulate(new SimulateRequest(PARAM_CTOR_PROGRAM, null));

            assertThat(t.status()).isEqualTo("OK");
            // Some step shows Person() over main().
            assertThat(t.steps()).anyMatch(s -> s.callStack().size() == 2
                    && s.callStack().get(0).methodName().equals("Person"));
            assertThat(lastHeap(t).get(0).type()).isEqualTo("Person");
            assertThat(lastHeap(t).get(0).fields().get(0).value()).isEqualTo("John");
        }
    }
}
