package com.visualizer.interpreter;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.visualizer.api.SimulationController;
import com.visualizer.api.dto.ExecutionStepDto;
import com.visualizer.api.dto.ExecutionTrace;
import com.visualizer.api.dto.HeapObjectDto;
import com.visualizer.api.dto.SimulateRequest;
import com.visualizer.api.dto.ValueDto;
import com.visualizer.interpreter.engine.InterpretationResult;
import com.visualizer.interpreter.engine.ProgramInterpreter;
import com.visualizer.interpreter.runtime.ReferenceValue;
import com.visualizer.parser.JavaCodeParser;
import com.visualizer.parser.ParseOutcome;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2E: arrays as indexed heap objects. Covers allocation (int/String/object),
 * default values, indexed reads/writes, length, bounds checking, heap snapshots,
 * trace events, and controller integration.
 */
class ArrayTest {

    private final ProgramInterpreter interpreter = new ProgramInterpreter();
    private final JavaCodeParser parser = new JavaCodeParser();

    private InterpretationResult run(String body) {
        String source = """
                class Person {
                    String name;
                }
                public class Main {
                    public static void main(String[] args) {
                %s
                    }
                }
                """.formatted(body);
        CompilationUnit cu = new JavaParser().parse(source).getResult().orElseThrow();
        return interpreter.run(cu);
    }

    private static List<HeapObjectDto> lastHeap(ExecutionTrace t) {
        return t.steps().get(t.steps().size() - 1).heap();
    }

    @Nested
    class Allocation {

        @Test
        void intArrayAllocatesOnHeapWithDefaults() {
            InterpretationResult r = run("int[] nums = new int[3];");
            assertThat(((ReferenceValue) r.variable("nums")).objectId()).isEqualTo(1001);

            HeapObjectDto array = lastHeap(r.trace()).get(0);
            assertThat(array.category()).isEqualTo("ARRAY");
            assertThat(array.type()).isEqualTo("int[3]");
            assertThat(array.fields()).isNull();
            assertThat(array.arrayElements()).extracting(ValueDto::value)
                    .containsExactly("0", "0", "0");
        }

        @Test
        void stringArrayElementsDefaultToNull() {
            InterpretationResult r = run("String[] names = new String[2];");
            HeapObjectDto array = lastHeap(r.trace()).get(0);
            assertThat(array.type()).isEqualTo("String[2]");
            assertThat(array.arrayElements()).extracting(ValueDto::value)
                    .containsExactly("null", "null");
            assertThat(array.arrayElements()).extracting(ValueDto::kind)
                    .containsExactly("PRIMITIVE", "PRIMITIVE"); // String is a scalar here
        }

        @Test
        void objectArrayElementsDefaultToNullReference() {
            InterpretationResult r = run("Person[] people = new Person[2];");
            HeapObjectDto array = lastHeap(r.trace()).get(0);
            assertThat(array.type()).isEqualTo("Person[2]");
            assertThat(array.arrayElements()).extracting(ValueDto::kind)
                    .containsExactly("REFERENCE", "REFERENCE");
            assertThat(array.arrayElements()).extracting(ValueDto::ref)
                    .containsExactly(null, null); // null references
        }
    }

    @Nested
    class ReadsAndWrites {

        @Test
        void assignsAndReadsElements() {
            InterpretationResult r = run("""
                            int[] nums = new int[3];
                            nums[0] = 10;
                            nums[1] = 20;
                            nums[2] = 30;
                            int x = nums[1];
                    """);
            assertThat(r.variable("x").asInt()).isEqualTo(20);
            HeapObjectDto array = lastHeap(r.trace()).get(0);
            assertThat(array.arrayElements()).extracting(ValueDto::value)
                    .containsExactly("10", "20", "30");
        }

        @Test
        void lengthReturnsArraySize() {
            InterpretationResult r = run("""
                            int[] nums = new int[5];
                            int size = nums.length;
                    """);
            assertThat(r.variable("size").asInt()).isEqualTo(5);
        }

        @Test
        void objectArrayElementCanReferenceAHeapObject() {
            InterpretationResult r = run("""
                            Person[] people = new Person[1];
                            Person p = new Person();
                            people[0] = p;
                    """);
            // people[0] now references the same id as p.
            int pid = ((ReferenceValue) r.variable("p")).objectId();
            HeapObjectDto array = lastHeap(r.trace()).stream()
                    .filter(o -> o.category().equals("ARRAY")).findFirst().orElseThrow();
            assertThat(array.arrayElements().get(0).ref()).isEqualTo(pid);
        }
    }

    @Nested
    class Bounds {

        @Test
        void readOutOfBoundsFails() {
            ExecutionTrace t = new Interpreter(parser).simulate("""
                    public class Main {
                        public static void main(String[] args) {
                            int[] nums = new int[3];
                            int x = nums[100];
                        }
                    }
                    """);
            assertThat(t.status()).isEqualTo("ERROR");
            assertThat(t.error().message()).contains("ArrayIndexOutOfBounds");
        }

        @Test
        void negativeIndexFails() {
            ExecutionTrace t = new Interpreter(parser).simulate("""
                    public class Main {
                        public static void main(String[] args) {
                            int[] nums = new int[3];
                            nums[-1] = 5;
                        }
                    }
                    """);
            assertThat(t.status()).isEqualTo("ERROR");
            assertThat(t.error().message()).contains("ArrayIndexOutOfBounds");
        }
    }

    @Nested
    class TraceEvents {

        @Test
        void emitsCreateAssignAndReadEvents() {
            ExecutionTrace t = run("""
                            int[] nums = new int[3];
                            nums[1] = 20;
                            int x = nums[1];
                    """).trace();

            assertThat(t.steps()).anyMatch(s -> s.event().equals("ARRAY_CREATE")
                    && s.description().equals("Create int array length 3"));
            assertThat(t.steps()).anyMatch(s -> s.event().equals("ARRAY_ASSIGN")
                    && s.description().equals("Assign value 20 to nums[1]"));
            assertThat(t.steps()).anyMatch(s -> s.event().equals("ARRAY_READ")
                    && s.description().equals("Read value 20 from nums[1]"));
        }

        @Test
        void heapSnapshotsReflectIndependentSteps() {
            ExecutionTrace t = run("""
                            int[] nums = new int[2];
                            nums[0] = 10;
                            nums[1] = 20;
                    """).trace();

            ExecutionStepDto afterFirst = t.steps().stream()
                    .filter(s -> s.description().equals("Assign value 10 to nums[0]"))
                    .findFirst().orElseThrow();
            // At that step element 0 is 10 but element 1 is still default 0.
            assertThat(afterFirst.heap().get(0).arrayElements()).extracting(ValueDto::value)
                    .containsExactly("10", "0");
            // Final snapshot has both; the earlier one is untouched.
            assertThat(lastHeap(t).get(0).arrayElements()).extracting(ValueDto::value)
                    .containsExactly("10", "20");
            assertThat(afterFirst.heap().get(0).arrayElements().get(1).value()).isEqualTo("0");
        }
    }

    @Nested
    class Validation {

        @Test
        void supportedArrayProgramIsValid() {
            ParseOutcome outcome = parser.parseAndValidate("""
                    public class Main {
                        public static void main(String[] args) {
                            int[] nums = new int[3];
                            nums[0] = 1;
                            int x = nums[0];
                            int n = nums.length;
                        }
                    }
                    """);
            assertThat(outcome.isValid()).isTrue();
        }
    }

    @Nested
    class Integration {

        @Test
        void simulateRunsSuccessCriteriaProgram() {
            SimulationController controller = new SimulationController(new Interpreter(parser));
            ExecutionTrace t = controller.simulate(new SimulateRequest("""
                    public class Main {
                        public static void main(String[] args) {
                            int[] nums = new int[3];
                            nums[0] = 10;
                            nums[1] = 20;
                            nums[2] = 30;
                            System.out.println(nums[1]);
                        }
                    }
                    """, null));

            assertThat(t.status()).isEqualTo("OK");
            ExecutionStepDto last = t.steps().get(t.steps().size() - 1);
            assertThat(last.heap()).hasSize(1);
            assertThat(last.heap().get(0).type()).isEqualTo("int[3]");
            assertThat(last.heap().get(0).arrayElements()).extracting(ValueDto::value)
                    .containsExactly("10", "20", "30");
            assertThat(last.console().get(0).text()).isEqualTo("20");
            assertThat(last.callStack().get(0).variables().get(0).ref()).isEqualTo(1001);
        }
    }
}
