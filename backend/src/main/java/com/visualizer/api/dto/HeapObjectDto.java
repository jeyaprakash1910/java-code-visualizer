package com.visualizer.api.dto;

import java.util.List;

/**
 * A snapshot of one heap-allocated object/array/string at a given step.
 */
public record HeapObjectDto(
        int id,
        String type,
        String category,                 // "OBJECT" | "ARRAY" | "STRING"
        List<VariableDto> fields,        // for OBJECT/STRING; null for ARRAY
        List<ValueDto> arrayElements,    // for ARRAY; null otherwise
        String gcState                   // "REACHABLE" | "UNREACHABLE" | "COLLECTED"
) {}
