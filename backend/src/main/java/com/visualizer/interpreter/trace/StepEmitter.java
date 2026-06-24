package com.visualizer.interpreter.trace;

import com.visualizer.interpreter.engine.RuntimeConsole;
import com.visualizer.interpreter.gc.ReachabilityAnalyzer;
import com.visualizer.interpreter.runtime.CallStack;
import com.visualizer.interpreter.runtime.Heap;
import com.visualizer.interpreter.runtime.HeapEntity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The one bridge between statement execution and trace recording. Given the live
 * {@link CallStack} and {@link RuntimeConsole}, it captures a fresh snapshot (via
 * {@link SnapshotFactory}) and hands a finished step to the {@link TraceRecorder}.
 *
 * <p>Snapshot creation lives here and nowhere else, so executors only say "emit a
 * step of this event with this description at this line" — they never touch DTOs.
 * Call {@code emit} <em>after</em> the corresponding runtime mutation so the
 * snapshot reflects the new state.</p>
 *
 * <p>It also drives the educational GC pass (Phase 2F): every snapshot stamps each
 * heap entity's {@code gcState} from a live {@link ReachabilityAnalyzer} run, and
 * {@link #runGc()} / {@link #collect()} emit the dedicated GC trace steps. The set
 * of already-collected ids lives here (orchestration), not in the storage classes.</p>
 */
public final class StepEmitter {

    private final TraceRecorder recorder;
    private final SnapshotFactory snapshots;
    private final CallStack callStack;
    private final Heap heap;
    private final RuntimeConsole console;
    private final ReachabilityAnalyzer analyzer;

    /** Ids already moved to COLLECTED — sticky across the rest of the trace. */
    private final Set<Integer> collected = new LinkedHashSet<>();
    /** Reachable set from the previous {@link #runGc()}, to detect new losses. */
    private Set<Integer> lastReachable = new LinkedHashSet<>();

    public StepEmitter(TraceRecorder recorder,
                       SnapshotFactory snapshots,
                       CallStack callStack,
                       Heap heap,
                       RuntimeConsole console,
                       ReachabilityAnalyzer analyzer) {
        this.recorder = recorder;
        this.snapshots = snapshots;
        this.callStack = callStack;
        this.heap = heap;
        this.console = console;
        this.analyzer = analyzer;
    }

    public void emit(int line, StepEvent event, String description) {
        Set<Integer> reachable = analyzer.reachable(callStack, heap);
        recorder.record(
                line,
                event,
                description,
                snapshots.snapshotCallStack(callStack),
                snapshots.snapshotHeap(heap, reachable, collected),
                snapshots.snapshotConsole(console)
        );
    }

    /**
     * Re-run reachability and emit a {@code GC_MARK} step for every object that has
     * <em>newly</em> become unreachable since the last pass (a lost reference / a
     * reference that lived only in a now-popped frame). Called after each top-level
     * statement.
     */
    public void runGc() {
        Set<Integer> reachable = analyzer.reachable(callStack, heap);
        for (HeapEntity entity : List.copyOf(heap.allObjects())) {
            int id = entity.objectId();
            if (!reachable.contains(id) && !collected.contains(id) && lastReachable.contains(id)) {
                emit(0, StepEvent.GC_MARK, "Object " + id + " became unreachable");
            }
        }
        lastReachable = reachable;
    }

    /**
     * A single collection pass: every currently-unreachable, not-yet-collected
     * entity moves to {@code COLLECTED} and gets a {@code GC_COLLECT} step. Collected
     * entities stay visible in later snapshots (history is preserved).
     */
    public void collect() {
        Set<Integer> reachable = analyzer.reachable(callStack, heap);
        for (HeapEntity entity : List.copyOf(heap.allObjects())) {
            int id = entity.objectId();
            if (!reachable.contains(id) && !collected.contains(id)) {
                collected.add(id); // before emit, so the snapshot shows COLLECTED
                emit(0, StepEvent.GC_COLLECT, "Collected object " + id);
            }
        }
    }
}
