package com.visualizer.interpreter;

import com.visualizer.api.dto.ExecutionTrace;
import com.visualizer.parser.JavaCodeParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The execution-limit guard must stop infinite loops cleanly (as an ERROR trace)
 * rather than hanging the server. Each test has a hard wall-clock timeout so a
 * regression that reintroduces a hang fails loudly instead of blocking the build.
 */
class ExecutionSafetyTest {

    private final Interpreter interpreter = new Interpreter(new JavaCodeParser());

    private ExecutionTrace simulate(String body) {
        return interpreter.simulate("""
                public class Main {
                    public static void main(String[] args) {
                %s
                    }
                }
                """.formatted(body));
    }

    private void assertLimitError(ExecutionTrace t) {
        assertThat(t.status()).isEqualTo("ERROR");
        assertThat(t.steps()).isEmpty();
        assertThat(t.error()).isNotNull();
        assertThat(t.error().type()).isEqualTo("EXECUTION_LIMIT");
        assertThat(t.error().message())
                .isEqualTo("Execution limit exceeded. Possible infinite loop detected.");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void infiniteWhileLoopIsStopped() {
        assertLimitError(simulate("while (true) { }"));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void infiniteForLoopIsStopped() {
        assertLimitError(simulate("for (;;) { }"));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void nestedInfiniteLoopsAreStopped() {
        assertLimitError(simulate("""
                        while (true) {
                            while (true) { }
                        }
                """));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void infiniteLoopWithBodyWorkIsStopped() {
        assertLimitError(simulate("""
                        int x = 0;
                        while (x >= 0) {
                            x = x + 1;
                        }
                """));
    }

    @Test
    void finiteLoopWithinBudgetSucceeds() {
        ExecutionTrace t = simulate("""
                        int sum = 0;
                        for (int i = 0; i < 100; i++) {
                            sum += i;
                        }
                """);
        assertThat(t.status()).isEqualTo("OK");
    }
}
