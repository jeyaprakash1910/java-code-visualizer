package com.visualizer.interpreter.engine;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.visualizer.interpreter.runtime.ArrayHeapObject;
import com.visualizer.interpreter.runtime.CallStack;
import com.visualizer.interpreter.runtime.FieldDefinition;
import com.visualizer.interpreter.runtime.Heap;
import com.visualizer.interpreter.runtime.HeapEntity;
import com.visualizer.interpreter.runtime.HeapObject;
import com.visualizer.interpreter.runtime.ReferenceValue;
import com.visualizer.interpreter.runtime.TypeMismatchException;
import com.visualizer.interpreter.runtime.Value;
import com.visualizer.interpreter.runtime.ValueType;
import com.visualizer.interpreter.runtime.Variable;
import com.visualizer.interpreter.trace.StepEmitter;
import com.visualizer.interpreter.trace.StepEvent;

import java.util.List;
import java.util.Map;

/**
 * Evaluates JavaParser {@link Expression}s into runtime {@link Value}s.
 *
 * <p>This is the home of <em>all</em> Java language semantics: numeric promotion
 * (int → double), string concatenation, comparison rules, short-circuit boolean
 * logic, and the narrowing casts implied by compound assignments. The runtime
 * model stores only already-correct values; it never coerces. Some expressions
 * (assignments, {@code ++}/{@code --}) mutate variables via the {@link CallStack}
 * as a side effect of being evaluated, exactly as in Java.</p>
 */
public final class ExpressionEvaluator {

    private final CallStack callStack;
    private final Heap heap;
    /** className → its field schema, used to default-initialize new objects. */
    private final Map<String, List<FieldDefinition>> classFields;
    /** Emits array trace events (CREATE/READ/ASSIGN) at the point they occur. */
    private final StepEmitter steps;
    /** Wired after construction (see {@link MethodInvoker}); null until then. */
    private MethodInvoker methodInvoker;

    public ExpressionEvaluator(CallStack callStack, Heap heap,
                               Map<String, List<FieldDefinition>> classFields,
                               StepEmitter steps) {
        this.callStack = callStack;
        this.heap = heap;
        this.classFields = classFields;
        this.steps = steps;
    }

    /** Supply the invoker used for user-method call expressions (breaks a construction cycle). */
    public void setMethodInvoker(MethodInvoker methodInvoker) {
        this.methodInvoker = methodInvoker;
    }

    public Value evaluate(Expression expr) {
        if (expr instanceof IntegerLiteralExpr lit) {
            return Value.of(lit.asNumber().intValue());
        }
        if (expr instanceof DoubleLiteralExpr lit) {
            return Value.of(lit.asDouble());
        }
        if (expr instanceof BooleanLiteralExpr lit) {
            return Value.of(lit.getValue());
        }
        if (expr instanceof StringLiteralExpr lit) {
            return Value.of(unescape(lit.getValue()));
        }
        if (expr instanceof NameExpr name) {
            return resolveName(name.getNameAsString()).value();
        }
        if (expr instanceof ThisExpr) {
            return evaluateThis();
        }
        if (expr instanceof FieldAccessExpr field) {
            return evaluateFieldAccess(field);
        }
        if (expr instanceof ArrayCreationExpr creation) {
            return evaluateArrayCreation(creation);
        }
        if (expr instanceof ArrayAccessExpr access) {
            return evaluateArrayRead(access);
        }
        if (expr instanceof EnclosedExpr enclosed) {
            return evaluate(enclosed.getInner());
        }
        if (expr instanceof UnaryExpr unary) {
            return evaluateUnary(unary);
        }
        if (expr instanceof BinaryExpr binary) {
            return evaluateBinary(binary);
        }
        if (expr instanceof AssignExpr assign) {
            return evaluateAssign(assign);
        }
        if (expr instanceof ObjectCreationExpr creation) {
            return evaluateObjectCreation(creation);
        }
        if (expr instanceof MethodCallExpr call) {
            return evaluateMethodCall(call);
        }
        throw new InterpreterException("Unsupported expression: " + expr.getClass().getSimpleName());
    }

    // ---- Object creation (Phase 2A) -----------------------------------------

    /**
     * {@code new Person()} → allocate a {@link HeapObject} on the {@link Heap} and
     * yield a {@link com.visualizer.interpreter.runtime.ReferenceValue} pointing at
     * it. Fields, constructors and arguments are out of scope, so the object is
     * created bare. (Validation has already rejected any constructor arguments.)
     */
    private Value evaluateObjectCreation(ObjectCreationExpr creation) {
        String className = creation.getType().getNameAsString();
        List<FieldDefinition> fields = classFields.getOrDefault(className, List.of());
        HeapObject object = heap.allocate(className, fields);
        // Evaluate constructor arguments in the caller's frame, then run the
        // constructor on the new object (a no-op when the class has none).
        List<Value> arguments = new java.util.ArrayList<>(creation.getArguments().size());
        for (Expression arg : creation.getArguments()) {
            arguments.add(evaluate(arg));
        }
        if (methodInvoker != null) {
            methodInvoker.invokeConstructor(object.objectId(), className, arguments);
        }
        return Value.reference(object.objectId(), object.className());
    }

    /**
     * Evaluate {@code scope.name}: the special array {@code length} (a read of the
     * array's fixed size), otherwise a regular object field read.
     */
    private Value evaluateFieldAccess(FieldAccessExpr field) {
        if (field.getNameAsString().equals("length")) {
            Value scope = evaluate(field.getScope());
            if (scope instanceof ReferenceValue ref && !ref.isNull()
                    && heap.get(ref.objectId()) instanceof ArrayHeapObject array) {
                return Value.of(array.length());
            }
        }
        return resolveField(field).value();
    }

    /**
     * Resolve {@code scope.field} to the heap object's field {@link Variable}, so
     * callers can read or assign through it. The scope must evaluate to a non-null
     * reference (Phase 2B has no nested {@code a.b.c} chains in scope, but this
     * works for them transitively since the scope is evaluated recursively).
     */
    private Variable resolveField(FieldAccessExpr field) {
        Value scope = evaluate(field.getScope());
        if (!(scope instanceof ReferenceValue ref)) {
            throw new InterpreterException(
                    "Field access requires an object, got " + scope.type().javaName());
        }
        if (ref.isNull()) {
            throw new InterpreterException(
                    "NullPointerException: cannot access field '" + field.getNameAsString()
                            + "' of a null reference");
        }
        HeapEntity entity = heap.get(ref.objectId());
        if (!(entity instanceof HeapObject object)) {
            throw new InterpreterException("'" + field.getNameAsString()
                    + "' is not a field (the reference points to an array)");
        }
        if (!object.hasField(field.getNameAsString())) {
            throw new InterpreterException("Object of type " + object.className()
                    + " has no field '" + field.getNameAsString() + "'");
        }
        return object.field(field.getNameAsString());
    }

    // ---- Arrays (Phase 2E) ---------------------------------------------------

    /**
     * {@code new int[3]} → allocate a default-initialized array on the heap and
     * yield a {@link ReferenceValue} pointing at it. Single dimension only; size is
     * evaluated to an int (a negative size is a runtime error, as in Java).
     */
    private Value evaluateArrayCreation(ArrayCreationExpr creation) {
        String elementTypeName = creation.getElementType().asString();
        ValueType elementType = ValueType.resolveDeclared(elementTypeName);
        Expression sizeExpr = creation.getLevels().get(0).getDimension()
                .orElseThrow(() -> new InterpreterException("Array size is required"));
        int length = asArrayLength(evaluate(sizeExpr));

        ArrayHeapObject array = heap.allocateArray(elementType, elementTypeName, length);
        steps.emit(line(creation), StepEvent.ARRAY_CREATE,
                "Create " + elementTypeName + " array length " + length);
        return Value.reference(array.objectId(), elementTypeName + "[]");
    }

    /** {@code nums[1]} (read) → bounds-checked element read; emits ARRAY_READ. */
    private Value evaluateArrayRead(ArrayAccessExpr access) {
        ArrayHeapObject array = resolveArray(access.getName());
        int index = asIndex(evaluate(access.getIndex()));
        checkBounds(array, index);
        Value value = array.get(index);
        steps.emit(line(access), StepEvent.ARRAY_READ,
                "Read " + describe(value) + " from " + access);
        return value;
    }

    /** {@code nums[1] = 20} → bounds-checked element write; emits ARRAY_ASSIGN. */
    private Value assignArrayElement(AssignExpr assign, ArrayAccessExpr access) {
        ArrayHeapObject array = resolveArray(access.getName());
        int index = asIndex(evaluate(access.getIndex()));
        checkBounds(array, index);
        ValueType elementType = array.elementType();
        Value rhs = evaluate(assign.getValue());

        Value result;
        if (assign.getOperator() == AssignExpr.Operator.ASSIGN) {
            result = coerce(rhs, elementType, false);
        } else {
            Value combined = arithmetic(compoundToBinary(assign.getOperator()), array.get(index), rhs);
            result = coerce(combined, elementType, true);
        }
        array.set(index, result);
        steps.emit(line(access), StepEvent.ARRAY_ASSIGN,
                "Assign " + describe(result) + " to " + access);
        return result;
    }

    /** Resolve an expression to a non-null array on the heap. */
    private ArrayHeapObject resolveArray(Expression arrayExpr) {
        Value value = evaluate(arrayExpr);
        if (!(value instanceof ReferenceValue ref)) {
            throw new InterpreterException("Array access requires an array, got "
                    + value.type().javaName());
        }
        if (ref.isNull()) {
            throw new InterpreterException(
                    "NullPointerException: cannot index a null array reference");
        }
        if (!(heap.get(ref.objectId()) instanceof ArrayHeapObject array)) {
            throw new InterpreterException("Indexed value is not an array");
        }
        return array;
    }

    private int asIndex(Value value) {
        if (value.type() != ValueType.INT) {
            throw new InterpreterException("Array index must be an int, got "
                    + value.type().javaName());
        }
        return value.asInt();
    }

    private int asArrayLength(Value value) {
        int length = asIndex(value);
        if (length < 0) {
            throw new InterpreterException("NegativeArraySizeException: " + length);
        }
        return length;
    }

    private void checkBounds(ArrayHeapObject array, int index) {
        if (index < 0 || index >= array.length()) {
            throw new InterpreterException("ArrayIndexOutOfBoundsException: Index " + index
                    + " out of bounds for length " + array.length());
        }
    }

    /** "value 20" for primitives, "reference 1001" for non-null references. */
    private static String describe(Value value) {
        if (value instanceof ReferenceValue ref && !ref.isNull()) {
            return "reference " + ref.display();
        }
        return "value " + value.display();
    }

    private static int line(Node node) {
        return node.getBegin().map(p -> p.line).orElse(0);
    }

    // ---- Method calls (Phase 2D) --------------------------------------------

    /**
     * {@code add(10, 20)} → evaluate the arguments in the <em>caller's</em> frame,
     * then delegate to the {@link MethodInvoker}, which creates the callee frame,
     * binds parameters by value, runs the body, and returns the result.
     */
    private Value evaluateMethodCall(MethodCallExpr call) {
        if (methodInvoker == null) {
            throw new InterpreterException("Method calls are not available");
        }
        List<Value> arguments = new java.util.ArrayList<>(call.getArguments().size());
        for (Expression arg : call.getArguments()) {
            arguments.add(evaluate(arg));
        }
        if (call.getScope().isPresent()) {
            // Instance call: resolve the receiver, then dispatch on its runtime class.
            Value receiver = evaluate(call.getScope().get());
            if (!(receiver instanceof ReferenceValue ref)) {
                throw new InterpreterException("Cannot call '" + call.getNameAsString()
                        + "' on a non-object value");
            }
            if (ref.isNull()) {
                throw new InterpreterException("NullPointerException: cannot call '"
                        + call.getNameAsString() + "' on a null reference");
            }
            if (!(heap.get(ref.objectId()) instanceof HeapObject object)) {
                throw new InterpreterException("Cannot call a method on an array");
            }
            return methodInvoker.invokeInstance(
                    ref.objectId(), object.className(), call.getNameAsString(), arguments);
        }
        return methodInvoker.invokeStatic(call.getNameAsString(), arguments);
    }

    // ---- Coercion (the "promotion" step) ------------------------------------

    /**
     * Convert {@code value} to {@code target}, applying Java widening (int→double)
     * and, when {@code allowNarrowing}, the narrowing cast implied by compound
     * assignment (double→int truncation). Called before any store so the runtime
     * model only ever receives a value of the exact declared type.
     */
    Value coerce(Value value, ValueType target, boolean allowNarrowing) {
        if (value.type() == target) {
            return value;
        }
        if (target == ValueType.DOUBLE && value.type() == ValueType.INT) {
            return Value.of((double) value.asInt());
        }
        if (allowNarrowing && target == ValueType.INT && value.type() == ValueType.DOUBLE) {
            return Value.of((int) value.asDouble());
        }
        throw new TypeMismatchException("<assignment>", target, value.type());
    }

    // ---- Unary ---------------------------------------------------------------

    private Value evaluateUnary(UnaryExpr unary) {
        UnaryExpr.Operator op = unary.getOperator();
        return switch (op) {
            case PLUS -> requireNumeric(evaluate(unary.getExpression()), "unary +");
            case MINUS -> negate(evaluate(unary.getExpression()));
            case LOGICAL_COMPLEMENT -> Value.of(!asBoolean(evaluate(unary.getExpression()), "!"));
            case PREFIX_INCREMENT -> incDec(unary, +1, true);
            case PREFIX_DECREMENT -> incDec(unary, -1, true);
            case POSTFIX_INCREMENT -> incDec(unary, +1, false);
            case POSTFIX_DECREMENT -> incDec(unary, -1, false);
            default -> throw new InterpreterException("Unsupported unary operator: " + op);
        };
    }

    private Value negate(Value v) {
        if (v.type() == ValueType.INT) {
            return Value.of(-v.asInt());
        }
        if (v.type() == ValueType.DOUBLE) {
            return Value.of(-v.asDouble());
        }
        throw new InterpreterException("Operator '-' requires a numeric operand");
    }

    /** Apply ++/-- to a variable or field; returns the new value (prefix) or old (postfix). */
    private Value incDec(UnaryExpr unary, int delta, boolean prefix) {
        Expression operand = unary.getExpression();
        Variable var;
        if (operand instanceof NameExpr name) {
            var = resolveName(name.getNameAsString());
        } else if (operand instanceof FieldAccessExpr field) {
            var = resolveField(field); // e.g. this.age++
        } else {
            throw new InterpreterException("++/-- requires a variable or field operand");
        }
        Value old = var.value();
        Value updated;
        if (old.type() == ValueType.INT) {
            updated = Value.of(old.asInt() + delta);
        } else if (old.type() == ValueType.DOUBLE) {
            updated = Value.of(old.asDouble() + delta);
        } else {
            throw new InterpreterException("++/-- requires a numeric variable");
        }
        var.assign(updated);
        return prefix ? updated : old;
    }

    // ---- Binary --------------------------------------------------------------

    private Value evaluateBinary(BinaryExpr binary) {
        BinaryExpr.Operator op = binary.getOperator();

        // Short-circuit boolean operators must not evaluate the right side eagerly.
        if (op == BinaryExpr.Operator.AND) {
            return Value.of(asBoolean(evaluate(binary.getLeft()), "&&")
                    && asBoolean(evaluate(binary.getRight()), "&&"));
        }
        if (op == BinaryExpr.Operator.OR) {
            return Value.of(asBoolean(evaluate(binary.getLeft()), "||")
                    || asBoolean(evaluate(binary.getRight()), "||"));
        }

        Value left = evaluate(binary.getLeft());
        Value right = evaluate(binary.getRight());
        return switch (op) {
            case PLUS, MINUS, MULTIPLY, DIVIDE, REMAINDER -> arithmetic(op, left, right);
            case EQUALS -> Value.of(areEqual(left, right));
            case NOT_EQUALS -> Value.of(!areEqual(left, right));
            case LESS, LESS_EQUALS, GREATER, GREATER_EQUALS -> relational(op, left, right);
            default -> throw new InterpreterException("Unsupported binary operator: " + op);
        };
    }

    /** Arithmetic, with the {@code +} overload for string concatenation. */
    Value arithmetic(BinaryExpr.Operator op, Value left, Value right) {
        if (op == BinaryExpr.Operator.PLUS
                && (left.type() == ValueType.STRING || right.type() == ValueType.STRING)) {
            return Value.of(left.display() + right.display());
        }
        requireNumeric(left, op.asString());
        requireNumeric(right, op.asString());

        if (left.type() == ValueType.DOUBLE || right.type() == ValueType.DOUBLE) {
            double a = toDouble(left);
            double b = toDouble(right);
            return Value.of(switch (op) {
                case PLUS -> a + b;
                case MINUS -> a - b;
                case MULTIPLY -> a * b;
                case DIVIDE -> a / b;
                case REMAINDER -> a % b;
                default -> throw new InterpreterException("Unsupported arithmetic operator: " + op);
            });
        }
        int a = left.asInt();
        int b = right.asInt();
        return Value.of(switch (op) {
            case PLUS -> a + b;
            case MINUS -> a - b;
            case MULTIPLY -> a * b;
            case DIVIDE -> a / b;
            case REMAINDER -> a % b;
            default -> throw new InterpreterException("Unsupported arithmetic operator: " + op);
        });
    }

    private Value relational(BinaryExpr.Operator op, Value left, Value right) {
        requireNumeric(left, op.asString());
        requireNumeric(right, op.asString());
        double a = toDouble(left);
        double b = toDouble(right);
        return Value.of(switch (op) {
            case LESS -> a < b;
            case LESS_EQUALS -> a <= b;
            case GREATER -> a > b;
            case GREATER_EQUALS -> a >= b;
            default -> throw new InterpreterException("Unsupported relational operator: " + op);
        });
    }

    /**
     * Value equality for {@code ==}/{@code !=}. Numerics compare by promoted value;
     * booleans and Strings compare by value. (Strings use value equality here, a
     * deliberate simplification of Java reference semantics for teaching.)
     */
    private boolean areEqual(Value left, Value right) {
        if (isNumeric(left) && isNumeric(right)) {
            return toDouble(left) == toDouble(right);
        }
        if (left.type() == ValueType.BOOLEAN && right.type() == ValueType.BOOLEAN) {
            return left.asBoolean() == right.asBoolean();
        }
        if (left.type() == ValueType.STRING && right.type() == ValueType.STRING) {
            return java.util.Objects.equals(left.asString(), right.asString());
        }
        throw new InterpreterException(
                "Cannot compare " + left.type().javaName() + " and " + right.type().javaName());
    }

    // ---- Assignment ----------------------------------------------------------

    private Value evaluateAssign(AssignExpr assign) {
        if (assign.getTarget() instanceof ArrayAccessExpr access) {
            return assignArrayElement(assign, access);
        }
        Variable target = resolveAssignTarget(assign.getTarget());
        ValueType targetType = target.declaredType();
        Value rhs = evaluate(assign.getValue());

        Value result;
        if (assign.getOperator() == AssignExpr.Operator.ASSIGN) {
            result = coerce(rhs, targetType, false);
        } else {
            Value combined = arithmetic(compoundToBinary(assign.getOperator()), target.value(), rhs);
            // Compound assignment carries an implicit narrowing cast in Java.
            result = coerce(combined, targetType, true);
        }
        target.assign(result);
        return result;
    }

    /** The assignable slot named by an lvalue: a local variable or an object field. */
    private Variable resolveAssignTarget(Expression target) {
        if (target instanceof NameExpr name) {
            return resolveName(name.getNameAsString());
        }
        if (target instanceof FieldAccessExpr field) {
            return resolveField(field);
        }
        throw new InterpreterException("Assignment target must be a variable or field");
    }

    /**
     * Resolve an unqualified name to its {@link Variable} slot. Lookup order
     * (Phase 3A): a local/parameter in the current frame, then — inside an instance
     * method — a field of the receiver object. This is the implicit-receiver binding
     * that lets {@code name = n} touch the receiver's field without an explicit
     * {@code this}.
     */
    /**
     * Evaluate {@code this} (Phase 3B): the reference to the current instance
     * method's receiver, read from the frame's {@code receiverId}. Available only
     * inside an instance method.
     */
    private Value evaluateThis() {
        Integer receiverId = callStack.current().receiverId();
        if (receiverId == null) {
            throw new InterpreterException("'this' is not available outside an instance method");
        }
        String className = heap.get(receiverId) instanceof HeapObject object
                ? object.className()
                : callStack.current().className();
        return Value.reference(receiverId, className);
    }

    private Variable resolveName(String name) {
        var frame = callStack.current();
        if (frame.isDeclared(name)) {
            return frame.lookup(name);
        }
        Integer receiverId = frame.receiverId();
        if (receiverId != null && heap.get(receiverId) instanceof HeapObject object
                && object.hasField(name)) {
            return object.field(name);
        }
        // Fall back to the frame's own error for a clear "undefined variable" message.
        return frame.lookup(name);
    }

    private BinaryExpr.Operator compoundToBinary(AssignExpr.Operator op) {
        return switch (op) {
            case PLUS -> BinaryExpr.Operator.PLUS;
            case MINUS -> BinaryExpr.Operator.MINUS;
            case MULTIPLY -> BinaryExpr.Operator.MULTIPLY;
            case DIVIDE -> BinaryExpr.Operator.DIVIDE;
            case REMAINDER -> BinaryExpr.Operator.REMAINDER;
            default -> throw new InterpreterException("Unsupported compound operator: " + op);
        };
    }

    // ---- Helpers -------------------------------------------------------------

    private static boolean isNumeric(Value v) {
        return v.type() == ValueType.INT || v.type() == ValueType.DOUBLE;
    }

    private static double toDouble(Value v) {
        return v.type() == ValueType.INT ? v.asInt() : v.asDouble();
    }

    private Value requireNumeric(Value v, String context) {
        if (!isNumeric(v)) {
            throw new InterpreterException(
                    "Operator '" + context + "' requires numeric operands, got " + v.type().javaName());
        }
        return v;
    }

    private boolean asBoolean(Value v, String context) {
        if (v.type() != ValueType.BOOLEAN) {
            throw new InterpreterException(
                    "Operator '" + context + "' requires a boolean operand, got " + v.type().javaName());
        }
        return v.asBoolean();
    }

    /** Translate the common Java escape sequences JavaParser leaves literal. */
    private static String unescape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '0' -> sb.append('\0');
                    case '\\' -> sb.append('\\');
                    case '\'' -> sb.append('\'');
                    case '"' -> sb.append('"');
                    default -> sb.append('\\').append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
