/*
 * This file is part of Velthoric.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velthoric.physics.terrain.storage;

import com.github.stephengold.joltjni.ShapeRefC;
import net.xmx.velthoric.physics.body.AbstractDataStore;
import net.xmx.velthoric.physics.terrain.VxSectionPos;
import net.xmx.velthoric.physics.terrain.management.VxTerrainManager;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A data-oriented and thread-safe store for the state of all terrain chunk physics bodies.
 * <p>
 * This class uses a "Structure of Arrays" (SoA) layout and modern concurrency utilities
 * like Atomic arrays and fine-grained locking to avoid becoming a bottleneck under high thread contention.
 * Access to individual chunk data is lock-free, while structural changes (adding/removing chunks)
 * use a dedicated lock.
 *
 * @author xI-Mx-Ix
 */
public final class VxChunkDataStore extends AbstractDataStore {
    private static final int INITIAL_CAPACITY = 4096;
    public static final int UNUSED_BODY_ID = 0;

    // --- Concurrency and Allocation ---
    private final Object allocationLock = new Object(); // Dedicated lock for structural changes (add/remove/resize)
    private final ConcurrentHashMap<VxSectionPos, Integer> posToIndex = new ConcurrentHashMap<>();
    private volatile AtomicReferenceArray<VxSectionPos> indexToPos;
    private final Deque<Integer> freeIndices = new ArrayDeque<>();
    private int count = 0;
    private volatile int capacity = 0;

    // --- Chunk State Data (SoA) using Atomic Arrays for lock-free access ---
    private AtomicIntegerArray states;
    private AtomicIntegerArray bodyIds;
    private AtomicReferenceArray<ShapeRefC> shapeRefs;
    private AtomicIntegerArray isPlaceholder; // Using 1 for true, 0 for false
    private AtomicIntegerArray rebuildVersions;
    private AtomicIntegerArray referenceCounts;

    public VxChunkDataStore() {
        allocate(INITIAL_CAPACITY);
    }

    private void allocate(int newCapacity) {
        // This method is only called from within the allocationLock, so it's safe.
        states = growAtomic(states, newCapacity);
        bodyIds = growAtomic(bodyIds, newCapacity);
        shapeRefs = growAtomic(shapeRefs, newCapacity);
        isPlaceholder = growAtomic(isPlaceholder, newCapacity, 1); // Default to placeholder=true
        rebuildVersions = growAtomic(rebuildVersions, newCapacity);
        referenceCounts = growAtomic(referenceCounts, newCapacity);

        // A volatile write ensures visibility of the new arrays to other threads before capacity is updated.
        indexToPos = growAtomic(indexToPos, newCapacity);

        this.capacity = newCapacity;
    }

    /**
     * Reserves a new index for a terrain chunk or retrieves an existing one.
     * This operation is highly concurrent, using a lock only for the slow path of new allocations.
     *
     * @param pos The world-space position of the chunk section.
     * @return The data store index for the chunk.
     */
    public int addChunk(VxSectionPos pos) {
        // Fast path: Check if it already exists, completely lock-free.
        Integer existingIndex = posToIndex.get(pos);
        if (existingIndex != null) {
            return existingIndex;
        }

        // Slow path: A new index must be allocated, requiring a lock.
        synchronized (allocationLock) {
            // Double-check in case another thread added it while we were waiting for the lock.
            existingIndex = posToIndex.get(pos);
            if (existingIndex != null) {
                return existingIndex;
            }

            if (count == capacity) {
                allocate(capacity + (capacity >> 1)); // Grow by 50%
            }
            int index = freeIndices.isEmpty() ? count++ : freeIndices.pop();

            posToIndex.put(pos, index);
            indexToPos.set(index, pos);

            resetIndex(index);
            return index;
        }
    }

    /**
     * Releases the index for a given chunk position, making it available for reuse.
     * This also handles the cleanup of associated resources like {@link ShapeRefC}.
     *
     * @param pos The position of the chunk to remove.
     * @return The released index, or null if the chunk was not found.
     */
    @Nullable
    public Integer removeChunk(VxSectionPos pos) {
        // Structural change, requires the lock.
        synchronized (allocationLock) {
            Integer index = posToIndex.remove(pos);
            if (index != null) {
                ShapeRefC shape = shapeRefs.getAndSet(index, null);
                if (shape != null) {
                    shape.close();
                }
                freeIndices.push(index);
                indexToPos.set(index, null);
                return index;
            }
        }
        return null;
    }

    /**
     * Clears all data and resets the store to its initial state.
     */
    public void clear() {
        synchronized (allocationLock) {
            for (int i = 0; i < count; i++) {
                ShapeRefC shape = shapeRefs.get(i);
                if (shape != null) {
                    shape.close();
                }
            }
            posToIndex.clear();
            freeIndices.clear();
            count = 0;
            allocate(INITIAL_CAPACITY);
        }
    }

    // --- Lock-Free Accessors and Atomic Operations ---

    public int getState(int index) {
        return states.get(index);
    }

    public void setState(int index, int state) {
        this.states.set(index, state);
    }

    /**
     * Atomically attempts to reset the state of a chunk from LOADING_SCHEDULED back to UNLOADED.
     * This is used by worker threads to safely abort a task if the target chunk is no longer loaded.
     *
     * @param index The index of the chunk.
     * @return True if the state was successfully changed, false otherwise.
     */
    public boolean tryResetStateFromScheduled(int index) {
        return this.states.compareAndSet(index, VxTerrainManager.STATE_LOADING_SCHEDULED, VxTerrainManager.STATE_UNLOADED);
    }

    /**
     * Atomically attempts to advance the state of a chunk from LOADING_SCHEDULED to GENERATING_SHAPE.
     * This is used by worker threads to claim a task after the chunk has been successfully loaded.
     *
     * @param index The index of the chunk.
     * @return True if the state was successfully changed, false otherwise.
     */
    public boolean tryAdvanceStateToGenerating(int index) {
        return this.states.compareAndSet(index, VxTerrainManager.STATE_LOADING_SCHEDULED, VxTerrainManager.STATE_GENERATING_SHAPE);
    }

    public int getBodyId(int index) {
        return bodyIds.get(index);
    }

    public void setBodyId(int index, int bodyId) {
        this.bodyIds.set(index, bodyId);
    }

    public boolean isPlaceholder(int index) {
        return isPlaceholder.get(index) == 1;
    }

    public void setPlaceholder(int index, boolean placeholder) {
        this.isPlaceholder.set(index, placeholder ? 1 : 0);
    }

    public int incrementAndGetRefCount(int index) {
        return this.referenceCounts.incrementAndGet(index);
    }

    public int decrementAndGetRefCount(int index) {
        return this.referenceCounts.decrementAndGet(index);
    }

    public boolean isVersionStale(int index, int version) {
        return version < rebuildVersions.get(index);
    }

    /**
     * Atomically attempts to mark a chunk for shape generation using a lock-free
     * Compare-And-Set (CAS) loop. This is critical for performance.
     *
     * @param index The index of the chunk to schedule.
     * @return The new, unique version number for the generation task if successful, or -1 if the
     *         chunk cannot be scheduled (e.g., it is already being processed).
     */
    public int scheduleForGeneration(int index) {
        while (true) {
            int currentState = states.get(index);
            if (currentState == VxTerrainManager.STATE_REMOVING ||
                    currentState == VxTerrainManager.STATE_LOADING_SCHEDULED ||
                    currentState == VxTerrainManager.STATE_GENERATING_SHAPE) {
                return -1; // Indicate that the operation failed because the chunk is busy.
            }
            // Atomically try to switch state from its current value to LOADING_SCHEDULED.
            if (states.compareAndSet(index, currentState, VxTerrainManager.STATE_LOADING_SCHEDULED)) {
                // If successful, we have exclusive rights. Increment version and return.
                return rebuildVersions.incrementAndGet(index);
            }
            // If CAS failed, another thread changed the state. Loop and retry.
        }
    }

    /**
     * Safely sets the shape, ensuring the previous shape is closed.
     *
     * @param index The data store index.
     * @param shape The new shape reference.
     */
    public void setShape(int index, ShapeRefC shape) {
        ShapeRefC oldShape = shapeRefs.getAndSet(index, shape);
        if (oldShape != null && oldShape != shape) {
            oldShape.close();
        }
    }

    @Nullable
    public Integer getIndexForPos(VxSectionPos pos) {
        return posToIndex.get(pos);
    }

    @Nullable
    public VxSectionPos getPosForIndex(int index) {
        if (index < 0 || index >= capacity) {
            return null;
        }
        return indexToPos.get(index);
    }

    public Set<VxSectionPos> getManagedPositions() {
        return new HashSet<>(posToIndex.keySet());
    }

    public Collection<Integer> getActiveIndices() {
        return new ArrayList<>(posToIndex.values());
    }

    /**
     * Returns a copy of all active body IDs. This is safe to iterate over.
     *
     * @return A new array containing all body IDs.
     */
    public int[] getBodyIds() {
        synchronized (allocationLock) { // Lock needed to get a consistent view of `count` and the `bodyIds` array
            int currentCount = this.count;
            int[] bodyIdsCopy = new int[currentCount];
            for (int i = 0; i < currentCount; i++) {
                bodyIdsCopy[i] = this.bodyIds.get(i);
            }
            return bodyIdsCopy;
        }
    }

    public int getChunkCount() {
        return posToIndex.size();
    }

    public int getCapacity() {
        return this.capacity;
    }

    private void resetIndex(int index) {
        states.set(index, VxTerrainManager.STATE_UNLOADED);
        bodyIds.set(index, UNUSED_BODY_ID);
        shapeRefs.set(index, null);
        isPlaceholder.set(index, 1); // true
        rebuildVersions.set(index, 0);
        referenceCounts.set(index, 0);
    }

    // --- Helper methods for growing atomic arrays ---

    private static AtomicIntegerArray growAtomic(AtomicIntegerArray oldArray, int newCapacity) {
        return growAtomic(oldArray, newCapacity, 0);
    }

    private static AtomicIntegerArray growAtomic(AtomicIntegerArray oldArray, int newCapacity, int defaultValue) {
        AtomicIntegerArray newArray = new AtomicIntegerArray(newCapacity);
        if (oldArray != null) {
            int copyLength = Math.min(oldArray.length(), newCapacity);
            for (int i = 0; i < copyLength; i++) {
                newArray.set(i, oldArray.get(i));
            }
        }
        for (int i = (oldArray != null ? oldArray.length() : 0); i < newCapacity; i++) {
            newArray.set(i, defaultValue);
        }
        return newArray;
    }

    private static <T> AtomicReferenceArray<T> growAtomic(AtomicReferenceArray<T> oldArray, int newCapacity) {
        AtomicReferenceArray<T> newArray = new AtomicReferenceArray<>(newCapacity);
        if (oldArray != null) {
            int copyLength = Math.min(oldArray.length(), newCapacity);
            for (int i = 0; i < copyLength; i++) {
                newArray.set(i, oldArray.get(i));
            }
        }
        return newArray;
    }
}