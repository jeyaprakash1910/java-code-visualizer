package com.visualizer.parser;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.VoidType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.List;
import java.util.Set;

/**
 * Walks a parsed {@link com.github.javaparser.ast.CompilationUnit} and records a
 * {@link ValidationError} for every construct that the Phase 1 interpreter cannot
 * handle yet. It never throws: all findings are accumulated into the supplied list
 * so the user sees every problem at once rather than one at a time.
 *
 * <p>Allowed (Phase 1A): a single class containing only a {@code main} method, with
 * local variables of type {@code int}/{@code double}/{@code boolean}/{@code String},
 * assignments, binary expressions, {@code if}/{@code while}/{@code for}, and
 * {@code System.out.print(ln)}.</p>
 */
public class ValidationVisitor extends VoidVisitorAdapter<List<ValidationError>> {

    /** Scalar types a local variable may currently be declared with. */
    private static final Set<String> ALLOWED_PRIMITIVES = Set.of("int", "double", "boolean");
    private static final String ALLOWED_REFERENCE_TYPE = "String";

    /** Names of user-defined classes declared in this compilation unit. */
    private final Set<String> userDefinedClasses;
    /** Names of callable methods declared in the class that declares {@code main}. */
    private final Set<String> declaredMethods;
    /** Classes that declare a constructor (so {@code new T(args)} is allowed). */
    private final Set<String> classesWithConstructor;

    public ValidationVisitor(Set<String> userDefinedClasses, Set<String> declaredMethods,
                             Set<String> classesWithConstructor) {
        this.userDefinedClasses = userDefinedClasses;
        this.declaredMethods = declaredMethods;
        this.classesWithConstructor = classesWithConstructor;
    }

    private static Integer lineOf(Node node) {
        return node.getBegin().map(p -> p.line).orElse(null);
    }

    private static void reject(List<ValidationError> errors, Node node, String message) {
        errors.add(new ValidationError(lineOf(node), message));
    }

    // ---- Top-level structure -------------------------------------------------

    @Override
    public void visit(ClassOrInterfaceDeclaration decl, List<ValidationError> errors) {
        if (decl.isInterface()) {
            reject(errors, decl, "Interfaces are not supported. Define a single class.");
        }
        // Phase 4A: single-class `extends` is allowed (validated structurally in
        // JavaCodeParser); `implements` is not.
        if (!decl.getImplementedTypes().isEmpty()) {
            reject(errors, decl, "Interfaces ('implements') are not supported.");
        }
        super.visit(decl, errors);
    }

    @Override
    public void visit(ConstructorDeclaration constructor, List<ValidationError> errors) {
        // Phase 3C: constructors are supported. Descend to validate the body, but
        // this(...) / super(...) delegation is still rejected (see below).
        super.visit(constructor, errors);
    }

    @Override
    public void visit(SuperExpr expr, List<ValidationError> errors) {
        reject(errors, expr, "'super' is not supported yet.");
        super.visit(expr, errors);
    }

    @Override
    public void visit(ExplicitConstructorInvocationStmt stmt, List<ValidationError> errors) {
        // this(...) / super(...) constructor delegation.
        reject(errors, stmt, "Constructor calls (this(...) / super(...)) are not supported yet.");
        super.visit(stmt, errors);
    }

    @Override
    public void visit(FieldDeclaration field, List<ValidationError> errors) {
        // Phase 2B: object fields are supported, but only as default-initialized
        // slots of a supported type — no initializers (that needs constructors) and
        // no static fields.
        if (field.isStatic()) {
            reject(errors, field, "Static fields are not supported yet.");
        }
        for (VariableDeclarator var : field.getVariables()) {
            if (!isAllowedType(var.getType())) {
                reject(errors, var, "Unsupported field type '" + var.getTypeAsString()
                        + "'. Allowed types: int, double, boolean, String, or a user-defined class.");
            }
            if (var.getInitializer().isPresent()) {
                reject(errors, var, "Field initializers are not supported yet. Fields start at their"
                        + " Java default; assign them inside main.");
            }
        }
        // Intentionally do not descend further.
    }

    // ---- Disallowed constructs ----------------------------------------------

    @Override
    public void visit(ImportDeclaration importDecl, List<ValidationError> errors) {
        reject(errors, importDecl,
                "Imports are not supported. Streams, collections and other libraries are unavailable.");
        super.visit(importDecl, errors);
    }

    @Override
    public void visit(ObjectCreationExpr expr, List<ValidationError> errors) {
        String typeName = expr.getType().getNameAsString();
        if (!userDefinedClasses.contains(typeName)) {
            reject(errors, expr, "Object creation ('new " + expr.getTypeAsString()
                    + "') is not supported. Only user-defined classes in this file can be instantiated.");
        } else if (expr.getAnonymousClassBody().isPresent()) {
            reject(errors, expr, "Anonymous classes are not supported yet.");
        } else if (!expr.getArguments().isEmpty() && !classesWithConstructor.contains(typeName)) {
            // Arguments require a matching constructor (Phase 3C). Arity is checked at runtime.
            reject(errors, expr, "Class '" + typeName + "' has no constructor; use 'new "
                    + typeName + "()'.");
        }
        super.visit(expr, errors);
    }

    @Override
    public void visit(ArrayCreationExpr expr, List<ValidationError> errors) {
        // Phase 2E: single-dimensional `new T[n]` of a supported element type, with
        // an explicit size and no `{...}` initializer.
        if (expr.getLevels().size() > 1) {
            reject(errors, expr, "Multi-dimensional arrays are not supported yet.");
        } else {
            if (expr.getLevels().get(0).getDimension().isEmpty()) {
                reject(errors, expr, "Array size is required (array initializers are not supported).");
            }
            if (!isSupportedElementType(expr.getElementType())) {
                reject(errors, expr, "Unsupported array element type '"
                        + expr.getElementType().asString()
                        + "'. Allowed: int, double, boolean, String, or a user-defined class.");
            }
        }
        if (expr.getInitializer().isPresent()) {
            reject(errors, expr, "Array initializers ('new T[]{...}') are not supported yet.");
        }
        super.visit(expr, errors);
    }

    @Override
    public void visit(ArrayInitializerExpr expr, List<ValidationError> errors) {
        reject(errors, expr, "Array initializers ('{...}') are not supported yet.");
        super.visit(expr, errors);
    }

    @Override
    public void visit(ArrayAccessExpr expr, List<ValidationError> errors) {
        // Indexed reads/writes are supported in Phase 2E; nothing to reject here.
        super.visit(expr, errors);
    }

    @Override
    public void visit(LambdaExpr expr, List<ValidationError> errors) {
        reject(errors, expr, "Lambda expressions are not supported yet.");
        super.visit(expr, errors);
    }

    @Override
    public void visit(MethodReferenceExpr expr, List<ValidationError> errors) {
        reject(errors, expr, "Method references are not supported yet.");
        super.visit(expr, errors);
    }

    @Override
    public void visit(TryStmt stmt, List<ValidationError> errors) {
        reject(errors, stmt, "try/catch is not supported yet.");
        super.visit(stmt, errors);
    }

    @Override
    public void visit(SwitchStmt stmt, List<ValidationError> errors) {
        reject(errors, stmt, "switch statements are not supported yet.");
        super.visit(stmt, errors);
    }

    // ---- Types & calls -------------------------------------------------------

    @Override
    public void visit(VariableDeclarator var, List<ValidationError> errors) {
        if (!isAllowedType(var.getType())) {
            reject(errors, var, "Unsupported variable type '" + var.getTypeAsString()
                    + "'. Allowed types: int, double, boolean, String, or a user-defined class.");
        }
        super.visit(var, errors);
    }

    @Override
    public void visit(MethodCallExpr call, List<ValidationError> errors) {
        if (isPrintCall(call)) {
            super.visit(call, errors);
            return;
        }
        // A call to a user-defined method: either unqualified (a static method of the
        // main class) or qualified (an instance method, e.g. p.setName(...)). In both
        // cases the method name must be declared by some class; the receiver's actual
        // class is resolved at runtime.
        if (!declaredMethods.contains(call.getNameAsString())) {
            reject(errors, call, "Method call '" + call.getNameAsString()
                    + "' is not supported. Only System.out.print/println and methods"
                    + " declared in this file may be called.");
        }
        super.visit(call, errors);
    }

    @Override
    public void visit(Parameter param, List<ValidationError> errors) {
        Type type = param.getType();
        // `String... args` (vararg) is acceptable as main's parameter.
        boolean stringVararg = param.isVarArgs() && isStringType(type);
        if (!stringVararg && !isAllowedType(type)) {
            reject(errors, param, "Unsupported parameter type '" + param.getType().asString()
                    + "'. Allowed types: int, double, boolean, String, a user-defined class,"
                    + " or a single-dimensional array of those.");
        }
        super.visit(param, errors);
    }

    // ---- Helpers -------------------------------------------------------------

    private boolean isAllowedType(Type type) {
        // A single-dimensional array of a supported element type (Phase 2E).
        if (type instanceof ArrayType arrayType) {
            return isSupportedElementType(arrayType.getComponentType());
        }
        return isSupportedElementType(type);
    }

    /** A scalar or user-class type usable as a variable/element/parameter type. */
    private boolean isSupportedElementType(Type type) {
        if (type instanceof PrimitiveType primitive) {
            return ALLOWED_PRIMITIVES.contains(primitive.getType().asString());
        }
        if (isStringType(type)) {
            return true;
        }
        // A reference to a user-defined class declared in this file (no type args).
        if (type instanceof ClassOrInterfaceType cls) {
            return cls.getTypeArguments().isEmpty()
                    && userDefinedClasses.contains(cls.getNameAsString());
        }
        return false;
    }

    private static boolean isStringType(Type type) {
        if (type instanceof ClassOrInterfaceType cls) {
            return cls.getNameAsString().equals(ALLOWED_REFERENCE_TYPE)
                    && cls.getTypeArguments().isEmpty();
        }
        return false;
    }

    /** True for {@code System.out.println(...)} / {@code System.out.print(...)}. */
    private static boolean isPrintCall(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (!name.equals("println") && !name.equals("print")) {
            return false;
        }
        return call.getScope()
                .map(scope -> scope.toString().equals("System.out"))
                .orElse(false);
    }
}
