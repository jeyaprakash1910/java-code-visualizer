package com.visualizer.interpreter.engine;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.visualizer.interpreter.runtime.CallStack;
import com.visualizer.interpreter.runtime.ReferenceValue;
import com.visualizer.interpreter.runtime.StackFrame;
import com.visualizer.interpreter.runtime.Value;
import com.visualizer.interpreter.runtime.ValueType;
import com.visualizer.interpreter.trace.StepEmitter;
import com.visualizer.interpreter.trace.StepEvent;

import java.util.List;
import java.util.Map;

/**
 * Executes JavaParser {@link Statement}s, mutating the {@link CallStack} and
 * {@link RuntimeConsole}. All value computation is delegated to the
 * {@link ExpressionEvaluator}; this class owns control flow, statement effects
 * (declaration, printing, branching, looping), and the trace hooks.
 *
 * <p>After each meaningful runtime mutation it asks the {@link StepEmitter} to
 * record an {@link com.visualizer.api.dto.ExecutionStepDto} — always <em>after</em>
 * the mutation, so the captured snapshot reflects the new state. Snapshot building
 * itself lives entirely in the emitter/snapshot factory.</p>
 */
public final class StatementExecutor implements MethodInvoker {

    private final CallStack callStack;
    private final RuntimeConsole console;
    private final ExpressionEvaluator evaluator;
    private final StepEmitter steps;
    private final ExecutionContext context;
    /** methodName → its declaration, used to invoke user static methods (Phase 2D). */
    private final Map<String, MethodDeclaration> methods;
    /** Class that declares the methods — used as the frame's class label. */
    private final String className;

    public StatementExecutor(CallStack callStack,
                             RuntimeConsole console,
                             ExpressionEvaluator evaluator,
                             StepEmitter steps,
                             ExecutionContext context,
                             Map<String, MethodDeclaration> methods,
                             String className) {
        this.callStack = callStack;
        this.console = console;
        this.evaluator = evaluator;
        this.steps = steps;
        this.context = context;
        this.methods = methods;
        this.className = className;
    }

    public void execute(Statement stmt) {
        // Safety guard: every executed statement counts against the budget, so any
        // loop (incl. empty-bodied / nested infinite loops) is bounded.
        context.tick();
        if (stmt instanceof BlockStmt block) {
            block.getStatements().forEach(this::execute);
        } else if (stmt instanceof ExpressionStmt exprStmt) {
            executeExpressionStatement(exprStmt.getExpression());
        } else if (stmt instanceof IfStmt ifStmt) {
            executeIf(ifStmt);
        } else if (stmt instanceof WhileStmt whileStmt) {
            executeWhile(whileStmt);
        } else if (stmt instanceof ForStmt forStmt) {
            executeFor(forStmt);
        } else if (stmt instanceof ReturnStmt returnStmt) {
            executeReturn(returnStmt);
        } else {
            throw new InterpreterException("Unsupported statement: " + stmt.getClass().getSimpleName());
        }
    }

    // ---- Method invocation (Phase 2D) ---------------------------------------

    /**
     * Invoke a user static method: bind parameters by value into a fresh frame,
     * push it, run the body, capture the {@code return} value (via
     * {@link ReturnSignal}), pop, and hand the value back to the caller.
     *
     * <p>Pass-by-value falls out for free: primitive {@link Value}s are immutable
     * (a copy is the value itself), and a {@link ReferenceValue} copies the id, so
     * the callee shares the caller's object but reassigning the parameter only
     * rebinds the callee's local slot.</p>
     */
    @Override
    public Value invokeMethod(String methodName, List<Value> arguments) {
        MethodDeclaration method = methods.get(methodName);
        if (method == null) {
            throw new InterpreterException("Unknown method: " + methodName);
        }
        List<Parameter> parameters = method.getParameters();
        if (parameters.size() != arguments.size()) {
            throw new InterpreterException("Method '" + methodName + "' expects "
                    + parameters.size() + " argument(s), got " + arguments.size());
        }

        StackFrame frame = new StackFrame(className, methodName);
        for (int i = 0; i < parameters.size(); i++) {
            Parameter param = parameters.get(i);
            ValueType type = ValueType.resolveDeclared(param.getType().asString());
            Value bound = evaluator.coerce(arguments.get(i), type, false);
            frame.declare(param.getNameAsString(), type, bound);
        }

        callStack.push(frame);
        steps.emit(line(method), StepEvent.METHOD_ENTER, "Enter method " + methodName);

        Value returnValue = null;
        try {
            method.getBody().ifPresent(this::execute);
        } catch (ReturnSignal signal) {
            returnValue = signal.value();
        }
        if (returnValue != null && !method.getType().isVoidType()) {
            returnValue = evaluator.coerce(returnValue,
                    ValueType.resolveDeclared(method.getType().asString()), false);
        }

        callStack.pop();
        steps.emit(line(method), StepEvent.METHOD_EXIT, "Exit method " + methodName);
        return returnValue;
    }

    private void executeReturn(ReturnStmt ret) {
        Value value = ret.getExpression().map(evaluator::evaluate).orElse(null);
        String desc;
        if (value == null) {
            desc = "Return (void)";
        } else if (value instanceof ReferenceValue ref && !ref.isNull()) {
            desc = "Return reference " + ref.display();
        } else {
            desc = "Return value " + value.display();
        }
        // Emit before unwinding so the snapshot still shows the returning frame.
        steps.emit(line(ret), StepEvent.RETURN, desc);
        throw new ReturnSignal(value);
    }

    // ---- Expression statements ----------------------------------------------

    private void executeExpressionStatement(Expression expr) {
        if (expr instanceof VariableDeclarationExpr decl) {
            executeDeclaration(decl, false);
        } else if (expr instanceof MethodCallExpr call) {
            if (isPrintCall(call)) {
                executePrint(call);
            } else {
                // A user static method invoked as a statement: run for its side
                // effects (frame push/pop + trace), discarding any return value.
                evaluator.evaluate(call);
            }
        } else {
            // Assignments, ++/--, etc. — evaluated purely for their side effects.
            evaluator.evaluate(expr);
            Expression target = assignmentTarget(expr);
            if (target != null) {
                // Re-read the (pure) lvalue for its new value; works for both a local
                // variable and an object field. `target.toString()` renders it as
                // written, e.g. "x" or "p.name". A reference copy is described as
                // "reference <id>" to make aliasing visible in the trace.
                Value current = evaluator.evaluate(target);
                String what = current instanceof ReferenceValue ref && !ref.isNull()
                        ? "reference " + ref.display()
                        : "value " + current.display();
                steps.emit(line(expr), StepEvent.ASSIGN, "Assign " + what + " to " + target);
            }
        }
    }

    /** The lvalue a bare assignment / {@code ++} / {@code --} writes to, or null. */
    private static Expression assignmentTarget(Expression expr) {
        if (expr instanceof AssignExpr assign
                && (assign.getTarget() instanceof NameExpr || assign.getTarget() instanceof FieldAccessExpr)) {
            return assign.getTarget();
        }
        if (expr instanceof UnaryExpr unary && unary.getExpression() instanceof NameExpr name
                && isIncrementOrDecrement(unary.getOperator())) {
            return name;
        }
        return null;
    }

    private static boolean isIncrementOrDecrement(UnaryExpr.Operator op) {
        return op == UnaryExpr.Operator.PREFIX_INCREMENT
                || op == UnaryExpr.Operator.POSTFIX_INCREMENT
                || op == UnaryExpr.Operator.PREFIX_DECREMENT
                || op == UnaryExpr.Operator.POSTFIX_DECREMENT;
    }

    /**
     * Declare each variable in the expression, emitting a DECLARE step per variable.
     * When {@code allowRedeclare} (used by {@code for}-loop init), an existing name is
     * re-assigned instead of failing — a pragmatic stand-in for block scoping, which
     * the flat frame doesn't model.
     */
    private void executeDeclaration(VariableDeclarationExpr decl, boolean allowRedeclare) {
        for (var variable : decl.getVariables()) {
            String name = variable.getNameAsString();
            String declaredTypeName = variable.getType().asString();
            ValueType type = resolveType(declaredTypeName);
            boolean initialized = variable.getInitializer().isPresent();
            Value value = variable.getInitializer()
                    .map(init -> evaluator.coerce(evaluator.evaluate(init), type, false))
                    .orElseGet(() -> defaultValue(type, declaredTypeName));
            if (allowRedeclare && callStack.current().isDeclared(name)) {
                callStack.assign(name, value);
            } else {
                callStack.declare(name, type, value);
            }
            String desc;
            if (!initialized) {
                desc = "Declare variable " + name;
            } else if (value instanceof ReferenceValue ref && !ref.isNull()) {
                // A reference initializer copies an id (aliasing), not the object.
                desc = "Declare variable " + name + " = reference " + ref.display();
            } else {
                desc = "Declare variable " + name + " = " + value.display();
            }
            steps.emit(line(variable), StepEvent.DECLARE, desc);
        }
    }

    /**
     * Resolve a declared type token. The four scalars map to their {@link ValueType};
     * anything else is a user-defined class (validated upstream), so it is a
     * {@link ValueType#REFERENCE}.
     */
    private static ValueType resolveType(String declaredTypeName) {
        return switch (declaredTypeName) {
            case "int" -> ValueType.INT;
            case "double" -> ValueType.DOUBLE;
            case "boolean" -> ValueType.BOOLEAN;
            case "String" -> ValueType.STRING;
            default -> ValueType.REFERENCE;
        };
    }

    private static Value defaultValue(ValueType type, String declaredTypeName) {
        return switch (type) {
            case INT -> Value.of(0);
            case DOUBLE -> Value.of(0.0);
            case BOOLEAN -> Value.of(false);
            case STRING -> Value.of((String) null);
            case REFERENCE -> Value.nullReference(declaredTypeName);
        };
    }

    // ---- Printing ------------------------------------------------------------

    /** True for {@code System.out.print(ln)} — everything else is a user method call. */
    private static boolean isPrintCall(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (!name.equals("println") && !name.equals("print")) {
            return false;
        }
        return call.getScope().map(scope -> scope.toString().equals("System.out")).orElse(false);
    }

    private void executePrint(MethodCallExpr call) {
        String name = call.getNameAsString();
        boolean hasArg = !call.getArguments().isEmpty();
        String text = hasArg ? evaluator.evaluate(call.getArgument(0)).display() : "";
        if (name.equals("println")) {
            if (hasArg) {
                console.println(text);
            } else {
                console.println();
            }
        } else if (name.equals("print")) {
            console.print(text);
        } else {
            throw new InterpreterException("Unsupported method call: " + name);
        }
        steps.emit(line(call), StepEvent.PRINT, hasArg ? "Print value " + text : "Print newline");
    }

    // ---- Control flow --------------------------------------------------------

    private void executeIf(IfStmt ifStmt) {
        if (condition(ifStmt.getCondition())) {
            steps.emit(line(ifStmt), StepEvent.IF_BRANCH, "Take if branch (condition true)");
            execute(ifStmt.getThenStmt());
        } else if (ifStmt.getElseStmt().isPresent()) {
            steps.emit(line(ifStmt), StepEvent.IF_BRANCH, "Take else branch (condition false)");
            execute(ifStmt.getElseStmt().get());
        } else {
            steps.emit(line(ifStmt), StepEvent.IF_BRANCH, "Skip if (condition false)");
        }
    }

    private void executeWhile(WhileStmt whileStmt) {
        while (condition(whileStmt.getCondition())) {
            steps.emit(line(whileStmt), StepEvent.WHILE_START, "Enter while loop iteration");
            execute(whileStmt.getBody());
            steps.emit(line(whileStmt), StepEvent.WHILE_END, "Exit while loop iteration");
        }
    }

    private void executeFor(ForStmt forStmt) {
        for (Expression init : forStmt.getInitialization()) {
            if (init instanceof VariableDeclarationExpr decl) {
                executeDeclaration(decl, true);
            } else {
                evaluator.evaluate(init);
            }
        }
        while (forStmt.getCompare().map(this::condition).orElse(true)) {
            steps.emit(line(forStmt), StepEvent.FOR_START, "Enter for loop iteration");
            execute(forStmt.getBody());
            forStmt.getUpdate().forEach(evaluator::evaluate);
            steps.emit(line(forStmt), StepEvent.FOR_END, "Exit for loop iteration");
        }
    }

    private boolean condition(Expression expr) {
        Value value = evaluator.evaluate(expr);
        if (value.type() != ValueType.BOOLEAN) {
            throw new InterpreterException("Condition must be boolean, got " + value.type().javaName());
        }
        return value.asBoolean();
    }

    /** 1-based source line of a node, or 0 when unknown. */
    private static int line(Node node) {
        return node.getBegin().map(p -> p.line).orElse(0);
    }
}
