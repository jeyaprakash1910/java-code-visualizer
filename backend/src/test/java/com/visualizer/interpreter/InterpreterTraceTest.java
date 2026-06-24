package com.visualizer.interpreter;

import com.visualizer.api.dto.ConsoleOutputDto;
import com.visualizer.api.dto.ExecutionStepDto;
import com.visualizer.api.dto.ExecutionTrace;
import com.visualizer.api.dto.StackFrameDto;
import com.visualizer.api.dto.VariableDto;
import com.visualizer.parser.JavaCodeParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the full Phase 1D pipeline: source → parse → validate →
 * execute → {@link ExecutionTrace}. Verifies step count, event sequence, line
 * numbers, variable values, and console output for the supported subset.
 */
class InterpreterTraceTest {

    private final Interpreter interpreter = new Interpreter(new JavaCodeParser());

    private ExecutionTrace simulate(String source) {
        return interpreter.simulate(source);
    }

    private List<String> events(ExecutionTrace trace) {
        return trace.steps().stream().map(ExecutionStepDto::event).toList();
    }

    /** Variables of the (single) current frame at a given step. */
    private List<VariableDto> varsAt(ExecutionTrace trace, int step) {
        StackFrameDto frame = trace.steps().get(step).callStack().get(0);
        return frame.variables();
    }

    private String valueOf(List<VariableDto> vars, String name) {
        return vars.stream().filter(v -> v.name().equals(name)).findFirst().orElseThrow().value();
    }

    // 1. Variable declaration -------------------------------------------------

    @Test
    void declaration() {
        ExecutionTrace t = simulate("""
                public class Main {
                    public static void main(String[] args) {
                        int x = 10;
                    }
                }
                """);
        assertThat(t.status()).isEqualTo("OK");
        assertThat(t.metadata().entryPoint()).isEqualTo("Main.main");
        assertThat(t.metadata().totalSteps()).isEqualTo(1);
        assertThat(events(t)).containsExactly("DECLARE");

        ExecutionStepDto step = t.steps().get(0);
        assertThat(step.stepIndex()).isZero();
        assertThat(step.line()).isEqualTo(3);
        assertThat(step.description()).contains("Declare variable x");
        assertThat(step.heap()).isEmpty();
        assertThat(valueOf(varsAt(t, 0), "x")).isEqualTo("10");
    }

    // 2. Assignment -----------------------------------------------------------

    @Test
    void assignment() {
        ExecutionTrace t = simulate("""
                public class Main {
                    public static void main(String[] args) {
                        int x = 10;
                        x = 20;
                    }
                }
                """);
        assertThat(events(t)).containsExactly("DECLARE", "ASSIGN");
        ExecutionStepDto assign = t.steps().get(1);
        assertThat(assign.line()).isEqualTo(4);
        assertThat(assign.description()).isEqualTo("Assign value 20 to x");
        assertThat(valueOf(varsAt(t, 1), "x")).isEqualTo("20");
    }

    // 3. Increment ------------------------------------------------------------

    @Test
    void increment() {
        ExecutionTrace t = simulate("""
                public class Main {
                    public static void main(String[] args) {
                        int x = 10;
                        x++;
                    }
                }
                """);
        assertThat(events(t)).containsExactly("DECLARE", "ASSIGN");
        assertThat(t.steps().get(1).description()).isEqualTo("Assign value 11 to x");
        assertThat(valueOf(varsAt(t, 1), "x")).isEqualTo("11");
    }

    // 4. Print ----------------------------------------------------------------

    @Test
    void print() {
        ExecutionTrace t = simulate("""
                public class Main {
                    public static void main(String[] args) {
                        int x = 10;
                        System.out.println(x);
                    }
                }
                """);
        assertThat(events(t)).containsExactly("DECLARE", "PRINT");
        ExecutionStepDto print = t.steps().get(1);
        assertThat(print.line()).isEqualTo(4);
        assertThat(print.description()).isEqualTo("Print value 10");

        List<ConsoleOutputDto> console = print.console();
        assertThat(console).hasSize(1);
        assertThat(console.get(0).sequence()).isZero();
        assertThat(console.get(0).text()).isEqualTo("10");
        assertThat(console.get(0).newline()).isTrue();
    }

    // 5. If / Else ------------------------------------------------------------

    @Test
    void ifElseTakesElseBranch() {
        ExecutionTrace t = simulate("""
                public class Main {
                    public static void main(String[] args) {
                        int x = 3;
                        if (x > 10) {
                            x = 1;
                        } else {
                            x = 2;
                        }
                    }
                }
                """);
        assertThat(events(t)).containsExactly("DECLARE", "IF_BRANCH", "ASSIGN");
        assertThat(t.steps().get(1).line()).isEqualTo(4);
        assertThat(t.steps().get(1).description()).contains("else branch");
        assertThat(t.steps().get(2).line()).isEqualTo(7);
        assertThat(valueOf(varsAt(t, 2), "x")).isEqualTo("2");
    }

    // 6. While loop -----------------------------------------------------------

    @Test
    void whileLoop() {
        ExecutionTrace t = simulate("""
                public class Main {
                    public static void main(String[] args) {
                        int n = 0;
                        while (n < 2) {
                            n++;
                        }
                    }
                }
                """);
        assertThat(events(t)).containsExactly(
                "DECLARE",
                "WHILE_START", "ASSIGN", "WHILE_END",
                "WHILE_START", "ASSIGN", "WHILE_END");
        assertThat(t.steps().get(1).description()).isEqualTo("Enter while loop iteration");
        assertThat(t.steps().get(3).description()).isEqualTo("Exit while loop iteration");
        // final n == 2 on the last step
        assertThat(valueOf(varsAt(t, 6), "n")).isEqualTo("2");
    }

    // 7. For loop -------------------------------------------------------------

    @Test
    void forLoop() {
        ExecutionTrace t = simulate("""
                public class Main {
                    public static void main(String[] args) {
                        int sum = 0;
                        for (int i = 0; i < 2; i++) {
                            sum += i;
                        }
                    }
                }
                """);
        assertThat(events(t)).containsExactly(
                "DECLARE",                       // sum
                "DECLARE",                       // i
                "FOR_START", "ASSIGN", "FOR_END",
                "FOR_START", "ASSIGN", "FOR_END");
        assertThat(t.steps().get(1).description()).contains("Declare variable i");
        assertThat(t.steps().get(2).line()).isEqualTo(4);
        assertThat(t.steps().get(3).line()).isEqualTo(5);
        assertThat(valueOf(varsAt(t, 7), "sum")).isEqualTo("1");
    }

    // Heap stays empty across every step --------------------------------------

    @Test
    void heapIsAlwaysEmpty() {
        ExecutionTrace t = simulate("""
                public class Main {
                    public static void main(String[] args) {
                        int x = 1;
                        String s = "hi";
                        System.out.println(s);
                    }
                }
                """);
        assertThat(t.steps()).allSatisfy(step -> assertThat(step.heap()).isEmpty());
        // String is rendered as a PRIMITIVE variable (no heap reference).
        VariableDto s = varsAt(t, 1).stream().filter(v -> v.name().equals("s")).findFirst().orElseThrow();
        assertThat(s.kind()).isEqualTo("PRIMITIVE");
        assertThat(s.value()).isEqualTo("hi");
    }

    // Validation failures come back as an ERROR trace -------------------------

    @Test
    void validationFailureReturnsErrorTrace() {
        ExecutionTrace t = simulate("""
                public class Main {
                    public static void main(String[] args) {
                        int[] nums = {1, 2, 3};
                    }
                }
                """);
        assertThat(t.status()).isEqualTo("ERROR");
        assertThat(t.steps()).isEmpty();
        assertThat(t.error()).isNotNull();
        assertThat(t.error().type()).isEqualTo("VALIDATION_ERROR");
        assertThat(t.error().line()).isEqualTo(3);
    }
}
