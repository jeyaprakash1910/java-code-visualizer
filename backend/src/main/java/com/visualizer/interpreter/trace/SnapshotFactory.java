package com.visualizer.interpreter.trace;

import com.visualizer.api.dto.ConsoleOutputDto;
import com.visualizer.api.dto.HeapObjectDto;
import com.visualizer.api.dto.StackFrameDto;
import com.visualizer.interpreter.engine.RuntimeConsole;
import com.visualizer.interpreter.runtime.CallStack;
import com.visualizer.interpreter.runtime.Heap;
import com.visualizer.interpreter.runtime.HeapEntity;
import com.visualizer.interpreter.runtime.StackFrame;

import java.util.ArrayList;
import java.util.List;

/**
 * The single place that turns live runtime state into <em>immutable</em> DTO
 * snapshots. Every call materializes fresh, unmodifiable lists from the current
 * state, so a snapshot can never be mutated by later execution — there are no
 * shared references back into the runtime model.
 */
public final class SnapshotFactory {

    /**
     * Snapshot the whole call stack, top frame first and marked current.
     * Frame ids are stable by stack position ({@code f0} = bottom/main).
     */
    public List<StackFrameDto> snapshotCallStack(CallStack callStack) {
        List<StackFrame> frames = callStack.frames(); // top → bottom
        int depth = frames.size();
        List<StackFrameDto> result = new ArrayList<>(depth);
        for (int i = 0; i < depth; i++) {
            String frameId = "f" + (depth - 1 - i);
            boolean isCurrent = (i == 0);
            result.add(RuntimeDtoMapper.toStackFrameDto(frames.get(i), frameId, isCurrent));
        }
        // result is freshly built and never exposed elsewhere, so an unmodifiable
        // wrapper is safe and immutable — avoids the second copy List.copyOf makes.
        return java.util.Collections.unmodifiableList(result);
    }

    /** Snapshot console output cumulatively up to now, in emission order. */
    public List<ConsoleOutputDto> snapshotConsole(RuntimeConsole console) {
        List<RuntimeConsole.Entry> entries = console.entries();
        List<ConsoleOutputDto> result = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            RuntimeConsole.Entry e = entries.get(i);
            result.add(new ConsoleOutputDto(i, e.text(), e.newline()));
        }
        return java.util.Collections.unmodifiableList(result);
    }

    /**
     * Snapshot the heap, in allocation order, stamping each entity's GC state from
     * the supplied reachability sets (Phase 2F): {@code COLLECTED} if already
     * collected, else {@code REACHABLE} if reachable, else {@code UNREACHABLE}.
     * Each call materializes a fresh, unmodifiable list of immutable DTOs.
     */
    public List<HeapObjectDto> snapshotHeap(Heap heap,
                                            java.util.Set<Integer> reachableIds,
                                            java.util.Set<Integer> collectedIds) {
        List<HeapObjectDto> result = new ArrayList<>();
        for (HeapEntity entity : heap.allObjects()) {
            String gcState;
            if (collectedIds.contains(entity.objectId())) {
                gcState = "COLLECTED";
            } else if (reachableIds.contains(entity.objectId())) {
                gcState = "REACHABLE";
            } else {
                gcState = "UNREACHABLE";
            }
            result.add(RuntimeDtoMapper.toHeapObjectDto(entity, gcState));
        }
        return java.util.Collections.unmodifiableList(result);
    }
}
