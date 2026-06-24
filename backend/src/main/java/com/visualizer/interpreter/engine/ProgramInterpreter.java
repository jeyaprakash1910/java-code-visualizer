package com.visualizer.interpreter.engine;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.visualizer.interpreter.gc.ReachabilityAnalyzer;
import com.visualizer.interpreter.runtime.CallStack;
import com.visualizer.interpreter.runtime.FieldDefinition;
import com.visualizer.interpreter.runtime.Heap;
import com.visualizer.interpreter.runtime.ValueType;
import com.visualizer.interpreter.trace.SnapshotFactory;
import com.visualizer.interpreter.trace.StepEmitter;
import com.visualizer.interpreter.trace.TraceRecorder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level driver for execution: locate {@code main}, set up a fresh
 * {@link CallStack} with its frame, run the body statement-by-statement while
 * recording a step per meaningful action, and hand back the
 * {@link InterpretationResult} (final state + {@code ExecutionTrace}).
 *
 * <p>Not a Spring bean and not wired to the controller directly — the
 * {@code interpreter.Interpreter} facade owns parse/validate and HTTP concerns.
 * This assumes the source already passed validation, so it trusts the structure
 * (exactly one class, a {@code main} method) and does not re-validate.</p>
 */
public final class ProgramInterpreter {

    /** Validated CU in, final runtime state + recorded trace out. */
    public InterpretationResult run(CompilationUnit cu) {
        // With user-defined classes (Phase 2A) the CU may hold several classes;
        // the entry point is the one declaring main, not simply the first class.
        ClassOrInterfaceDeclaration clazz = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> c.getMethods().stream().anyMatch(m -> m.getNameAsString().equals("main")))
                .findFirst()
                .orElseThrow(() -> new InterpreterException("No class with a main method found"));
        MethodDeclaration main = clazz.getMethods().stream()
                .filter(m -> m.getNameAsString().equals("main"))
                .findFirst()
                .orElseThrow(() -> new InterpreterException("No main method found"));

        CallStack callStack = new CallStack();
        callStack.push(clazz.getNameAsString(), "main");
        Heap heap = new Heap();

        RuntimeConsole console = new RuntimeConsole();
        TraceRecorder recorder = new TraceRecorder();
        StepEmitter emitter = new StepEmitter(recorder, new SnapshotFactory(),
                callStack, heap, console, new ReachabilityAnalyzer());

        ExpressionEvaluator evaluator =
                new ExpressionEvaluator(callStack, heap, classFields(cu), emitter);
        ExecutionContext context = new ExecutionContext();
        Map<String, MethodDeclaration> methodRegistry = methodRegistry(clazz);
        StatementExecutor executor = new StatementExecutor(
                callStack, console, evaluator, emitter, context, methodRegistry, clazz.getNameAsString());
        // Break the evaluator ↔ executor cycle: calls in expressions reach the executor.
        evaluator.setMethodInvoker(executor);

        // A top-level `return;` in main unwinds via ReturnSignal; absorb it here.
        // After each top-level statement, run a GC pass so newly-lost references
        // surface as GC_MARK steps; finish with one collection pass.
        try {
            if (main.getBody().isPresent()) {
                for (var statement : main.getBody().get().getStatements()) {
                    executor.execute(statement);
                    emitter.runGc();
                }
            }
        } catch (ReturnSignal ignored) {
            // main returned early (void) — nothing more to do.
        }
        emitter.collect();

        String entryPoint = clazz.getNameAsString() + ".main";
        // Leave the main frame on the stack so callers can inspect final variables.
        return new InterpretationResult(callStack, console, recorder.build(entryPoint));
    }

    /**
     * Build the field schema for every class in the CU: {@code className → ordered
     * field definitions}. Each field's declared type is resolved to a
     * {@link ValueType} (user classes → {@link ValueType#REFERENCE}); validation has
     * already confirmed the types are supported.
     */
    /** {@code methodName → declaration} for every method in the main class (Phase 2D). */
    private static Map<String, MethodDeclaration> methodRegistry(ClassOrInterfaceDeclaration clazz) {
        Map<String, MethodDeclaration> registry = new LinkedHashMap<>();
        for (MethodDeclaration method : clazz.getMethods()) {
            registry.put(method.getNameAsString(), method);
        }
        return registry;
    }

    private static Map<String, List<FieldDefinition>> classFields(CompilationUnit cu) {
        Map<String, List<FieldDefinition>> result = new LinkedHashMap<>();
        for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            List<FieldDefinition> fields = new ArrayList<>();
            for (FieldDeclaration field : clazz.getFields()) {
                String typeName = field.getElementType().asString();
                ValueType type = ValueType.resolveDeclared(typeName);
                field.getVariables().forEach(v ->
                        fields.add(new FieldDefinition(v.getNameAsString(), type, typeName)));
            }
            result.put(clazz.getNameAsString(), fields);
        }
        return result;
    }
}
