package com.visualizer.interpreter.trace;

import com.visualizer.api.dto.HeapObjectDto;
import com.visualizer.api.dto.StackFrameDto;
import com.visualizer.api.dto.ValueDto;
import com.visualizer.api.dto.VariableDto;
import com.visualizer.interpreter.runtime.ArrayHeapObject;
import com.visualizer.interpreter.runtime.HeapEntity;
import com.visualizer.interpreter.runtime.HeapObject;
import com.visualizer.interpreter.runtime.ReferenceValue;
import com.visualizer.interpreter.runtime.StackFrame;
import com.visualizer.interpreter.runtime.Value;
import com.visualizer.interpreter.runtime.Variable;

import java.util.List;

/**
 * Pure, stateless conversion from the runtime model to the API DTO contract.
 *
 * <p>Phase 1D has no heap, so every {@link Value} is a primitive (Strings
 * included — they map to a {@code PRIMITIVE} {@link ValueDto}/{@link VariableDto}
 * carrying their text, never a heap reference). The DTO records are used exactly
 * as defined; nothing here invents new structure.</p>
 */
public final class RuntimeDtoMapper {

    private RuntimeDtoMapper() {
    }

    /**
     * {@code Value → ValueDto}. Scalars become {@code PRIMITIVE} DTOs; a
     * {@link ReferenceValue} becomes a {@code REFERENCE} DTO carrying the object's
     * class as the declared type and the heap id as {@code ref} ({@code null} for
     * the null reference).
     */
    public static ValueDto toValueDto(Value value) {
        if (value instanceof ReferenceValue ref) {
            return ValueDto.reference(ref.className(), ref.objectId());
        }
        return ValueDto.primitive(value.type().javaName(), value.display());
    }

    /**
     * {@code HeapEntity → HeapObjectDto}: dispatches to the object or array shape,
     * stamping the supplied {@code gcState} (REACHABLE / UNREACHABLE / COLLECTED).
     */
    public static HeapObjectDto toHeapObjectDto(HeapEntity entity, String gcState) {
        if (entity instanceof ArrayHeapObject array) {
            return toArrayDto(array, gcState);
        }
        return toObjectDto((HeapObject) entity, gcState);
    }

    /** A regular object: populates {@code fields}; {@code arrayElements} stays null. */
    private static HeapObjectDto toObjectDto(HeapObject object, String gcState) {
        List<VariableDto> fields = object.fields().stream()
                .map(RuntimeDtoMapper::toVariableDto)
                .toList();
        return new HeapObjectDto(
                object.objectId(),
                object.className(),
                "OBJECT",
                fields,
                null,        // not an array
                gcState
        );
    }

    /** An array: populates {@code arrayElements}; {@code fields} stays null. */
    private static HeapObjectDto toArrayDto(ArrayHeapObject array, String gcState) {
        List<ValueDto> elements = array.elements().stream()
                .map(RuntimeDtoMapper::toValueDto)
                .toList();
        // e.g. "int[3]" — element type plus length, for the heap label.
        String type = array.elementTypeName() + "[" + array.length() + "]";
        return new HeapObjectDto(
                array.objectId(),
                type,
                "ARRAY",
                null,        // not an object
                elements,
                gcState
        );
    }

    /** {@code Variable → VariableDto} (flattened, reusing {@link #toValueDto}). */
    public static VariableDto toVariableDto(Variable variable) {
        ValueDto v = toValueDto(variable.value());
        return new VariableDto(variable.name(), v.declaredType(), v.kind(), v.value(), v.ref());
    }

    /** {@code StackFrame → StackFrameDto}. Variables keep declaration order. */
    public static StackFrameDto toStackFrameDto(StackFrame frame, String frameId, boolean isCurrent) {
        List<VariableDto> variables = frame.variables().stream()
                .map(RuntimeDtoMapper::toVariableDto)
                .toList();
        return new StackFrameDto(frameId, frame.methodName(), frame.className(), isCurrent, variables);
    }
}
