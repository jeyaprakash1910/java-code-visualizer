interface Props {
  index: number;
  total: number;
  playing: boolean;
  isFirst: boolean;
  isLast: boolean;
  description: string | null;
  onPrev: () => void;
  onNext: () => void;
  onTogglePlay: () => void;
  onReset: () => void;
  onScrub: (i: number) => void;
}

export default function StepControls({
  index,
  total,
  playing,
  isFirst,
  isLast,
  description,
  onPrev,
  onNext,
  onTogglePlay,
  onReset,
  onScrub,
}: Props) {
  const disabled = total === 0;

  return (
    <div className="flex flex-col gap-2 rounded-lg border border-white/10 bg-panel px-4 py-3">
      <div className="flex items-center gap-3">
        <button
          className="rounded bg-panel-alt px-3 py-1.5 text-sm hover:bg-white/10 disabled:opacity-40"
          onClick={onReset}
          disabled={disabled}
          title="Reset to first step"
        >
          ⏮ Reset
        </button>
        <button
          className="rounded bg-panel-alt px-3 py-1.5 text-sm hover:bg-white/10 disabled:opacity-40"
          onClick={onPrev}
          disabled={disabled || isFirst}
        >
          ◄ Prev
        </button>
        <button
          className="rounded bg-accent px-4 py-1.5 text-sm font-medium text-black hover:opacity-90 disabled:opacity-40"
          onClick={onTogglePlay}
          disabled={disabled || isLast}
        >
          {playing ? "⏸ Pause" : "▶ Play"}
        </button>
        <button
          className="rounded bg-panel-alt px-3 py-1.5 text-sm hover:bg-white/10 disabled:opacity-40"
          onClick={onNext}
          disabled={disabled || isLast}
        >
          Next ►
        </button>
        <span className="ml-auto text-sm tabular-nums text-white/60">
          {disabled ? "0 / 0" : `${index + 1} / ${total}`}
        </span>
      </div>

      <input
        type="range"
        min={0}
        max={Math.max(total - 1, 0)}
        value={index}
        onChange={(e) => onScrub(Number(e.target.value))}
        disabled={disabled}
        className="w-full accent-accent"
      />

      <p className="min-h-[1.25rem] text-sm text-white/70">
        {description ?? "Click Run to generate an execution trace."}
      </p>
    </div>
  );
}
