package com.visualizer.api.dto;

/**
 * A value held by a variable or array element.
 * Either a PRIMITIVE (value set, ref null) or a REFERENCE (value null, ref = heap id or null for null-reference).
 */
public record ValueDto(
        String kind,         // "PRIMITIVE" | "REFERENCE"
        String declaredType, // e.g. "int", "String"
        String value,        // textual primitive value; null when reference
        Integer ref          // heap id; null when primitive or null-reference
) {
    public static ValueDto primitive(String declaredType, String value) {
        return new ValueDto("PRIMITIVE", declaredType, value, null);
    }

    public static ValueDto reference(String declaredType, Integer ref) {
        return new ValueDto("REFERENCE", declaredType, null, ref);
    }
}
