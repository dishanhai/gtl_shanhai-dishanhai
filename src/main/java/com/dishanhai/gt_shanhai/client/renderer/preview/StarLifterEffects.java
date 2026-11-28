package com.dishanhai.gt_shanhai.client.renderer.preview;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.renderer.hostfx.HostFxDraw;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterGeometry;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterStructure;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterVisualState;
import com.mojang.blaze3d.shaders.AbstractUniform;
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

/** 摘星者的星面、日珥、日冕与采集流，所有坐标以预览基准半径为单位。 */
final class StarLifterEffects {
    private static final double TAU = Math.PI * 2.0;
    static final int PLASMA_PERIOD = 1040;
    private record Plasma(ShaderInstance shader, AbstractUniform phase, AbstractUniform eye, AbstractUniform surfaceDrift,
                          AbstractUniform coronaDrift, AbstractUniform coronaPose, AbstractUniform coronaAlpha,
                          AbstractUniform activity, AbstractUniform collectorFrame, AbstractUniform stellarScale,
                          AbstractUniform reserveFraction, AbstractUniform observationExposure) {}

    record Activity(float ignition, float load, float fault, float resonance, float reserveFraction) {
        static Activity at(StarLifterVisualState state, double gameTick) {
            return sample(state, state.ignition(gameTick), state.reserveFraction(gameTick));
        }

        static Activity sample(StarLifterVisualState state, float ignition, float reserveFraction) {
            return new Activity(ignition, state.mode() == StarLifterVisualState.Mode.HIGH_LOAD ?
                    Math.clamp((ignition - .60f) / .40f, 0, 1) : 0,
                    state.mode() == StarLifterVisualState.Mode.FAULT ? 1 : 0, state.resonance() ? 1 : 0, reserveFraction);
        }

        Activity withoutOperations() { return new Activity(0, 0, 0, 0, reserveFraction); }

        float stellarScale() { return StarLifterGeometry.photosphereScale(reserveFraction); }

        void apply(AbstractUniform uniform) { uniform.set(ignition, load, fault, resonance); }
    }

    private static Plasma plasma;
    private static VertexBuffer corona;
    private static final Matrix4f BILLBOARD = new Matrix4f();
    private static final Matrix4f COLLECTOR_FRAME = new Matrix4f();

    private static final MultiBufferSource.BufferSource BUFFERS =
            MultiBufferSource.immediate(new BufferBuilder(1024));
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
                    DefaultVertexFormat.POSITION_TEX_COLOR), shader -> {
                shader.safeGetUniform("ContactAngle").set((float) (StarLifterGeometry.collectorFaceAngle(0) - .11));
                plasma = new Plasma(shader, shader.safeGetUniform("PlasmaPhase"), shader.safeGetUniform("EyeLocal"),
                        shader.safeGetUniform("SurfaceDrift"), shader.safeGetUniform("CoronaDrift"),
                        shader.safeGetUniform("CoronaPose"), shader.safeGetUniform("CoronaAlpha"),
                        shader.safeGetUniform("Activity"), shader.safeGetUniform("CollectorFrame"),
                        shader.safeGetUniform("StellarScale"), shader.safeGetUniform("ReserveFraction"),
                        shader.safeGetUniform("ObservationExposure"));
            });
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
                        .setShaderState(new RenderStateShard.ShaderStateShard(() -> plasma == null ? null : plasma.shader()))
                        .setTransparencyState(PLASMA_BLEND)
                        .setDepthTestState(new RenderStateShard.DepthTestStateShard("lequal", GL11.GL_LEQUAL))
                        .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, depthWrite))
                        .setCullState(new RenderStateShard.CullStateShard(depthWrite))
                        .createCompositeState(false));
    }

    static VertexBuffer bakeSurface() {
        return StarLifterRenderer.uploadMesh(DefaultVertexFormat.POSITION_TEX_COLOR,
                builder -> StarLifterGeometry.star((surface, a, b, c, d) -> {
                    surfaceVertex(builder, a);
                    surfaceVertex(builder, b);
                    surfaceVertex(builder, c);
                    surfaceVertex(builder, d);
                }));
    }

    private static void surfaceVertex(VertexConsumer vertices, StarLifterGeometry.Point point) {
        vertices.vertex(point.x(), point.y(), point.z()).uv(0, 0).color(255, 255, 255, 255).endVertex();
    }

    static void drawPlasma(VertexBuffer surface, Matrix4f pose, Matrix4f projection, Vec3 eye,
                           Vec3 cameraUp, Quaternionf heading, double ticks, Activity activity) {
        drawPlasma(surface, pose, projection, eye, cameraUp, heading, ticks, activity, 1);
    }

    static void drawPlasma(VertexBuffer surface, Matrix4f pose, Matrix4f projection, Vec3 eye,
                           Vec3 cameraUp, Quaternionf heading, double ticks, Activity activity, float exposure) {
        float phase = (float) plasmaPhase(ticks);
        float sin2 = (float) Math.sin(2 * phase);
        plasma.phase().set(phase);
        plasma.eye().set((float) eye.x, (float) eye.y, (float) eye.z);
        plasma.surfaceDrift().set((float) Math.sin(3 * phase), (float) Math.cos(3 * phase), sin2);
        float stellarScale = activity.stellarScale();
        plasma.stellarScale().set(stellarScale);
        plasma.reserveFraction().set(activity.reserveFraction());
        plasma.observationExposure().set(exposure);
        activity.apply(plasma.activity());
        if (activity.ignition() > .46f || activity.fault() > 0) {
            plasma.collectorFrame().set(COLLECTOR_FRAME.rotation(heading)
                    .rotate(StarLifterGeometry.RINGS.get(StarLifterGeometry.COLLECTOR).orientation(ticks)).transpose());
        }
        PHOTOSPHERE.setupRenderState();
        try (var profile = StarLifterProfile.begin("StellarSurface", true)) {
            surface.bind();
            surface.drawWithShader(pose, projection, plasma.shader());
            StarLifterProfile.draw(profile, 1, 6 * 16 * 16 * 4, 0);
        } finally {
            VertexBuffer.unbind();
            PHOTOSPHERE.clearRenderState();
        }

        double distance = eye.length();
        double radius = StarLifterGeometry.STAR_RADIUS * stellarScale;
        if (distance <= radius * 1.15) return;
        try (var profile = StarLifterProfile.begin("StellarCorona", true)) {
            plasma.coronaDrift().set((float) Math.cos(2 * phase), sin2, (float) Math.cos(phase));
            drawCorona(pose, eye, cameraUp, distance, radius);
            StarLifterProfile.draw(profile, 1, 4, 0);
        }
    }

    private static void drawCorona(Matrix4f pose, Vec3 eye, Vec3 cameraUp, double distance, double radius) {
        Vec3 sight = eye.scale(1.0 / distance);
        // 在星球的切圆平面绘制日冕，近看时也与星缘重合。
        double tangentOffset = radius * radius / distance;
        double tangentRadius = Math.sqrt(radius * radius - tangentOffset * tangentOffset);
        Vec3 center = sight.scale(tangentOffset);
        Vec3 right = cameraUp.cross(sight).normalize().scale(tangentRadius * 1.62);
        Vec3 up = sight.cross(right);
        int alpha = (int) (255 * Math.min(1, (distance / radius - 1.15) / 0.32));
        BILLBOARD.set((float) right.x, (float) right.y, (float) right.z, 0,
                (float) up.x, (float) up.y, (float) up.z, 0, 0, 0, 1, 0,
                (float) center.x, (float) center.y, (float) center.z, 1).mulLocal(pose);
        plasma.coronaPose().set(BILLBOARD);
        plasma.coronaAlpha().set(alpha / 255.0f);
        if (corona == null) bakeCorona();
        CORONA.setupRenderState();
        try {
            corona.bind();
            corona.drawWithShader(RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), plasma.shader());
        } finally {
            VertexBuffer.unbind();
            CORONA.clearRenderState();
        }
    }

    private static void bakeCorona() {
        try (var profile = StarLifterProfile.begin("ResourceUpdate", true)) {
            corona = StarLifterRenderer.uploadMesh(DefaultVertexFormat.POSITION_TEX_COLOR, builder -> {
                builder.vertex(-1, -1, 0).uv(2, 0).color(255, 255, 255, 255).endVertex();
                builder.vertex(1, -1, 0).uv(3, 0).color(255, 255, 255, 255).endVertex();
                builder.vertex(1, 1, 0).uv(3, 1).color(255, 255, 255, 255).endVertex();
                builder.vertex(-1, 1, 0).uv(2, 1).color(255, 255, 255, 255).endVertex();
            });
            StarLifterProfile.draw(profile, 0, 0, 4 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize());
        }
    }

    static void release() {
        if (corona != null) {
            corona.close();
            corona = null;
        }
    }

    static void drawCollectors(Matrix4f pose, Matrix4f projection, Quaternionf heading, double ticks, Activity activity,
                               StarLifterStreams.Quality quality, int ringMask, boolean stellar,
                               boolean fixed, boolean collectors) {
        try (var profile = StarLifterProfile.begin("Collectors", true)) {
            if (fixed) drawClasps(pose, heading, ticks, .28f + .72f * activity.ignition(), profile);
            if ((stellar || collectors) && StarLifterStreams.available()) {
                try (var ribbons = StarLifterProfile.begin("RibbonDraw", true)) {
                    int vertices = StarLifterStreams.draw(pose, projection, heading, ticks, activity, quality,
                            ringMask, stellar, fixed, collectors);
                    StarLifterProfile.draw(ribbons, 1, vertices, 0);
                    StarLifterProfile.draw(profile, 1, vertices, 0);
                }
            }
        }
    }

    private static void drawClasps(Matrix4f pose, Quaternionf heading, double ticks, float strength,
                                   StarLifterProfile profile) {
        RenderType layer = HostFxDraw.beamLayer();
        var vertices = BUFFERS.getBuffer(layer);
        var installation = new Matrix4f(pose).rotate(heading);
        int count = 0;
        double angle = StarLifterGeometry.RINGS.get(StarLifterGeometry.OUTER_RING).rimDegrees(ticks);
        for (Vec3 latch : StarLifterStructure.claspInnerCenters()) {
            count += drawClaspPulse(vertices, installation, latch, angle, strength);
        }
        BUFFERS.endBatch(layer);
        StarLifterProfile.draw(profile, count == 0 ? 0 : 1, count, count * DefaultVertexFormat.POSITION_COLOR.getVertexSize());
    }

    private static int drawClaspPulse(VertexConsumer vertices, Matrix4f pose, Vec3 latch, double rimDegrees, float strength) {
        double advance = StarLifterGeometry.claspProgress(latch.z, rimDegrees);
        if (advance <= 0 || advance >= 1) return 0;
        // 两道短线沿上下扣爪的内沿滑动，截面和结构阴影由实体方块提供。
        double scale = StarLifterGeometry.DEFAULT_RADIUS;
        double x = (latch.x + 13.3 - 11.6 * advance) / scale;
        double length = 1.2 / scale;
        double width = .42 / scale;
        double z = latch.z / scale;
        int alpha = (int) (210 * strength * Math.sin(Math.PI * advance));
        for (int face = -1; face <= 1; face += 2) {
            double y = face * 6.37 / scale;
            lightVertex(vertices, pose, new Vec3(x - length, y, z - width), 72, 175, 255, 0);
            lightVertex(vertices, pose, new Vec3(x, y, z - width), 97, 213, 255, alpha);
            lightVertex(vertices, pose, new Vec3(x, y, z + width), 97, 213, 255, alpha);
            lightVertex(vertices, pose, new Vec3(x - length, y, z + width), 72, 175, 255, 0);
        }
        return 8;
    }

    static double plasmaPhase(double ticks) {
        return (ticks % PLASMA_PERIOD) * (TAU / PLASMA_PERIOD);
    }

    private static void lightVertex(VertexConsumer vertices, Matrix4f pose, Vec3 point, int red, int green, int blue, int alpha) {
        vertices.vertex(pose, (float) point.x, (float) point.y, (float) point.z)
                .color(red, green, blue, alpha).endVertex();
    }
}
