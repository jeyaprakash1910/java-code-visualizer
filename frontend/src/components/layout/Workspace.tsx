import type { ReactNode } from "react";

interface Props {
  header: ReactNode;
  editor: ReactNode;
  controls: ReactNode;
  callStack: ReactNode;
  variables: ReactNode;
  heap: ReactNode;
  console: ReactNode;
}

/**
 * Top-level grid: editor on the left, visualization panels on the right.
 * Step controls span beneath the editor column.
 */
export default function Workspace({
  header,
  editor,
  controls,
  callStack,
  variables,
  heap,
  console,
}: Props) {
  return (
    <div className="flex h-full flex-col gap-3 p-3">
      {header}
      <div className="grid min-h-0 flex-1 grid-cols-1 gap-3 lg:grid-cols-2">
        {/* Left column: editor + controls */}
        <div className="flex min-h-0 flex-col gap-3">
          <div className="min-h-0 flex-1">{editor}</div>
          {controls}
        </div>

        {/* Right column: visualization panels */}
        <div className="grid min-h-0 grid-rows-[1fr_1fr] gap-3">
          <div className="grid min-h-0 grid-cols-2 gap-3">
            {callStack}
            {variables}
          </div>
          <div className="grid min-h-0 grid-cols-2 gap-3">
            {heap}
            {console}
          </div>
        </div>
      </div>
    </div>
  );
}
