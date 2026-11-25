package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiInstabilityGeometry;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/** 归墟激光环及结构电弧。激光环的静态网格在 GPU 上随时间翘曲。 */
public final class HostFxBeamRing {
    private HostFxBeamRing() {}
    public static final ResourceLocation SHADER_ID =
            new ResourceLocation(GTDishanhaiMod.MOD_ID, "shanhai_host_beam_ring");
    public static final VertexFormat BEAM_RING_VERTEX_FORMAT = DefaultVertexFormat.POSITION_TEX;
    private static final HostFxLazyHandle<ShaderInstance> HANDLE = new HostFxLazyHandle<>(HostFxBeamRing::createShader);
    private static ShaderInstance pendingClose;
    private static VertexBuffer buffer;
    private static FallbackMesh fallbackMesh;
    private static int lastPasses, lastFallbackQuads;
    private static int loggedBuilds = -1, warnedBuilds = -1;

    public static int passes() { return lastPasses; }
    public static int fallbackQuads() { return lastFallbackQuads; }
    public static void markSkipped() { lastPasses = 0; lastFallbackQuads = 0; }
    public static boolean ready() { return shader() != null; }
    public static String stats() {
        return HostFxBeamRingGeom.stats() + " passes=" + lastPasses + "/1 fallbackQuads=" + lastFallbackQuads;
    }
    public static boolean onResourceReload() {
        GuixuStructureLightning.reset();
        ShaderInstance old = HANDLE.invalidate();
        if (old != null) {
            pendingClose = old;
        }
        loggedBuilds = warnedBuilds = -1;
        return old != null;
    }
    public static void reset() {
        GuixuStructureLightning.reset();
        if (buffer != null) { buffer.close(); buffer = null; }
        ShaderInstance old = HANDLE.invalidate();
        if (old != null) old.close();
        if (pendingClose != null) pendingClose.close();
        pendingClose = null;
        loggedBuilds = warnedBuilds = -1;
        markSkipped();
    }
    private static ShaderInstance createShader() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getResourceManager() == null) return null;
        try {
            return new ShaderInstance(mc.getResourceManager(), SHADER_ID, BEAM_RING_VERTEX_FORMAT);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("吸積流 shader 無法建立：" + SHADER_ID, error);
        }
    }
    private static ShaderInstance shader() {
        if (pendingClose != null) { pendingClose.close(); pendingClose = null; }
        ShaderInstance shader = HANDLE.get();
        if (shader != null && loggedBuilds != HANDLE.builds()) {
            loggedBuilds = HANDLE.builds();
            GTDishanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] ring_shader_ok mode=accretion-flow {}", HostFxBeamRingGeom.stats());
            String bad = HostFxBeamRingGeom.selfCheck();
            if (bad != null) GTDishanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] ring_size_mismatch {}", bad);
        }
        if (shader == null && HANDLE.failed() && warnedBuilds != HANDLE.builds()) {
            warnedBuilds = HANDLE.builds();
            GTDishanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] ring_shader_failed，使用顶点色激光环", HANDLE.lastError());
        }
        return shader;
    }
    private static VertexBuffer ringBuffer() {
        if (buffer == null) {
            var mesh = HostFxBeamRingGeom.mesh(HostFxBeamRingGeom.SEGMENTS, HostFxBeamRingGeom.RADIAL_CELLS);
            var knots = mesh.knots();
            BufferBuilder builder = Tesselator.getInstance().getBuilder();
            builder.begin(VertexFormat.Mode.QUADS, BEAM_RING_VERTEX_FORMAT);
            mesh.forEachQuad((a, b, c, d) -> {
                meshVertex(builder, knots[a]);
                meshVertex(builder, knots[b]);
                meshVertex(builder, knots[c]);
                meshVertex(builder, knots[d]);
            });
            buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            buffer.upload(builder.end());
        }
        return buffer;
    }

    private static void meshVertex(VertexConsumer consumer, HostFxBeamRingGeom.Knot knot) {
        consumer.vertex(knot.x(), 0, knot.z()).uv(knot.radial(), knot.angle()).endVertex();
    }

    public static int draw(PoseStack poseStack, float seconds,
                           float camX, float camY, float camZ, float[] glowRgb) {
        markSkipped();
        ShaderInstance shader = shader();
        if (shader == null) return 0;
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        try {
            shader.safeGetUniform("CameraPosition").set(camX, camY, camZ);
            shader.safeGetUniform("BeamColor").set(glowRgb[0], glowRgb[1], glowRgb[2]);
            shader.safeGetUniform("BeamBrightness").set(HostFxDraw.beamBrightness());
            shader.safeGetUniform("FlowTime").set(GuixuAccretionFlow.flowClock(seconds));
            shader.safeGetUniform("WarpPhase").set(GuixuAccretionFlow.warpPhase(seconds));
            VertexBuffer mesh = ringBuffer();
            mesh.bind();
            mesh.drawWithShader(poseStack.last().pose(), RenderSystem.getProjectionMatrix(), shader);
            lastPasses = 1;
        } finally {
            VertexBuffer.unbind();
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
        }
        return lastPasses;
    }
    /** 使用相同轮廓和色调的单个加性批次。 */
    public static int drawFallback(PoseStack poseStack, float seconds,
                                   float camX, float camY, float camZ, float[] glowRgb) {
        markSkipped();
        var layer = HostFxDraw.beamLayer();
        var buffers = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        VertexConsumer consumer = buffers.getBuffer(layer);
        double distance = Math.sqrt(camX * camX + camY * camY + camZ * camZ);
        float radialPixels = Math.abs(RenderSystem.getProjectionMatrix().m11())
                * Minecraft.getInstance().getWindow().getHeight() * .5F * HostFxBeamRingGeom.WIDTH;
        // 顶点色同时受屏幕像素和径向网格间距限制。
        float footprint = Math.max(.5F / HostFxBeamRingGeom.FALLBACK_RADIAL_CELLS,
                (float) (distance / Math.max(1, radialPixels)));
        if (fallbackMesh == null) fallbackMesh = new FallbackMesh();
        fallbackMesh.shade(seconds, footprint, camX, camY, camZ);
        fallbackMesh.draw(consumer, poseStack.last().pose(), glowRgb);
        buffers.endBatch(layer);
        lastFallbackQuads = HostFxBeamRingGeom.FALLBACK_SEGMENTS * HostFxBeamRingGeom.FALLBACK_RADIAL_CELLS;
        return lastFallbackQuads;
    }

    /** 同一加性層一次提交全部電弧；不依賴吸積盤自訂 shader，回落時仍可顯示。 */
    public static int drawLightning(PoseStack poseStack,
            ShanhaiInstabilityGeometry.Frame frame, float[] glowRgb) {
        if (frame.quads().isEmpty()) return 0;
        var layer = HostFxDraw.beamLayer();
        var buffers = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        VertexConsumer consumer = buffers.getBuffer(layer);
        Matrix4f matrix = poseStack.last().pose();
        for (var q : frame.quads()) {
            lightningVertex(consumer, matrix, q.a(), q, glowRgb);
            lightningVertex(consumer, matrix, q.b(), q, glowRgb);
            lightningVertex(consumer, matrix, q.c(), q, glowRgb);
            lightningVertex(consumer, matrix, q.d(), q, glowRgb);
        }
        buffers.endBatch(layer);
        return frame.quads().size();
    }

    private static void lightningVertex(VertexConsumer consumer, Matrix4f matrix,
            ShanhaiInstabilityGeometry.Point p, ShanhaiInstabilityGeometry.Quad q, float[] glow) {
        consumer.vertex(matrix, (float) p.x(), (float) p.y(), (float) p.z())
                .color(q.red() * glow[0], q.green() * glow[1], q.blue() * glow[2],
                        q.alpha() * HostFxDraw.beamBrightness()).endVertex();
    }
    private static final class FallbackMesh {
        private final HostFxBeamRingGeom.Mesh mesh = HostFxBeamRingGeom.mesh(
                HostFxBeamRingGeom.FALLBACK_SEGMENTS, HostFxBeamRingGeom.FALLBACK_RADIAL_CELLS);
        private final HostFxBeamRingGeom.Knot[] knots = mesh.knots();
        private final float[] height = new float[knots.length - mesh.columnSize()];
        private final float[][] color = new float[height.length][4];

        private void shade(float seconds, float footprint, float camX, float camY, float camZ) {
            float warp = GuixuAccretionFlow.warpPhase(seconds);
            float clock = GuixuAccretionFlow.flowClock(seconds);
            for (int i = 0; i < height.length; i++) {
                var knot = knots[i];
                height[i] = GuixuAccretionFlow.heightAtPhase(knot.radial(), knot.angle(), warp);
                GuixuAccretionFlow.shadeAt(knot.radial(), knot.angle(), clock, footprint,
                        camX - knot.x(), camY - height[i], camZ - knot.z(), color[i]);
            }
        }

        private void draw(VertexConsumer consumer, Matrix4f matrix, float[] glow) {
            float brightness = HostFxDraw.beamBrightness();
            mesh.forEachQuad((a, b, c, d) -> {
                vertex(consumer, matrix, a, glow, brightness);
                vertex(consumer, matrix, b, glow, brightness);
                vertex(consumer, matrix, c, glow, brightness);
                vertex(consumer, matrix, d, glow, brightness);
            });
        }

        private void vertex(VertexConsumer consumer, Matrix4f matrix, int index, float[] glow, float brightness) {
            var knot = knots[index];
            int sample = index < height.length ? index : index - height.length;
            float[] rgba = color[sample];
            consumer.vertex(matrix, knot.x(), height[sample], knot.z())
                    .color(rgba[0] * glow[0], rgba[1] * glow[1], rgba[2] * glow[2], rgba[3] * brightness).endVertex();
        }
    }
}
