/*
 * This file is part of Velthoric.
 * Licensed under LGPL 3.0.
 */
package net.xmx.velthoric.builtin.marble;

import com.github.stephengold.joltjni.RVec3;
import com.github.stephengold.joltjni.enumerate.EBodyType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.xmx.velthoric.network.VxByteBuf;
import net.xmx.velthoric.physics.object.client.VxClientObjectManager;
import net.xmx.velthoric.physics.object.client.VxRenderState;
import net.xmx.velthoric.physics.object.client.body.VxClientRigidBody;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.UUID;

public class MarbleClientRigidBody extends VxClientRigidBody {

    private float radius = 0.15f;
    private static final ItemStack MARBLE_ITEM_STACK = new ItemStack(Items.MAGMA_CREAM);

    public MarbleClientRigidBody(UUID id, VxClientObjectManager manager, int dataStoreIndex, EBodyType objectType) {
        super(id, manager, dataStoreIndex, objectType);
    }

    @Override
    public void readSyncData(VxByteBuf buf) {
        this.radius = buf.readFloat();
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, float partialTicks, int packedLight, VxRenderState renderState) {
        poseStack.pushPose();

        RVec3 renderPosition = renderState.transform.getTranslation();
        poseStack.translate(renderPosition.x(), renderPosition.y(), renderPosition.z());
        poseStack.mulPose(Minecraft.getInstance().gameRenderer.getMainCamera().rotation());

        BakedModel itemModel = Minecraft.getInstance().getItemRenderer().getItemModelShaper().getItemModel(MARBLE_ITEM_STACK);
        TextureAtlasSprite sprite = itemModel.getParticleIcon();

        VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS));
        Matrix4f matrix4f = poseStack.last().pose();
        Matrix3f matrix3f = poseStack.last().normal();

        addVertex(vertexConsumer, matrix4f, matrix3f, -radius, -radius, sprite.getU0(), sprite.getV1(), packedLight);
        addVertex(vertexConsumer, matrix4f, matrix3f,  radius, -radius, sprite.getU1(), sprite.getV1(), packedLight);
        addVertex(vertexConsumer, matrix4f, matrix3f,  radius,  radius, sprite.getU1(), sprite.getV0(), packedLight);
        addVertex(vertexConsumer, matrix4f, matrix3f, -radius,  radius, sprite.getU0(), sprite.getV0(), packedLight);

        poseStack.popPose();
    }

    private void addVertex(VertexConsumer consumer, Matrix4f pose, Matrix3f normal, float x, float y, float u, float v, int packedLight) {
        consumer.vertex(pose, x, y, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(packedLight)
                .normal(normal, 0.0f, 1.0f, 0.0f)
                .endVertex();
    }
}