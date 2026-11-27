package com.dishanhai.gt_shanhai.client.renderer.preview;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.renderer.hostfx.HostFxDraw;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterGeometry;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;

import java.io.IOException;

/** 摘星者的星面、日冕与采集流，所有坐标以预览基准半径为单位。 */
final class StarLifterEffects {
    private static final double TAU = Math.PI * 2.0;
    private static final double STAR_RADIUS = StarLifterGeometry.STAR_RADIUS;
    private static final int STREAM_STEPS = 18;
    private static final int TRACK_PULSES = 8;
    private static final int TRACK_STEPS = 12;
    private static ShaderInstance plasma;

    private static final MultiBufferSource.BufferSource BUFFERS =
            MultiBufferSource.immediate(new BufferBuilder(96 * 1024));
    private static final RenderStateShard.TransparencyStateShard PLASMA_BLEND =
            new RenderStateShard.TransparencyStateShard("shanhai_star_lifter_blend",
                    () -> {
                        RenderSystem.enableBlend();
                        RenderSystem.defaultBlendFunc();
                    },
                    () -> {
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                    });
    private static final RenderType PHOTOSPHERE = plasmaLayer("shanhai_star_lifter_photosphere", true);
    private static final RenderType CORONA = plasmaLayer("shanhai_star_lifter_corona", false);

    private StarLifterEffects() {}

    static void registerShader(RegisterShadersEvent event) {
        plasma = null;
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    new ResourceLocation(GTDishanhaiMod.MOD_ID, "star_lifter_plasma"),
                    DefaultVertexFormat.POSITION_TEX_COLOR), shader -> plasma = shader);
        } catch (IOException | RuntimeException error) {
            GTDishanhaiMod.LOGGER.warn("摘星者星面着色器加载失败", error);
        }
    }

    static boolean hasPlasma() {
        return plasma != null;
    }

    private static RenderType plasmaLayer(String name, boolean depthWrite) {
        return RenderType.create(name, DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS,
                256, false, false, RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(() -> plasma))
                        .setTransparencyState(PLASMA_BLEND)
                        .setDepthTestState(new RenderStateShard.DepthTestStateShard("lequal", GL11.GL_LEQUAL))
                        .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, depthWrite))
                        .setCullState(new RenderStateShard.CullStateShard(depthWrite))
                        .createCompositeState(false));
    }

    static VertexBuffer bakeSurface() {
        var builder = new BufferBuilder(160 * 1024);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        StarLifterGeometry.star((surface, a, b, c, d) -> {
            surfaceVertex(builder, a);
            surfaceVertex(builder, b);
            surfaceVertex(builder, c);
            surfaceVertex(builder, d);
        });
        return StarLifterRenderer.upload(builder);
    }

    private static void surfaceVertex(VertexConsumer vertices, StarLifterGeometry.Point point) {
        vertices.vertex(point.x(), point.y(), point.z()).uv(0, 0).color(255, 255, 255, 255).endVertex();
    }

    static void drawPlasma(VertexBuffer surface, Matrix4f pose, Matrix4f projection, Vec3 eye,
                           Vec3 cameraUp, double ticks) {
        plasma.safeGetUniform("StarSeconds").set((float) (ticks / 20.0));
        plasma.safeGetUniform("EyeLocal").set((float) eye.x, (float) eye.y, (float) eye.z);
        PHOTOSPHERE.setupRenderState();
        try {
            surface.bind();
            surface.drawWithShader(pose, projection, plasma);
        } finally {
            VertexBuffer.unbind();
            PHOTOSPHERE.clearRenderState();
        }

        double distance = eye.length();
        if (distance <= STAR_RADIUS * 1.15) return;
        Vec3 sight = eye.scale(1.0 / distance);
        // 在星球的切圆平面绘制日冕，近看时也与星缘重合。
        double tangentOffset = STAR_RADIUS * STAR_RADIUS / distance;
        double tangentRadius = Math.sqrt(STAR_RADIUS * STAR_RADIUS - tangentOffset * tangentOffset);
        Vec3 center = sight.scale(tangentOffset);
        Vec3 right = cameraUp.cross(sight).normalize().scale(tangentRadius * 1.62);
        Vec3 up = sight.cross(right);
        int alpha = (int) (255 * Math.min(1, (distance / STAR_RADIUS - 1.15) / 0.32));
        var vertices = BUFFERS.getBuffer(CORONA);
        glowVertex(vertices, pose, center.subtract(right).subtract(up), 2, 0, alpha);
        glowVertex(vertices, pose, center.add(right).subtract(up), 3, 0, alpha);
        glowVertex(vertices, pose, center.add(right).add(up), 3, 1, alpha);
        glowVertex(vertices, pose, center.subtract(right).add(up), 2, 1, alpha);
        BUFFERS.endBatch(CORONA);
    }

    private static void glowVertex(VertexConsumer vertices, Matrix4f pose, Vec3 point, float u, float v, int alpha) {
        vertices.vertex(pose, (float) point.x, (float) point.y, (float) point.z)
                .uv(u, v).color(255, 255, 255, alpha).endVertex();
    }

    static void drawCollectors(Matrix4f pose, Quaternionf heading, double ticks) {
        RenderType layer = HostFxDraw.beamLayer();
        var vertices = BUFFERS.getBuffer(layer);
        var installation = new Matrix4f(pose).rotate(heading);
        var rings = StarLifterGeometry.RINGS;
        var inner = new Matrix4f(installation).rotate(rings.get(StarLifterGeometry.COLLECTOR).orientation(ticks));
        for (int i = 0; i < StarLifterGeometry.COLLECTOR_COUNT; i++) {
            drawStream(vertices, inner, i, ticks);
        }

        var rim = rings.get(StarLifterGeometry.OUTER_RING);
        installation.rotate(rim.orientation(ticks));
        drawTrack(vertices, installation, rim, ticks);
        BUFFERS.endBatch(layer);
    }

    private record StreamSection(Vec3 center, Vec3 tangent, double width, int alpha) {}

    private static void drawStream(VertexConsumer vertices, Matrix4f pose, int index, double ticks) {
        double angle = StarLifterGeometry.collectorFaceAngle(index);
        double destination = StarLifterGeometry.collectorFaceRadius();
        StreamSection previous = streamSection(angle, destination, index, ticks, 0);
        for (int i = 1; i <= STREAM_STEPS; i++) {
            StreamSection current = streamSection(angle, destination, index, ticks, (double) i / STREAM_STEPS);
            ribbon(vertices, pose, previous, current, new Vec3(0, previous.width, 0), new Vec3(0, current.width, 0));
            ribbon(vertices, pose, previous, current, previous.tangent.scale(previous.width), current.tangent.scale(current.width));
            previous = current;
        }
    }

    private static StreamSection streamSection(double angle, double destination, int index, double ticks, double t) {
        double phase = index * 2.39996323;
        double bow = Math.sin(Math.PI * t);
        double azimuth = angle + bow * (.32 + .13 * Math.sin(ticks * .11 + phase - t * 4));
        double y = bow * (.047 * Math.sin(ticks * .12 + phase - t * 4.4) + .022);
        double radius = STAR_RADIUS - .002 + t * (destination - STAR_RADIUS + .002);
        double cos = Math.cos(azimuth), sin = Math.sin(azimuth);
        double pulse = .5 + .5 * Math.cos(t * TAU * 1.7 - ticks * .31 + phase);
        double width = (.003 + .010 * bow) * (.65 + .35 * pulse);
        int alpha = (int) ((.30 + .70 * bow) * (56 + 155 * pulse * pulse));
        return new StreamSection(new Vec3(radius * cos, y, radius * sin), new Vec3(-sin, 0, cos), width, alpha);
    }

    private static void ribbon(VertexConsumer vertices, Matrix4f pose, StreamSection a, StreamSection b,
                               Vec3 widthA, Vec3 widthB) {
        lightVertex(vertices, pose, a.center.subtract(widthA), 147, 193, 255, 0);
        lightVertex(vertices, pose, b.center.subtract(widthB), 147, 193, 255, 0);
        lightVertex(vertices, pose, b.center, 190, 220, 255, b.alpha);
        lightVertex(vertices, pose, a.center, 190, 220, 255, a.alpha);
        lightVertex(vertices, pose, a.center, 190, 220, 255, a.alpha);
        lightVertex(vertices, pose, b.center, 190, 220, 255, b.alpha);
        lightVertex(vertices, pose, b.center.add(widthB), 147, 193, 255, 0);
        lightVertex(vertices, pose, a.center.add(widthA), 147, 193, 255, 0);
    }

    private static void drawTrack(VertexConsumer vertices, Matrix4f pose, StarLifterGeometry.Ring rim, double ticks) {
        for (int pulse = 0; pulse < TRACK_PULSES; pulse++) {
            double head = ticks * .022 + TAU * pulse / TRACK_PULSES;
            for (int i = 0; i < TRACK_STEPS; i++) {
                double tail = (double) i / TRACK_STEPS;
                double next = (double) (i + 1) / TRACK_STEPS;
                for (int face = -1; face <= 1; face += 2) {
                    double y = face * rim.height() * .161;
                    trackVertex(vertices, pose, rim, y, head, tail, -1);
                    trackVertex(vertices, pose, rim, y, head, tail, 1);
                    trackVertex(vertices, pose, rim, y, head, next, 1);
                    trackVertex(vertices, pose, rim, y, head, next, -1);
                }
            }
        }
    }

    private static void trackVertex(VertexConsumer vertices, Matrix4f pose, StarLifterGeometry.Ring rim,
                                    double y, double head, double tail, int edge) {
        double angle = head - .49 * tail;
        double radius = rim.radius() + edge * rim.width() * .062;
        double envelope = Math.min(1, 13 * tail) * (1 - tail) * (1 - tail);
        lightVertex(vertices, pose, new Vec3(radius * Math.cos(angle), y, radius * Math.sin(angle)),
                93, 216, 255, (int) (240 * envelope));
    }

    private static void lightVertex(VertexConsumer vertices, Matrix4f pose, Vec3 point, int red, int green, int blue, int alpha) {
        vertices.vertex(pose, (float) point.x, (float) point.y, (float) point.z)
                .color(red, green, blue, alpha).endVertex();
    }
}
