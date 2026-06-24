package com.visualizer.interpreter.runtime;

/**
 * Anything that lives on the {@link Heap} and has a stable identity: a regular
 * {@link HeapObject} (fields) or an {@link ArrayHeapObject} (indexed elements).
 *
 * <p>Sealed so the DTO mapper and snapshot factory can dispatch exhaustively on
 * the two heap shapes. Pure storage — no Java semantics here.</p>
 */
public sealed interface HeapEntity permits HeapObject, ArrayHeapObject {

    int objectId();
}
