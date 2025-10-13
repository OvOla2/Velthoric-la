/*
 * This file is part of Velthoric.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velthoric.physics.body.registry;

import net.minecraft.resources.ResourceLocation;
import net.xmx.velthoric.physics.body.type.VxBody;
import net.xmx.velthoric.physics.world.VxPhysicsWorld;
import java.util.UUID;

/**
 * Represents the type of a physics body.
 * This class is an immutable container for the type's unique identifier, a factory
 * for creating instances of the body, and metadata such as whether it can be
 * summoned by a command.
 *
 * @param <T> The class of the {@link VxBody} this type represents.
 * @author xI-Mx-Ix
 */
public final class VxBodyType<T extends VxBody> {

    // The unique identifier for this body type (e.g., "my_mod:my_car").
    private final ResourceLocation typeId;
    // The factory function used to create new instances of this body type.
    private final Factory<T> factory;
    // Whether this body type can be summoned via commands.
    private final boolean summonable;

    // Private constructor, use the Builder to create instances.
    private VxBodyType(ResourceLocation typeId, Factory<T> factory, boolean summonable) {
        this.typeId = typeId;
        this.factory = factory;
        this.summonable = summonable;
    }

    /**
     * Creates a new instance of the physics body.
     *
     * @param world The physics world the body will belong to.
     * @param id    The unique UUID for the new instance.
     * @return A new body of type T.
     */
    public T create(VxPhysicsWorld world, UUID id) {
        return this.factory.create(this, world, id);
    }

    /**
     * @return The unique {@link ResourceLocation} of this type.
     */
    public ResourceLocation getTypeId() {
        return typeId;
    }

    /**
     * @return True if this body type can be summoned by commands, false otherwise.
     */
    public boolean isSummonable() {
        return summonable;
    }

    /**
     * A functional interface for a factory that creates physics bodies.
     *
     * @param <T> The type of body to create.
     */
    @FunctionalInterface
    public interface Factory<T extends VxBody> {
        /**
         * Creates a new instance.
         *
         * @param type  The {@link VxBodyType} definition.
         * @param world The physics world.
         * @param id    The instance's UUID.
         * @return A new instance of type T.
         */
        T create(VxBodyType<T> type, VxPhysicsWorld world, UUID id);
    }

    /**
     * A builder for creating {@link VxBodyType} instances with a fluent API.
     *
     * @param <T> The class of the {@link VxBody}.
     */
    public static class Builder<T extends VxBody> {
        private final Factory<T> factory;
        private boolean summonable = true;

        private Builder(Factory<T> factory) {
            this.factory = factory;
        }

        /**
         * Creates a new builder.
         *
         * @param factory The factory function for this type.
         * @param <T>     The class of the {@link VxBody}.
         * @return A new Builder instance.
         */
        public static <T extends VxBody> Builder<T> create(Factory<T> factory) {
            return new Builder<>(factory);
        }

        /**
         * Marks this body type as not summonable by commands.
         *
         * @return This builder, for chaining.
         */
        public Builder<T> noSummon() {
            this.summonable = false;
            return this;
        }

        /**
         * Builds the final, immutable {@link VxBodyType}.
         *
         * @param typeId The unique {@link ResourceLocation} for this type.
         * @return A new {@link VxBodyType} instance.
         */
        public VxBodyType<T> build(ResourceLocation typeId) {
            return new VxBodyType<>(typeId, factory, summonable);
        }
    }
}