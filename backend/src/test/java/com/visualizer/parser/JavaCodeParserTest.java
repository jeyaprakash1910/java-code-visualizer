package com.visualizer.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validation coverage for {@link JavaCodeParser}: the supported subset is accepted,
 * and every rejected construct produces a line-numbered {@link ValidationError}.
 */
class JavaCodeParserTest {

    private final JavaCodeParser parser = new JavaCodeParser();

    private List<ValidationError> errorsFor(String source) {
        return parser.parseAndValidate(source).errors();
    }

    private static String wrapInMain(String body) {
        return """
                public class Main {
                    public static void main(String[] args) {
                %s
                    }
                }
                """.formatted(body);
    }

    // ---- Accepted ------------------------------------------------------------

    @Test
    void acceptsSupportedSubset() {
        String source = wrapInMain("""
                        int count = 0;
                        double avg = 1.5;
                        boolean done = false;
                        String label = "n=";
                        for (int i = 0; i < 10; i = i + 1) {
                            count = count + i;
                            if (count > 5) {
                                done = true;
                            }
                        }
                        while (!done) {
                            count = count - 1;
                        }
                        System.out.println(label + count);
                        System.out.print(avg);
                """);

        ParseOutcome outcome = parser.parseAndValidate(source);

        assertThat(outcome.isValid()).isTrue();
        assertThat(outcome.errors()).isEmpty();
        assertThat(outcome.compilationUnit()).isNotNull();
    }

    @Test
    void parseConvenienceReturnsAstForValidSource() {
        assertThat(parser.parse(wrapInMain("int x = 1;"))).isNotNull();
    }

    // ---- Structural ----------------------------------------------------------

    @Test
    void rejectsSyntaxErrorWithLine() {
        List<ValidationError> errors = errorsFor("public class Main { ");
        assertThat(errors).isNotEmpty();
    }

    @Test
    void rejectsMissingMainMethod() {
        List<ValidationError> errors = errorsFor("public class Main { }");
        assertThat(errors).anyMatch(e -> e.message().contains("main"));
    }

    @Test
    void allowsUserDefinedClassAlongsideMain() {
        // Phase 2A: a user-defined (empty) class may accompany the entry class.
        List<ValidationError> errors = errorsFor("""
                class B { }
                public class A { public static void main(String[] args) {} }
                """);
        assertThat(errors).isEmpty();
    }

    @Test
    void rejectsMultipleClassesDeclaringMain() {
        List<ValidationError> errors = errorsFor("""
                class A { public static void main(String[] args) {} }
                class B { public static void main(String[] args) {} }
                """);
        assertThat(errors).anyMatch(e -> e.message().contains("one class may declare a main"));
    }

    @Test
    void rejectsExtendingAnUndefinedClass() {
        // Phase 4A: extends is allowed, but only of a class defined in this file.
        List<ValidationError> errors = errorsFor("""
                public class Main extends Object {
                    public static void main(String[] args) {}
                }
                """);
        assertThat(errors).anyMatch(e -> e.message().contains("Superclass"));
    }

    @Test
    void allowsExtendingAUserClass() {
        List<ValidationError> errors = errorsFor("""
                class Animal { String name; }
                class Dog extends Animal { int age; }
                public class Main {
                    public static void main(String[] args) {
                        Dog d = new Dog();
                    }
                }
                """);
        assertThat(errors).isEmpty();
    }

    @Test
    void rejectsSelfInheritance() {
        List<ValidationError> errors = errorsFor("""
                class A extends A {}
                public class Main { public static void main(String[] args) {} }
                """);
        assertThat(errors).anyMatch(e -> e.message().contains("cannot extend itself"));
    }

    @Test
    void rejectsInheritanceCycle() {
        List<ValidationError> errors = errorsFor("""
                class A extends B {}
                class B extends A {}
                public class Main { public static void main(String[] args) {} }
                """);
        assertThat(errors).anyMatch(e -> e.message().contains("cycle"));
    }

    @Test
    void allowsExtraStaticMethods() {
        // Phase 2D: additional static methods are supported.
        List<ValidationError> errors = errorsFor("""
                public class Main {
                    public static void main(String[] args) {}
                    static int helper() { return 1; }
                }
                """);
        assertThat(errors).isEmpty();
    }

    @Test
    void rejectsInstanceMethodsInMainClass() {
        // Phase 3A: instance methods are allowed in user classes, but the class
        // declaring main may only have static methods.
        List<ValidationError> errors = errorsFor("""
                public class Main {
                    public static void main(String[] args) {}
                    int helper() { return 1; }
                }
                """);
        assertThat(errors).anyMatch(e -> e.message().contains("must be static"));
    }

    @Test
    void allowsInstanceMethodsInUserClasses() {
        List<ValidationError> errors = errorsFor("""
                class Person {
                    String name;
                    void setName(String n) { name = n; }
                }
                public class Main {
                    public static void main(String[] args) {
                        Person p = new Person();
                        p.setName("John");
                    }
                }
                """);
        assertThat(errors).isEmpty();
    }

    @Test
    void allowsDirectRecursion() {
        // Phase 3E: a method may call itself.
        List<ValidationError> errors = errorsFor("""
                public class Main {
                    public static void main(String[] args) {}
                    static int loop(int n) { if (n == 0) return 0; return loop(n - 1); }
                }
                """);
        assertThat(errors).isEmpty();
    }

    @Test
    void rejectsMutualRecursion() {
        List<ValidationError> errors = errorsFor("""
                public class Main {
                    public static void main(String[] args) {}
                    static int a(int n) { return b(n); }
                    static int b(int n) { return a(n); }
                }
                """);
        assertThat(errors).anyMatch(e -> e.message().contains("Mutual recursion"));
    }

    @Test
    void rejectsOverloadedMethods() {
        List<ValidationError> errors = errorsFor("""
                public class Main {
                    public static void main(String[] args) {}
                    static int f() { return 1; }
                    static int f(int x) { return x; }
                }
                """);
        assertThat(errors).anyMatch(e -> e.message().contains("overloading"));
    }

    @Test
    void rejectsFieldInitializers() {
        // Phase 2B: bare fields are allowed, but initializers (which need a
        // constructor) are not.
        List<ValidationError> errors = errorsFor("""
                public class Main {
                    int field = 1;
                    public static void main(String[] args) {}
                }
                """);
        assertThat(errors).anyMatch(e -> e.message().contains("Field initializers"));
    }

    @Test
    void allowsBareFieldDeclarations() {
        List<ValidationError> errors = errorsFor("""
                class Person {
                    String name;
                    int age;
                }
                public class Main {
                    public static void main(String[] args) {}
                }
                """);
        assertThat(errors).isEmpty();
    }

    // ---- Disallowed constructs ----------------------------------------------

    @Test
    void rejectsObjectCreation() {
        List<ValidationError> errors = errorsFor(wrapInMain("Object o = new Object();"));
        assertThat(errors).anyMatch(e -> e.message().contains("Object creation"));
    }

    @Test
    void rejectsMultiDimensionalArrays() {
        List<ValidationError> errors = errorsFor(wrapInMain("int[][] nums = new int[3][3];"));
        assertThat(errors).anyMatch(e -> e.message().contains("Multi-dimensional"));
    }

    @Test
    void rejectsArrayInitializers() {
        List<ValidationError> errors = errorsFor(wrapInMain("int[] nums = {1, 2, 3};"));
        assertThat(errors).anyMatch(e -> e.message().contains("initializers"));
    }

    @Test
    void allowsSingleDimensionalArrays() {
        List<ValidationError> errors = errorsFor(wrapInMain("int[] nums = new int[3]; nums[0] = 5; int x = nums[0];"));
        assertThat(errors).isEmpty();
    }

    @Test
    void rejectsLambda() {
        List<ValidationError> errors = errorsFor(wrapInMain("Runnable r = () -> {};"));
        assertThat(errors).anyMatch(e -> e.message().contains("Lambda"));
    }

    @Test
    void rejectsImportsForCollectionsAndStreams() {
        List<ValidationError> errors = errorsFor("""
                import java.util.List;
                public class Main {
                    public static void main(String[] args) {}
                }
                """);
        assertThat(errors).anyMatch(e -> e.message().contains("Imports"));
    }

    @Test
    void rejectsTryCatch() {
        List<ValidationError> errors = errorsFor(wrapInMain("""
                        try { int x = 1; } catch (Exception e) {}
                """));
        assertThat(errors).anyMatch(e -> e.message().contains("try/catch"));
    }

    @Test
    void rejectsSwitch() {
        List<ValidationError> errors = errorsFor(wrapInMain("""
                        int x = 1;
                        switch (x) { default: break; }
                """));
        assertThat(errors).anyMatch(e -> e.message().contains("switch"));
    }

    @Test
    void rejectsUnsupportedVariableType() {
        List<ValidationError> errors = errorsFor(wrapInMain("long big = 1L;"));
        assertThat(errors).anyMatch(e -> e.message().contains("Unsupported variable type"));
    }

    @Test
    void rejectsArbitraryMethodCalls() {
        List<ValidationError> errors = errorsFor(wrapInMain("int x = Math.max(1, 2);"));
        assertThat(errors).anyMatch(e -> e.message().contains("is not supported"));
    }

    @Test
    void errorsCarryLineNumbers() {
        List<ValidationError> errors = errorsFor(wrapInMain("Object o = new Object();"));
        assertThat(errors).allMatch(e -> e.line() != null);
    }
}
