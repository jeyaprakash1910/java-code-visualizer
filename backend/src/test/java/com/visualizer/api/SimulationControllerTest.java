package com.visualizer.api;

import com.visualizer.api.dto.ExecutionTrace;
import com.visualizer.api.dto.SimulateRequest;
import com.visualizer.interpreter.Interpreter;
import com.visualizer.parser.JavaCodeParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The controller delegates to the real {@link Interpreter} — no mock in the path. */
class SimulationControllerTest {

    private final SimulationController controller =
            new SimulationController(new Interpreter(new JavaCodeParser()));

    @Test
    void simulateReturnsRealTraceForSubmittedCode() {
        String source = """
                public class Main {
                    public static void main(String[] args) {
                        int x = 21;
                        System.out.println(x);
                    }
                }
                """;
        ExecutionTrace trace = controller.simulate(new SimulateRequest(source, null));

        assertThat(trace.status()).isEqualTo("OK");
        // Real interpretation of the *submitted* code, not the Phase 0 mock.
        assertThat(trace.steps()).hasSize(2);
        assertThat(trace.steps().get(1).console().get(0).text()).isEqualTo("21");
    }

    @Test
    void simulateReturnsErrorResponseForInfiniteLoop() {
        String source = """
                public class Main {
                    public static void main(String[] args) {
                        while (true) { }
                    }
                }
                """;
        ExecutionTrace trace = controller.simulate(new SimulateRequest(source, null));

        assertThat(trace.status()).isEqualTo("ERROR");
        assertThat(trace.error().type()).isEqualTo("EXECUTION_LIMIT");
        assertThat(trace.error().message())
                .isEqualTo("Execution limit exceeded. Possible infinite loop detected.");
        assertThat(trace.steps()).isEmpty();
    }
}
