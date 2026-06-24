// 1:1 TypeScript mirror of the backend ExecutionTrace JSON contract.
// Keep this in sync with backend com.visualizer.api.dto.*

export type ValueKind = "PRIMITIVE" | "REFERENCE";
export type HeapCategory = "OBJECT" | "ARRAY" | "STRING";
export type GcState = "REACHABLE" | "UNREACHABLE" | "COLLECTED";
export type StepEvent =
  | "DECLARE"
  | "ASSIGN"
  | "METHOD_ENTER"
  | "METHOD_EXIT"
  | "NEW_OBJECT"
  | "PRINT"
  | "GC";

export interface ValueDto {
  kind: ValueKind;
  declaredType: string;
  value: string | null;
  ref: number | null;
}

export interface VariableDto {
  name: string;
  declaredType: string;
  kind: ValueKind;
  value: string | null;
  ref: number | null;
}

export interface StackFrameDto {
  frameId: string;
  methodName: string;
  className: string;
  isCurrent: boolean;
  variables: VariableDto[];
}

export interface HeapObjectDto {
  id: number;
  type: string;
  category: HeapCategory;
  fields: VariableDto[] | null;
  arrayElements: ValueDto[] | null;
  gcState: GcState;
}

export interface ConsoleOutputDto {
  sequence: number;
  text: string;
  newline: boolean;
}

export interface ExecutionStepDto {
  stepIndex: number;
  line: number;
  description: string;
  event: StepEvent;
  callStack: StackFrameDto[];
  heap: HeapObjectDto[];
  console: ConsoleOutputDto[];
}

export interface ErrorInfoDto {
  type: string;
  message: string;
  line: number | null;
}

export interface TraceMetadataDto {
  totalSteps: number;
  entryPoint: string;
}

export interface ExecutionTrace {
  status: "OK" | "ERROR";
  error: ErrorInfoDto | null;
  metadata: TraceMetadataDto | null;
  steps: ExecutionStepDto[];
}

export interface SimulateRequest {
  sourceCode: string;
  options?: { maxSteps?: number };
}
