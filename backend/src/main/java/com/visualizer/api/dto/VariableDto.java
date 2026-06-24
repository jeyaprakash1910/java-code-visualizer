package com.visualizer.api.dto;

/**
 * A named variable in a stack frame or a named field of a heap object.
 * Flattened to match the JSON contract (kind/value/ref inlined alongside name).
 */
public record VariableDto(
        String name,
        String declaredType,
        String kind,    // "PRIMITIVE" | "REFERENCE"
        String value,   // textual primitive value; null when reference
        Integer ref     // heap id; null when primitive or null-reference
) {
    public static VariableDto primitive(String name, String declaredType, String value) {
        return new VariableDto(name, declaredType, "PRIMITIVE", value, null);
    }

    public static VariableDto reference(String name, String declaredType, Integer ref) {
        return new VariableDto(name, declaredType, "REFERENCE", null, ref);
    }
}
