package com.visualizer.interpreter.engine;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.visualizer.interpreter.runtime.ValueType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end execution tests for the Phase 1C engine over the supported subset. */
class ProgramInterpreterTest {

    private final ProgramInterpreter interpreter = new ProgramInterpreter();

    /** Parse {@code body} wrapped in a main method and execute it. */
    private InterpretationResult run(String body) {
        String source = """
                public class Main {
                    public static void main(String[] args) {
                %s
                    }
                }
                """.formatted(body);
        CompilationUnit cu = new JavaParser().parse(source).getResult().orElseThrow();
        return interpreter.run(cu);
    }

    @Nested
    class Declarations {

        @Test
        void declaresAllFourTypes() {
            InterpretationResult r = run("""
                            int i = 7;
                            double d = 2.5;
                            boolean b = true;
                            String s = "hi";
                    """);
            assertThat(r.variable("i").asInt()).isEqualTo(7);
            assertThat(r.variable("d").asDouble()).isEqualTo(2.5);
            assertThat(r.variable("b").asBoolean()).isTrue();
            assertThat(r.variable("s").asString()).isEqualTo("hi");
        }

        @Test
        void uninitializedVariablesGetDefaults() {
            InterpretationResult r = run("""
                            int i;
                            boolean b;
                    """);
            assertThat(r.variable("i").asInt()).isZero();
            assertThat(r.variable("b").asBoolean()).isFalse();
        }
    }

    @Nested
    class Assignment {

        @Test
        void reassignsVariable() {
            InterpretationResult r = run("""
                            int x = 1;
                            x = 42;
                    """);
            assertThat(r.variable("x").asInt()).isEqualTo(42);
        }
    }

    @Nested
    class Arithmetic {

        @Test
        void addsTwoVariables() {
            InterpretationResult r = run("""
                            int x = 10;
                            int y = 5;
                            int z = x + y;
                    """);
            assertThat(r.variable("z").asInt()).isEqualTo(15);
        }

        @Test
        void integerDivisionAndRemainder() {
            InterpretationResult r = run("""
                            int q = 7 / 2;
                            int m = 7 % 2;
                    """);
            assertThat(r.variable("q").asInt()).isEqualTo(3);
            assertThat(r.variable("m").asInt()).isEqualTo(1);
        }

        @Test
        void respectsPrecedenceAndParentheses() {
            InterpretationResult r = run("""
                            int a = 2 + 3 * 4;
                            int b = (2 + 3) * 4;
                    """);
            assertThat(r.variable("a").asInt()).isEqualTo(14);
            assertThat(r.variable("b").asInt()).isEqualTo(20);
        }
    }

    @Nested
    class Comparison {

        @Test
        void greaterThanIsTrue() {
            InterpretationResult r = run("boolean result = 10 > 5;");
            assertThat(r.variable("result").asBoolean()).isTrue();
        }

        @Test
        void equalityAcrossNumericTypes() {
            InterpretationResult r = run("boolean eq = 5 == 5.0;");
            assertThat(r.variable("eq").asBoolean()).isTrue();
        }
    }

    @Nested
    class BooleanLogic {

        @Test
        void andIsFalse() {
            InterpretationResult r = run("boolean result = true && false;");
            assertThat(r.variable("result").asBoolean()).isFalse();
        }

        @Test
        void orAndNot() {
            InterpretationResult r = run("""
                            boolean a = false || true;
                            boolean b = !false;
                    """);
            assertThat(r.variable("a").asBoolean()).isTrue();
            assertThat(r.variable("b").asBoolean()).isTrue();
        }
    }

    @Nested
    class UnaryOperators {

        @Test
        void postfixIncrementAndDecrement() {
            InterpretationResult r = run("""
                            int x = 5;
                            x++;
                            x++;
                            x--;
                    """);
            assertThat(r.variable("x").asInt()).isEqualTo(6);
        }

        @Test
        void prefixReturnsUpdatedValue() {
            InterpretationResult r = run("""
                            int x = 5;
                            int y = ++x;
                    """);
            assertThat(r.variable("x").asInt()).isEqualTo(6);
            assertThat(r.variable("y").asInt()).isEqualTo(6);
        }

        @Test
        void postfixReturnsOldValue() {
            InterpretationResult r = run("""
                            int x = 5;
                            int y = x++;
                    """);
            assertThat(r.variable("x").asInt()).isEqualTo(6);
            assertThat(r.variable("y").asInt()).isEqualTo(5);
        }
    }

    @Nested
    class CompoundAssignments {

        @Test
        void plusAndTimesEquals() {
            InterpretationResult r = run("""
                            int x = 5;
                            x += 10;
                            x *= 2;
                    """);
            assertThat(r.variable("x").asInt()).isEqualTo(30);
        }

        @Test
        void compoundAssignmentNarrowsToInt() {
            InterpretationResult r = run("""
                            int x = 5;
                            x += 2.5;
                    """);
            assertThat(r.variable("x").asInt()).isEqualTo(7);
        }

        @Test
        void stringPlusEqualsConcatenates() {
            InterpretationResult r = run("""
                            String s = "a";
                            s += "b";
                            s += 1;
                    """);
            assertThat(r.variable("s").asString()).isEqualTo("ab1");
        }
    }

    @Nested
    class IfElse {

        @Test
        void takesElseBranch() {
            InterpretationResult r = run("""
                            int x = 3;
                            String which;
                            if (x > 10) {
                                which = "big";
                            } else {
                                which = "small";
                            }
                    """);
            assertThat(r.variable("which").asString()).isEqualTo("small");
        }
    }

    @Nested
    class Loops {

        @Test
        void whileLoopAccumulates() {
            InterpretationResult r = run("""
                            int total = 0;
                            int n = 1;
                            while (n <= 4) {
                                total += n;
                                n++;
                            }
                    """);
            assertThat(r.variable("total").asInt()).isEqualTo(10);
        }

        @Test
        void forLoopAccumulates() {
            InterpretationResult r = run("""
                            int sum = 0;
                            for (int i = 0; i < 5; i++) {
                                sum += i;
                            }
                    """);
            assertThat(r.variable("sum").asInt()).isEqualTo(10);
        }

        @Test
        void sequentialForLoopsReuseLoopVariable() {
            InterpretationResult r = run("""
                            int a = 0;
                            for (int i = 0; i < 3; i++) { a += i; }
                            for (int i = 0; i < 2; i++) { a += i; }
                    """);
            assertThat(r.variable("a").asInt()).isEqualTo(4); // (0+1+2) + (0+1)
        }
    }

    @Nested
    class Printing {

        @Test
        void printlnAddsNewlinesPrintDoesNot() {
            InterpretationResult r = run("""
                            System.out.print("a");
                            System.out.print("b");
                            System.out.println("c");
                            System.out.println("d");
                    """);
            assertThat(r.output()).isEqualTo("abc\nd\n");
        }

        @Test
        void loopOutputOrderPreserved() {
            InterpretationResult r = run("""
                            for (int i = 0; i < 3; i++) {
                                System.out.println(i);
                            }
                    """);
            assertThat(r.output()).isEqualTo("0\n1\n2\n");
        }

        @Test
        void noArgPrintlnEmitsBlankLine() {
            InterpretationResult r = run("""
                            System.out.println();
                    """);
            assertThat(r.output()).isEqualTo("\n");
        }
    }

    @Nested
    class NumericPromotion {

        @Test
        void intLiteralPromotedToDoubleOnDeclaration() {
            InterpretationResult r = run("double d = 5;");
            assertThat(r.variable("d").type()).isEqualTo(ValueType.DOUBLE);
            assertThat(r.variable("d").asDouble()).isEqualTo(5.0);
        }

        @Test
        void intArithmeticAssignedToDouble() {
            InterpretationResult r = run("double x = 5 + 2;");
            assertThat(r.variable("x").asDouble()).isEqualTo(7.0);
        }

        @Test
        void mixedArithmeticPromotesToDouble() {
            InterpretationResult r = run("double x = 1 + 2.5;");
            assertThat(r.variable("x").asDouble()).isEqualTo(3.5);
        }
    }

    @Nested
    class StringConcatenation {

        @Test
        void stringPlusInt() {
            InterpretationResult r = run("String s = \"Hello \" + 5;");
            assertThat(r.variable("s").asString()).isEqualTo("Hello 5");
        }

        @Test
        void stringPlusString() {
            InterpretationResult r = run("String t = \"A\" + \"B\";");
            assertThat(r.variable("t").asString()).isEqualTo("AB");
        }

        @Test
        void stringPlusBooleanAndDouble() {
            InterpretationResult r = run("String s = \"\" + true + 1.5;");
            assertThat(r.variable("s").asString()).isEqualTo("true1.5");
        }
    }

    @Nested
    class Examples {

        @Test
        void countingLoopExampleProducesExpectedOutput() {
            CompilationUnit cu = new JavaParser().parse(ExamplePrograms.COUNTING_LOOP)
                    .getResult().orElseThrow();
            assertThat(interpreter.run(cu).output()).isEqualTo("0\n1\n2\n3\n4\n");
        }

        @Test
        void branchingExampleTakesPass() {
            CompilationUnit cu = new JavaParser().parse(ExamplePrograms.BRANCHING)
                    .getResult().orElseThrow();
            assertThat(interpreter.run(cu).output()).isEqualTo("pass\n");
        }
    }
}
