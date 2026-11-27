package com.dishanhai.gt_shanhai.client.renderer.machine;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.vertex.PoseStack;

import com.gtladd.gtladditions.client.render.machine.antichrist.AntichristBeamRenderer;
import com.gtladd.gtladditions.client.render.machine.antichrist.AntichristRenderProfile;
import com.gtladd.gtladditions.client.render.machine.antichrist.AntichristStarRenderer;
import com.gtladd.gtladditions.api.client.scene.*;

import java.util.List;

/** 宿主与微缩星使用不同的场景部件键，分别参与不透明、透明与光束批次。 */
final class PrimordialNeutronStarFrame {
    private static final ProviderHandle PROVIDER = SceneRendering.register(
            new ResourceLocation("gt_shanhai", "primordial_neutron_star"));
    private static final PartRenderer<Star> OPAQUE = (context, parts) -> draw(context, parts, 0);
    private static final PartRenderer<Star> GLOW = (context, parts) -> draw(context, parts, 1);
    private static final PartRenderer<Star> BEAM = (context, parts) -> draw(context, parts, 2);

    static void enqueue(BlockEntity blockEntity, AntichristRenderProfile host, AntichristRenderProfile miniature) {
        SceneFrame frame = SceneRendering.frame();
        if (frame == null || blockEntity.isRemoved()) return;
        submit(frame, new Star(blockEntity, host), 0);
        if (miniature != null) submit(frame, new Star(blockEntity, miniature), 3);
    }

    private static void submit(SceneFrame frame, Star star, int firstPart) {
        BlockPos pos = star.blockEntity.getBlockPos();
        Vec3 center = star.profile.getStarPos();
        double radius = star.profile.getStarRadius();
        frame.submit(ScenePart.builder(PartKey.block(PROVIDER, pos, firstPart), OPAQUE, star)
                .pass(PartPass.OPAQUE)
                .bounds(PartBounds.sphere(pos.getX() + center.x, pos.getY() + center.y, pos.getZ() + center.z, radius))
                .build());
        frame.submit(ScenePart.builder(PartKey.block(PROVIDER, pos, firstPart + 1), GLOW, star)
                .blend(PartBlend.STRAIGHT)
                .bounds(PartBounds.sphere(pos.getX() + center.x, pos.getY() + center.y, pos.getZ() + center.z, radius * 1.04))
                .build());
        if (star.profile.getShouldRenderBeam()) {
            frame.submit(ScenePart.builder(PartKey.block(PROVIDER, pos, firstPart + 2), BEAM, star)
                    .blend(PartBlend.STRAIGHT)
                    .bounds(PartBounds.box(AntichristBeamRenderer.INSTANCE.bounds(star.profile, pos)))
                    .build());
        }
    }

    private static void draw(PartDrawContext context, List<ScenePart<Star>> parts, int layer) {
        SceneFrame frame = context.getFrame();
        Vec3 camera = new Vec3(frame.getCameraX(), frame.getCameraY(), frame.getCameraZ());
        PoseStack poseStack = context.getPoseStack();
        for (ScenePart<Star> part : parts) {
            Star star = part.getPayload();
            if (star.blockEntity.isRemoved()) {
                context.report(part, PartOutcome.CULLED);
                continue;
            }
            BlockPos pos = star.blockEntity.getBlockPos();
            poseStack.pushPose();
            try {
                poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
                boolean drawn = switch (layer) {
                    case 0 -> AntichristStarRenderer.INSTANCE.drawOpaque(star.profile, poseStack);
                    case 1 -> AntichristStarRenderer.INSTANCE.drawTransparent(star.profile, poseStack);
                    default -> AntichristBeamRenderer.INSTANCE.draw(star.profile, poseStack, pos, camera);
                };
                if (!drawn) context.report(part, PartOutcome.RESOURCE_UNAVAILABLE);
            } finally {
                poseStack.popPose();
            }
        }
    }

    private static final class Star {
        final BlockEntity blockEntity;
        final AntichristRenderProfile profile;

        Star(BlockEntity blockEntity, AntichristRenderProfile profile) {
            this.blockEntity = blockEntity;
            this.profile = profile;
        }
    }
}
