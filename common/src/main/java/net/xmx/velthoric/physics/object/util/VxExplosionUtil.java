/*
 * This file is part of Velthoric.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velthoric.physics.object.util;

import com.github.stephengold.joltjni.*;
import com.github.stephengold.joltjni.operator.Op;
import com.github.stephengold.joltjni.readonly.ConstBroadPhaseQuery;
import net.xmx.velthoric.physics.world.VxPhysicsWorld;

/**
 * A utility class for applying explosion forces to physics objects.
 *
 * @author xI-Mx-Ix
 */
public class VxExplosionUtil {

    /**
     * Applies an explosion impulse to all physics bodies within a given radius.
     * The force applied to each body falls off with distance from the explosion center.
     *
     * @param physicsWorld      The world in which the explosion occurs.
     * @param explosionCenter   The center point of the explosion.
     * @param explosionRadius   The radius of the explosion's effect.
     * @param explosionStrength The magnitude of the impulse at the center of the explosion.
     */
    public void applyExplosion(VxPhysicsWorld physicsWorld, Vec3 explosionCenter, float explosionRadius, float explosionStrength) {
        if (!physicsWorld.isRunning()) return;

        // Execute on the physics thread to ensure thread safety.
        physicsWorld.execute(() -> {
            PhysicsSystem physicsSystem = physicsWorld.getPhysicsSystem();
            if (physicsSystem == null) return;

            // Use the broad phase query to efficiently find all bodies within the explosion's AABB.
            ConstBroadPhaseQuery broadPhaseQuery = physicsSystem.getBroadPhaseQuery();
            AllHitCollideShapeBodyCollector collector = new AllHitCollideShapeBodyCollector();

            // Default filters that collide with everything.
            BroadPhaseLayerFilter broadPhaseFilter = new BroadPhaseLayerFilter();
            ObjectLayerFilter objectFilter = new ObjectLayerFilter();

            try {
                // Perform a sphere collision query to find intersecting bodies.
                broadPhaseQuery.collideSphere(explosionCenter, explosionRadius, collector, broadPhaseFilter, objectFilter);

                int[] hitBodyIds = collector.getHits();

                for (int bodyId : hitBodyIds) {
                    // Activate the body to ensure the impulse is applied.
                    physicsSystem.getBodyInterface().activateBody(bodyId);

                    try (BodyLockWrite lock = new BodyLockWrite(physicsSystem.getBodyLockInterface(), bodyId)) {
                        if (lock.succeededAndIsInBroadPhase()) {
                            Body body = lock.getBody();
                            // Static bodies are not affected by explosions.
                            if (body.isStatic()) {
                                continue;
                            }

                            if (body.isRigidBody()) {
                                RVec3 bodyPosR = body.getCenterOfMassPosition();
                                Vec3 vectorToBody = Op.minus(bodyPosR, explosionCenter.toRVec3()).toVec3();

                                float distanceSq = vectorToBody.lengthSq();
                                // Check if the body is within the radius and not at the exact center.
                                if (distanceSq < explosionRadius * explosionRadius && distanceSq > 1.0E-6f) {
                                    float distance = (float)Math.sqrt(distanceSq);

                                    // Calculate impulse with a squared falloff.
                                    float falloff = 1.0f - (distance / explosionRadius);
                                    float impulseMagnitude = explosionStrength * falloff * falloff;

                                    Vec3 impulseDirection = vectorToBody.normalized();
                                    Vec3 impulse = Op.star(impulseDirection, impulseMagnitude);

                                    body.addImpulse(impulse);
                                }
                            } else if (body.isSoftBody()) {
                                // For soft bodies, apply the impulse to each individual vertex.
                                SoftBodyMotionProperties motionProperties = (SoftBodyMotionProperties) body.getMotionProperties();
                                SoftBodyVertex[] vertices = motionProperties.getVertices();
                                if (vertices.length == 0) continue;

                                for(SoftBodyVertex vertex : vertices) {
                                    Vec3 vertexPos = vertex.getPosition();
                                    Vec3 vectorToVertex = Op.minus(vertexPos, explosionCenter);

                                    float distanceSq = vectorToVertex.lengthSq();
                                    if (distanceSq < explosionRadius * explosionRadius && distanceSq > 1.0E-6f) {
                                        float distance = (float)Math.sqrt(distanceSq);
                                        float falloff = 1.0f - (distance / explosionRadius);

                                        // Distribute the total strength among all vertices.
                                        float impulseMagnitude = (explosionStrength / vertices.length) * falloff * falloff;

                                        Vec3 impulseDirection = vectorToVertex.normalized();
                                        Vec3 impulse = Op.star(impulseDirection, impulseMagnitude);

                                        // Apply the impulse by changing the vertex's velocity.
                                        if(vertex.getInvMass() > 0) {
                                            Vec3 deltaV = Op.star(impulse, vertex.getInvMass());
                                            vertex.setVelocity(Op.plus(vertex.getVelocity(), deltaV));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                // Ensure native resources are freed.
                collector.close();
                broadPhaseFilter.close();
                objectFilter.close();
            }
        });
    }
}