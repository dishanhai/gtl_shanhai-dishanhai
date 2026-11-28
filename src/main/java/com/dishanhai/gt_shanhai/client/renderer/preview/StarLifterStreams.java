package com.dishanhai.gt_shanhai.client.renderer.preview;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterGeometry;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterStructure;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.AbstractUniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.io.IOException;

import static org.lwjgl.opengl.GL11.GL_LEQUAL;

/** A shared ribbon mesh stores the original curve samples; only their phase and transforms change. */
final class StarLifterStreams {
    private static final VertexFormat FORMAT = DefaultVertexFormat.POSITION_TEX_COLOR;
    private static Program program;
    private static final RenderType LAYER = RenderType.create("shanhai_star_lifter_streams", FORMAT,
            VertexFormat.Mode.QUADS, 256, false, false, RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(() -> program == null ? null : program.shader()))
                    .setTransparencyState(new RenderStateShard.TransparencyStateShard("shanhai_star_lifter_premultiplied",
                            () -> {
                                RenderSystem.enableBlend();
                                RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
                            }, () -> {
                                RenderSystem.disableBlend();
                                RenderSystem.defaultBlendFunc();
                            }))
                    .setDepthTestState(new RenderStateShard.DepthTestStateShard("lequal", GL_LEQUAL))
                    .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, false))
                    .setCullState(new RenderStateShard.CullStateShard(false))
                    .createCompositeState(false));

    private record Program(ShaderInstance shader, AbstractUniform phase, AbstractUniform activity,
                           AbstractUniform visibility, AbstractUniform[] rings, AbstractUniform heading,
                           AbstractUniform starRadius) {}
    private record Mesh(VertexBuffer buffer, int vertices) {}
    private static final Mesh[] MESHES = new Mesh[Quality.values().length];
    private static final Matrix4f ORIENTATION = new Matrix4f();
    private static final Matrix4f MODEL_VIEW = new Matrix4f();

    enum Quality {
        NEAR(6, 16, 6, 18, 3, 12, 3, 12, 9, 8),
        MEDIUM(4, 12, 5, 12, 0, 0, 2, 9, 6, 0),
        FAR(2, 8, 4, 8, 0, 0, 1, 6, 4, 0);

        final int prominences, prominenceSteps, beams, beamSteps, filaments, filamentSteps;
        final int couplings, couplingSteps, trackSteps, motes;

        Quality(int prominences, int prominenceSteps, int beams, int beamSteps, int filaments, int filamentSteps,
                int couplings, int couplingSteps, int trackSteps, int motes) {
            this.prominences = prominences;
            this.prominenceSteps = prominenceSteps;
            this.beams = beams;
            this.beamSteps = beamSteps;
            this.filaments = filaments;
            this.filamentSteps = filamentSteps;
            this.couplings = couplings;
            this.couplingSteps = couplingSteps;
            this.trackSteps = trackSteps;
            this.motes = motes;
        }
    }

    private StarLifterStreams() {}

    static void register(RegisterShadersEvent event) {
        program = null;
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    new ResourceLocation(GTDishanhaiMod.MOD_ID, "star_lifter_streams"), FORMAT), shader -> {
                var rings = StarLifterGeometry.RINGS;
                shader.safeGetUniform("CollectorRadius").set((float) StarLifterGeometry.collectorFaceRadius());
                shader.safeGetUniform("RingRadius").set((float) rings.get(0).radius(), (float) rings.get(1).radius(),
                        (float) rings.get(2).radius(), (float) rings.get(3).radius());
                shader.safeGetUniform("RingWidth").set((float) rings.get(0).width(), (float) rings.get(1).width(),
                        (float) rings.get(2).width(), (float) rings.get(3).width());
                shader.safeGetUniform("RingHeight").set((float) rings.get(0).height(), (float) rings.get(1).height(),
                        (float) rings.get(2).height(), (float) rings.get(3).height());
                var control = StarLifterStructure.controlAnchor();
                shader.safeGetUniform("ControlAnchor").set((float) control.x, (float) control.y, (float) control.z,
                        1 / StarLifterGeometry.DEFAULT_RADIUS);
                shader.safeGetUniform("CollectorAngle").set((float) StarLifterGeometry.collectorFaceAngle(0),
                        (float) (StarLifterGeometry.collectorFaceAngle(1) - StarLifterGeometry.collectorFaceAngle(0)));
                program = new Program(shader, shader.safeGetUniform("PlasmaPhase"), shader.safeGetUniform("Activity"),
                        shader.safeGetUniform("Visibility"), new AbstractUniform[]{shader.safeGetUniform("Ring0"),
                        shader.safeGetUniform("Ring1"), shader.safeGetUniform("Ring2"), shader.safeGetUniform("Ring3")},
                        shader.safeGetUniform("Heading"), shader.safeGetUniform("StarRadius"));
            });
        } catch (IOException | RuntimeException error) {
            GTDishanhaiMod.LOGGER.warn("摘星者采集流着色器加载失败", error);
        }
    }

    static boolean available() {
        return program != null;
    }

    static int draw(Matrix4f pose, Matrix4f projection, Quaternionf heading, double ticks,
                    StarLifterEffects.Activity activity, Quality quality, int ringMask, boolean stellar,
                    boolean fixed, boolean collection) {
        var mesh = MESHES[quality.ordinal()];
        if (mesh == null) {
            try (var profile = StarLifterProfile.begin("ResourceUpdate", true)) {
                int[] vertices = {0};
                var buffer = StarLifterRenderer.uploadMesh(FORMAT, builder -> vertices[0] = writeMesh(builder, quality));
                mesh = new Mesh(buffer, vertices[0]);
                MESHES[quality.ordinal()] = mesh;
                StarLifterProfile.draw(profile, 0, 0, mesh.vertices() * FORMAT.getVertexSize());
            }
        }
        var rings = StarLifterGeometry.RINGS;
        program.phase().set((float) StarLifterEffects.plasmaPhase(ticks));
        activity.apply(program.activity());
        program.starRadius().set((float) (StarLifterGeometry.STAR_RADIUS * activity.stellarScale()));
        program.visibility().set(ringMask, stellar ? 1f : 0f, fixed ? 1f : 0f, collection ? 1f : 0f);
        program.heading().set(ORIENTATION.rotation(heading));
        for (int i = 0; i < rings.size(); i++) {
            program.rings()[i].set(ORIENTATION.rotation(heading).rotate(rings.get(i).orientation(ticks)));
        }
        LAYER.setupRenderState();
        try {
            mesh.buffer().bind();
            mesh.buffer().drawWithShader(MODEL_VIEW.set(RenderSystem.getModelViewMatrix()).mul(pose), projection, program.shader());
        } finally {
            VertexBuffer.unbind();
            LAYER.clearRenderState();
        }
        return mesh.vertices();
    }

    private static int writeMesh(BufferBuilder builder, Quality quality) {
        int vertices = paths(builder, 0, 0, quality.prominences, quality.prominenceSteps, 2, true);
        vertices += paths(builder, 1, 0, quality.beams, quality.beamSteps, 2, true);
        for (int ring = 0; ring < StarLifterGeometry.RINGS.size(); ring++) {
            int first = ring * 4;
            if (quality == Quality.FAR) {
                vertices += paths(builder, 2, first, 1, quality.trackSteps, 2, false);
                vertices += paths(builder, 2, first + 2, 1, quality.trackSteps, 2, false);
            } else {
                int pulses = ring == StarLifterGeometry.OUTER_RING ? 4 : 3;
                vertices += paths(builder, 2, first, pulses, quality.trackSteps, 2, false);
            }
        }
        vertices += paths(builder, 3, 0, quality.filaments, quality.filamentSteps, 1, true);
        vertices += paths(builder, 4, 0, quality.beams, quality.trackSteps + 4, 1, false);
        vertices += paths(builder, 5, 0, quality == Quality.NEAR ? 8 : 4, quality.trackSteps, 1, true);
        vertices += paths(builder, 6, 0, quality.couplings * 2, quality.couplingSteps, 1, true);
        vertices += paths(builder, 7, 0, quality.motes, 1, 2, false);
        return vertices;
    }

    private static int paths(BufferBuilder builder, int family, int first, int count, int steps, int axes,
                             boolean feathered) {
        for (int i = first; i < first + count; i++) {
            for (int step = 0; step < steps; step++) {
                float a = (float) step / steps;
                float b = (float) (step + 1) / steps;
                for (int axis = 0; axis < axes; axis++) {
                    if (feathered) {
                        quad(builder, a, b, i, -1, 0, family, axis, true);
                        quad(builder, a, b, i, 0, 1, family, axis, true);
                    } else {
                        quad(builder, a, b, i, -1, 1, family, axis, false);
                    }
                }
            }
        }
        return count * steps * axes * (feathered ? 8 : 4);
    }

    private static void quad(BufferBuilder builder, float a, float b, int index, int lo, int hi, int family,
                             int axis, boolean feathered) {
        int left = !feathered || lo == 0 ? 255 : 0;
        int right = !feathered || hi == 0 ? 255 : 0;
        builder.vertex(a, index, lo).uv(family, axis).color(255, 255, 255, left).endVertex();
        builder.vertex(b, index, lo).uv(family, axis).color(255, 255, 255, left).endVertex();
        builder.vertex(b, index, hi).uv(family, axis).color(255, 255, 255, right).endVertex();
        builder.vertex(a, index, hi).uv(family, axis).color(255, 255, 255, right).endVertex();
    }

    static void release() {
        for (int i = 0; i < MESHES.length; i++) {
            if (MESHES[i] != null) {
                MESHES[i].buffer().close();
                MESHES[i] = null;
            }
        }
    }
}
