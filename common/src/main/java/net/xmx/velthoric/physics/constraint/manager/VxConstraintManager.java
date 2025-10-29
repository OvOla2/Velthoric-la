/*
 * This file is part of Velthoric.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velthoric.physics.constraint.manager;

import com.github.stephengold.joltjni.*;
import com.github.stephengold.joltjni.enumerate.EConstraintSpace;
import com.github.stephengold.joltjni.std.StringStream;
import net.minecraft.world.level.ChunkPos;
import net.xmx.velthoric.init.VxMainClass;
import net.xmx.velthoric.physics.constraint.VxConstraint;
import net.xmx.velthoric.physics.constraint.persistence.VxConstraintStorage;
import net.xmx.velthoric.physics.body.manager.VxBodyManager;
import net.xmx.velthoric.physics.body.type.VxBody;
import net.xmx.velthoric.physics.world.VxPhysicsWorld;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the lifecycle of physics constraints within a physics world.
 * This includes creation, activation, deactivation, and persistence.
 *
 * @author xI-Mx-Ix
 */
public class VxConstraintManager {

    /**
     * A special, constant UUID used to represent a constraint to the static world itself,
     * rather than to a specific physics body. This is a convention understood by the manager.
     */
    public static final UUID WORLD_BODY_ID = new UUID(0, 0);

    private final VxPhysicsWorld world;
    private final VxBodyManager bodyManager;
    private final VxConstraintStorage constraintStorage;
    private final VxDependencyDataSystem dataSystem;
    private final Map<UUID, VxConstraint> activeConstraints = new ConcurrentHashMap<>();

    public VxConstraintManager(VxBodyManager bodyManager) {
        this.bodyManager = bodyManager;
        this.world = bodyManager.getPhysicsWorld();
        this.constraintStorage = new VxConstraintStorage(world.getLevel(), this);
        this.dataSystem = new VxDependencyDataSystem(this);
    }

    public void initialize() {
        constraintStorage.initialize();
    }

    /**
     * Cleans up resources on shutdown. This method explicitly flushes all pending
     * constraint data to disk synchronously before clearing in-memory state,
     * guaranteeing a reliable save.
     */
    public void shutdown() {
        VxMainClass.LOGGER.debug("Flushing physics constraint persistence for world {}...", world.getDimensionKey().location());
        flushPersistence(true);
        VxMainClass.LOGGER.debug("Physics constraint persistence flushed for world {}.", world.getDimensionKey().location());

        activeConstraints.clear();
        dataSystem.clear();
        constraintStorage.shutdown();
    }

    /**
     * Saves all constraints associated with a given chunk using a single batch operation.
     * A constraint is considered to be in a chunk if its first (or only) real body is in that chunk.
     *
     * @param pos The position of the chunk.
     */
    public void saveConstraintsInChunk(ChunkPos pos) {
        long chunkKey = pos.toLong();
        List<VxConstraint> constraintsToSave = new ArrayList<>();
        for (VxConstraint constraint : activeConstraints.values()) {
            UUID bodyId = !constraint.getBody1Id().equals(WORLD_BODY_ID) ? constraint.getBody1Id() : constraint.getBody2Id();
            if (bodyId.equals(WORLD_BODY_ID)) continue;

            VxBody body = bodyManager.getVxBody(bodyId);
            if (body != null) {
                int index = body.getDataStoreIndex();
                if (index != -1 && bodyManager.getDataStore().chunkKey[index] == chunkKey) {
                    constraintsToSave.add(constraint);
                }
            }
        }
        if (!constraintsToSave.isEmpty()) {
            constraintStorage.storeConstraints(constraintsToSave);
        }
    }

    /**
     * Flushes all pending persistence operations to disk.
     *
     * @param block If true, this method will block the calling thread until all I/O is complete.
     *              This should only be true during a server shutdown.
     */
    public void flushPersistence(boolean block) {
        try {
            CompletableFuture<Void> future = constraintStorage.saveDirtyRegions();
            constraintStorage.getRegionIndex().save(); // The index is small and critical, saving it synchronously is okay.
            if (block) {
                future.join(); // Only block if explicitly required (e.g., on shutdown).
            }
        } catch (Exception e) {
            VxMainClass.LOGGER.error("Failed to flush physics constraint persistence for world {}", world.getLevel().dimension().location(), e);
        }
    }

    /**
     * Handles the unloading of all constraints within a specific chunk.
     * This method first saves all relevant constraints to ensure their state is persisted,
     * then removes them from the active simulation.
     *
     * @param pos The position of the chunk being unloaded.
     */
    public void onChunkUnload(ChunkPos pos) {
        long chunkKey = pos.toLong();
        List<VxConstraint> constraintsToSaveAndRemove = new ArrayList<>();

        for (VxConstraint constraint : activeConstraints.values()) {
            UUID bodyId = !constraint.getBody1Id().equals(WORLD_BODY_ID) ? constraint.getBody1Id() : constraint.getBody2Id();
            if (bodyId.equals(WORLD_BODY_ID)) continue;

            VxBody body = bodyManager.getVxBody(bodyId);
            if (body != null) {
                int index = body.getDataStoreIndex();
                if (index != -1 && bodyManager.getDataStore().chunkKey[index] == chunkKey) {
                    constraintsToSaveAndRemove.add(constraint);
                }
            }
        }

        if (constraintsToSaveAndRemove.isEmpty()) {
            return;
        }

        constraintStorage.storeConstraints(constraintsToSaveAndRemove);

        for (VxConstraint constraint : constraintsToSaveAndRemove) {
            removeConstraint(constraint.getConstraintId(), false);
        }
    }

    /**
     * Creates a new constraint and schedules it for activation once its dependent bodies are loaded.
     *
     * @param settings The Jolt settings for the constraint.
     * @param body1Id  The UUID of the first body. Can be WORLD_BODY_ID.
     * @param body2Id  The UUID of the second body. Can be WORLD_BODY_ID.
     * @return The newly created VxConstraint instance, or null if inputs are invalid.
     */
    @Nullable
    public VxConstraint createConstraint(TwoBodyConstraintSettings settings, UUID body1Id, UUID body2Id) {
        if (settings == null || body1Id == null || body2Id == null) {
            return null;
        }
        UUID constraintId = UUID.randomUUID();
        VxConstraint constraint = new VxConstraint(constraintId, body1Id, body2Id, settings);
        dataSystem.addPendingConstraint(constraint);
        return constraint;
    }

    /**
     * Adds a constraint from persistent storage to be processed for activation.
     * @param constraint The constraint loaded from storage.
     */
    public void addConstraintFromStorage(VxConstraint constraint) {
        dataSystem.addPendingConstraint(constraint);
    }

    /**
     * Activates a constraint by creating its Jolt counterpart and adding it to the physics system.
     * This method handles both two-body constraints and single-body (world-anchored) constraints.
     *
     * @param constraint The constraint to activate.
     */
    protected void activateConstraint(VxConstraint constraint) {
        world.execute(() -> {
            boolean isBody1World = constraint.getBody1Id().equals(WORLD_BODY_ID);
            boolean isBody2World = constraint.getBody2Id().equals(WORLD_BODY_ID);

            try (TwoBodyConstraintSettings loadedSettings = deserializeSettings(constraint)) {
                if (loadedSettings == null) {
                    VxMainClass.LOGGER.error("Failed to deserialize settings for constraint {}", constraint.getConstraintId());
                    return;
                }

                TwoBodyConstraint joltConstraint;
                BodyInterface bodyInterface = world.getPhysicsSystem().getBodyInterface();
                // Jolt uses the invalid body ID to represent the static world body in constraints.
                final int worldBodyJoltId = Jolt.cInvalidBodyId;

                if (isBody1World || isBody2World) {
                    // Handle world-anchored (single body) constraints
                    if (isBody1World && isBody2World) return; // Cannot constrain world to world

                    UUID realBodyUuid = isBody1World ? constraint.getBody2Id() : constraint.getBody1Id();
                    VxBody realBody = bodyManager.getVxBody(realBodyUuid);

                    if (realBody == null || realBody.getBodyId() == 0) {
                        dataSystem.addPendingConstraint(constraint);
                        return;
                    }

                    joltConstraint = isBody1World
                            ? bodyInterface.createConstraint(loadedSettings, worldBodyJoltId, realBody.getBodyId())
                            : bodyInterface.createConstraint(loadedSettings, realBody.getBodyId(), worldBodyJoltId);

                } else {
                    // Handle standard two-body constraints
                    VxBody body1 = bodyManager.getVxBody(constraint.getBody1Id());
                    VxBody body2 = bodyManager.getVxBody(constraint.getBody2Id());

                    if (body1 == null || body2 == null || body1.getBodyId() == 0 || body2.getBodyId() == 0) {
                        dataSystem.addPendingConstraint(constraint);
                        return;
                    }
                    joltConstraint = bodyInterface.createConstraint(loadedSettings, body1.getBodyId(), body2.getBodyId());
                }

                finishActivation(constraint, joltConstraint, loadedSettings);

            } catch (Exception e) {
                VxMainClass.LOGGER.error("Exception during constraint activation for {}", constraint.getConstraintId(), e);
            }
        });
    }

    /**
     * Completes the activation process for a Jolt constraint after it has been created.
     * This includes saving updated settings and adding it to the simulation.
     */
    private void finishActivation(VxConstraint constraint, TwoBodyConstraint joltConstraint, TwoBodyConstraintSettings settings) {
        if (joltConstraint == null) {
            VxMainClass.LOGGER.error("Failed to create Jolt constraint for {}", constraint.getConstraintId());
            return;
        }

        boolean wasCreatedWithWorldSpace = getConstraintSpace(settings) == EConstraintSpace.WorldSpace;
        if (wasCreatedWithWorldSpace) {
            try (ConstraintSettingsRef settingsRef = joltConstraint.getConstraintSettings();
                 ConstraintSettings canonicalSettingsRaw = settingsRef.getPtr()) {
                if (canonicalSettingsRaw instanceof TwoBodyConstraintSettings canonicalSettings) {
                    constraint.updateSettingsData(canonicalSettings);
                }
            }
        }

        world.getPhysicsSystem().addConstraint(joltConstraint);
        constraint.setJoltConstraint(joltConstraint);
        activeConstraints.put(constraint.getConstraintId(), constraint);
    }

    /**
     * Deserializes constraint settings from a VxConstraint's byte data using Jolt's object stream.
     *
     * @param constraint The constraint containing the data and subtype.
     * @return The deserialized TwoBodyConstraintSettings object, or null on failure.
     */
    @Nullable
    private TwoBodyConstraintSettings deserializeSettings(VxConstraint constraint) {
        String settingsString = new String(constraint.getSettingsData(), StandardCharsets.ISO_8859_1);
        try (StringStream stringStream = new StringStream(settingsString);
             TwoBodyConstraintSettingsRef settingsRef = new TwoBodyConstraintSettingsRef()) {
            if (ObjectStreamIn.sReadObject(stringStream, settingsRef)) {
                return settingsRef.getPtr();
            }
            return null;
        }
    }

    /**
     * Gets the EConstraintSpace from a TwoBodyConstraintSettings object.
     * Returns LocalSpace for types that don't have a space property.
     *
     * @param settings The settings object.
     * @return The EConstraintSpace.
     */
    private EConstraintSpace getConstraintSpace(TwoBodyConstraintSettings settings) {
        if (settings instanceof PointConstraintSettings s) return s.getSpace();
        if (settings instanceof HingeConstraintSettings s) return s.getSpace();
        if (settings instanceof ConeConstraintSettings s) return s.getSpace();
        if (settings instanceof DistanceConstraintSettings s) return s.getSpace();
        if (settings instanceof FixedConstraintSettings s) return s.getSpace();
        if (settings instanceof SixDofConstraintSettings s) return s.getSpace();
        if (settings instanceof SliderConstraintSettings s) return s.getSpace();
        if (settings instanceof SwingTwistConstraintSettings s) return s.getSpace();
        return EConstraintSpace.LocalToBodyCom; // Default for types without this property
    }

    public void removeConstraint(UUID constraintId, boolean discardData) {
        VxConstraint constraint = activeConstraints.remove(constraintId);
        if (constraint != null && constraint.getJoltConstraint() != null) {
            world.execute(() -> {
                world.getPhysicsSystem().removeConstraint(constraint.getJoltConstraint());
                constraint.getJoltConstraint().close();
            });
        }
        if (discardData) {
            constraintStorage.removeData(constraintId);
        }
    }

    public void removeConstraintsForBody(UUID bodyId, boolean discardData) {
        activeConstraints.values().stream()
                .filter(c -> c.getBody1Id().equals(bodyId) || c.getBody2Id().equals(bodyId))
                .forEach(c -> removeConstraint(c.getConstraintId(), discardData));
        dataSystem.removeForBody(bodyId);
    }

    public boolean hasActiveConstraint(UUID id) {
        return activeConstraints.containsKey(id);
    }

    public VxConstraintStorage getConstraintStorage() {
        return constraintStorage;
    }

    public VxBodyManager getBodyManager() {
        return bodyManager;
    }

    public VxDependencyDataSystem getDataSystem() {
        return dataSystem;
    }
}