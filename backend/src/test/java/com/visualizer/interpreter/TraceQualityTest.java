package com.visualizer.interpreter;

import com.visualizer.api.dto.ExecutionStepDto;
import com.visualizer.api.dto.ExecutionTrace;
import com.visualizer.parser.JavaCodeParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cross-cutting invariants every trace must satisfy: sequential stepIndex,
 * populated line numbers, meaningful descriptions, and immutable snapshots.
 */
class TraceQualityTest {

    private final Interpreter interpreter = new Interpreter(new JavaCodeParser());

    /** A program exercising declare, assign, print, if/else, while, and for. */
    private ExecutionTrace richTrace() {
        return interpreter.simulate("""
                public class Main {
                    public static void main(String[] args) {
                        int total = 0;
                        for (int i = 1; i <= 3; i++) {
                            total += i;
                        }
                        if (total > 5) {
                            System.out.println("big " + total);
                        } else {
                            System.out.println("small");
                        }
                        int n = 2;
                        while (n > 0) {
                            n--;
                        }
                    }
                }
                """);
    }

    @Test
    void stepIndexIsSequentialFromZero() {
        ExecutionTrace t = richTrace();
        assertThat(t.steps()).isNotEmpty();
        for (int i = 0; i < t.steps().size(); i++) {
            assertThat(t.steps().get(i).stepIndex()).isEqualTo(i);
        }
        assertThat(t.metadata().totalSteps()).isEqualTo(t.steps().size());
    }

    @Test
    void everyStepHasAPopulatedLineNumber() {
        assertThat(richTrace().steps())
                .allSatisfy(step -> assertThat(step.line()).isGreaterThan(0));
    }

    @Test
    void everyStepHasAMeaningfulDescriptionAndEvent() {
        assertThat(richTrace().steps()).allSatisfy(step -> {
            assertThat(step.description()).isNotBlank();
            assertThat(step.event()).isNotBlank();
        });
    }

    @Test
    void snapshotsAreImmutable() {
        ExecutionStepDto step = richTrace().steps().get(0);
        assertThatThrownBy(() -> step.callStack().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> step.console().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> step.heap().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
