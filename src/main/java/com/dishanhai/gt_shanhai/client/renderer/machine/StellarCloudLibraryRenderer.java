package com.dishanhai.gt_shanhai.client.renderer.machine;

import com.dishanhai.gt_shanhai.client.renderer.hostfx.HostFxDraw;
import com.dishanhai.gt_shanhai.common.machine.part.cloud.StellarCloudLibraryMachine;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Matrix4f;

/** 山海：玻璃穹頂下的懸浮雲核；僅成形時顯示，不修改世界方塊或遊戲粒子。 */
public final class StellarCloudLibraryRenderer extends WorkableCasingMachineRenderer {
    public StellarCloudLibraryRenderer(ResourceLocation casing, ResourceLocation overlay) {
        super(casing, overlay);
    }

    @Override public boolean hasTESR(BlockEntity blockEntity) { return true; }
    @Override public boolean isGlobalRenderer(BlockEntity blockEntity) { return true; }
    @Override public int getViewDistance() { return 96; }

    @Override
    public void render(BlockEntity blockEntity, float partialTick, PoseStack poses,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (!(blockEntity instanceof IMachineBlockEntity holder)
                || !(holder.getMetaMachine() instanceof StellarCloudLibraryMachine machine)
                || !machine.isFormed() || blockEntity.getLevel() == null) return;
        var back = machine.getFrontFacing().getOpposite();
        var anchor = StellarCloudCoreGeometry.anchor(back.getStepX(), back.getStepZ());
        double seconds = ((double) blockEntity.getLevel().getGameTime() + partialTick) / 20.0;
        poses.pushPose();
        try {
            poses.translate(anchor.x(), anchor.y(), anchor.z());
            var vertices = buffers.getBuffer(HostFxDraw.beamLayer());
            var matrix = poses.last().pose();
            for (var quad : StellarCloudCoreGeometry.frame(seconds)) {
                vertex(vertices, matrix, quad.a(), quad);
                vertex(vertices, matrix, quad.b(), quad);
                vertex(vertices, matrix, quad.c(), quad);
                vertex(vertices, matrix, quad.d(), quad);
            }
        } finally {
            poses.popPose();
        }
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix,
                               StellarCloudCoreGeometry.Point point, StellarCloudCoreGeometry.Quad color) {
        consumer.vertex(matrix, (float) point.x(), (float) point.y(), (float) point.z())
                .color(color.red(), color.green(), color.blue(), color.alpha()).endVertex();
    }
}
