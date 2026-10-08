package com.dishanhai.gt_shanhai.client.renderer.machine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.vertex.PoseStack;

import com.gtladd.gtladditions.client.render.machine.antichrist.AntichristBeamRenderer;
import com.gtladd.gtladditions.client.render.machine.antichrist.AntichristRenderProfile;
import com.gtladd.gtladditions.client.render.machine.antichrist.AntichristStarRenderer;
import com.gtladd.gtladditions.client.render.machine.deferred.DeferredRenderContext;
import com.gtladd.gtladditions.client.render.machine.deferred.DeferredRenderEntry;

/**
 * 同一个方块在延迟批次里只能占一条记录。这条记录先画宿主中子星和光束，
 * 再画微缩中子星。微缩星不带光束。
 */
final class PrimordialNeutronStarFrame implements DeferredRenderEntry {

    private final BlockEntity blockEntity;
    private final AntichristRenderProfile host;
    private final AntichristRenderProfile miniature;

    PrimordialNeutronStarFrame(BlockEntity blockEntity, AntichristRenderProfile host,
                               AntichristRenderProfile miniature) {
        this.blockEntity = blockEntity;
        this.host = host;
        this.miniature = miniature;
    }

    @Override
    public BlockEntity getBlockEntity() {
        return blockEntity;
    }

    @Override
    public void render(DeferredRenderContext context) {
        if (blockEntity.isRemoved()) return;
        PoseStack poseStack = context.getPoseStack();
        Vec3 camera = context.getCameraPosition();
        BlockPos pos = blockEntity.getBlockPos();
        poseStack.pushPose();
        try {
            poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
            draw(poseStack);
        } finally {
            poseStack.popPose();
        }
    }

    private void draw(PoseStack poseStack) {
        AntichristStarRenderer.INSTANCE.renderOpaque(host, poseStack);
        AntichristStarRenderer.INSTANCE.renderTransparent(host, poseStack);
        AntichristBeamRenderer.INSTANCE.render(host, poseStack, blockEntity);
        if (miniature == null) return;
        AntichristStarRenderer.INSTANCE.renderOpaque(miniature, poseStack);
        AntichristStarRenderer.INSTANCE.renderTransparent(miniature, poseStack);
    }
}
