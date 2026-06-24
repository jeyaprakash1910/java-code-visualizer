import type { ExecutionTrace, SimulateRequest } from "../types/trace";

// Relative URL: proxied to the Spring Boot backend by Vite in dev (see vite.config.ts).
const SIMULATE_URL = "/api/simulate";

export async function simulate(req: SimulateRequest): Promise<ExecutionTrace> {
  const res = await fetch(SIMULATE_URL, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(req),
  });

  if (!res.ok) {
    throw new Error(`Simulation request failed: ${res.status} ${res.statusText}`);
  }

  return (await res.json()) as ExecutionTrace;
}
