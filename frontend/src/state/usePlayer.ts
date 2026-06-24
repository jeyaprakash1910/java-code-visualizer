import { useCallback, useEffect, useRef, useState } from "react";
import type { ExecutionTrace, ExecutionStepDto } from "../types/trace";

const PLAY_INTERVAL_MS = 900;

/**
 * Holds the loaded trace and the current step index, and exposes navigation.
 * Stepping is pure client-side array navigation — no backend calls.
 */
export function usePlayer() {
  const [trace, setTrace] = useState<ExecutionTrace | null>(null);
  const [index, setIndex] = useState(0);
  const [playing, setPlaying] = useState(false);
  const timer = useRef<number | null>(null);

  const steps = trace?.steps ?? [];
  const total = steps.length;
  const currentStep: ExecutionStepDto | null = total > 0 ? steps[index] : null;

  const loadTrace = useCallback((t: ExecutionTrace) => {
    setTrace(t);
    setIndex(0);
    setPlaying(false);
  }, []);

  const next = useCallback(() => {
    setIndex((i) => Math.min(i + 1, Math.max(total - 1, 0)));
  }, [total]);

  const prev = useCallback(() => {
    setIndex((i) => Math.max(i - 1, 0));
  }, []);

  const goTo = useCallback(
    (i: number) => {
      if (total === 0) return;
      setIndex(Math.max(0, Math.min(i, total - 1)));
    },
    [total]
  );

  const reset = useCallback(() => {
    setIndex(0);
    setPlaying(false);
  }, []);

  const togglePlay = useCallback(() => {
    if (total === 0) return;
    setPlaying((p) => !p);
  }, [total]);

  // Auto-advance while playing; stop at the last step.
  useEffect(() => {
    if (!playing) return;
    if (index >= total - 1) {
      setPlaying(false);
      return;
    }
    timer.current = window.setTimeout(() => setIndex((i) => i + 1), PLAY_INTERVAL_MS);
    return () => {
      if (timer.current) window.clearTimeout(timer.current);
    };
  }, [playing, index, total]);

  return {
    trace,
    steps,
    total,
    index,
    currentStep,
    playing,
    loadTrace,
    next,
    prev,
    goTo,
    reset,
    togglePlay,
    isFirst: index === 0,
    isLast: total === 0 || index === total - 1,
  };
}
