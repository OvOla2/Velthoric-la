/*
 * This file is part of Velthoric.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velthoric.physics.body.manager.chunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.ChunkPos;
import net.xmx.velthoric.physics.body.manager.VxBodyDataStore;
import net.xmx.velthoric.physics.body.manager.VxBodyManager;
import net.xmx.velthoric.physics.body.manager.VxNetworkDispatcher;
import net.xmx.velthoric.physics.body.type.VxBody;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages the spatial partitioning of physics bodies into chunks.
 * This class is responsible for tracking which objects reside in which chunk,
 * facilitating efficient proximity queries and handling chunk-based operations.
 *
 * @author xI-Mx-Ix
 */
public class VxChunkManager {

    private final VxBodyManager bodyManager;
    private final VxBodyDataStore dataStore;
    private final VxNetworkDispatcher networkDispatcher;

    /**
     * A spatial map that groups bodies by their chunk position for efficient proximity queries.
     * Uses a Long2ObjectOpenHashMap to reduce boxing overhead and improve cache performance.
     * It is not inherently thread safe, so external synchronization is required when accessed concurrently.
     */
    private final Long2ObjectOpenHashMap<List<VxBody>> bodiesByChunk = new Long2ObjectOpenHashMap<>();

    /**
     * Constructs a new VxChunkMap.
     *
     * @param bodyManager The parent body manager.
     */
    public VxChunkManager(VxBodyManager bodyManager) {
        this.bodyManager = bodyManager;
        this.dataStore = bodyManager.getDataStore();
        this.networkDispatcher = bodyManager.getNetworkDispatcher();
    }

    /**
     * Starts tracking a body, adding it to the appropriate chunk list based on its position.
     *
     * @param body The body to start tracking.
     */
    public void startTracking(VxBody body) {
        int index = body.getDataStoreIndex();
        if (index == -1) return;

        long key = bodyManager.getBodyChunkPos(index).toLong();
        dataStore.chunkKey[index] = key;

        bodiesByChunk.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(body);
    }


    /**
     * Stops tracking a body, removing it from its last known chunk list.
     *
     * @param body The body to stop tracking.
     */
    public void stopTracking(VxBody body) {
        int index = body.getDataStoreIndex();
        if (index == -1) return;

        long key = dataStore.chunkKey[index];
        if (key != Long.MAX_VALUE) {
            List<VxBody> list = bodiesByChunk.get(key);
            if (list != null) {
                list.remove(body);
                if (list.isEmpty()) {
                    bodiesByChunk.remove(key, list); // Use conditional remove
                }
            }
        }
    }


    /**
     * Updates the chunk tracking information for a body when it moves across a chunk border.
     * This method ensures the body is correctly listed in the new chunk and removed from the old one,
     * and notifies the network dispatcher of the change.
     *
     * @param body    The body that moved.
     * @param fromKey The long-encoded key of the chunk it moved from.
     * @param toKey   The long-encoded key of the chunk it moved to.
     */
    public void updateBodyTracking(VxBody body, long fromKey, long toKey) {
        int index = body.getDataStoreIndex();
        if (index != -1) {
            dataStore.chunkKey[index] = toKey;
        }

        // Remove from the old chunk's list.
        if (fromKey != Long.MAX_VALUE) {
            List<VxBody> fromList = bodiesByChunk.get(fromKey);
            if (fromList != null) {
                fromList.remove(body);
                if (fromList.isEmpty()) {
                    bodiesByChunk.remove(fromKey, fromList); // Use conditional remove
                }
            }
        }
        // Add to the new chunk's list.
        bodiesByChunk.computeIfAbsent(toKey, k -> new CopyOnWriteArrayList<>()).add(body);


        // Notify the network dispatcher about the movement for client-side tracking updates.
        networkDispatcher.onBodyMoved(body, new ChunkPos(fromKey), new ChunkPos(toKey));
    }


    /**
     * Retrieves a list of all bodies within a specific chunk.
     *
     * @param pos The position of the chunk.
     * @return A list of objects in that chunk, which may be empty.
     */
    public List<VxBody> getBodiesInChunk(ChunkPos pos) {
        return bodiesByChunk.getOrDefault(pos.toLong(), Collections.emptyList());
    }

    /**
     * Atomically removes all bodies associated with a chunk and returns them.
     * This is a highly efficient bulk operation for chunk unloading.
     *
     * @param pos The position of the chunk to clear.
     * @return The list of bodies that were in the chunk, or an empty list if none.
     */
    public List<VxBody> removeAllInChunk(ChunkPos pos) {
        List<VxBody> removed = bodiesByChunk.remove(pos.toLong());
        return removed != null ? removed : Collections.emptyList();
    }
}