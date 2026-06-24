# Progress Log — Phases 0 & 1

**Status:** ✅ **Phase 0 and Phase 1 (1A–1E) complete**, verified end-to-end on localhost. 89 backend tests green. Real execution traces flow from editor → interpreter → panels; no mock remains in the execution path, and the server cannot hang on infinite loops.
**Last updated:** 2026-06-23

> This file combines the former `phase-0-log.md` and `phase-1-log.md`.

---
---

# Phase 0 — Skeleton (locked JSON contract)

**Status:** ✅ Complete and verified working in localhost (end-to-end).

## Goal

Build the **skeleton** — a fully wired but logic-free pipeline — so that data flows
all the way from the editor to the panels before any interpreter exists:

```
User clicks Run → backend returns a hardcoded mock trace → frontend renders it → user steps through it
```

The point was to **de-risk the architecture and lock the JSON contract** first. Everything
from Phase 1 onward is "just" filling in real interpreter logic behind this stable interface.

## What we built

### Backend (Spring Boot, Maven)

| Area | File(s) | What it does |
|---|---|---|
| App entry | `VisualizerApplication.java` | Spring Boot main class |
| Build config | `pom.xml` | Maven setup; Spring Web + Validation + JavaParser; **targets Java 17** (see note below) |
| Runtime config | `application.yml` | Server on port 8080; CORS allowed origin |
| CORS | `config/WebConfig.java` | Lets the frontend (`localhost:5173`) call `/api/**` |
| **DTOs (locked contract)** | `api/dto/*.java` | `ExecutionTrace`, `ExecutionStepDto`, `StackFrameDto`, `HeapObjectDto`, `VariableDto`, `ValueDto`, `ConsoleOutputDto`, `ErrorInfoDto`, `TraceMetadataDto`, `SimulateRequest` — Java records mirroring the JSON contract |
| **Mock controller** | `api/SimulationController.java` | `POST /api/simulate` + `GET /api/health`. Ignored the submitted code, returned the mock trace |
| **Hardcoded trace** | `api/MockTraceFactory.java` | Built a 7-step trace for a small sample program (**later removed in Phase 1E**) |
| Placeholders | `parser/JavaCodeParser.java`, `interpreter/Interpreter.java`, `support/UnsupportedFeatureException.java` | Empty stubs — no real logic yet |

### Frontend (React + TypeScript + Vite)

| Area | File(s) | What it does |
|---|---|---|
| Build config | `package.json`, `vite.config.ts`, `tsconfig.json` | Vite on 5173; proxies `/api` → backend :8080 |
| Styling | `tailwind.config.js`, `postcss.config.js`, `styles/index.css` | TailwindCSS + dark theme + current-line highlight CSS |
| **Contract mirror** | `types/trace.ts` | 1:1 TypeScript mirror of the backend DTOs |
| API client | `api/simulate.ts` | `fetch` wrapper that POSTs to `/api/simulate` |
| Playback state | `state/usePlayer.ts` | Holds the trace + current step index; prev/next/play/scrub — pure client-side navigation |
| Editor | `components/editor/CodeEditor.tsx` | Monaco editor + highlights the current execution line |
| Controls | `components/controls/StepControls.tsx` | Reset / Prev / Play-Pause / Next + slider + step description |
| Panels | `components/panels/*.tsx` | `CallStackPanel`, `VariablesPanel`, `HeapPanel` (React Flow graph), `ConsolePanel`, shared `Panel` chrome |
| Layout | `components/layout/Workspace.tsx`, `App.tsx` | Editor on the left, four visualization panels on the right; **Run** button wires it together |

## Setup issue hit (and fix)

- **`release version 21 not supported`** when running `mvn spring-boot:run`. The dev
  machine only has **JDK 17**. **Fix:** changed `pom.xml` to target Java 17
  (`java.version` / `maven.compiler.release` = 17). Nothing requires 21; can be bumped later.

## Phase 0 end state

| Piece | State |
|---|---|
| REST contract (`ExecutionTrace` DTOs) | ✅ Real & locked |
| Frontend UI + step playback | ✅ Real |
| `/api/simulate` response | ⚠️ Hardcoded mock — ignored submitted code |
| Parser / interpreter | ⛔ Empty placeholders |

---
---

# Phase 1 — Real interpreter

**Status:** ✅ Complete. Sub-phases 1A–1E done and tested.

Make the backend actually **interpret** the submitted Java code for the simplest
supported subset, replacing the Phase 0 hardcoded mock. Split into sub-phases so each
layer is built and tested in isolation before the next:

```
1A Parse + Validate → 1B Runtime Model → 1C Interpreter Engine → 1D Trace Recording → 1E Stability/Safety
  (done)               (done)             (done)                  (done)               (done)
```

## Supported subset

| Category | Supported |
|---|---|
| Types | `int`, `double`, `boolean`, `String` |
| Statements | var declaration, assignment, expression stmt, `if`/`else`, `while`, `for`, `System.out.print`/`println` |
| Expressions | literals, `NameExpr`, arithmetic `+ - * / %`, comparison `== != < <= > >=`, boolean `&& \|\| !`, unary `++ --`, assignment `= += -= *= /= %=` |
| Structure | one class, a `main` method |

Everything else (objects, arrays, extra methods, lambdas, streams, collections,
`try/catch`, `switch`, inheritance) is rejected at validation time with a line number.

---

## Phase 1A — Parsing & Validation

Turn raw source into an AST and reject anything outside the supported subset,
with line-numbered errors. **No execution.**

| File | What it does |
|---|---|
| `parser/JavaCodeParser.java` | Parses source → `CompilationUnit` (JavaParser); runs structural rules (exactly one class, must have `main`, no inheritance) + the visitor. Returns a `ParseOutcome`. |
| `parser/ValidationVisitor.java` | `VoidVisitorAdapter` that **accumulates all** unsupported-construct errors (never throws). Rejects object creation, arrays, lambdas, method refs, `try/catch`, `switch`, imports, fields, non-`main` methods, interfaces, and any call other than `System.out.print/println`; restricts variable types. |
| `parser/ValidationError.java` | `record (Integer line, String message)`. |
| `parser/ParseOutcome.java` | Either a valid AST or a non-empty error list (`isValid()`). |

**Design choice:** collect *all* errors rather than throw on the first, so the user
sees every problem at once.

**Tests:** `JavaCodeParserTest` — 17 tests.

---

## Phase 1B — Runtime Model

A storage-only model the interpreter can later drive. **No language semantics here.**

| File | What it does |
|---|---|
| `interpreter/runtime/Value.java` | Sealed interface; records `IntValue`/`DoubleValue`/`BoolValue`/`StringValue`. Factories `Value.of(...)`, typed accessors (`asInt`…), `display()`. |
| `interpreter/runtime/ValueType.java` | Enum `INT/DOUBLE/BOOLEAN/STRING` with `javaName()` / `fromJavaName()`. |
| `interpreter/runtime/Variable.java` | Fixed name + declared type, mutable value; every `assign` is **type-checked** (exact match). |
| `interpreter/runtime/StackFrame.java` | Ordered name→Variable map; `declare`/`lookup`/`assign`/`read`. **Rejects re-declaration in the same frame** (shadowing prevention). |
| `interpreter/runtime/CallStack.java` | LIFO of frames; var ops delegate to the **current frame only** (lexical, not dynamic, scoping). |
| Exceptions | `RuntimeModelException` base + `UndefinedVariableException`, `VariableAlreadyDeclaredException`, `TypeMismatchException`, `EmptyCallStackException`. |
| `interpreter/runtime/RuntimeModelExample.java` | Runnable demo of intended usage (not wired to Spring). |

**Key decision (locked):** the runtime model stays **storage-only** — it must not
know Java semantics. Promotion/coercion/concatenation live in the interpreter layer.
So `Variable.assign` is strict; the evaluator coerces *before* storing. No
`coerceToDouble()`/`promote()`/`convertTo()` on `Value`.

```
AST → Interpreter / ExpressionEvaluator → Runtime Model (storage)
```

**Tests:** `RuntimeModelTest` — 18 tests.

---

## Phase 1C — Interpreter Engine

Execute supported code against the runtime model and capture console output.
**No trace recording, no heap, no REST integration.**

| File | What it does |
|---|---|
| `interpreter/engine/ExpressionEvaluator.java` | Evaluates expressions → `Value`. **Owns all Java semantics:** int→double promotion, string concatenation, comparison/equality, short-circuit `&&`/`\|\|`, unary `+ - ! ++ --`, compound-assignment narrowing. |
| `interpreter/engine/StatementExecutor.java` | Executes statements, mutating the stack + console: declarations, assignments, `if/else`, `while`, `for`, print/println. |
| `interpreter/engine/RuntimeConsole.java` | Order-preserving in-memory capture of `print`/`println`. |
| `interpreter/engine/ProgramInterpreter.java` | Driver: locate `main`, push initial frame, run body, return final state. |
| `interpreter/engine/InterpretationResult.java` | Final state (call stack + console) with test-friendly accessors. |
| `interpreter/engine/InterpreterException.java` | Engine-level error type. |
| `interpreter/engine/ExamplePrograms.java` | Five complete supported-subset example programs. |

**Naming note:** the driver is `ProgramInterpreter`, not `Interpreter`, to avoid
clashing with the `interpreter/Interpreter.java` facade (the REST/trace entry point).

**Tests:** `ProgramInterpreterTest` — 31 tests (declarations, assignment, arithmetic
incl. integer division & precedence, comparison, boolean logic, unary prefix/postfix,
compound assignment incl. narrowing & String `+=`, if/else, while/for, printing order,
numeric promotion, string concatenation, example programs).

---

## Phase 1D — Trace Recording & real `ExecutionTrace`

Replace the Phase 0 mock with real, step-by-step traces generated *during* execution,
wired through the facade and controller. **No DTO/contract/heap/frontend changes.**

| File | What it does |
|---|---|
| `interpreter/trace/StepEvent.java` | Enum of emitted events: `DECLARE`, `ASSIGN`, `PRINT`, `IF_BRANCH`, `WHILE_START`/`WHILE_END`, `FOR_START`/`FOR_END`. Its `name()` is written verbatim into the DTO's free-form `event` String. |
| `interpreter/trace/RuntimeDtoMapper.java` | Pure runtime→DTO mapping: `Value→ValueDto`, `Variable→VariableDto`, `StackFrame→StackFrameDto`. |
| `interpreter/trace/SnapshotFactory.java` | The **single** place that builds immutable snapshots (call stack, cumulative console, empty heap). |
| `interpreter/trace/TraceRecorder.java` | Accumulates `ExecutionStepDto`s, assigns `stepIndex`, builds the final `ExecutionTrace`. Independent of execution logic. |
| `interpreter/trace/StepEmitter.java` | The one bridge executor→recorder; centralizes snapshot creation so executors never touch DTOs. |
| `interpreter/Interpreter.java` | **Facade implemented:** parse → validate → execute → trace. Validation failure → `ExecutionTrace.error(ErrorInfoDto)` (line + message). |

**Modified:** `SimulationController` (injects `Interpreter`, returns the real trace),
`StatementExecutor` (trace hooks after each mutation), `ProgramInterpreter` (wires
recorder/emitter, builds trace), `InterpretationResult` (carries the trace),
`RuntimeConsole` (tracks discrete entries for `ConsoleOutputDto`).

**Key decisions:**
- **Snapshot *after* mutation**, from one emit point — snapshots reflect new state, no duplicated snapshot logic.
- **Immutability** — every snapshot is a fresh copy, so later mutations never leak into past steps.
- **String stays `PRIMITIVE`**, heap stays `[]` every step (no heap until Phase 2). The frontend renders this unchanged.
- **New event strings are safe** — the frontend never branches on `event` at runtime (compile-time TS union only), so the UI keeps working and the DTO is untouched.

**Tests:** `InterpreterTraceTest` (8), `SnapshotFactoryTest` (3),
`SimulationControllerTest` (the controller returns a real trace of the *submitted* code).

---

## Phase 1E — Stability, Safety & Cleanup

Harden the system before Phase 2. Not new features — preventing crashes, hangs, and
technical debt.

| File | What it does |
|---|---|
| `interpreter/engine/ExecutionContext.java` | Per-run safety budget (`maxExecutionSteps` default **10,000**); every executed statement `tick()`s the counter. |
| `interpreter/engine/ExecutionLimitExceededException.java` | Thrown when the budget is exceeded — message `"Execution limit exceeded. Possible infinite loop detected."` |

**Modified:** `StatementExecutor` (ticks the budget on every statement, so empty/nested
infinite loops are bounded), `ProgramInterpreter` (creates the context), `Interpreter`
(maps the limit exception → `ErrorInfoDto("EXECUTION_LIMIT", …)`), `SnapshotFactory`
(low-risk: one fewer list copy per snapshot, still immutable).

**Removed:** `api/MockTraceFactory.java` — the Phase 0 mock, now dead code, deleted entirely.

**Tests:** `ExecutionSafetyTest` (5 — infinite `while`/`for`/nested loops stopped with a
hard wall-clock `@Timeout`; finite loop still succeeds), `TraceQualityTest` (4 —
sequential `stepIndex`, populated line numbers, non-blank descriptions, immutable
snapshots), `SimulationControllerTest` (+1 — proper ERROR response for an infinite loop).

**Example error response:**
```json
{
  "status": "ERROR",
  "error": { "type": "EXECUTION_LIMIT", "message": "Execution limit exceeded. Possible infinite loop detected.", "line": null },
  "metadata": null,
  "steps": []
}
```

---

## What is real vs. mocked (end of Phase 1)

| Piece | State |
|---|---|
| Parser + validation | ✅ Real (1A) |
| Runtime model | ✅ Real (1B) |
| Expression/statement execution + console | ✅ Real (1C) |
| `ExecutionTrace` generation | ✅ Real (1D) |
| Infinite-loop safety guard | ✅ Real (1E) |
| `/api/simulate` response | ✅ Real trace from submitted code |
| DTOs / frontend | ⛔ Untouched by design |
| Heap (objects/arrays/references/GC) | ⛔ Empty `[]` — deferred to Phase 2 |

---

## How it runs

```bash
# Backend
cd backend && mvn spring-boot:run        # http://localhost:8080

# Frontend
cd frontend && npm install && npm run dev # http://localhost:5173
```

Open `http://localhost:5173`, type a supported-subset program, click **▶ Run**, then
use Prev/Next/Play or the slider. The highlighted line, Call Stack, Variables, and
Console update per step. (Heap panel stays empty until Phase 2.)

---

## Carried-forward simplifications (visible in traces)

1. **For-loop scoping** — the flat frame has no block scope, so a reused `int i`
   across sequential loops is re-assigned rather than re-declared. Correct for
   sequential/nested loops with distinct names; not true lexical scoping.
2. **String `==`** uses value equality, not Java reference semantics — a deliberate
   teaching simplification.

Both are intentional and worth revisiting when the heap arrives.

---

## Next: Phase 2 (not started)

Heap objects, references, arrays, methods beyond `main`, and garbage collection — at
which point Strings move off the "primitive" representation onto the heap and the two
simplifications above get revisited.
