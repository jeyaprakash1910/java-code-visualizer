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
import com.visualizer.parser.ParseOutcome;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3A: instance methods. Invocation on a receiver, implicit field binding,
 * primitive/reference/void returns, nested calls, call-stack frames, and validation.
 */
class InstanceMethodTest {

    private final ProgramInterpreter interpreter = new ProgramInterpreter();
    private final JavaCodeParser parser = new JavaCodeParser();

    private InterpretationResult run(String source) {
        CompilationUnit cu = new JavaParser().parse(source).getResult().orElseThrow();
        return interpreter.run(cu);
    }

    private static List<HeapObjectDto> lastHeap(ExecutionTrace t) {
        return t.steps().get(t.steps().size() - 1).heap();
    }

    private static final String SET_NAME_PROGRAM = """
            class Person {
                String name;
                void setName(String n) {
                    name = n;
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
    class FieldMutation {

        @Test
        void instanceMethodWritesReceiverField() {
            ExecutionTrace t = run(SET_NAME_PROGRAM).trace();
            HeapObjectDto person = lastHeap(t).get(0);
            assertThat(person.type()).isEqualTo("Person");
            assertThat(person.fields()).extracting(VariableDto::name, VariableDto::value)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("name", "John"));
        }

        @Test
        void instanceMethodReadsReceiverField() {
            InterpretationResult r = run("""
                    class Person {
                        String name;
                        void setName(String n) { name = n; }
                        String getName() { return name; }
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
    }

    @Nested
    class ReturnValues {

        @Test
        void primitiveReturnFromInstanceMethod() {
            InterpretationResult r = run("""
                    class Calculator {
                        int add(int a, int b) { return a + b; }
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Calculator c = new Calculator();
                            int result = c.add(10, 20);
                        }
                    }
                    """);
            assertThat(r.variable("result").asInt()).isEqualTo(30);
        }

        @Test
        void referenceReturnFromInstanceMethod() {
            InterpretationResult r = run("""
                    class Factory {
                        Person create() { return new Person(); }
                    }
                    class Person {
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Factory f = new Factory();
                            Person p = f.create();
                        }
                    }
                    """);
            // f is 1001, the created Person is 1002.
            assertThat(((ReferenceValue) r.variable("p")).objectId()).isEqualTo(1002);
        }

        @Test
        void voidInstanceMethodRunsForSideEffects() {
            InterpretationResult r = run("""
                    class Greeter {
                        void greet() { System.out.println("hi"); }
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Greeter g = new Greeter();
                            g.greet();
                        }
                    }
                    """);
            assertThat(r.output()).isEqualTo("hi\n");
        }
    }

    @Nested
    class NestedCalls {

        @Test
        void instanceMethodResultFeedsAnotherCall() {
            InterpretationResult r = run("""
                    class Calculator {
                        int add(int a, int b) { return a + b; }
                        int twice(int x) { return add(x, x); }
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Calculator c = new Calculator();
                            int result = c.twice(c.add(2, 3));
                        }
                    }
                    """);
            assertThat(r.variable("result").asInt()).isEqualTo(10); // (2+3)+(2+3)
        }
    }

    @Nested
    class CallStackAndTrace {

        @Test
        void instanceCallPushesAFrameAndQualifiesEvents() {
            ExecutionTrace t = run(SET_NAME_PROGRAM).trace();

            assertThat(t.steps()).anyMatch(s -> s.event().equals("METHOD_ENTER")
                    && s.description().equals("Enter method Person.setName"));
            assertThat(t.steps()).anyMatch(s -> s.event().equals("METHOD_EXIT")
                    && s.description().equals("Exit method Person.setName"));

            ExecutionStepDto enter = t.steps().stream()
                    .filter(s -> s.event().equals("METHOD_ENTER")).findFirst().orElseThrow();
            assertThat(enter.callStack()).hasSize(2);
            StackFrameDto top = enter.callStack().get(0);
            assertThat(top.methodName()).isEqualTo("setName");
            assertThat(top.className()).isEqualTo("Person");
            assertThat(top.variables()).extracting(VariableDto::name, VariableDto::value)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("n", "John"));
            assertThat(enter.callStack().get(1).methodName()).isEqualTo("main");
        }

        @Test
        void framePopsAfterInstanceMethodReturns() {
            ExecutionTrace t = run(SET_NAME_PROGRAM).trace();
            ExecutionStepDto last = t.steps().get(t.steps().size() - 1);
            assertThat(last.callStack()).hasSize(1);
            assertThat(last.callStack().get(0).methodName()).isEqualTo("main");
        }
    }

    @Nested
    class Validation {

        @Test
        void instanceMethodProgramIsValid() {
            ParseOutcome outcome = parser.parseAndValidate(SET_NAME_PROGRAM);
            assertThat(outcome.isValid()).isTrue();
        }

        @Test
        void recursiveInstanceMethodIsAllowed() {
            // Phase 3E: direct recursion is supported, including on instance methods.
            ParseOutcome outcome = parser.parseAndValidate("""
                    class Counter {
                        int sum(int n) { if (n == 0) return 0; return n + sum(n - 1); }
                    }
                    public class Main {
                        public static void main(String[] args) {}
                    }
                    """);
            assertThat(outcome.isValid()).isTrue();
        }

        @Test
        void overloadedInstanceMethodIsRejected() {
            ParseOutcome outcome = parser.parseAndValidate("""
                    class C {
                        int f() { return 1; }
                        int f(int x) { return x; }
                    }
                    public class Main {
                        public static void main(String[] args) {}
                    }
                    """);
            assertThat(outcome.isValid()).isFalse();
            assertThat(outcome.errors()).anyMatch(e -> e.message().contains("overloading"));
        }

        @Test
        void constructorIsNowAllowed() {
            // Phase 3C: constructors are supported.
            ParseOutcome outcome = parser.parseAndValidate("""
                    class Person {
                        Person() {}
                    }
                    public class Main {
                        public static void main(String[] args) {}
                    }
                    """);
            assertThat(outcome.isValid()).isTrue();
        }
    }

    @Nested
    class Integration {

        @Test
        void simulateRunsSuccessCriteriaProgram() {
            SimulationController controller = new SimulationController(new Interpreter(parser));
            ExecutionTrace t = controller.simulate(new SimulateRequest(SET_NAME_PROGRAM, null));

            assertThat(t.status()).isEqualTo("OK");
            // Some step shows setName() over main().
            assertThat(t.steps()).anyMatch(s -> s.callStack().size() == 2
                    && s.callStack().get(0).methodName().equals("setName")
                    && s.callStack().get(0).className().equals("Person"));
            // Final heap: the Person's name field is John.
            assertThat(lastHeap(t).get(0).fields().get(0).value()).isEqualTo("John");
        }
    }
}
