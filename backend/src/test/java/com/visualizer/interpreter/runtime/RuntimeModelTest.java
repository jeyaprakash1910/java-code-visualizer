package com.visualizer.interpreter.runtime;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for the Phase 1B runtime model: {@link Value}/{@link Variable}/{@link StackFrame}/{@link CallStack}. */
class RuntimeModelTest {

    @Nested
    class Values {

        @Test
        void carryTypeAndTypedAccessors() {
            assertThat(Value.of(42).asInt()).isEqualTo(42);
            assertThat(Value.of(1.5).asDouble()).isEqualTo(1.5);
            assertThat(Value.of(true).asBoolean()).isTrue();
            assertThat(Value.of("hi").asString()).isEqualTo("hi");

            assertThat(Value.of(42).type()).isEqualTo(ValueType.INT);
            assertThat(Value.of("hi").type()).isEqualTo(ValueType.STRING);
        }

        @Test
        void wrongAccessorThrows() {
            assertThatThrownBy(() -> Value.of(42).asBoolean())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void displayIsHumanReadable() {
            assertThat(Value.of(7).display()).isEqualTo("7");
            assertThat(Value.of(true).display()).isEqualTo("true");
            assertThat(Value.of((String) null).display()).isEqualTo("null");
        }
    }

    @Nested
    class Variables {

        @Test
        void declarationStoresInitialValue() {
            Variable v = new Variable("x", ValueType.INT, Value.of(3));
            assertThat(v.name()).isEqualTo("x");
            assertThat(v.declaredType()).isEqualTo(ValueType.INT);
            assertThat(v.value().asInt()).isEqualTo(3);
        }

        @Test
        void assignmentUpdatesValue() {
            Variable v = new Variable("x", ValueType.INT, Value.of(3));
            v.assign(Value.of(10));
            assertThat(v.value().asInt()).isEqualTo(10);
        }

        @Test
        void typeMismatchOnDeclarationIsRejected() {
            assertThatExceptionOfType(TypeMismatchException.class)
                    .isThrownBy(() -> new Variable("x", ValueType.INT, Value.of(true)));
        }

        @Test
        void typeMismatchOnAssignmentIsRejected() {
            Variable v = new Variable("x", ValueType.INT, Value.of(3));
            assertThatExceptionOfType(TypeMismatchException.class)
                    .isThrownBy(() -> v.assign(Value.of("nope")))
                    .withMessageContaining("'x'");
        }
    }

    @Nested
    class Frames {

        @Test
        void declareLookupAndAssign() {
            StackFrame frame = new StackFrame("Main", "main");
            frame.declare("count", ValueType.INT, Value.of(0));

            assertThat(frame.isDeclared("count")).isTrue();
            assertThat(frame.read("count").asInt()).isEqualTo(0);

            frame.assign("count", Value.of(5));
            assertThat(frame.read("count").asInt()).isEqualTo(5);
        }

        @Test
        void preservesDeclarationOrder() {
            StackFrame frame = new StackFrame("Main", "main");
            frame.declare("a", ValueType.INT, Value.of(1));
            frame.declare("b", ValueType.DOUBLE, Value.of(2.0));
            frame.declare("c", ValueType.BOOLEAN, Value.of(true));

            assertThat(frame.variables())
                    .extracting(Variable::name)
                    .containsExactly("a", "b", "c");
        }

        @Test
        void redeclarationInSameFrameIsRejected() {
            StackFrame frame = new StackFrame("Main", "main");
            frame.declare("x", ValueType.INT, Value.of(1));

            assertThatExceptionOfType(VariableAlreadyDeclaredException.class)
                    .isThrownBy(() -> frame.declare("x", ValueType.DOUBLE, Value.of(2.0)))
                    .withMessageContaining("'x'");
        }

        @Test
        void lookupOfUnknownVariableThrows() {
            StackFrame frame = new StackFrame("Main", "main");
            assertThatExceptionOfType(UndefinedVariableException.class)
                    .isThrownBy(() -> frame.lookup("ghost"))
                    .withMessageContaining("'ghost'");
        }

        @Test
        void assignToUnknownVariableThrows() {
            StackFrame frame = new StackFrame("Main", "main");
            assertThatExceptionOfType(UndefinedVariableException.class)
                    .isThrownBy(() -> frame.assign("ghost", Value.of(1)));
        }

        @Test
        void mapViewIsUnmodifiable() {
            StackFrame frame = new StackFrame("Main", "main");
            frame.declare("x", ValueType.INT, Value.of(1));
            assertThatThrownBy(() -> frame.asMap().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class Stack {

        @Test
        void pushPopAndDepth() {
            CallStack stack = new CallStack();
            assertThat(stack.isEmpty()).isTrue();

            stack.push("Main", "main");
            assertThat(stack.depth()).isEqualTo(1);
            assertThat(stack.current().methodName()).isEqualTo("main");

            stack.push("Main", "helper");
            assertThat(stack.depth()).isEqualTo(2);
            assertThat(stack.current().methodName()).isEqualTo("helper");

            assertThat(stack.pop().methodName()).isEqualTo("helper");
            assertThat(stack.depth()).isEqualTo(1);
        }

        @Test
        void delegatesVariableOpsToCurrentFrame() {
            CallStack stack = new CallStack();
            stack.push("Main", "main");
            stack.declare("n", ValueType.INT, Value.of(2));
            stack.assign("n", Value.of(8));
            assertThat(stack.read("n").asInt()).isEqualTo(8);
        }

        @Test
        void framesDoNotSeeCallerLocals() {
            CallStack stack = new CallStack();
            stack.push("Main", "main");
            stack.declare("local", ValueType.INT, Value.of(1));

            stack.push("Main", "helper"); // new frame, no inherited scope
            assertThatExceptionOfType(UndefinedVariableException.class)
                    .isThrownBy(() -> stack.lookup("local"));
        }

        @Test
        void operationsOnEmptyStackThrow() {
            CallStack stack = new CallStack();
            assertThatExceptionOfType(EmptyCallStackException.class).isThrownBy(stack::current);
            assertThatExceptionOfType(EmptyCallStackException.class).isThrownBy(stack::pop);
        }

        @Test
        void framesListedTopToBottom() {
            CallStack stack = new CallStack();
            stack.push("Main", "main");
            stack.push("Main", "helper");
            assertThat(stack.frames())
                    .extracting(StackFrame::methodName)
                    .containsExactly("helper", "main");
        }
    }
}
