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
import com.visualizer.interpreter.runtime.ReferenceValue;
import com.visualizer.parser.JavaCodeParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3D: method chaining. A {@code receiver.method(args)} whose result is itself
 * a reference can immediately become the next receiver — fluent {@code return this}
 * patterns and chains across objects, all from normal call evaluation.
 */
class MethodChainingTest {

    private final ProgramInterpreter interpreter = new ProgramInterpreter();
    private final JavaCodeParser parser = new JavaCodeParser();

    private InterpretationResult run(String source) {
        CompilationUnit cu = new JavaParser().parse(source).getResult().orElseThrow();
        return interpreter.run(cu);
    }

    private static List<HeapObjectDto> lastHeap(ExecutionTrace t) {
        return t.steps().get(t.steps().size() - 1).heap();
    }

    private static final String FLUENT_PERSON = """
            class Person {
                String name;
                int age;
                Person setName(String name) { this.name = name; return this; }
                Person setAge(int age) { this.age = age; return this; }
            }
            """;

    @Nested
    class ReturnThis {

        @Test
        void methodReturningThisYieldsTheReceiver() {
            InterpretationResult r = run(FLUENT_PERSON + """
                    public class Main {
                        public static void main(String[] args) {
                            Person p = new Person();
                            Person same = p.setName("John");
                        }
                    }
                    """);
            // `same` is the very same object as `p`.
            assertThat(((ReferenceValue) r.variable("same")).objectId())
                    .isEqualTo(((ReferenceValue) r.variable("p")).objectId());
        }
    }

    @Nested
    class Chains {

        @Test
        void chainedTwoCallsMutateTheSameObject() {
            ExecutionTrace t = run(FLUENT_PERSON + """
                    public class Main {
                        public static void main(String[] args) {
                            Person p = new Person();
                            p.setName("John").setAge(30);
                        }
                    }
                    """).trace();
            assertThat(lastHeap(t)).hasSize(1);
            assertThat(lastHeap(t).get(0).fields()).extracting(VariableDto::name, VariableDto::value)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("name", "John"),
                            org.assertj.core.groups.Tuple.tuple("age", "30"));
        }

        @Test
        void chainedThreeCallsOnANewObjectAssignedToLocal() {
            // The success-criteria program: new Person().setName(..).setAge(..).
            InterpretationResult r = run(FLUENT_PERSON + """
                    public class Main {
                        public static void main(String[] args) {
                            Person p = new Person()
                                .setName("John")
                                .setAge(30);
                        }
                    }
                    """);
            assertThat(((ReferenceValue) r.variable("p")).objectId()).isEqualTo(1001);
            HeapObjectDto person = lastHeap(r.trace()).get(0);
            assertThat(person.fields()).extracting(VariableDto::name, VariableDto::value)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("name", "John"),
                            org.assertj.core.groups.Tuple.tuple("age", "30"));
        }

        @Test
        void chainAcrossADifferentReturnedObject() {
            // duplicate() returns a *new* object; the chain then mutates that one,
            // leaving the original untouched.
            ExecutionTrace t = run("""
                    class Person {
                        String name;
                        Person setName(String name) { this.name = name; return this; }
                        Person duplicate() {
                            Person copy = new Person();
                            copy.name = this.name;
                            return copy;
                        }
                    }
                    public class Main {
                        public static void main(String[] args) {
                            Person original = new Person().setName("John");
                            Person renamed = original.duplicate().setName("Bob");
                        }
                    }
                    """).trace();
            // 1001 original stays "John"; 1002 the duplicate becomes "Bob".
            assertThat(lastHeap(t).get(0).fields().get(0).value()).isEqualTo("John");
            assertThat(lastHeap(t).get(1).fields().get(0).value()).isEqualTo("Bob");
        }
    }

    @Nested
    class TraceAndStack {

        @Test
        void eachChainedCallEntersReturnsAndExitsInOrder() {
            ExecutionTrace t = run(FLUENT_PERSON + """
                    public class Main {
                        public static void main(String[] args) {
                            Person p = new Person();
                            p.setName("John").setAge(30);
                        }
                    }
                    """).trace();

            // The chain produces, in evaluation order: setName then setAge, each with
            // its own ENTER / RETURN / EXIT.
            List<String> chainEvents = t.steps().stream()
                    .map(ExecutionStepDto::event)
                    .filter(e -> e.equals("METHOD_ENTER") || e.equals("RETURN") || e.equals("METHOD_EXIT"))
                    .toList();
            assertThat(chainEvents).containsExactly(
                    "METHOD_ENTER", "RETURN", "METHOD_EXIT",   // setName
                    "METHOD_ENTER", "RETURN", "METHOD_EXIT");  // setAge

            assertThat(t.steps()).anyMatch(s -> s.description().equals("Enter method Person.setName"));
            assertThat(t.steps()).anyMatch(s -> s.description().equals("Enter method Person.setAge"));
            // return this → "Return reference 1001" for both.
            assertThat(t.steps().stream().filter(s -> s.event().equals("RETURN")))
                    .allMatch(s -> s.description().equals("Return reference 1001"));
        }

        @Test
        void chainCallsUseNormalPushPopWithNoNestedChainFrame() {
            ExecutionTrace t = run(FLUENT_PERSON + """
                    public class Main {
                        public static void main(String[] args) {
                            Person p = new Person();
                            p.setName("John").setAge(30);
                        }
                    }
                    """).trace();
            // Every method frame is exactly one deep over main (no stacked chain frames).
            int maxDepth = t.steps().stream().mapToInt(s -> s.callStack().size()).max().orElseThrow();
            assertThat(maxDepth).isEqualTo(2);
            // Ends back in main only.
            assertThat(t.steps().get(t.steps().size() - 1).callStack()).hasSize(1);
        }
    }

    @Nested
    class Integration {

        @Test
        void simulateRunsSuccessCriteriaProgram() {
            SimulationController controller = new SimulationController(new Interpreter(parser));
            ExecutionTrace t = controller.simulate(new SimulateRequest(FLUENT_PERSON + """
                    public class Main {
                        public static void main(String[] args) {
                            Person p =
                                new Person()
                                    .setName("John")
                                    .setAge(30);
                        }
                    }
                    """, null));

            assertThat(t.status()).isEqualTo("OK");
            ExecutionStepDto last = t.steps().get(t.steps().size() - 1);
            assertThat(last.callStack()).hasSize(1);
            assertThat(last.callStack().get(0).variables().get(0).ref()).isEqualTo(1001);
            assertThat(last.heap().get(0).fields()).extracting(VariableDto::name, VariableDto::value)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("name", "John"),
                            org.assertj.core.groups.Tuple.tuple("age", "30"));
        }
    }
}
