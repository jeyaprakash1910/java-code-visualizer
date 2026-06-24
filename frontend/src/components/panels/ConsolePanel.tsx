import Panel from "./Panel";
import type { ConsoleOutputDto } from "../../types/trace";

interface Props {
  output: ConsoleOutputDto[];
}

export default function ConsolePanel({ output }: Props) {
  return (
    <Panel title="Console Output">
      {output.length === 0 ? (
        <p className="text-sm text-white/40">No output yet.</p>
      ) : (
        <pre className="whitespace-pre-wrap font-mono text-sm text-green-300">
          {output.map((o) => o.text + (o.newline ? "\n" : "")).join("")}
        </pre>
      )}
    </Panel>
  );
}
