package net.xmx.xbullet.physics.object.physicsobject;

import com.github.stephengold.joltjni.Vec3;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.xmx.xbullet.math.PhysicsTransform;
import net.xmx.xbullet.physics.object.riding.RidingProxyEntity;
import net.xmx.xbullet.physics.world.PhysicsWorld;

import javax.annotation.Nullable;
import java.util.UUID;

public interface IPhysicsObject {
    UUID getPhysicsId();
    String getObjectTypeIdentifier();
    EObjectType getPhysicsObjectType();
    Level getLevel();
    boolean isRemoved();
    void markRemoved();
    PhysicsTransform getCurrentTransform();
    int getBodyId();
    void setBodyId(int bodyId);
    void initializePhysics(PhysicsWorld physicsWorld);
    void removeFromPhysics(PhysicsWorld physicsWorld);

    void onRightClickWithTool(ServerPlayer player);

    boolean isPhysicsInitialized();
    void onLeftClick(ServerPlayer player, Vec3 hitPoint, Vec3 hitNormal);
    void onRightClick(ServerPlayer player, Vec3 hitPoint, Vec3 hitNormal);
    void confirmPhysicsInitialized();
    void updateStateFromPhysicsThread(long timestampNanos, @Nullable PhysicsTransform transform, @Nullable Vec3 linearVelocity, @Nullable Vec3 angularVelocity, @Nullable float[] softBodyVertices, boolean isActive);
    Vec3 getLastSyncedLinearVel();
    Vec3 getLastSyncedAngularVel();
    boolean isPhysicsActive();
    long getLastUpdateTimestampNanos();
    @Nullable float[] getLastSyncedVertexData();

    void markDataDirty();
    boolean isDataDirty();
    void clearDataDirty();

    void gameTick(ServerLevel level);
    void physicsTick(PhysicsWorld physicsWorld);

    void fixedGameTick(ServerLevel level);
    void fixedPhysicsTick(PhysicsWorld physicsWorld);

    void writeData(FriendlyByteBuf buf);
    void readData(FriendlyByteBuf buf);

    void writeSyncData(FriendlyByteBuf buf);
    void readSyncData(FriendlyByteBuf buf);

    void setRidingProxy(@Nullable RidingProxyEntity proxy);

    @Nullable
    RidingProxyEntity getRidingProxy();
}