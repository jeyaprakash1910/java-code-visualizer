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
        checkMethods(cu, errors);
        new ValidationVisitor(userDefinedClassNames(cu), declaredMethodNames(cu)).visit(cu, errors);
        return errors;
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

    /** Names of methods declared in the main class — the set callable from main (Phase 2D). */
    private static java.util.Set<String> declaredMethodNames(CompilationUnit cu) {
        return mainClass(cu)
                .map(cls -> cls.getMethods().stream()
                        .map(MethodDeclaration::getNameAsString)
                        .collect(java.util.stream.Collectors.<String>toSet()))
                .orElseGet(java.util.Set::of);
    }

    /**
     * Method-level structural rules (Phase 2D): methods live only in the main class,
     * have unique names (no overloading), and never call themselves (no recursion).
     */
    private void checkMethods(CompilationUnit cu, List<ValidationError> errors) {
        java.util.Optional<ClassOrInterfaceDeclaration> main = mainClass(cu);
        if (main.isEmpty()) {
            return; // missing-main already reported by checkStructure
        }
        ClassOrInterfaceDeclaration mainCls = main.get();

        // Helper methods are only supported in the class that declares main.
        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (cls != mainCls && !cls.getMethods().isEmpty()) {
                cls.getMethods().forEach(m -> errors.add(new ValidationError(
                        m.getBegin().map(p -> p.line).orElse(null),
                        "Methods are only supported in the class that declares main.")));
            }
        }

        // Unique names (reject overloading / duplicates) within the main class.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (MethodDeclaration m : mainCls.getMethods()) {
            if (!seen.add(m.getNameAsString())) {
                errors.add(new ValidationError(m.getBegin().map(p -> p.line).orElse(null),
                        "Method overloading / duplicate method '" + m.getNameAsString()
                                + "' is not supported."));
            }
        }

        // No direct recursion: a method may not call itself (unqualified) in its body.
        for (MethodDeclaration m : mainCls.getMethods()) {
            String name = m.getNameAsString();
            boolean recurses = m.findAll(MethodCallExpr.class).stream()
                    .anyMatch(call -> call.getScope().isEmpty() && call.getNameAsString().equals(name));
            if (recurses) {
                errors.add(new ValidationError(m.getBegin().map(p -> p.line).orElse(null),
                        "Recursion is not supported. Method '" + name + "' calls itself."));
            }
        }
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
