# Java Code Visualizer

An educational tool that visually explains Java execution step-by-step — call stack, heap memory,
object references, variable values, method calls, and console output.

See [docs/architecture.md](docs/architecture.md) for the full architecture and roadmap.

> **Status: Phase 0 (skeleton).** The backend returns a *hardcoded* mock trace; the interpreter is
> not implemented yet. The goal of this phase is a working end-to-end pipeline:
> **Run → backend returns mock trace → frontend renders it → user steps through it.**

## Project layout

```
backend/    Spring Boot (Java 21, Maven, JavaParser) — REST API at /api/simulate
frontend/   React + TypeScript + Vite + Monaco + React Flow + TailwindCSS
docs/       Architecture document
```

## Prerequisites

- Java 21+ and Maven
- Node.js 18+ and npm

## Run the backend

```bash
cd backend
mvn spring-boot:run
```

Serves on `http://localhost:8080`. Quick check: `curl http://localhost:8080/api/health` → `OK`.

## Run the frontend

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`. Vite proxies `/api/*` to the backend, so both must be running.

## Try it

1. The editor is pre-filled with a small sample program.
2. Click **▶ Run**. The backend returns the mock `ExecutionTrace`.
3. Use **Prev / Next / Play** or the slider to move between steps.
4. Watch the highlighted line, Call Stack, Variables, Heap, and Console update per step.

## What's mocked vs. real

| Piece | Phase 0 state |
|---|---|
| REST contract (`ExecutionTrace` DTOs) | Real & locked |
| Frontend UI + step playback | Real |
| `/api/simulate` response | **Hardcoded mock** (`MockTraceFactory`) — ignores submitted code |
| Parser / interpreter | Empty placeholders (Phase 1+) |
