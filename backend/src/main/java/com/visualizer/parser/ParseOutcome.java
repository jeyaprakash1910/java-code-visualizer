package com.visualizer.parser;

import com.github.javaparser.ast.CompilationUnit;

import java.util.List;

/**
 * Result of {@link JavaCodeParser#parseAndValidate(String)}: either a valid AST or
 * a non-empty, line-numbered list of reasons it was rejected. Exactly one of the
 * two sides is populated.
 *
 * @param compilationUnit the parsed AST when {@code valid}; otherwise {@code null}
 * @param errors          the problems when invalid; empty when valid
 */
public record ParseOutcome(CompilationUnit compilationUnit, List<ValidationError> errors) {

    public static ParseOutcome success(CompilationUnit cu) {
        return new ParseOutcome(cu, List.of());
    }

    public static ParseOutcome failure(List<ValidationError> errors) {
        return new ParseOutcome(null, List.copyOf(errors));
    }

    public boolean isValid() {
        return errors.isEmpty();
    }
}
