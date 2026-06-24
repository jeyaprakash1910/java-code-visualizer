package com.visualizer.interpreter;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.visualizer.api.SimulationController;
import com.visualizer.api.dto.ExecutionStepDto;
import com.visualizer.api.dto.ExecutionTrace;
import com.visualizer.api.dto.HeapObjectDto;
import com.visualizer.api.dto.SimulateRequest;
import com.visualizer.api.dto.VariableDto;
import com.visualizer.interpreter.engine.InterpretationResult;
import com.visualizer.interpreter.engine.ProgramInterpreter;
import com.visualizer.parser.JavaCodeParser;
import com.visualizer.parser.ParseOutcome;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * Phase 4A: field inheritance. A subclass object carries its parents' fields plus
 * its own (parent fields first), and inherited fields read/write/default exactly
 * like declared ones.
 */
class InheritanceTest {

    private final ProgramInterpreter interpreter = new ProgramInterpreter();
    private final JavaCodeParser parser = new JavaCodeParser();

    private InterpretationResult run(String source) {
        CompilationUnit cu = new JavaParser().parse(source).getResult().orElseThrow();
        return interpreter.run(cu);
    }

    private static List<HeapObjectDto> lastHeap(ExecutionTrace t) {
        return t.steps().get(t.steps().size() - 1).heap();
    }

    private static final String DOG_PROGRAM = """
            class Animal {
                String name;
            }
            class Dog extends Animal {
                int age;
            }
            public class Main {
                public static void main(String[] args) {
                    Dog d = new Dog();
                    d.name = "Rocky";
                    d.age = 5;
                }
            }
            """;

    @Nested
    class FieldLayout {

        @Test
        void subclassObjectHoldsInheritedThenOwnFields() {
            ExecutionTrace t = run(DOG_PROGRAM).trace();
            HeapObjectDto dog = lastHeap(t).get(0);
            assertThat(dog.type()).isEqualTo("Dog");
            assertThat(dog.fields()).extracting(VariableDto::name, VariableDto::value)
                    .containsExactly(
                            tuple("name", "Rocky"),  // inherited from Animal
                            tuple("age", "5"));      // own
        }

        @Test
        void inheritedFieldsTakeJavaDefaults() {
            ExecutionTrace t = run("""
                    class Animal {
                        String name;
                        int legs;
                    }
                    class Dog extends Animal {
                        int age;
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Dog d = new Dog();
                        }
                    }
                    """).trace();
            assertThat(lastHeap(t).get(0).fields()).extracting(VariableDto::name, VariableDto::value)
                    .containsExactly(
                            tuple("name", "null"),
                            tuple("legs", "0"),
                            tuple("age", "0"));
        }

        @Test
        void multiLevelInheritanceFlattensAllAncestors() {
            ExecutionTrace t = run("""
                    class Animal { String name; }
                    class Mammal extends Animal { boolean warmBlooded; }
                    class Dog extends Mammal { int age; }
                    public class Main {
                        public static void main(String[] args) {
                            Dog d = new Dog();
                            d.name = "Rex";
                            d.warmBlooded = true;
                            d.age = 3;
                        }
                    }
                    """).trace();
            // Root-first order: Animal.name, Mammal.warmBlooded, Dog.age.
            assertThat(lastHeap(t).get(0).fields()).extracting(VariableDto::name, VariableDto::value)
                    .containsExactly(
                            tuple("name", "Rex"),
                            tuple("warmBlooded", "true"),
                            tuple("age", "3"));
        }
    }

    @Nested
    class FieldAccess {

        @Test
        void parentFieldReadThroughSubclass() {
            InterpretationResult r = run("""
                    class Animal { String name; }
                    class Dog extends Animal { int age; }
                    public class Main {
                        public static void main(String[] args) {
                            Dog d = new Dog();
                            d.name = "Rocky";
                            String n = d.name;
                        }
                    }
                    """);
            assertThat(r.variable("n").asString()).isEqualTo("Rocky");
        }

        @Test
        void inheritedFieldAssignmentTracesLikeANormalField() {
            ExecutionTrace t = run(DOG_PROGRAM).trace();
            assertThat(t.steps()).anyMatch(s -> s.event().equals("ASSIGN")
                    && s.description().equals("Assign value Rocky to d.name"));
        }
    }

    @Nested
    class Validation {

        @Test
        void inheritanceProgramIsValid() {
            assertThat(parser.parseAndValidate(DOG_PROGRAM).isValid()).isTrue();
        }

        @Test
        void missingParentIsRejected() {
            ParseOutcome outcome = parser.parseAndValidate("""
                    class Dog extends Animal { int age; }
                    public class Main { public static void main(String[] args) {} }
                    """);
            assertThat(outcome.isValid()).isFalse();
            assertThat(outcome.errors()).anyMatch(e -> e.message().contains("Superclass"));
        }

        @Test
        void selfInheritanceIsRejected() {
            ParseOutcome outcome = parser.parseAndValidate("""
                    class A extends A {}
                    public class Main { public static void main(String[] args) {} }
                    """);
            assertThat(outcome.isValid()).isFalse();
        }

        @Test
        void inheritanceCycleIsRejected() {
            ParseOutcome outcome = parser.parseAndValidate("""
                    class A extends B {}
                    class B extends A {}
                    public class Main { public static void main(String[] args) {} }
                    """);
            assertThat(outcome.isValid()).isFalse();
            assertThat(outcome.errors()).anyMatch(e -> e.message().contains("cycle"));
        }
    }

    @Nested
    class Integration {

        @Test
        void simulateRunsSuccessCriteriaProgram() {
            SimulationController controller = new SimulationController(new Interpreter(parser));
            ExecutionTrace t = controller.simulate(new SimulateRequest(DOG_PROGRAM, null));

            assertThat(t.status()).isEqualTo("OK");
            ExecutionStepDto last = t.steps().get(t.steps().size() - 1);
            assertThat(last.callStack().get(0).variables().get(0).ref()).isEqualTo(1001);
            assertThat(last.heap()).hasSize(1);
            assertThat(last.heap().get(0).type()).isEqualTo("Dog");
            assertThat(last.heap().get(0).fields()).extracting(VariableDto::name, VariableDto::value)
                    .containsExactly(tuple("name", "Rocky"), tuple("age", "5"));
        }
    }
}
