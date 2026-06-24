import { useMemo } from "react";
import ReactFlow, {
  Background,
  Controls,
  type Edge,
  type Node,
} from "reactflow";
import "reactflow/dist/style.css";
import Panel from "./Panel";
import type { ExecutionStepDto, HeapObjectDto } from "../../types/trace";

interface Props {
  step: ExecutionStepDto | null;
}

function heapNodeLabel(o: HeapObjectDto): string {
  if (o.category === "ARRAY") {
    const elems = (o.arrayElements ?? [])
      .map((e) => (e.kind === "REFERENCE" ? (e.ref == null ? "null" : `#${e.ref}`) : e.value))
      .join(", ");
    return `${o.type} #${o.id}\n[${elems}]`;
  }
  const fields = (o.fields ?? [])
    .map((f) => `${f.name} = ${f.kind === "REFERENCE" ? (f.ref == null ? "null" : `#${f.ref}`) : f.value}`)
    .join("\n");
  return `${o.type} #${o.id}\n${fields}`;
}

/**
 * Heap object graph. Each heap object is a node; references between objects
 * (object→object, array element→object) become edges. Variable→object
 * references are summarized via the node id label for now.
 */
export default function HeapPanel({ step }: Props) {
  const { nodes, edges } = useMemo(() => {
    const heap = step?.heap ?? [];
    const nodes: Node[] = heap.map((o, i) => ({
      id: String(o.id),
      position: { x: 20, y: 20 + i * 120 },
      data: { label: heapNodeLabel(o) },
      style: {
        whiteSpace: "pre",
        fontFamily: "monospace",
        fontSize: 12,
        padding: 10,
        borderRadius: 8,
        border: o.gcState === "REACHABLE" ? "1px solid #7c93ff" : "1px dashed #888",
        background: "#252537",
        color: "#e6e6f0",
        opacity: o.gcState === "COLLECTED" ? 0.3 : 1,
      },
    }));

    const edges: Edge[] = [];
    for (const o of heap) {
      const refs = [
        ...(o.fields ?? []).filter((f) => f.kind === "REFERENCE" && f.ref != null).map((f) => f.ref!),
        ...(o.arrayElements ?? []).filter((e) => e.kind === "REFERENCE" && e.ref != null).map((e) => e.ref!),
      ];
      for (const target of refs) {
        edges.push({
          id: `${o.id}->${target}`,
          source: String(o.id),
          target: String(target),
          animated: true,
          style: { stroke: "#7c93ff" },
        });
      }
    }
    return { nodes, edges };
  }, [step]);

  return (
    <Panel title="Heap Memory" className="min-h-[200px]">
      {nodes.length === 0 ? (
        <p className="text-sm text-white/40">Heap is empty — no objects allocated yet.</p>
      ) : (
        <div className="h-full min-h-[180px]">
          <ReactFlow nodes={nodes} edges={edges} fitView proOptions={{ hideAttribution: true }}>
            <Background color="#333" gap={16} />
            <Controls showInteractive={false} />
          </ReactFlow>
        </div>
      )}
    </Panel>
  );
}
