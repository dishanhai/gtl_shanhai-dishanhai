package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuBlackHolePreview;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuContainmentField;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuHostGeometry;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuKerrOrbit;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuVisionCoreMachine;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuStarRenderer.ambientVertex;
import static com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuKerrOrbit.Track.INNER;
import static com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuKerrOrbit.Track.OUTER;

/** Penrose confinement arcs and Kerr cargo trails around the host's main black hole. */
final class GuixuBlackHoleMechanicsRenderer {
    private static final float COLLAPSE_SECONDS = 2.5F;
    private static final float RECOVERY_SECONDS = 1.2F;
    private static final Color INNER_COLOR = new Color(.42F, .72F, 1);
    private static final Color OUTER_COLOR = new Color(1, .67F, .22F);
    private static final Color CONTAINED_COLOR = new Color(.21F, .92F, .82F);
    private static final Color HYPERSTABLE_COLOR = new Color(.62F, .85F, 1);
    private static final Color UNSTABLE_COLOR = new Color(1, .14F, .21F);

    private GuixuBlackHoleMechanicsRenderer() {}

    static void render(PoseStack pose, GuixuVisionCoreMachine host, Direction facing, float horizon,
                       double tick, boolean simple, GuixuBlackHolePreview.Frame preview) {
        var frame = preview != null ? preview : new GuixuBlackHolePreview.Frame(host.getKerrSpin(),
                host.getContainmentVisualStatus(), host.getContainmentVisualStability(),
                host.getKerrOrbit(INNER), host.getKerrOrbit(OUTER),
                host.getKerrRecovery(INNER), host.getKerrRecovery(OUTER), host.getContainmentCollapseTick());
        var status = frame.status();
        float inner = frame.innerOrbit();
        float outer = frame.outerOrbit();
        float collapse = eventAge(tick, frame.collapse(), COLLAPSE_SECONDS);
        float innerRecovery = eventAge(tick, frame.innerRecovery(), RECOVERY_SECONDS);
        float outerRecovery = eventAge(tick, frame.outerRecovery(), RECOVERY_SECONDS);
        if (status == GuixuContainmentField.Status.CLOSED && inner == 0 && outer == 0
                && collapse >= 1 && innerRecovery >= 1 && outerRecovery >= 1) return;

        var layer = HostFxDraw.beamLayer();
        var buffers = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        var vertices = buffers.getBuffer(layer);
        var draw = new Rings(vertices,
                new Matrix4f(pose.last().pose()).rotate(GuixuStarRenderer.diskRotation(facing)),
                horizon, tick / 20, simple ? 36 : 72);
        float stress = 1 - Math.clamp(frame.stability(), 0, 100) / 100;
        if (collapse < 1) {
            draw.ring(1.23F + 3.2F * collapse, 0.09F + 0.12F * collapse, .03F,
                    0, Math.TAU, UNSTABLE_COLOR, .88F * (1 - collapse), .02F, false);
        } else {
            draw.containment(status, stress, frame.spin());
            if (status != GuixuContainmentField.Status.CLOSED) {
                var source = host.getContainmentVisualSource();
                if (preview != null && source.equals(BlockPos.ZERO)) {
                    source = GuixuHostGeometry.slotPos(host.getPos(), facing, GuixuHostGeometry.slots().getFirst());
                }
                if (host.getLevel().hasChunkAt(source)) {
                    var local = HostFxBlackHole.cameraLocal(facing, source.getX() + .5, source.getY() + .5,
                            source.getZ() + .5, host.getPos());
                    confinementLink(vertices, pose.last().pose(), new Vector3f(local), horizon, tick / 20,
                            containmentColor(status, stress), simple);
                }
            }
        }
        draw.orbit(INNER, inner, frame.spin(), stress, innerRecovery, INNER_COLOR);
        draw.orbit(OUTER, outer, frame.spin(), stress, outerRecovery, OUTER_COLOR);
        buffers.endBatch(layer);
    }

    private static float eventAge(double now, long started, float durationSeconds) {
        return started < 0 || now < started ? 1 : (float) Math.min(1, (now - started) / (20 * durationSeconds));
    }

    private static Color containmentColor(GuixuContainmentField.Status status, float stress) {
        return switch (status) {
            case CONTAINED -> new Color(.21F + stress * .76F, .92F - stress * .51F, .82F - stress * .61F);
            case HYPERSTABLE -> HYPERSTABLE_COLOR;
            case UNSTABLE -> UNSTABLE_COLOR;
            case CLOSED -> CONTAINED_COLOR;
        };
    }

    private static void confinementLink(VertexConsumer vertices, Matrix4f matrix, Vector3f source, float horizon,
                                       double seconds, Color color, boolean simple) {
        if (source.lengthSquared() < 4 * horizon * horizon) return;
        var destination = new Vector3f(source).normalize(horizon * 1.42F);
        var travel = new Vector3f(destination).sub(source);
        var side = new Vector3f(travel).cross(0, 1, 0);
        if (side.lengthSquared() < .001F) side.set(1, 0, 0);
        side.normalize(Math.min(.55F, horizon * .045F));
        int pulses = simple ? 1 : 3;
        for (int pulse = 0; pulse < pulses; pulse++) {
            double phase = seconds * .6 + pulse / (double) pulses;
            float progress = (float) (phase - Math.floor(phase));
            float fade = .65F * (float) Math.sin(progress * Math.PI);
            var head = new Vector3f(source).fma(progress, travel);
            var tail = new Vector3f(source).fma(Math.max(0, progress - .13F), travel);
            linkVertex(vertices, matrix, new Vector3f(tail).add(side), color, 0);
            linkVertex(vertices, matrix, tail.sub(side), color, 0);
            linkVertex(vertices, matrix, new Vector3f(head).sub(side), color, fade);
            linkVertex(vertices, matrix, head.add(side), color, fade);
        }
    }

    private static void linkVertex(VertexConsumer vertices, Matrix4f matrix, Vector3f point, Color color, float alpha) {
        ambientVertex(vertices, matrix, point.x, point.y, point.z, color.red(), color.green(), color.blue(), alpha);
    }

    private record Color(float red, float green, float blue) {}

    private record Rings(VertexConsumer vertices, Matrix4f matrix, float horizon, double seconds, int segments) {
        void containment(GuixuContainmentField.Status status, float stress, int spin) {
            double rotation = seconds * (.44 + spin * .008);
            switch (status) {
                case CLOSED -> { }
                case CONTAINED -> {
                    Color tint = containmentColor(status, stress);
                    for (int arc = 0; arc < 3; arc++) {
                        ring(1.39F, .042F, .18F, rotation + arc * Math.TAU / 3, 1.55,
                                tint, .46F + stress * .24F, .015F + .13F * stress, true);
                    }
                }
                case HYPERSTABLE -> {
                    ring(1.38F, .044F, .17F, rotation, Math.TAU, HYPERSTABLE_COLOR, .51F, .008F, false);
                    ring(1.49F, .031F, -.17F, -rotation, Math.TAU, CONTAINED_COLOR, .36F, .008F, false);
                }
                case UNSTABLE -> {
                    for (int arc = 0; arc < 4; arc++) {
                        double age = seconds * 4.1 + arc * 1.7;
                        ring(1.43F + .12F * (float) Math.sin(age), .072F, .12F,
                                rotation + arc * Math.TAU / 4, 1.12, UNSTABLE_COLOR,
                                .52F + .27F * (float) Math.sin(age), .16F, true);
                    }
                }
            }
        }

        void orbit(GuixuKerrOrbit.Track track, float progress, int spin, float stress,
                   float recovery, Color color) {
            if (progress == 0 && recovery >= 1) return;
            float radius = GuixuKerrOrbit.radius(track, spin);
            boolean inner = track == INNER;
            float height = inner ? .28F : .36F;
            double winding = inner ? 1 : -1;
            double travelled = Math.max(0, progress - 1) * Math.TAU / GuixuKerrOrbit.STEPS;
            double head = winding * travelled + (inner ? 0 : Math.PI);
            float ripple = stress * (inner ? .36F : .075F);
            if (progress > 0) {
                ring(radius, .037F, height, 0, Math.TAU, color, .29F, ripple, false);
                ring(radius, .12F, height, head - winding * 1.35, winding * 1.35,
                        color, .84F, ripple, true);
            }
            if (recovery < 1) {
                ring(radius + .86F * recovery, .09F, height, head - .58, 1.16 + 1.75 * recovery,
                        color, .87F * (1 - recovery), 0, false);
            }
        }

        void ring(float radius, float width, float height, double from, double arc,
                  Color color, float alpha, float ripple, boolean headGlow) {
            int count = Math.max(4, (int) Math.ceil(Math.abs(arc) * segments / Math.TAU));
            for (int i = 0; i < count; i++) {
                float start = i / (float) count;
                float end = (i + 1) / (float) count;
                double a = from + arc * start;
                double b = from + arc * end;
                float ra = radius + ripple * (float) Math.sin(a * 9 - seconds * 5);
                float rb = radius + ripple * (float) Math.sin(b * 9 - seconds * 5);
                float aa = alpha * (headGlow ? start * start : 1);
                float ab = alpha * (headGlow ? end * end : 1);
                vertex(ra - width, height, a, color, aa);
                vertex(ra + width, height, a, color, aa * .34F);
                vertex(rb + width, height, b, color, ab * .34F);
                vertex(rb - width, height, b, color, ab);
            }
        }

        private void vertex(float radius, float y, double angle, Color color, float alpha) {
            ambientVertex(vertices, matrix, (float) Math.cos(angle) * radius * horizon,
                    y * horizon, (float) Math.sin(angle) * radius * horizon,
                    color.red(), color.green(), color.blue(), alpha);
        }
    }
}
