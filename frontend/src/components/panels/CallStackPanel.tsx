import Panel from "./Panel";
import type { StackFrameDto } from "../../types/trace";

interface Props {
  frames: StackFrameDto[];
}

/** Renders call-stack frames top-first (most recent call on top). */
export default function CallStackPanel({ frames }: Props) {
  return (
    <Panel title="Call Stack">
      {frames.length === 0 ? (
        <p className="text-sm text-white/40">Stack is empty.</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {[...frames].reverse().map((f) => (
            <li
              key={f.frameId}
              className={`rounded border px-3 py-2 ${
                f.isCurrent ? "border-accent bg-accent/10" : "border-white/10 bg-panel-alt"
              }`}
            >
              <div className="font-mono text-sm">
                {f.className}.{f.methodName}()
              </div>
              <div className="mt-1 text-xs text-white/50">
                {f.variables.length} local{f.variables.length === 1 ? "" : "s"}
              </div>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}
