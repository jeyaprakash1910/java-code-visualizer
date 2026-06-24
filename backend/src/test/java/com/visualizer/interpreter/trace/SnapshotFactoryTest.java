package com.visualizer.interpreter.trace;

import com.visualizer.api.dto.ConsoleOutputDto;
import com.visualizer.api.dto.StackFrameDto;
import com.visualizer.interpreter.engine.RuntimeConsole;
import com.visualizer.interpreter.runtime.CallStack;
import com.visualizer.interpreter.runtime.Value;
import com.visualizer.interpreter.runtime.ValueType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Snapshots must be immutable and decoupled from later runtime mutations. */
class SnapshotFactoryTest {

    private final SnapshotFactory factory = new SnapshotFactory();

    @Test
    void callStackSnapshotIsUnaffectedByLaterMutation() {
        CallStack stack = new CallStack();
        stack.push("Main", "main");
        stack.declare("x", ValueType.INT, Value.of(1));

        List<StackFrameDto> snapshot = factory.snapshotCallStack(stack);

        // Mutate the runtime after snapshotting.
        stack.assign("x", Value.of(99));
        stack.declare("y", ValueType.INT, Value.of(2));

        StackFrameDto frame = snapshot.get(0);
        assertThat(frame.frameId()).isEqualTo("f0");
        assertThat(frame.isCurrent()).isTrue();
        assertThat(frame.variables()).hasSize(1);
        assertThat(frame.variables().get(0).value()).isEqualTo("1"); // not 99
    }

    @Test
    void consoleSnapshotIsCumulativeAndFrozen() {
        RuntimeConsole console = new RuntimeConsole();
        console.println("a");

        List<ConsoleOutputDto> first = factory.snapshotConsole(console);
        console.print("b"); // later write must not leak into the old snapshot

        assertThat(first).hasSize(1);
        assertThat(first.get(0).text()).isEqualTo("a");
        assertThat(first.get(0).newline()).isTrue();

        List<ConsoleOutputDto> second = factory.snapshotConsole(console);
        assertThat(second).hasSize(2);
        assertThat(second.get(1).text()).isEqualTo("b");
        assertThat(second.get(1).newline()).isFalse();
    }

    @Test
    void heapIsEmptyWhenNothingAllocated() {
        assertThat(factory.snapshotHeap(new com.visualizer.interpreter.runtime.Heap(),
                java.util.Set.of(), java.util.Set.of())).isEmpty();
    }
}
