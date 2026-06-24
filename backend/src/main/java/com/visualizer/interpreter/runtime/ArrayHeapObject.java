package com.visualizer.interpreter.runtime;

import java.util.List;
import java.util.Objects;

/**
 * A single-dimensional array living on the {@link Heap} (Phase 2E): an identity
 * ({@code objectId}), its element type, a fixed {@code length}, and the element
 * slots — all default-initialized at allocation.
 *
 * <p>Pure storage: it offers raw indexed get/set and a {@link #length()}, but does
 * <em>no</em> bounds checking or type coercion — those are Java semantics the
 * interpreter owns. {@code elementType} is the resolved {@link ValueType};
 * {@code elementTypeName} is the source name (e.g. {@code int}, {@code Person}).</p>
 */
public final class ArrayHeapObject implements HeapEntity {

    private final int objectId;
    private final ValueType elementType;
    private final String elementTypeName;
    private final Value[] elements;

    public ArrayHeapObject(int objectId, ValueType elementType, String elementTypeName, Value[] elements) {
        this.objectId = objectId;
        this.elementType = Objects.requireNonNull(elementType, "elementType");
        this.elementTypeName = Objects.requireNonNull(elementTypeName, "elementTypeName");
        this.elements = Objects.requireNonNull(elements, "elements");
    }

    @Override
    public int objectId() {
        return objectId;
    }

    public ValueType elementType() {
        return elementType;
    }

    public String elementTypeName() {
        return elementTypeName;
    }

    public int length() {
        return elements.length;
    }

    /** Raw read; caller is responsible for bounds checking. */
    public Value get(int index) {
        return elements[index];
    }

    /** Raw write; caller is responsible for bounds checking and type correctness. */
    public void set(int index, Value value) {
        elements[index] = value;
    }

    /** Elements in index order; unmodifiable snapshot helper for the DTO mapper. */
    public List<Value> elements() {
        return List.of(elements);
    }
}
