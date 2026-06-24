package com.visualizer.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns raw Java source into a {@link CompilationUnit} and checks, before any
 * execution, that it only uses the subset of Java the interpreter supports.
 *
 * <p>Two-phase: a syntactic parse (JavaParser) followed by a semantic validation
 * pass ({@link ValidationVisitor} plus the top-level structural rules below).
 * Both phases collect <em>all</em> problems with line numbers so the user can fix
 * everything in one go. Phase 1A stops here — it does not interpret anything.</p>
 */
@Component
public class JavaCodeParser {

    /**
     * Parse and validate {@code sourceCode}.
     *
     * @return a result that is either parseable+valid (carrying the AST) or holds
     *         one or more {@link ValidationError}s.
     */
    public ParseOutcome parseAndValidate(String sourceCode) {
        ParseResult<CompilationUnit> result = new JavaParser().parse(sourceCode == null ? "" : sourceCode);

        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            List<ValidationError> syntaxErrors = new ArrayList<>();
            for (Problem problem : result.getProblems()) {
                Integer line = problem.getLocation()
                        .flatMap(loc -> loc.getBegin().getRange())
                        .map(range -> range.begin.line)
                        .orElse(null);
                syntaxErrors.add(new ValidationError(line, problem.getMessage()));
            }
            if (syntaxErrors.isEmpty()) {
                syntaxErrors.add(new ValidationError(null, "Source code could not be parsed."));
            }
            return ParseOutcome.failure(syntaxErrors);
        }

        CompilationUnit cu = result.getResult().get();
        List<ValidationError> errors = validate(cu);
        return errors.isEmpty() ? ParseOutcome.success(cu) : ParseOutcome.failure(errors);
    }

    /** Convenience for callers that only want the AST and don't care about details. */
    public CompilationUnit parse(String sourceCode) {
        ParseOutcome outcome = parseAndValidate(sourceCode);
        if (!outcome.isValid()) {
            throw new IllegalArgumentException(
                    "Source is not valid: " + outcome.errors().get(0));
        }
        return outcome.compilationUnit();
    }

    /** Run the structural rules and the construct-level {@link ValidationVisitor}. */
    private List<ValidationError> validate(CompilationUnit cu) {
        List<ValidationError> errors = new ArrayList<>();
        checkStructure(cu, errors);
        checkInheritance(cu, errors);
        checkMethods(cu, errors);
        new ValidationVisitor(userDefinedClassNames(cu), declaredMethodNames(cu),
                classesWithConstructor(cu)).visit(cu, errors);
        return errors;
    }

    /**
     * Inheritance rules (Phase 4A): a class may {@code extends} exactly one existing
     * user class, with no self-inheritance and no cycles.
     */
    private void checkInheritance(CompilationUnit cu, List<ValidationError> errors) {
        List<ClassOrInterfaceDeclaration> classes = cu.findAll(ClassOrInterfaceDeclaration.class);
        java.util.Set<String> classNames = classes.stream()
                .map(ClassOrInterfaceDeclaration::getNameAsString)
                .collect(java.util.stream.Collectors.toSet());

        // parent map for cycle detection (only edges to known classes).
        java.util.Map<String, String> parent = new java.util.HashMap<>();
        for (ClassOrInterfaceDeclaration cls : classes) {
            if (cls.getExtendedTypes().isEmpty()) {
                continue;
            }
            String child = cls.getNameAsString();
            String superName = cls.getExtendedTypes(0).getNameAsString();
            Integer line = cls.getBegin().map(p -> p.line).orElse(null);
            if (superName.equals(child)) {
                errors.add(new ValidationError(line, "A class cannot extend itself ('" + child + "')."));
                continue;
            }
            if (!classNames.contains(superName)) {
                errors.add(new ValidationError(line, "Superclass '" + superName
                        + "' of '" + child + "' is not defined in this file."));
                continue;
            }
            parent.put(child, superName);
        }

        // Cycle detection: walk each class's ancestry; a repeat is a cycle.
        for (String start : parent.keySet()) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            String current = start;
            while (current != null && seen.add(current)) {
                current = parent.get(current);
            }
            if (current != null) { // re-entered a class already on the path
                errors.add(new ValidationError(null,
                        "Inheritance cycle detected involving class '" + start + "'."));
            }
        }
    }

    /** Classes declaring at least one constructor (so {@code new T(args)} is allowed). */
    private static java.util.Set<String> classesWithConstructor(CompilationUnit cu) {
        return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> !c.getConstructors().isEmpty())
                .map(ClassOrInterfaceDeclaration::getNameAsString)
                .collect(java.util.stream.Collectors.toSet());
    }

    /** All class names declared in the compilation unit (Phase 2A: instantiable types). */
    private static java.util.Set<String> userDefinedClassNames(CompilationUnit cu) {
        return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> !c.isInterface())
                .map(ClassOrInterfaceDeclaration::getNameAsString)
                .collect(java.util.stream.Collectors.toSet());
    }

    /** The class that declares {@code main}, if any. */
    private static java.util.Optional<ClassOrInterfaceDeclaration> mainClass(CompilationUnit cu) {
        return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> c.getMethods().stream().anyMatch(JavaCodeParser::isMainSignature))
                .findFirst();
    }

    /** Names of every method declared in any class — the set callable (Phase 3A). */
    private static java.util.Set<String> declaredMethodNames(CompilationUnit cu) {
        return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .flatMap(cls -> cls.getMethods().stream())
                .map(MethodDeclaration::getNameAsString)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Method-level structural rules (Phase 3A): instance methods are allowed in
     * user classes, but the main class may declare only static methods. Within each
     * class, method names are unique (no overloading) and never directly recursive.
     */
    private void checkMethods(CompilationUnit cu, List<ValidationError> errors) {
        java.util.Optional<ClassOrInterfaceDeclaration> main = mainClass(cu);
        if (main.isEmpty()) {
            return; // missing-main already reported by checkStructure
        }
        ClassOrInterfaceDeclaration mainCls = main.get();

        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            // At most one constructor per class (no overloading / no chaining) — Phase 3C.
            if (cls.getConstructors().size() > 1) {
                cls.getConstructors().stream().skip(1).forEach(ctor ->
                        errors.add(new ValidationError(ctor.getBegin().map(p -> p.line).orElse(null),
                                "Overloaded / multiple constructors are not supported. Keep one"
                                        + " constructor in '" + cls.getNameAsString() + "'.")));
            }

            // The class declaring main may hold only static methods (no `this` there).
            if (cls == mainCls) {
                for (MethodDeclaration m : cls.getMethods()) {
                    if (!m.isStatic()) {
                        errors.add(new ValidationError(m.getBegin().map(p -> p.line).orElse(null),
                                "Methods in the class declaring main must be static. Move instance"
                                        + " method '" + m.getNameAsString() + "' into a user class."));
                    }
                }
            }

            // Unique names within the class (reject overloading / duplicates).
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (MethodDeclaration m : cls.getMethods()) {
                if (!seen.add(m.getNameAsString())) {
                    errors.add(new ValidationError(m.getBegin().map(p -> p.line).orElse(null),
                            "Method overloading / duplicate method '" + m.getNameAsString()
                                    + "' is not supported."));
                }
            }

            // Direct recursion is allowed (Phase 3E); mutual recursion is not.
            checkMutualRecursion(cls, errors);
        }
    }

    /**
     * Reject mutual recursion within a class (Phase 3E): two distinct methods that
     * can reach each other through unqualified intra-class calls. A method calling
     * itself (direct recursion) is permitted — it is an SCC of one node.
     */
    private void checkMutualRecursion(ClassOrInterfaceDeclaration cls, List<ValidationError> errors) {
        java.util.Set<String> methodNames = cls.getMethods().stream()
                .map(MethodDeclaration::getNameAsString)
                .collect(java.util.stream.Collectors.toSet());

        // Call graph from unqualified calls that target a sibling method.
        java.util.Map<String, java.util.Set<String>> callees = new java.util.HashMap<>();
        for (MethodDeclaration m : cls.getMethods()) {
            java.util.Set<String> targets = m.findAll(MethodCallExpr.class).stream()
                    .filter(call -> call.getScope().isEmpty())
                    .map(MethodCallExpr::getNameAsString)
                    .filter(methodNames::contains)
                    .collect(java.util.stream.Collectors.toSet());
            callees.computeIfAbsent(m.getNameAsString(), k -> new java.util.HashSet<>()).addAll(targets);
        }

        java.util.Set<String> reported = new java.util.HashSet<>();
        for (MethodDeclaration m : cls.getMethods()) {
            String name = m.getNameAsString();
            java.util.Set<String> reachable = reachableMethods(name, callees);
            boolean mutual = reachable.stream()
                    .anyMatch(other -> !other.equals(name)
                            && reachableMethods(other, callees).contains(name));
            if (mutual && reported.add(name)) {
                errors.add(new ValidationError(m.getBegin().map(p -> p.line).orElse(null),
                        "Mutual recursion is not supported. Method '" + name
                                + "' participates in a call cycle."));
            }
        }
    }

    /** Methods reachable from {@code start} in one or more unqualified-call steps. */
    private static java.util.Set<String> reachableMethods(
            String start, java.util.Map<String, java.util.Set<String>> callees) {
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.Deque<String> queue =
                new java.util.ArrayDeque<>(callees.getOrDefault(start, java.util.Set.of()));
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (visited.add(next)) {
                queue.addAll(callees.getOrDefault(next, java.util.Set.of()));
            }
        }
        return visited;
    }

    /**
     * Exactly one class must declare {@code main}; any additional top-level types
     * must be classes (Phase 2A allows user-defined classes alongside the entry class).
     */
    private void checkStructure(CompilationUnit cu, List<ValidationError> errors) {
        List<TypeDeclaration<?>> types = cu.getTypes();
        if (types.isEmpty()) {
            errors.add(new ValidationError(null, "No class found. Define a single class with a main method."));
            return;
        }

        for (TypeDeclaration<?> type : types) {
            if (!(type instanceof ClassOrInterfaceDeclaration cls) || cls.isInterface()) {
                errors.add(new ValidationError(type.getBegin().map(p -> p.line).orElse(null),
                        "Top-level types must be classes."));
            }
        }

        long mainCount = types.stream()
                .filter(t -> t instanceof ClassOrInterfaceDeclaration cls && !cls.isInterface())
                .map(t -> (ClassOrInterfaceDeclaration) t)
                .filter(cls -> cls.getMethods().stream().anyMatch(JavaCodeParser::isMainSignature))
                .count();
        if (mainCount == 0) {
            errors.add(new ValidationError(types.get(0).getBegin().map(p -> p.line).orElse(null),
                    "No 'public static void main(String[] args)' method found."));
        } else if (mainCount > 1) {
            errors.add(new ValidationError(types.get(0).getBegin().map(p -> p.line).orElse(null),
                    "Only one class may declare a main method."));
        }
    }

    private static boolean isMainSignature(MethodDeclaration method) {
        return method.getNameAsString().equals("main")
                && method.getParameters().size() == 1
                && method.getType().isVoidType();
    }
}
