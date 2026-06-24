import { useState } from "react";
import { simulate } from "./api/simulate";
import { usePlayer } from "./state/usePlayer";
import { SAMPLE_CODE } from "./sampleCode";
import Workspace from "./components/layout/Workspace";
import CodeEditor from "./components/editor/CodeEditor";
import StepControls from "./components/controls/StepControls";
import CallStackPanel from "./components/panels/CallStackPanel";
import VariablesPanel from "./components/panels/VariablesPanel";
import HeapPanel from "./components/panels/HeapPanel";
import ConsolePanel from "./components/panels/ConsolePanel";

export default function App() {
  const [code, setCode] = useState(SAMPLE_CODE);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const player = usePlayer();

  const currentFrame =
    player.currentStep && player.currentStep.callStack.length > 0
      ? player.currentStep.callStack[player.currentStep.callStack.length - 1]
      : null;

  async function handleRun() {
    setRunning(true);
    setError(null);
    try {
      const trace = await simulate({ sourceCode: code });
      if (trace.status === "ERROR") {
        setError(trace.error?.message ?? "Simulation failed.");
        return;
      }
      player.loadTrace(trace);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Network error.");
    } finally {
      setRunning(false);
    }
  }

  const header = (
    <header className="flex items-center gap-4">
      <h1 className="text-lg font-semibold">Java Code Visualizer</h1>
      <span className="text-xs text-white/40">
        Phase 1 — live interpreter (int/double/boolean/String, loops & conditionals)
      </span>
      <button
        className="ml-auto rounded bg-accent px-4 py-1.5 text-sm font-medium text-black hover:opacity-90 disabled:opacity-50"
        onClick={handleRun}
        disabled={running}
      >
        {running ? "Running…" : "▶ Run"}
      </button>
    </header>
  );

  return (
    <div className="h-full">
      {error && (
        <div className="mx-3 mt-3 rounded border border-red-500/40 bg-red-500/10 px-3 py-2 text-sm text-red-300">
          {error}
        </div>
      )}
      <Workspace
        header={header}
        editor={
          <CodeEditor
            value={code}
            onChange={setCode}
            highlightLine={player.currentStep?.line ?? null}
          />
        }
        controls={
          <StepControls
            index={player.index}
            total={player.total}
            playing={player.playing}
            isFirst={player.isFirst}
            isLast={player.isLast}
            description={player.currentStep?.description ?? null}
            onPrev={player.prev}
            onNext={player.next}
            onTogglePlay={player.togglePlay}
            onReset={player.reset}
            onScrub={player.goTo}
          />
        }
        callStack={<CallStackPanel frames={player.currentStep?.callStack ?? []} />}
        variables={<VariablesPanel frame={currentFrame} />}
        heap={<HeapPanel step={player.currentStep} />}
        console={<ConsolePanel output={player.currentStep?.console ?? []} />}
      />
    </div>
  );
}
