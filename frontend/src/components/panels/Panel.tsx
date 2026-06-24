import type { ReactNode } from "react";

interface Props {
  title: string;
  children: ReactNode;
  className?: string;
}

/** Shared chrome for every visualization panel. */
export default function Panel({ title, children, className }: Props) {
  return (
    <div
      className={`flex min-h-0 flex-col overflow-hidden rounded-lg border border-white/10 bg-panel ${className ?? ""}`}
    >
      <div className="border-b border-white/10 px-3 py-2 text-xs font-semibold uppercase tracking-wide text-white/50">
        {title}
      </div>
      <div className="min-h-0 flex-1 overflow-auto p-3">{children}</div>
    </div>
  );
}
