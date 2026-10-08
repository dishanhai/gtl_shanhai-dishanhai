package com.dishanhai.gt_shanhai.client.renderer.machine;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 在中子星约束光束外侧画往复运动的星球。
 * <p>
 * 姿态与 {@code AntichristBeamRenderer} 相同：先移到星心，再绕 Y 转
 * {@link #beamYawDegrees(Direction)}。这个偏航和
 * {@code AntichristRenderProfile.getBeamYawDegrees} 的字节码一致
 * （NORTH 0、EAST -90、SOUTH 180、WEST 90），本地 -Z 才贴着光束。
 */
final class PrimordialNeutronStarSuctionPlanets {

    /** 客户端渲染线程专用，避免每帧分配。 */
    private static final float[] LOCAL = new float[3];
    private static final Quaternionf BEAM_ROTATION = new Quaternionf();
    private static final Vector3f BEAM_POINT = new Vector3f();

    private PrimordialNeutronStarSuctionPlanets() {}

    static void render(Direction facing, float continuousTick, float starRadius, PoseStack poseStack) {
        if (Minecraft.getInstance().screen != null) return;

        VertexBuffer[] buffers = PrimordialOmegaEngineModelBuffers.getBuffers();
        if (buffers == null) return;

        Vec3 star = PrimordialSphereAnchor.center(facing);
        poseStack.pushPose();
        try {
            poseStack.translate(star.x, star.y, star.z);
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(beamYawDegrees(facing)));
            PrimordialOmegaEngineModelBuffers.beginRender();
            try {
                for (int i = 0; i < PrimordialNeutronStarSuction.COUNT; i++) {
                    PrimordialNeutronStarSuction.writeLocalPosition(i, continuousTick, starRadius, LOCAL);
                    drawPlanet(poseStack, buffers, i, continuousTick);
                }
            } finally {
                PrimordialOmegaEngineModelBuffers.endRender();
            }
        } finally {
            poseStack.popPose();
        }
    }

    private static void drawPlanet(PoseStack poseStack, VertexBuffer[] buffers, int index, float tick) {
        float scale = PrimordialNeutronStarSuction.blockRadius(index)
                / PrimordialNeutronStarSuction.MODEL_HALF_EXTENT;
        int model = PrimordialOmegaEngineModelBuffers.ORBIT_START + (index % 3);
        poseStack.pushPose();
        poseStack.translate(LOCAL[0], LOCAL[1], LOCAL[2]);
        poseStack.mulPose(new Quaternionf().fromAxisAngleDeg(0.2f, 1.0f, 0.15f,
                (tick * (0.8f + index * 0.17f) + index * 40.0f) % 360.0f));
        poseStack.scale(scale, scale, scale);
        PrimordialOmegaEngineModelBuffers.draw(buffers[model], poseStack);
        poseStack.popPose();
    }

    /**
     * 把光束本地点换成相对控制器的偏移。延迟批次里的星体只认这一个偏移。
     */
    static Vec3 toAnchorOffset(Direction facing, Vec3 star, float x, float y, float z) {
        BEAM_ROTATION.rotationY((float) Math.toRadians(beamYawDegrees(facing)));
        BEAM_POINT.set(x, y, z).rotate(BEAM_ROTATION);
        return star.add(BEAM_POINT.x, BEAM_POINT.y, BEAM_POINT.z);
    }

    static float beamYawDegrees(Direction facing) {
        return switch (facing) {
            case EAST -> -90.0f;
            case SOUTH -> 180.0f;
            case WEST -> 90.0f;
            default -> 0.0f;
        };
    }
}
