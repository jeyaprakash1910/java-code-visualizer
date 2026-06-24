import Panel from "./Panel";
import type { StackFrameDto, VariableDto } from "../../types/trace";

interface Props {
  /** The current (top) frame whose locals we show. */
  frame: StackFrameDto | null;
}

function renderValue(v: VariableDto) {
  if (v.kind === "REFERENCE") {
    return v.ref == null ? (
      <span className="text-white/40">null</span>
    ) : (
      <span className="text-accent">→ #{v.ref}</span>
    );
  }
  return <span className="text-emerald-300">{v.value}</span>;
}

export default function VariablesPanel({ frame }: Props) {
  const vars = frame?.variables ?? [];
  return (
    <Panel title={`Variables${frame ? ` · ${frame.methodName}()` : ""}`}>
      {vars.length === 0 ? (
        <p className="text-sm text-white/40">No variables in scope.</p>
      ) : (
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs text-white/40">
              <th className="pb-1 font-normal">Name</th>
              <th className="pb-1 font-normal">Type</th>
              <th className="pb-1 font-normal">Value</th>
            </tr>
          </thead>
          <tbody className="font-mono">
            {vars.map((v) => (
              <tr key={v.name} className="border-t border-white/5">
                <td className="py-1 pr-2">{v.name}</td>
                <td className="py-1 pr-2 text-white/50">{v.declaredType}</td>
                <td className="py-1">{renderValue(v)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Panel>
  );
}
