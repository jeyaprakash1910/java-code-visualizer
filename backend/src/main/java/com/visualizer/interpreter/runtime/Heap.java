package com.visualizer.interpreter.runtime;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The simulated heap: stores {@link HeapEntity}s (objects and arrays) and hands
 * out unique, monotonically increasing object ids starting at
 * {@link #FIRST_OBJECT_ID}.
 *
 * <p>Pure storage — like the rest of the runtime model it holds no Java semantics
 * and does no reachability/GC analysis. Insertion order is preserved so snapshots
 * list entities in allocation order.</p>
 */
public final class Heap {

    /** The id of the first allocated object; ids count up from here. */
    public static final int FIRST_OBJECT_ID = 1001;

    private final Map<Integer, HeapEntity> entities = new LinkedHashMap<>();
    private int nextObjectId = FIRST_OBJECT_ID;

    /** Allocate a new fieldless object of {@code className} and return it. */
    public HeapObject allocate(String className) {
        return allocate(className, List.of());
    }

    /**
     * Allocate a new object of {@code className}, default-initializing the given
     * fields (Phase 2B), and return it.
     */
    public HeapObject allocate(String className, List<FieldDefinition> fieldDefinitions) {
        int id = nextObjectId++;
        HeapObject object = new HeapObject(id, className, fieldDefinitions);
        entities.put(id, object);
        return object;
    }

    /**
     * Allocate a new single-dimensional array of {@code length} elements, each set
     * to the Java default for {@code elementType} (Phase 2E).
     */
    public ArrayHeapObject allocateArray(ValueType elementType, String elementTypeName, int length) {
        int id = nextObjectId++;
        Value[] elements = new Value[length];
        for (int i = 0; i < length; i++) {
            elements[i] = Value.defaultFor(elementType, elementTypeName);
        }
        ArrayHeapObject array = new ArrayHeapObject(id, elementType, elementTypeName, elements);
        entities.put(id, array);
        return array;
    }

    /**
     * Look up a heap entity by id.
     *
     * @throws RuntimeModelException if no entity with that id exists
     */
    public HeapEntity get(int objectId) {
        HeapEntity entity = entities.get(objectId);
        if (entity == null) {
            throw new RuntimeModelException("No heap object with id " + objectId);
        }
        return entity;
    }

    /** All live entities, in allocation order. */
    public Collection<HeapEntity> allObjects() {
        return java.util.Collections.unmodifiableCollection(entities.values());
    }
}
