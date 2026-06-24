package com.visualizer.interpreter;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.visualizer.api.SimulationController;
import com.visualizer.api.dto.ExecutionStepDto;
import com.visualizer.api.dto.ExecutionTrace;
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
 * Phase 3E: recursion visualization. Direct recursion runs as ordinary method
 * invocation — frames grow, parameters stay isolated, and returns unwind in order.
 */
class RecursionTest {

    private final ProgramInterpreter interpreter = new ProgramInterpreter();
    private final JavaCodeParser parser = new JavaCodeParser();

    private InterpretationResult run(String source) {
        CompilationUnit cu = new JavaParser().parse(source).getResult().orElseThrow();
        return interpreter.run(cu);
    }

    private static final String FACTORIAL = """
            public class Main {
                static int factorial(int n) {
                    if (n <= 1) {
                        return 1;
                    }
                    return n * factorial(n - 1);
                }
                public static void main(String[] args) {
                    int result = factorial(4);
                }
            }
            """;

    @Nested
    class ReturnValues {

        @Test
        void recursiveFactorial() {
            InterpretationResult r = run(FACTORIAL);
            assertThat(r.variable("result").asInt()).isEqualTo(24);
        }

        @Test
        void recursiveCountdown() {
            InterpretationResult r = run("""
                    public class Main {
                        static int countdown(int n) {
                            if (n == 0) { return 0; }
                            return countdown(n - 1);
                        }
                        public static void main(String[] args) {
                            int result = countdown(5);
                        }
                    }
                    """);
            assertThat(r.variable("result").asInt()).isZero();
        }

        @Test
        void recursiveSum() {
            InterpretationResult r = run("""
                    public class Main {
                        static int sum(int n) {
                            if (n == 0) { return 0; }
                            return n + sum(n - 1);
                        }
                        public static void main(String[] args) {
                            int result = sum(5);
                        }
                    }
                    """);
            assertThat(r.variable("result").asInt()).isEqualTo(15); // 5+4+3+2+1
        }

        @Test
        void recursiveInstanceMethod() {
            InterpretationResult r = run("""
                    class Counter {
                        int sum(int n) {
                            if (n == 0) { return 0; }
                            return n + sum(n - 1);
                        }
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Counter c = new Counter();
                            int result = c.sum(4);
                        }
                    }
                    """);
            assertThat(r.variable("result").asInt()).isEqualTo(10); // 4+3+2+1
        }
    }

    @Nested
    class StackBehavior {

        @Test
        void framesGrowToTheRecursionDepth() {
            ExecutionTrace t = run(FACTORIAL).trace();
            int maxDepth = t.steps().stream().mapToInt(s -> s.callStack().size()).max().orElseThrow();
            // main + factorial(4..1) = 5 frames at the deepest point.
            assertThat(maxDepth).isEqualTo(5);
        }

        @Test
        void eachFrameKeepsItsOwnParameterValue() {
            ExecutionTrace t = run(FACTORIAL).trace();
            // At the deepest step, the factorial frames hold n = 1,2,3,4 (top→bottom),
            // proving per-frame parameter isolation.
            ExecutionStepDto deepest = t.steps().stream()
                    .filter(s -> s.callStack().size() == 5)
                    .findFirst().orElseThrow();
            List<String> ns = deepest.callStack().stream()
                    .filter(f -> f.methodName().equals("factorial"))
                    .map(f -> f.variables().get(0).value())
                    .toList();
            assertThat(ns).containsExactly("1", "2", "3", "4");
        }
    }

    @Nested
    class Trace {

        @Test
        void returnsUnwindInOrder() {
            ExecutionTrace t = run(FACTORIAL).trace();
            List<String> returns = t.steps().stream()
                    .filter(s -> s.event().equals("RETURN"))
                    .map(ExecutionStepDto::description)
                    .toList();
            // factorial(1)->1, then 2, 6, 24 as the stack unwinds.
            assertThat(returns).containsExactly(
                    "Return value 1",
                    "Return value 2",
                    "Return value 6",
                    "Return value 24");
        }

        @Test
        void enterAndExitEventsAreBalanced() {
            ExecutionTrace t = run(FACTORIAL).trace();
            long enters = t.steps().stream().filter(s -> s.event().equals("METHOD_ENTER")).count();
            long exits = t.steps().stream().filter(s -> s.event().equals("METHOD_EXIT")).count();
            assertThat(enters).isEqualTo(4); // factorial(4),(3),(2),(1)
            assertThat(exits).isEqualTo(4);
        }
    }

    @Nested
    class Safety {

        @Test
        void runawayRecursionHitsTheExecutionLimit() {
            // No base case → unbounded recursion, stopped by ExecutionContext.
            ExecutionTrace t = new Interpreter(parser).simulate("""
                    public class Main {
                        static int spin(int n) {
                            return spin(n + 1);
                        }
                        public static void main(String[] args) {
                            int result = spin(0);
                        }
                    }
                    """);
            assertThat(t.status()).isEqualTo("ERROR");
            assertThat(t.error().type()).isEqualTo("EXECUTION_LIMIT");
        }
    }

    @Nested
    class Validation {

        @Test
        void directRecursionIsValid() {
            assertThat(parser.parseAndValidate(FACTORIAL).isValid()).isTrue();
        }

        @Test
        void mutualRecursionIsRejected() {
            ParseOutcome outcome = parser.parseAndValidate("""
                    public class Main {
                        static boolean isEven(int n) { if (n == 0) return true; return isOdd(n - 1); }
                        static boolean isOdd(int n) { if (n == 0) return false; return isEven(n - 1); }
                        public static void main(String[] args) {}
                    }
                    """);
            assertThat(outcome.isValid()).isFalse();
            assertThat(outcome.errors()).anyMatch(e -> e.message().contains("Mutual recursion"));
        }
    }

    @Nested
    class Integration {

        @Test
        void simulateRunsFactorial() {
            SimulationController controller = new SimulationController(new Interpreter(parser));
            ExecutionTrace t = controller.simulate(new SimulateRequest(FACTORIAL, null));

            assertThat(t.status()).isEqualTo("OK");
            ExecutionStepDto last = t.steps().get(t.steps().size() - 1);
            assertThat(last.callStack()).hasSize(1);
            assertThat(last.callStack().get(0).variables())
                    .extracting(VariableDto::name, VariableDto::value)
                    .contains(org.assertj.core.groups.Tuple.tuple("result", "24"));
            assertThat(last.heap()).isEmpty();
        }
    }
}
