package com.dishanhai.gt_shanhai.client.renderer.machine;

import com.dishanhai.gt_shanhai.common.machine.misc.EternalGregTechWorkshopMachine;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.client.model.data.ModelData;
import org.gtlcore.gtlcore.client.ClientUtil;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 永恒格雷工坊主机渲染器。
 * 移植 GTNL 原版 extra 结构环思路：多层旋转环，默认启用 DNA 式螺旋偏移。
 */
public class EternalGregTechWorkshopRenderer extends WorkableCasingMachineRenderer {

    private static final int DEFAULT_RENDER_COUNT = 1;
    private static final int MAX_RENDER_COUNT = 8;
    private static final float RENDER_CENTER_BACK_DISTANCE = 26.0f;
    private static final float RING_INITIAL_OFFSET = 11.0f;
    private static final float RING_STEP = 22.0f;
    private static final float SPIRAL_OFFSET_PER_LAYER = 5.0f;
    private static final float DECORATION_AXIS_LENGTH = 15.5f;
    private static final float DECORATION_RING_RADIUS = 31.0f;
    private static final int PLANET_TRAIL_SEGMENTS = 72;
    private static final int PLANET_TRAIL_ACTIVE_SEGMENTS = 34;
    private static final float MAX_RENDER_RADIUS = 70.0f;
    private static final List<ResourceLocation> LIGHT_HUNTER_SPHERES = List.of(
            new ResourceLocation("gtladditions", "obj/planets/the_nether"),
            new ResourceLocation("gtladditions", "obj/planets/overworld"),
            new ResourceLocation("gtladditions", "obj/planets/the_end"),
            new ResourceLocation("gtladditions", "obj/planets/ceres"),
            new ResourceLocation("gtladditions", "obj/planets/enceladus"),
            new ResourceLocation("gtladditions", "obj/planets/ganymede"),
            new ResourceLocation("gtladditions", "obj/planets/io"),
            new ResourceLocation("gtladditions", "obj/planets/mars"),
            new ResourceLocation("gtladditions", "obj/planets/mercury"),
            new ResourceLocation("gtladditions", "obj/planets/moon"),
            new ResourceLocation("gtladditions", "obj/planets/pluto"),
            new ResourceLocation("gtladditions", "obj/planets/titan"),
            new ResourceLocation("gtladditions", "obj/planets/venus")
    );

    public EternalGregTechWorkshopRenderer(ResourceLocation baseCasing, ResourceLocation workableModel) {
        super(baseCasing, workableModel);
    }

    @Override
    public void render(BlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        MetaMachine meta = getMetaMachine(blockEntity);
        if (!(meta instanceof EternalGregTechWorkshopMachine workshop) || !workshop.isRenderActive()) {
            return;
        }

        VertexBuffer ringBuffer = EternalGregTechWorkshopRingBuffer.getRingBuffer();
        if (ringBuffer == null) {
            return;
        }

        float tick = workshop.getOffsetTimer() + partialTick;
        int renderCount = getRenderCount(workshop);

        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShader(GameRenderer::getRendertypeSolidShader);
        RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);
        Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();

        poseStack.pushPose();
        translateToOriginalRenderCenter(poseStack, workshop);
        rotateLocalXAxisToWorkshopBack(poseStack, workshop);
        renderSpiralRings(ringBuffer, renderCount, tick, poseStack);
        renderDecorations(buffer, renderCount, tick, poseStack);
        renderLightHunterOrbitTrails(buffer, tick, poseStack);
        renderLightHunterSpheres(buffer, tick, poseStack);
        poseStack.popPose();

        VertexBuffer.unbind();
        RenderSystem.disableBlend();
    }

    @Override
    public void onAdditionalModel(Consumer<ResourceLocation> registry) {
        super.onAdditionalModel(registry);
        LIGHT_HUNTER_SPHERES.forEach(registry);
    }

    @Override
    public boolean hasTESR(BlockEntity blockEntity) {
        return true;
    }

    @Override
    public boolean isGlobalRenderer(BlockEntity blockEntity) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }

    private static MetaMachine getMetaMachine(BlockEntity blockEntity) {
        if (blockEntity instanceof IMachineBlockEntity machineBlockEntity) {
            return machineBlockEntity.getMetaMachine();
        }
        return null;
    }

    private static int getRenderCount(EternalGregTechWorkshopMachine workshop) {
        return Math.max(DEFAULT_RENDER_COUNT, Math.min(MAX_RENDER_COUNT, workshop.getMachineTier()));
    }

    private static void translateToOriginalRenderCenter(PoseStack poseStack, EternalGregTechWorkshopMachine workshop) {
        var back = workshop.getFrontFacing().getOpposite();
        poseStack.translate(
                0.5 + back.getStepX() * RENDER_CENTER_BACK_DISTANCE,
                0.5 + back.getStepY() * RENDER_CENTER_BACK_DISTANCE,
                0.5 + back.getStepZ() * RENDER_CENTER_BACK_DISTANCE);
    }

    private static void rotateLocalXAxisToWorkshopBack(PoseStack poseStack, EternalGregTechWorkshopMachine workshop) {
        switch (workshop.getFrontFacing()) {
            case NORTH -> poseStack.mulPose(new Quaternionf().fromAxisAngleDeg(0, 1, 0, 270.0f));
            case SOUTH -> poseStack.mulPose(new Quaternionf().fromAxisAngleDeg(0, 1, 0, 90.0f));
            case EAST -> poseStack.mulPose(new Quaternionf().fromAxisAngleDeg(0, 1, 0, 180.0f));
            default -> {
            }
        }
    }

    private static void renderSpiralRings(VertexBuffer ringBuffer, int renderCount, float tick, PoseStack poseStack) {
        float baseAngle = tick / 6.0f * 7.0f;
        int count = Math.max(1, renderCount);

        for (int i = 0; i < count; i++) {
            float distance = RING_INITIAL_OFFSET + i * RING_STEP;
            float spiral = i * SPIRAL_OFFSET_PER_LAYER;
            renderRingAt(ringBuffer, distance, baseAngle + spiral, poseStack);
            renderRingAt(ringBuffer, -distance, -baseAngle - spiral, poseStack);
        }
    }

    private static void renderRingAt(VertexBuffer ringBuffer, float localX, float angle, PoseStack poseStack) {
        poseStack.pushPose();
        poseStack.translate(localX, 0.0, 0.0);
        poseStack.mulPose(new Quaternionf().fromAxisAngleDeg(1, 0, 0, angle));
        ringBuffer.bind();
        ringBuffer.drawWithShader(poseStack.last().pose(), RenderSystem.getProjectionMatrix(),
                Objects.requireNonNull(RenderSystem.getShader()));
        poseStack.popPose();
    }

    private static void renderDecorations(MultiBufferSource buffer, int renderCount, float tick, PoseStack poseStack) {
        VertexConsumer consumer = buffer.getBuffer(RenderType.lightning());
        Matrix4f matrix = poseStack.last().pose();
        int count = Math.max(1, renderCount);
        renderAxisPulse(consumer, matrix, tick, count);
        renderNodeGlints(consumer, matrix, tick, count);
        renderDoubleHelix(consumer, matrix, tick);
        renderMiniCore(consumer, matrix, tick);
    }

    private static void renderAxisPulse(VertexConsumer consumer, Matrix4f matrix, float tick, int renderCount) {
        float maxX = RING_INITIAL_OFFSET + (renderCount - 1) * RING_STEP + 4.0f;
        maxX = Math.max(maxX, DECORATION_AXIS_LENGTH);
        float pulse = 0.45f + 0.22f * (float) Math.sin(tick * 0.16f);
        float band = 0.055f + pulse * 0.025f;

        addQuad(consumer, matrix,
                -maxX, -band, 0.0f,
                maxX, -band, 0.0f,
                maxX, band, 0.0f,
                -maxX, band, 0.0f,
                1.0f, 0.86f, 0.38f, 0.33f + pulse * 0.18f);
        addQuad(consumer, matrix,
                -maxX, 0.0f, -band,
                maxX, 0.0f, -band,
                maxX, 0.0f, band,
                -maxX, 0.0f, band,
                0.55f, 0.92f, 1.0f, 0.22f + pulse * 0.14f);

        float markerX = ((tick * 0.22f) % (maxX * 2.0f)) - maxX;
        addCrossBillboard(consumer, matrix, markerX, 0.0f, 0.0f,
                0.18f, 1.0f, 0.96f, 0.72f, 0.62f);
    }

    private static void renderNodeGlints(VertexConsumer consumer, Matrix4f matrix, float tick, int renderCount) {
        int nodes = 24;
        for (int layer = 0; layer < renderCount; layer++) {
            float distance = RING_INITIAL_OFFSET + layer * RING_STEP;
            for (int side = -1; side <= 1; side += 2) {
                float localX = side * distance;
                for (int i = 0; i < nodes; i++) {
                    float phase = (tick * 0.055f + i / (float) nodes + layer * 0.17f + (side > 0 ? 0.0f : 0.5f)) % 1.0f;
                    float wave = Math.max(0.0f, 1.0f - Math.abs(phase - 0.5f) * 5.0f);
                    if (wave <= 0.0f && i % 4 != 0) {
                        continue;
                    }

                    double angle = Math.PI * 2.0 * i / nodes + tick * 0.012f * side;
                    float y = (float) Math.cos(angle) * DECORATION_RING_RADIUS;
                    float z = (float) Math.sin(angle) * DECORATION_RING_RADIUS;
                    float size = 0.18f + wave * 0.22f;
                    float alpha = 0.10f + wave * 0.58f;
                    addCrossBillboard(consumer, matrix, localX, y, z,
                            size, 0.98f, 0.76f + wave * 0.20f, 0.34f, alpha);
                }
            }
        }
    }

    private static void renderDoubleHelix(VertexConsumer consumer, Matrix4f matrix, float tick) {
        int segments = 96;
        float minX = -DECORATION_AXIS_LENGTH;
        float maxX = DECORATION_AXIS_LENGTH;
        for (int strand = 0; strand < 2; strand++) {
            float phase = strand == 0 ? 0.0f : (float) Math.PI;
            float red = strand == 0 ? 1.0f : 0.55f;
            float green = strand == 0 ? 0.78f : 0.92f;
            float blue = strand == 0 ? 0.30f : 1.0f;
            for (int i = 0; i < segments; i++) {
                float t0 = i / (float) segments;
                float t1 = (i + 1) / (float) segments;
                float x0 = minX + (maxX - minX) * t0;
                float x1 = minX + (maxX - minX) * t1;
                float a0 = t0 * 720.0f + tick * 1.8f + phase * 57.29578f;
                float a1 = t1 * 720.0f + tick * 1.8f + phase * 57.29578f;
                addHelixSegment(consumer, matrix, x0, a0, x1, a1,
                        DECORATION_RING_RADIUS * 0.18f, 0.09f,
                        red, green, blue, 0.26f);
            }
        }
    }

    private static void renderMiniCore(VertexConsumer consumer, Matrix4f matrix, float tick) {
        float pulse = 0.45f + 0.20f * (float) Math.sin(tick * 0.12f);
        float size = 0.46f + pulse * 0.16f;
        addQuad(consumer, matrix,
                0.0f, -size, -size,
                0.0f, size, -size,
                0.0f, size, size,
                0.0f, -size, size,
                1.0f, 0.94f, 0.58f, 0.40f + pulse * 0.20f);
        addQuad(consumer, matrix,
                -size, 0.0f, -size,
                size, 0.0f, -size,
                size, 0.0f, size,
                -size, 0.0f, size,
                0.52f, 0.88f, 1.0f, 0.22f + pulse * 0.16f);
        addQuad(consumer, matrix,
                -size, -size, 0.0f,
                size, -size, 0.0f,
                size, size, 0.0f,
                -size, size, 0.0f,
                1.0f, 0.62f, 0.22f, 0.20f + pulse * 0.14f);
    }

    private static void renderLightHunterOrbitTrails(MultiBufferSource buffer, float tick, PoseStack poseStack) {
        VertexConsumer consumer = buffer.getBuffer(RenderType.lightning());
        Matrix4f matrix = poseStack.last().pose();
        for (int i = 0; i < LIGHT_HUNTER_SPHERES.size(); i++) {
            float radius = getPlanetOrbitRadius(i);
            float speed = getPlanetOrbitSpeed(i);
            float currentAngle = tick * speed + i * 27.0f;
            float width = 0.030f + (i % 4) * 0.010f;
            float red = 0.34f + (i % 3) * 0.18f;
            float green = 0.62f + (i % 5) * 0.055f;
            float blue = 1.0f;
            int activeSegments = Math.min(PLANET_TRAIL_SEGMENTS - 2,
                    PLANET_TRAIL_ACTIVE_SEGMENTS + (i % 5) * 3);
            float startOffset = 360.0f * i / LIGHT_HUNTER_SPHERES.size();

            for (int segment = 0; segment < activeSegments; segment++) {
                float fade = 1.0f - segment / (float) activeSegments;
                float alpha = 0.025f + fade * (0.145f + (i % 3) * 0.018f);
                float a0 = currentAngle - startOffset - segment * (360.0f / PLANET_TRAIL_SEGMENTS);
                float a1 = currentAngle - startOffset - (segment + 1) * (360.0f / PLANET_TRAIL_SEGMENTS);
                addOrbitTrailSegment(consumer, matrix, radius, width, a0, a1,
                        red, green, blue, alpha);
            }
        }
    }

    private static void renderLightHunterSpheres(MultiBufferSource buffer, float tick, PoseStack poseStack) {
        for (int i = 0; i < LIGHT_HUNTER_SPHERES.size(); i++) {
            float radius = getPlanetOrbitRadius(i);
            float speed = getPlanetOrbitSpeed(i);
            float angle = tick * speed + i * 27.0f;
            double radians = Math.toRadians(angle);
            float x = (float) Math.cos(radians) * radius;
            float y = 0.0f;
            float z = (float) Math.sin(radians) * radius;
            float scale = 1.2f;

            poseStack.pushPose();
            poseStack.translate(x, y, z);
            poseStack.mulPose(new Quaternionf().fromAxisAngleDeg(1.0f, 0.0f, 1.0f,
                    tick * (0.8f + i * 0.06f) + i * 23.0f));
            poseStack.scale(scale, scale, scale);
            ClientUtil.modelRenderer().renderModel(
                    poseStack.last(),
                    buffer.getBuffer(RenderType.solid()),
                    null,
                    ClientUtil.getBakedModel(LIGHT_HUNTER_SPHERES.get(i)),
                    1.0f, 1.0f, 1.0f,
                    15728880,
                    OverlayTexture.NO_OVERLAY,
                    ModelData.EMPTY,
                    RenderType.solid());
            poseStack.popPose();
        }
    }

    private static float getPlanetOrbitRadius(int index) {
        return 35.0f + index * 2.5f + (index % 3) * 0.85f;
    }

    private static float getPlanetOrbitSpeed(int index) {
        return 0.50f + index * 0.03f;
    }

    private static void addOrbitTrailSegment(VertexConsumer consumer, Matrix4f matrix,
                                             float radius, float width,
                                             float angle0, float angle1,
                                             float red, float green, float blue, float alpha) {
        double r0 = Math.toRadians(angle0);
        double r1 = Math.toRadians(angle1);
        float x0 = (float) Math.cos(r0) * radius;
        float z0 = (float) Math.sin(r0) * radius;
        float x1 = (float) Math.cos(r1) * radius;
        float z1 = (float) Math.sin(r1) * radius;
        float ox0 = (float) Math.cos(r0) * width;
        float oz0 = (float) Math.sin(r0) * width;
        float ox1 = (float) Math.cos(r1) * width;
        float oz1 = (float) Math.sin(r1) * width;

        addQuad(consumer, matrix,
                x0 - ox0, 0.0f, z0 - oz0,
                x1 - ox1, 0.0f, z1 - oz1,
                x1 + ox1, 0.0f, z1 + oz1,
                x0 + ox0, 0.0f, z0 + oz0,
                red, green, blue, alpha);
    }

    private static void addHelixSegment(VertexConsumer consumer, Matrix4f matrix,
                                        float x0, float angle0, float x1, float angle1,
                                        float radius, float width,
                                        float red, float green, float blue, float alpha) {
        double r0 = Math.toRadians(angle0);
        double r1 = Math.toRadians(angle1);
        float y0 = (float) Math.cos(r0) * radius;
        float z0 = (float) Math.sin(r0) * radius;
        float y1 = (float) Math.cos(r1) * radius;
        float z1 = (float) Math.sin(r1) * radius;
        float wy0 = (float) Math.cos(r0) * width;
        float wz0 = (float) Math.sin(r0) * width;
        float wy1 = (float) Math.cos(r1) * width;
        float wz1 = (float) Math.sin(r1) * width;

        addQuad(consumer, matrix,
                x0, y0 - wy0, z0 - wz0,
                x1, y1 - wy1, z1 - wz1,
                x1, y1 + wy1, z1 + wz1,
                x0, y0 + wy0, z0 + wz0,
                red, green, blue, alpha);
    }

    private static void addCrossBillboard(VertexConsumer consumer, Matrix4f matrix,
                                          float x, float y, float z, float size,
                                          float red, float green, float blue, float alpha) {
        addQuad(consumer, matrix,
                x, y - size, z,
                x, y, z - size,
                x, y + size, z,
                x, y, z + size,
                red, green, blue, alpha);
        addQuad(consumer, matrix,
                x - size * 0.45f, y, z - size * 0.45f,
                x + size * 0.45f, y, z - size * 0.45f,
                x + size * 0.45f, y, z + size * 0.45f,
                x - size * 0.45f, y, z + size * 0.45f,
                red, green, blue, alpha * 0.75f);
    }

    private static void addQuad(VertexConsumer consumer, Matrix4f matrix,
                                float x0, float y0, float z0,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                float x3, float y3, float z3,
                                float red, float green, float blue, float alpha) {
        consumer.vertex(matrix, x0, y0, z0).color(red, green, blue, alpha).endVertex();
        consumer.vertex(matrix, x1, y1, z1).color(red, green, blue, alpha).endVertex();
        consumer.vertex(matrix, x2, y2, z2).color(red, green, blue, alpha).endVertex();
        consumer.vertex(matrix, x3, y3, z3).color(red, green, blue, alpha).endVertex();
    }
}

