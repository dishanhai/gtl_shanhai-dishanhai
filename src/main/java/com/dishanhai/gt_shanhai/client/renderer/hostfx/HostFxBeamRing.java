package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/** 山海：外緣吸積流單遍繪製，沿用原光柱環的開關、重載及回落入口。 */
public final class HostFxBeamRing {
    private HostFxBeamRing() {}
    public static final ResourceLocation SHADER_ID =
            new ResourceLocation(GTDishanhaiMod.MOD_ID, "shanhai_host_beam_ring");
    public static final VertexFormat BEAM_RING_VERTEX_FORMAT = new VertexFormat(ImmutableMap.of(
            "Position", DefaultVertexFormat.ELEMENT_POSITION,
            "UV0", DefaultVertexFormat.ELEMENT_UV0,
            "UV1", DefaultVertexFormat.ELEMENT_UV1));
    public static final float ALPHA_PACK_SCALE = HostFxPillarBeam.ALPHA_PACK_SCALE;
    private static final HostFxLazyHandle<ShaderInstance> HANDLE = new HostFxLazyHandle<>(HostFxBeamRing::createShader);
    private static ShaderInstance pendingClose;
    private static VertexBuffer buffer;
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
        ShaderInstance old = HANDLE.invalidate();
        if (old != null) {
            pendingClose = old;
        }
        loggedBuilds = warnedBuilds = -1;
        return old != null;
    }
    public static void reset() {
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
            GTDishanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] ring_shader_failed（回落至暖白吸積流）", HANDLE.lastError());
        }
        return shader;
    }
    private static VertexBuffer buffer() {
        if (buffer == null) buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        return buffer;
    }
    public static int draw(PoseStack poseStack, float seconds, float spinDeg,
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
            setUniform(shader, "CameraPosition", camX, camY, camZ);
            setUniform(shader, "BeamColor", glowRgb[0], glowRgb[1], glowRgb[2]);
            setUniform(shader, "FlowTime", seconds);
            BufferBuilder builder = Tesselator.getInstance().getBuilder();
            builder.begin(VertexFormat.Mode.TRIANGLES, BEAM_RING_VERTEX_FORMAT);
            HostFxBeamRingGeom.emitTube((x, y, z, nx, ny, nz, u, v, alpha, angle) -> builder
                    .vertex(x, y, z).uv(u, v)
                    .overlayCoords(packAlpha(alpha * HostFxDraw.beamBrightness()), 0).endVertex(),
                    1, 1, seconds * 20, spinDeg);
            buffer().bind();
            buffer().upload(builder.end());
            buffer().drawWithShader(poseStack.last().pose(), RenderSystem.getProjectionMatrix(), shader);
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
    /** 單一雙面加性批次；與 GPU 使用相同輪廓、色溫與流向，不再回到藍色實心管。 */
    public static int drawFallback(PoseStack poseStack, float seconds, float spinDeg,
                                   float camX, float camY, float camZ, float[] glowRgb) {
        markSkipped();
        var layer = HostFxDraw.beamLayer();
        var buffers = net.minecraft.client.renderer.MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        VertexConsumer consumer = buffers.getBuffer(layer);
        double distance = Math.sqrt(camX * camX + camY * camY + camZ * camZ);
        float radialPixels = Math.abs(RenderSystem.getProjectionMatrix().m11())
                * Minecraft.getInstance().getWindow().getHeight() * .5F * HostFxBeamRingGeom.DIAMETER;
        FlowSink sink = new FlowSink(consumer, poseStack.last().pose(), seconds,
                (float) (distance / Math.max(1, radialPixels)), camX, camY, camZ, glowRgb);
        HostFxBeamRingGeom.emitTube(sink, 1, 1, seconds * 20, spinDeg);
        buffers.endBatch(layer);
        lastFallbackQuads = sink.vertices / 3;
        return lastFallbackQuads;
    }
    /** 位置色層採 QUADS；每個三角形重複最後一點，第二個退化三角形不產生像素。 */
    private static final class FlowSink implements HostFxBeamRingGeom.Sink {
        private final VertexConsumer consumer;
        private final Matrix4f matrix;
        private final float seconds, footprint, camX, camY, camZ;
        private final float[] glow, rgba = new float[4];
        private int vertices;
        private FlowSink(VertexConsumer consumer, Matrix4f matrix, float seconds, float footprint,
                         float camX, float camY, float camZ, float[] glow) {
            this.consumer = consumer; this.matrix = matrix; this.seconds = seconds; this.footprint = footprint;
            this.camX = camX; this.camY = camY; this.camZ = camZ; this.glow = glow;
        }
        @Override public void vertex(float x, float y, float z, float nx, float ny, float nz,
                                     float u, float v, float alpha, float angle) {
            GuixuAccretionFlow.shade((float) Math.hypot(x, z), v, seconds, footprint, camX, camY, camZ, rgba);
            put(x, y, z, alpha);
            if (++vertices % 3 == 0) put(x, y, z, alpha);
        }
        private void put(float x, float y, float z, float alpha) {
            consumer.vertex(matrix, x, y, z).color(rgba[0] * glow[0], rgba[1] * glow[1], rgba[2] * glow[2],
                    rgba[3] * alpha * HostFxDraw.beamBrightness()).endVertex();
        }
    }
    private static int packAlpha(float alpha) {
        return Math.max(0, Math.min(32767, Math.round(alpha * ALPHA_PACK_SCALE))) & 0xffff;
    }
    private static void setUniform(ShaderInstance shader, String name, float... values) {
        Uniform uniform = shader.getUniform(name);
        if (uniform == null) return;
        if (values.length == 1) uniform.set(values[0]); else uniform.set(values[0], values[1], values[2]);
    }
}
