# Java Code Visualizer — Architecture & Roadmap

## 1. Requirements Analysis

The core challenge: this is **not** a general-purpose Java IDE or compiler. It is a **pedagogical execution simulator**. That distinction drives every decision below.

Key implications:
- We don't need *correct* Java execution — we need **observable, step-granular** execution where every memory mutation is captured as a discrete event.
- A real JVM is a black box; it executes too fast and hides the stack/heap. So we must **simulate** execution and emit a state snapshot at each step.
- The output is a **timeline of memory states**, not a result. The "answer" is the journey.
- Scope must be deliberately narrow. Full Java (generics, lambdas, threads, reflection, classloaders) is a multi-year effort. We target the subset a student learning memory management actually writes.

### Two viable execution strategies

| Strategy | How | Pros | Cons |
|---|---|---|---|
| **A. Tree-walking interpreter** (recommended) | Parse to AST, then walk it ourselves, maintaining our own stack & heap | Total control over every step; trivial to emit snapshots; safe (no real code runs) | We reimplement Java semantics for the supported subset |
| **B. Real JVM + JDI/agent** | Run code in a sandboxed JVM, attach the Java Debug Interface, single-step | Real semantics for free | Hard to capture full heap cheaply; sandboxing/security burden; harder to control granularity; "magic" we can't fully explain |

**Decision: Strategy A — a custom tree-walking interpreter.** For an *educational* tool, owning the semantics is a feature: we control exactly what a "step" means and what memory looks like. We trade completeness for clarity and safety, which matches the MVP goal.

---

## 2. Proposed Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                        FRONTEND (React)                       │
│                                                               │
│  Code Editor ─► [Run] ─► HTTP POST /simulate                  │
│       ▲                          │                            │
│       │                          ▼                            │
│  Visualization Player ◄── ExecutionTrace (JSON)               │
│   ├─ Call Stack panel                                         │
│   ├─ Heap panel (object graph)                                │
│   ├─ Variables panel                                          │
│   ├─ Console panel                                            │
│   └─ Step controls (◄ ► ⏯ slider)                            │
└───────────────────────────────┬──────────────────────────────┘
                                 │ JSON over REST
┌────────────────────────────────▼─────────────────────────────┐
│                     BACKEND (Spring Boot)                     │
│                                                               │
│  Controller ─► SimulationService                              │
│                   │                                           │
│      ┌────────────┼─────────────────────────────┐            │
│      ▼            ▼                              ▼            │
│  Parser      Interpreter (tree-walker)     TraceRecorder      │
│  (JavaParser) │  ├─ CallStack (our model)   (emits           │
│   → AST       │  ├─ Heap (our model)         ExecutionSteps)  │
│               │  └─ StepEmitter hooks                         │
│                                                               │
│  Output: ExecutionTrace { steps[], metadata }                │
└──────────────────────────────────────────────────────────────┘
```

**The pipeline is stateless and one-shot:** code in → full trace out. The frontend then "plays" the trace locally with no further backend calls. This makes stepping forward/backward instant and the backend trivially scalable.

---

## 3. Technology Recommendations

| Concern | Recommendation | Why |
|---|---|---|
| **Frontend framework** | **React + TypeScript + Vite** | Component model maps cleanly to panels; huge ecosystem; TS gives us a typed mirror of the JSON contract. |
| **Code editor** | **Monaco Editor** (`@monaco-editor/react`) | Same engine as VS Code; Java syntax highlighting + line decorations (we'll highlight the "current line"). |
| **Visualization library** | **React Flow** for the heap object graph + **plain styled HTML/CSS** for stack & variables | React Flow handles nodes/edges/arrows for object references beautifully. Stack frames and variable tables are simple lists — don't over-engineer them with a graph lib. Use **D3** only later if we need custom GC animations. |
| **Backend framework** | **Spring Boot (Java 17+)** | A Java visualizer written in Java is idiomatic; lets us optionally interop with real Java types later; mature REST tooling. |
| **Java parsing library** | **JavaParser** (`com.github.javaparser`) | Best-in-class pure-Java parser → clean, well-documented AST + visitor API. (ANTLR with a Java grammar is the alternative but more work.) |
| **Data structures for memory simulation** | See below | — |

### Data structures for memory simulation
- **Call Stack** → a `Deque<StackFrame>` (push/pop on method enter/exit).
- **Stack frame variables** → `LinkedHashMap<String, Value>` (insertion-ordered so the UI shows declaration order).
- **Heap** → `Map<Integer, HeapObject>` keyed by a synthetic **object id** (our "address"). Ids are assigned from an incrementing counter — they're stand-ins for memory addresses.
- **Value** → a tagged union: either a **primitive** (type + literal) or a **reference** (the heap id, or `null`).
- **Reference graph** → derived: edges = every reference-typed field/variable pointing at a heap id. The frontend builds the visual graph from these.
- **Trace** → `List<ExecutionStep>`, where each step holds a *deep snapshot* (or a diff — see §8 note) of stack + heap + console.

> **Key modeling rule:** primitives live *in* the frame (by value); objects/arrays live *in the heap* and frames hold *references*. Getting this split right is the entire educational point of the tool.

---

## 4. Folder Structure

```
java-code-visualizer/
├── README.md
├── docs/
│   └── architecture.md
│
├── backend/                          # Spring Boot
│   ├── pom.xml
│   └── src/main/java/com/visualizer/
│       ├── VisualizerApplication.java
│       ├── api/
│       │   ├── SimulationController.java
│       │   └── dto/
│       │       ├── SimulateRequest.java
│       │       └── SimulateResponse.java        # = ExecutionTrace DTO
│       ├── parser/
│       │   └── JavaCodeParser.java              # wraps JavaParser
│       ├── interpreter/
│       │   ├── Interpreter.java                 # tree-walker entry
│       │   ├── eval/                            # expression/statement evaluators
│       │   │   ├── ExpressionEvaluator.java
│       │   │   └── StatementExecutor.java
│       │   ├── runtime/                         # our simulated VM
│       │   │   ├── CallStack.java
│       │   │   ├── StackFrame.java
│       │   │   ├── Heap.java
│       │   │   ├── HeapObject.java
│       │   │   ├── Value.java                   # primitive | reference
│       │   │   └── Console.java
│       │   └── trace/
│       │       ├── ExecutionStep.java
│       │       ├── TraceRecorder.java
│       │       └── SnapshotFactory.java
│       ├── support/                             # supported-feature guards
│       │   └── UnsupportedFeatureException.java
│       └── config/  (CORS, etc.)
│
└── frontend/                         # React + TS + Vite
    ├── package.json
    ├── vite.config.ts
    └── src/
        ├── main.tsx
        ├── App.tsx
        ├── api/
        │   └── simulate.ts                      # fetch wrapper
        ├── types/
        │   └── trace.ts                         # TS mirror of JSON contract
        ├── state/
        │   └── usePlayer.ts                     # step index, play/pause
        ├── components/
        │   ├── editor/CodeEditor.tsx            # Monaco
        │   ├── controls/StepControls.tsx
        │   ├── panels/
        │   │   ├── CallStackPanel.tsx
        │   │   ├── HeapPanel.tsx                # React Flow
        │   │   ├── VariablesPanel.tsx
        │   │   └── ConsolePanel.tsx
        │   └── layout/Workspace.tsx
        └── styles/
```

---

## 5. MVP Scope

The MVP proves the core loop end-to-end on the **smallest meaningful subset**:

**Supported in MVP**
- A single class with a `main` method (and a few helper static methods).
- Primitive types: `int`, `double`, `boolean`, `char`, plus `String` (treated as a heap object).
- Variable declaration, assignment, reassignment.
- Arithmetic, comparison, and boolean expressions.
- `if/else`, `while`, `for` loops.
- `System.out.println(...)` → console output.
- Static method calls (push/pop frames).
- Step forward / backward / play through the recorded trace.
- Live panels: Call Stack, Variables, Console. (Heap panel present but lightly used until objects land in Phase 2.)

**Explicitly out of MVP** → see §6.

The MVP's success criterion: paste a 15–30 line program with a loop and a helper method, press Run, and watch variables change and frames push/pop in sync with a highlighted current line.

---

## 6. Postponed Features (later versions)

- Multiple top-level classes, inheritance, polymorphism, interfaces, `abstract`.
- Constructors with `this`/`super` chaining nuance, instance vs static initialization order.
- Generics, collections (`ArrayList`, `HashMap`) shown as real internal structures.
- Exceptions / try-catch-finally and stack unwinding.
- Lambdas, streams, method references, anonymous classes.
- Threads / concurrency (explicitly out — memory model is too subtle).
- Recursion depth visualization beyond basic frame stacking.
- Real **Garbage Collection** algorithm animation (mark-sweep). (Phase 6.)
- Save/share a session, export as GIF/video, embeddable widget.
- Breakpoints, "run to line", watch expressions.

---

## 7. How Java Code Flows Through the System

```
1. CODE INPUT
   User types/pastes Java in Monaco → POST /simulate { sourceCode }

2. PARSING
   JavaCodeParser feeds source to JavaParser → CompilationUnit.
   Guard pass: a visitor rejects unsupported constructs early with a
   friendly "not yet supported: <feature> at line N" (UnsupportedFeatureException).

3. AST GENERATION
   We get a typed AST (ClassDeclaration → MethodDeclaration → statements/expressions).
   We locate the entry point (main) and build a symbol table of methods.

4. EXECUTION SIMULATION
   Interpreter walks the AST. It maintains our runtime model:
   CallStack (Deque<StackFrame>) + Heap (Map<id,HeapObject>) + Console.
   Each statement/expression mutates this model.

5. MEMORY STATE GENERATION
   At every meaningful boundary (before executing a line, after an assignment,
   on method enter/exit, on object creation), StepEmitter asks SnapshotFactory
   to capture an ExecutionStep: { line, stack, heap, console, description }.
   TraceRecorder appends it to the trace.

6. UI VISUALIZATION
   Backend returns ExecutionTrace (ordered list of steps).
   Frontend's usePlayer holds a step index; panels render trace.steps[index].
   Stepping is pure client-side array navigation — instant, no re-call.
```

The crucial architectural idea: **the backend produces a complete, replayable film; the frontend is just the projector.**

---

## 8. JSON Contract (Backend ⇄ Frontend)

### Request
```json
{
  "sourceCode": "public class Main { public static void main(String[] args){ int x = 5; } }",
  "options": { "maxSteps": 2000 }
}
```

### Response — `ExecutionTrace`
```json
{
  "status": "OK",
  "error": null,
  "metadata": { "totalSteps": 42, "entryPoint": "Main.main" },
  "steps": [
    {
      "stepIndex": 3,
      "line": 5,
      "description": "Assign 5 to local variable x",
      "event": "ASSIGN",
      "callStack": [
        {
          "frameId": "f0",
          "methodName": "main",
          "className": "Main",
          "isCurrent": true,
          "variables": [
            {
              "name": "x",
              "declaredType": "int",
              "kind": "PRIMITIVE",
              "value": "5",
              "ref": null
            },
            {
              "name": "s",
              "declaredType": "String",
              "kind": "REFERENCE",
              "value": null,
              "ref": 1001
            }
          ]
        }
      ],
      "heap": [
        {
          "id": 1001,
          "type": "String",
          "category": "OBJECT",
          "fields": [
            { "name": "value", "kind": "PRIMITIVE", "value": "\"hello\"", "ref": null }
          ],
          "arrayElements": null,
          "gcState": "REACHABLE"
        }
      ],
      "console": [
        { "sequence": 0, "text": "hello", "newline": true }
      ]
    }
  ]
}
```

Error shape (e.g. unsupported feature or parse error):
```json
{
  "status": "ERROR",
  "error": { "type": "UNSUPPORTED_FEATURE", "message": "lambdas are not yet supported", "line": 12 },
  "metadata": null,
  "steps": []
}
```

**Field conventions worth fixing now**
- `kind`: `"PRIMITIVE" | "REFERENCE"`. If `REFERENCE`, `value` is `null` and `ref` is a heap id (or `null` for `null`).
- Heap `category`: `"OBJECT" | "ARRAY" | "STRING"`.
- `gcState`: `"REACHABLE" | "UNREACHABLE" | "COLLECTED"` (only meaningful from Phase 6; default `REACHABLE`).
- `event`: a small enum (`DECLARE`, `ASSIGN`, `METHOD_ENTER`, `METHOD_EXIT`, `NEW_OBJECT`, `PRINT`, `GC`) used to drive UI animations/highlights.

> **Performance note (later optimization, not MVP):** full snapshots per step are simple but bloat the payload. If traces get large, switch `steps` to **base state + per-step diffs**. Keep the *snapshot* shape above as the canonical model; diffs are an encoding detail. Don't do this in MVP — clarity first.

---

## 9. Entity / Class Design

```java
// ---- Runtime model (backend simulation) ----

enum ValueKind { PRIMITIVE, REFERENCE }

class Value {
    ValueKind kind;
    String declaredType;   // "int", "String", ...
    String literal;        // primitive's textual value; null if reference
    Integer ref;           // heap id; null if primitive or null-reference
}

class Variable {
    String name;
    String declaredType;
    Value value;
}

class StackFrame {
    String frameId;
    String className;
    String methodName;
    int currentLine;
    LinkedHashMap<String, Variable> variables;  // declaration order
    Value returnSlot;       // value being returned, if any
}

class CallStack {
    Deque<StackFrame> frames;   // top = currently executing
}

enum HeapCategory { OBJECT, ARRAY, STRING }
enum GcState { REACHABLE, UNREACHABLE, COLLECTED }

class HeapObject {
    int id;                 // synthetic address
    HeapCategory category;
    String type;            // class name / element type
    LinkedHashMap<String, Variable> fields;  // for OBJECT/STRING
    List<Value> arrayElements;                // for ARRAY
    GcState gcState;
}

class Heap {
    Map<Integer, HeapObject> objects;
    int nextId;
}

class ConsoleOutput {
    int sequence;
    String text;
    boolean newline;
}

// ---- Trace model (serialized to frontend) ----

enum StepEvent { DECLARE, ASSIGN, METHOD_ENTER, METHOD_EXIT, NEW_OBJECT, PRINT, GC }

class ExecutionStep {
    int stepIndex;
    int line;
    String description;             // human-readable narration
    StepEvent event;
    List<StackFrame> callStack;     // snapshot (serialized form)
    List<HeapObject> heap;          // snapshot
    List<ConsoleOutput> console;    // cumulative console up to this step
}

class ExecutionTrace {
    String status;
    ErrorInfo error;
    TraceMetadata metadata;
    List<ExecutionStep> steps;
}
```

The frontend `types/trace.ts` is a 1:1 TypeScript mirror of `ExecutionStep` / `ExecutionTrace` — the contract lives in one shape, two languages.

---

## 10. Phased Implementation Plan

Each phase is **vertical** — it ships parser support → interpreter support → trace fields → UI panel updates, so every phase is demoable end-to-end.

### Phase 0 — Skeleton (foundation, do before Phase 1)
Spring Boot `/simulate` returning a hardcoded trace; React app with Monaco + four empty panels + step controls; lock the JSON contract. Proves the pipe before any semantics exist.

### Phase 1 — Variables
- Parse single class / `main`; primitives + `String`.
- Interpreter: declarations, assignment, arithmetic/boolean expressions, `if/while/for`, `println`.
- Emit steps on declare/assign/print.
- UI: Variables panel + Console panel live; current line highlighted.
- **Demo:** loop counting up, printing values.

### Phase 2 — Objects
- Support a single user-defined class with fields + a constructor; `new`.
- Objects allocated on the **Heap**; variables hold **references**.
- UI: Heap panel (React Flow) shows object nodes; reference arrows from a variable to its object.
- **Demo:** create a `Point(3,4)`, mutate a field, see heap change.

### Phase 3 — Method Calls
- Instance + static methods, parameters (pass primitives by value, references by value-of-reference — the classic teaching moment), `return`.
- Call stack pushes/pops frames; `this` binding.
- UI: Call Stack panel animates frame push/pop; current frame highlighted.
- **Demo:** a helper method that modifies an object's field vs. one that reassigns a parameter — show why one "sticks" and one doesn't.

### Phase 4 — Arrays
- `int[]`, `String[]`, object arrays; `new T[n]`, literals, indexing, `.length`.
- Arrays are heap objects with `arrayElements`.
- UI: array rendered as indexed cells in the heap; references from elements to objects.
- **Demo:** array of objects, iterate and mutate.

### Phase 5 — References (deep)
- Aliasing, `null`, reference reassignment, multiple variables → one object, nested object graphs.
- UI: multiple inbound arrows to one heap node; aliasing made visually obvious; `null` rendered distinctly.
- **Demo:** linked-list-style nodes pointing at each other.

### Phase 6 — Garbage Collection Visualization
- Reachability analysis from GC roots (stack frames + statics) after each step.
- Mark unreachable objects `UNREACHABLE`, then animate collection (`COLLECTED`).
- UI: dim/fade unreachable nodes; optional "Run GC" button with mark→sweep animation.
- **Demo:** reassign the only reference to an object → watch it become unreachable and get collected.

---

## Recommended Next Step

Lock the JSON contract (§8) and the TS/Java type mirrors, then build **Phase 0** (the empty-but-wired skeleton). That de-risks the whole project: once data flows editor → backend → panels with a hardcoded trace, every later phase is "just" filling in interpreter semantics behind a stable interface.
