/*
 * This file is part of Velthoric.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velthoric.physics.terrain.generation;

import com.github.stephengold.joltjni.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.xmx.velthoric.init.VxMainClass;
import net.xmx.velthoric.physics.terrain.cache.VxTerrainShapeCache;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates physics shapes for terrain chunks using a StaticCompoundShape.
 * This generator iterates through all blocks in a chunk snapshot and adds their
 * collision bounding boxes as individual box shapes to a single compound shape.
 * This approach is significantly faster than triangle-based mesh generation and uses
 * caching for both final shapes and individual box settings to improve performance.
 *
 * @author xI-Mx-Ix
 */
public final class VxTerrainGenerator {

    private final VxTerrainShapeCache shapeCache;
    private static final int BOX_SETTINGS_CACHE_CAPACITY = 256;

    /**
     * A thread-local cache for BoxShapeSettings. Each worker thread gets its own private cache,
     * eliminating thread contention and the need for synchronization when generating shapes in parallel.
     */
    private static final ThreadLocal<Map<Vec3, BoxShapeSettings>> threadLocalBoxSettingsCache = ThreadLocal.withInitial(() ->
            new LinkedHashMap<>(BOX_SETTINGS_CACHE_CAPACITY, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Vec3, BoxShapeSettings> eldest) {
                    boolean shouldRemove = size() > BOX_SETTINGS_CACHE_CAPACITY;
                    if (shouldRemove && eldest.getValue() != null) {
                        eldest.getValue().close();
                    }
                    return shouldRemove;
                }
            }
    );

    private static final ThreadLocal<Vec3> tempVec3Key = ThreadLocal.withInitial(Vec3::new);

    /**
     * Constructs a new terrain generator with caching capabilities.
     * @param shapeCache An in-memory cache for storing final, generated terrain shapes.
     */
    public VxTerrainGenerator(VxTerrainShapeCache shapeCache) {
        this.shapeCache = shapeCache;
    }

    /**
     * Generates a {@link ShapeRefC} for the given chunk snapshot, utilizing caches for performance.
     * <p>
     * This method first checks an in-memory cache for a pre-existing shape matching the chunk's content.
     * If not found, it creates a {@link StaticCompoundShape} composed of multiple {@link BoxShape}s,
     * where each box represents a block's collision AABB. It uses a thread-local cache for {@link BoxShapeSettings}
     * to avoid re-creating them and to ensure non-blocking parallel execution.
     *
     * @param level The server level, used to get context-aware collision shapes.
     * @param snapshot An immutable snapshot of the chunk section's block data.
     * @return A new reference to the generated compound shape (ShapeRefC), or {@code null} if the chunk is empty or generation fails.
     *         The caller is responsible for closing the returned shape reference.
     */
    public ShapeRefC generateShape(ServerLevel level, VxChunkSnapshot snapshot) {
        int contentHash = snapshot.hashCode();
        ShapeRefC cachedShape = shapeCache.get(contentHash);
        if (cachedShape != null) {
            return cachedShape;
        }

        if (snapshot.shapes().isEmpty()) {
            return null;
        }

        try (StaticCompoundShapeSettings compoundSettings = new StaticCompoundShapeSettings()) {
            boolean hasShapes = false;
            Vec3 halfExtentsKey = tempVec3Key.get();
            Map<Vec3, BoxShapeSettings> boxSettingsCache = threadLocalBoxSettingsCache.get();

            for (VxChunkSnapshot.ShapeInfo info : snapshot.shapes()) {
                BlockPos worldPos = snapshot.pos().getOrigin().offset(info.localPos());
                VoxelShape voxelShape = info.state().getCollisionShape(level, worldPos);

                for (AABB aabb : voxelShape.toAabbs()) {
                    float hx = (float) (aabb.getXsize() / 2.0);
                    float hy = (float) (aabb.getYsize() / 2.0);
                    float hz = (float) (aabb.getZsize() / 2.0);

                    if (hx <= 0.001f || hy <= 0.001f || hz <= 0.001f) {
                        continue;
                    }

                    halfExtentsKey.set(hx, hy, hz);
                    BoxShapeSettings boxSettings = boxSettingsCache.get(halfExtentsKey);
                    if (boxSettings == null) {
                        Vec3 newKey = new Vec3(hx, hy, hz);
                        boxSettings = new BoxShapeSettings(newKey, 0.0f);
                        boxSettingsCache.put(newKey, boxSettings);
                    }

                    float cx = (float) (info.localPos().getX() + aabb.minX + hx);
                    float cy = (float) (info.localPos().getY() + aabb.minY + hy);
                    float cz = (float) (info.localPos().getZ() + aabb.minZ + hz);

                    compoundSettings.addShape(cx, cy, cz, boxSettings);
                    hasShapes = true;
                }
            }

            if (!hasShapes) {
                return null;
            }

            try (ShapeResult result = compoundSettings.create()) {
                if (result.isValid()) {
                    ShapeRefC newShape = result.get();
                    shapeCache.put(contentHash, newShape);
                    return newShape.getPtr().toRefC();
                } else {
                    VxMainClass.LOGGER.error("Failed to create StaticCompoundShape for {}: {}", snapshot.pos(), result.getError());
                    return null;
                }
            }
        }
    }
}