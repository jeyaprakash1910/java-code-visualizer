import { useEffect, useRef } from "react";
import Editor, { type OnMount } from "@monaco-editor/react";

// Loosely typed to avoid a hard dependency on the `monaco-editor` type package.
type MonacoEditor = Parameters<OnMount>[0];

interface Props {
  value: string;
  onChange: (value: string) => void;
  /** 1-based source line to highlight as the current execution line. */
  highlightLine: number | null;
  readOnly?: boolean;
}

/**
 * Monaco-based Java editor. Highlights the current execution line using a
 * line decoration that tracks `highlightLine`.
 */
export default function CodeEditor({ value, onChange, highlightLine, readOnly }: Props) {
  const editorRef = useRef<MonacoEditor | null>(null);
  const decorationsRef = useRef<string[]>([]);

  const handleMount: OnMount = (ed) => {
    editorRef.current = ed;
  };

  useEffect(() => {
    const ed = editorRef.current;
    if (!ed) return;

    if (highlightLine == null) {
      decorationsRef.current = ed.deltaDecorations(decorationsRef.current, []);
      return;
    }

    decorationsRef.current = ed.deltaDecorations(decorationsRef.current, [
      {
        range: {
          startLineNumber: highlightLine,
          startColumn: 1,
          endLineNumber: highlightLine,
          endColumn: 1,
        },
        options: {
          isWholeLine: true,
          className: "current-line-highlight",
          marginClassName: "current-line-margin",
        },
      },
    ]);
    ed.revealLineInCenterIfOutsideViewport(highlightLine);
  }, [highlightLine]);

  return (
    <div className="h-full w-full overflow-hidden rounded-lg border border-white/10">
      <Editor
        height="100%"
        defaultLanguage="java"
        theme="vs-dark"
        value={value}
        onChange={(v) => onChange(v ?? "")}
        onMount={handleMount}
        options={{
          readOnly,
          minimap: { enabled: false },
          fontSize: 14,
          scrollBeyondLastLine: false,
          automaticLayout: true,
        }}
      />
    </div>
  );
}
