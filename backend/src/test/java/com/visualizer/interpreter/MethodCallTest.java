package com.visualizer.interpreter;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.visualizer.api.SimulationController;
import com.visualizer.api.dto.ExecutionStepDto;
import com.visualizer.api.dto.ExecutionTrace;
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
 * Phase 2D: static methods, parameters, return values, and call-stack frames.
 * Demonstrates Java pass-by-value for both primitives and references.
 */
class MethodCallTest {

    private final ProgramInterpreter interpreter = new ProgramInterpreter();
    private final JavaCodeParser parser = new JavaCodeParser();

    private InterpretationResult run(String source) {
        CompilationUnit cu = new JavaParser().parse(source).getResult().orElseThrow();
        return interpreter.run(cu);
    }

    private ExecutionTrace trace(String source) {
        return run(source).trace();
    }

    @Nested
    class ReturnValues {

        @Test
        void staticMethodReturnsPrimitiveSum() {
            InterpretationResult r = run("""
                    public class Main {
                        public static int add(int a, int b) {
                            return a + b;
                        }
                        public static void main(String[] args) {
                            int result = add(10, 20);
                        }
                    }
                    """);
            assertThat(r.variable("result").asInt()).isEqualTo(30);
        }

        @Test
        void stringReturnFlowsBack() {
            InterpretationResult r = run("""
                    public class Main {
                        public static String getName() {
                            return "John";
                        }
                        public static void main(String[] args) {
                            String s = getName();
                        }
                    }
                    """);
            assertThat(r.variable("s").asString()).isEqualTo("John");
        }

        @Test
        void referenceReturnFlowsBack() {
            InterpretationResult r = run("""
                    class Person {
                        String name;
                    }
                    public class Main {
                        public static Person createPerson() {
                            return new Person();
                        }
                        public static void main(String[] args) {
                            Person p = createPerson();
                        }
                    }
                    """);
            assertThat(((ReferenceValue) r.variable("p")).objectId()).isEqualTo(1001);
        }
    }

    @Nested
    class ParameterSemantics {

        @Test
        void primitiveParameterIsPassedByValue() {
            InterpretationResult r = run("""
                    public class Main {
                        public static void change(int n) {
                            n = 999;
                        }
                        public static void main(String[] args) {
                            int x = 10;
                            change(x);
                        }
                    }
                    """);
            // Mutating the parameter must NOT affect the caller's variable.
            assertThat(r.variable("x").asInt()).isEqualTo(10);
        }

        @Test
        void referenceParameterSharesTheObject() {
            InterpretationResult r = run("""
                    class Person {
                        String name;
                    }
                    public class Main {
                        public static void update(Person p) {
                            p.name = "John";
                        }
                        public static void main(String[] args) {
                            Person person = new Person();
                            update(person);
                        }
                    }
                    """);
            // The shared heap object was mutated through the parameter alias.
            ExecutionStepDto last = r.trace().steps().get(r.trace().steps().size() - 1);
            assertThat(last.heap()).hasSize(1);
            assertThat(last.heap().get(0).fields().get(0).value()).isEqualTo("John");
            assertThat(((ReferenceValue) r.variable("person")).objectId()).isEqualTo(1001);
        }

        @Test
        void reassigningReferenceParameterDoesNotAffectCaller() {
            InterpretationResult r = run("""
                    class Person {
                    }
                    public class Main {
                        public static void replace(Person p) {
                            p = new Person();
                        }
                        public static void main(String[] args) {
                            Person a = new Person();
                            replace(a);
                        }
                    }
                    """);
            // Caller still points at the original object (1001), not the one the
            // method allocated (1002).
            assertThat(((ReferenceValue) r.variable("a")).objectId()).isEqualTo(1001);
        }
    }

    @Nested
    class VoidAndMultipleCalls {

        @Test
        void voidMethodRunsForSideEffects() {
            InterpretationResult r = run("""
                    public class Main {
                        public static void greet() {
                            System.out.println("hi");
                        }
                        public static void main(String[] args) {
                            greet();
                        }
                    }
                    """);
            assertThat(r.output()).isEqualTo("hi\n");
        }

        @Test
        void multipleAndNestedCallsResolveCorrectly() {
            InterpretationResult r = run("""
                    public class Main {
                        public static int square(int n) {
                            return n * n;
                        }
                        public static int add(int a, int b) {
                            return a + b;
                        }
                        public static void main(String[] args) {
                            int x = square(3);
                            int y = add(square(2), 1);
                        }
                    }
                    """);
            assertThat(r.variable("x").asInt()).isEqualTo(9);
            assertThat(r.variable("y").asInt()).isEqualTo(5); // 2*2 + 1
        }
    }

    @Nested
    class CallStackAndTrace {

        @Test
        void enteringMethodPushesAFrameWithBoundParameters() {
            ExecutionTrace t = trace("""
                    public class Main {
                        public static int add(int a, int b) {
                            return a + b;
                        }
                        public static void main(String[] args) {
                            int result = add(10, 20);
                        }
                    }
                    """);
            ExecutionStepDto enter = t.steps().stream()
                    .filter(s -> s.event().equals("METHOD_ENTER"))
                    .findFirst().orElseThrow();

            // Two frames: add() on top (current), main() beneath.
            assertThat(enter.callStack()).hasSize(2);
            StackFrameDto top = enter.callStack().get(0);
            assertThat(top.methodName()).isEqualTo("add");
            assertThat(top.isCurrent()).isTrue();
            assertThat(top.variables()).extracting(VariableDto::name, VariableDto::value)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("a", "10"),
                            org.assertj.core.groups.Tuple.tuple("b", "20"));
            assertThat(enter.callStack().get(1).methodName()).isEqualTo("main");
        }

        @Test
        void emitsReturnAndExitEventsAndPopsTheFrame() {
            ExecutionTrace t = trace("""
                    public class Main {
                        public static int add(int a, int b) {
                            return a + b;
                        }
                        public static void main(String[] args) {
                            int result = add(10, 20);
                        }
                    }
                    """);
            assertThat(t.steps()).anyMatch(s -> s.event().equals("RETURN")
                    && s.description().equals("Return value 30"));

            // After METHOD_EXIT the add() frame is gone — only main() remains.
            ExecutionStepDto exit = t.steps().stream()
                    .filter(s -> s.event().equals("METHOD_EXIT"))
                    .findFirst().orElseThrow();
            assertThat(exit.callStack()).hasSize(1);
            assertThat(exit.callStack().get(0).methodName()).isEqualTo("main");

            // Event ordering: ENTER ... RETURN ... EXIT.
            List<String> events = t.steps().stream().map(ExecutionStepDto::event).toList();
            assertThat(events.indexOf("METHOD_ENTER"))
                    .isLessThan(events.indexOf("RETURN"));
            assertThat(events.indexOf("RETURN"))
                    .isLessThan(events.indexOf("METHOD_EXIT"));

            // Final state: result assigned in main.
            assertThat(t.steps().get(t.steps().size() - 1).callStack().get(0).variables())
                    .extracting(VariableDto::name, VariableDto::value)
                    .contains(org.assertj.core.groups.Tuple.tuple("result", "30"));
        }
    }

    @Nested
    class Validation {

        @Test
        void supportedMethodProgramIsValid() {
            ParseOutcome outcome = parser.parseAndValidate("""
                    public class Main {
                        public static int add(int a, int b) { return a + b; }
                        public static void main(String[] args) {
                            int result = add(10, 20);
                        }
                    }
                    """);
            assertThat(outcome.isValid()).isTrue();
        }

        @Test
        void callingUnknownMethodIsRejected() {
            ParseOutcome outcome = parser.parseAndValidate("""
                    public class Main {
                        public static void main(String[] args) {
                            int x = missing(1);
                        }
                    }
                    """);
            assertThat(outcome.isValid()).isFalse();
        }
    }

    @Nested
    class Integration {

        @Test
        void simulateDemonstratesReferencePassByValue() {
            SimulationController controller = new SimulationController(new Interpreter(parser));
            ExecutionTrace t = controller.simulate(new SimulateRequest("""
                    class Person {
                        String name;
                    }
                    public class Main {
                        public static void update(Person p) {
                            p.name = "John";
                        }
                        public static void main(String[] args) {
                            Person person = new Person();
                            update(person);
                        }
                    }
                    """, null));

            assertThat(t.status()).isEqualTo("OK");
            // Some step shows both update() and main() frames.
            assertThat(t.steps()).anyMatch(s -> s.callStack().size() == 2
                    && s.callStack().get(0).methodName().equals("update"));
            // Final heap: one Person whose name is John.
            ExecutionStepDto last = t.steps().get(t.steps().size() - 1);
            assertThat(last.heap()).hasSize(1);
            assertThat(last.heap().get(0).fields().get(0).value()).isEqualTo("John");
        }
    }
}
