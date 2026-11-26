package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import com.dishanhai.gt_shanhai.client.renderer.blackhole.BlackHoleLensSpec;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/** 山海：星芒與彎曲軌跡的短拖尾，共用既有加性層，一批提交、不寫深度。 */
public final class GuixuStarRenderer {
    // Blandford–Znajek：固定磁通下喷流功率近似正比于视界角速度平方。
    private static final double SPIN = BlackHoleLensSpec.KERR_SPIN;
    private static final double HORIZON_OMEGA = SPIN / (2.0 * (1.0 + Math.sqrt(1.0 - SPIN * SPIN)));
    private static final float JET_POWER = (float) (24.0 * HORIZON_OMEGA * HORIZON_OMEGA);
    private static final float DIAGONAL = (float) Math.sqrt(.5);
    private static final float[] JET_X = {1, DIAGONAL, 0, -DIAGONAL, -1, -DIAGONAL, 0, DIAGONAL};

    private GuixuStarRenderer() {}

    /** 呼叫方已將 PoseStack 放到環心並套用機器朝向，相機也使用同一座標系。 */
    public static void render(PoseStack pose, float[] camera, double seconds, double surfaceRadius,
                              float density, float brightness, float jets, boolean infallEnabled, boolean ambientEnabled) {
        render(pose, camera, seconds, surfaceRadius, density, brightness, jets, infallEnabled, infallEnabled, ambientEnabled,
                Direction.EAST, List.of(), GuixuStarField.ACCRETION_TRAILS, 0, false);
    }

    static void render(PoseStack pose, float[] camera, double seconds, double surfaceRadius,
                       float density, float brightness, float jets, boolean blackHoleEnabled, boolean backgroundInfall,
                       boolean ambientEnabled,
                       Direction facing, List<GuixuStellarEnd.GravityWave> stellarWaves, int accretionTrails, float diskHeat,
                       boolean legacyBlackHole) {
        if (!blackHoleEnabled && !ambientEnabled) return;
        if (ambientEnabled && blackHoleEnabled) GuixuDeepSpaceBackdrop.render(pose, camera, surfaceRadius, legacyBlackHole);
        var layer = HostFxDraw.beamLayer();
        var buffers = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        VertexConsumer vertices = buffers.getBuffer(layer);
        Matrix4f matrix = pose.last().pose();
        Matrix4f clip = new Matrix4f(RenderSystem.getProjectionMatrix()).mul(matrix);
        Vector3f head = new Vector3f(), tail = new Vector3f(), view = new Vector3f();
        Vector3f right = new Vector3f(), up = new Vector3f(), width = new Vector3f();
        for (int i = 0; backgroundInfall && i < GuixuStarField.PARTICLE_COUNT; i++) {
            float visibility = Math.max(0.0F, Math.min(1.0F, (density * GuixuStarField.PARTICLE_COUNT - i) / 8.0F));
            if (visibility <= 0) break;
            var star = GuixuStarField.sample(i, seconds, surfaceRadius);
            float alpha = star.alpha() * visibility * brightness;
            if (alpha <= 0.001F || !visible(clip, star)) continue;
            head.set((float) star.headX(), (float) star.headY(), (float) star.headZ());
            tail.set((float) star.tailX(), (float) star.tailY(), (float) star.tailZ());
            view.set(camera[0], camera[1], camera[2]).sub(head);
            if (view.lengthSquared() < 1.0e-8F) view.set(0, 0, 1);
            view.normalize();
            right.set(view).cross(0, 1, 0);
            if (right.lengthSquared() < 1.0e-8F) right.set(1, 0, 0);
            right.normalize();
            up.set(right).cross(view).normalize();
            float red = star.warm() ? 1.0F : 0.56F;
            float green = star.warm() ? 0.78F : 0.80F;
            float blue = star.warm() ? 0.48F : 1.0F;

            width.set(tail).sub(head).cross(view);
            if (width.lengthSquared() < 1.0e-8F) width.set(right);
            width.normalize().mul(star.size() * 0.38F);
            // 尾端透明，星點所在的前端明亮；拖尾跟隨軌跡而非固定朝向。
            ambientVertex(vertices, matrix, tail.x + width.x, tail.y + width.y, tail.z + width.z, red, green, blue, 0);
            ambientVertex(vertices, matrix, head.x + width.x, head.y + width.y, head.z + width.z, red, green, blue, alpha * 0.65F);
            ambientVertex(vertices, matrix, head.x - width.x, head.y - width.y, head.z - width.z, red, green, blue, alpha * 0.65F);
            ambientVertex(vertices, matrix, tail.x - width.x, tail.y - width.y, tail.z - width.z, red, green, blue, 0);
            ambientGlint(vertices, matrix, head, right, up, star.size() * 2.0F, star.size() * 0.22F,
                    red, green, blue, alpha * 0.65F);
            ambientGlint(vertices, matrix, head, right, up, star.size() * 0.22F, star.size() * 2.0F,
                    red, green, blue, alpha * 0.65F);
            ambientGlint(vertices, matrix, head, right, up, star.size() * 0.55F, star.size() * 0.55F,
                    1.0F, 0.96F, 0.90F, alpha);
        }
        if (backgroundInfall) {
            drawAccretionTrails(vertices, matrix, clip, camera, facing, seconds, surfaceRadius, accretionTrails, diskHeat);
        }
        if (blackHoleEnabled && jets > 0.001F) {
            drawJets(vertices, matrix, clip, camera, facing, seconds, surfaceRadius, jets);
        }
        if (ambientEnabled) {
            drawAmbient(vertices, matrix, clip, camera, HostFxSwitch.spinSeconds(), blackHoleEnabled ? surfaceRadius : 0,
                    density, brightness, HostFxSwitch.degraded(), stellarWaves);
        }
        buffers.endBatch(layer);
    }

    static Quaternionf diskRotation(Direction facing) {
        // 环心已套机器朝向，局部旋转还原到同一个世界盘面。
        Vector3f axis = new Vector3f(BlackHoleLensSpec.diskNormal())
                .rotateY((float) -Math.toRadians(HostFxGeometry.yawDegrees(facing)));
        return new Quaternionf().rotationTo(0, 1, 0, axis.x, axis.y, axis.z);
    }

    static void drawAccretionTrails(VertexConsumer vertices, Matrix4f matrix, Matrix4f clip, float[] camera,
                                    Direction facing, double seconds, double horizon, int count, float heat) {
        // The widest orbit spans 4.14 horizons radially and .85 vertically.
        // Include the billboard width before rotating into the machine frame.
        if (count == 0 || !HostFxVisibility.isVisible(clip, 0, 0, 0, (float) (4.3 * horizon + .1))) return;
        var frustum = new FrustumIntersection(clip, false);
        Quaternionf rotation = diskRotation(facing);
        Vector3f head = new Vector3f(), start = new Vector3f(), end = new Vector3f();
        Vector3f eye = new Vector3f(camera), sight = new Vector3f(), width = new Vector3f(), up = new Vector3f();
        for (int i = 0; i < count; i++) {
            double phase = GuixuStarField.accretionPhase(i, seconds);
            float opacity = (float) Math.min(1, Math.min(phase / .07, (1 - phase) / .085));
            if (opacity < .01F) continue;
            accretionPoint(head, rotation, i, phase, horizon);
            float hot = Math.clamp(.72F * (float) phase + .52F * heat, 0, 1);
            float red = 1F - hot * .10F, green = .46F + hot * .43F, blue = .19F + hot * .77F;
            float size = (float) (horizon * .011 + .035) * (1F + .42F * hot);
            float glow = opacity * (.40F + .47F * hot);
            double tail = Math.max(0, phase - .19);
            accretionPoint(start, rotation, i, tail, horizon);
            for (int segment = 0; segment < 5; segment++) {
                double fraction = (segment + 1) / 5.0;
                if (segment == 4) end.set(head);
                else accretionPoint(end, rotation, i, tail + (phase - tail) * fraction, horizon);
                if (!frustum.testAab(Math.min(start.x, end.x) - size, Math.min(start.y, end.y) - size,
                        Math.min(start.z, end.z) - size, Math.max(start.x, end.x) + size,
                        Math.max(start.y, end.y) + size, Math.max(start.z, end.z) + size)) {
                    start.set(end);
                    continue;
                }
                width.set(end).sub(start).cross(sight.set(eye).sub(end));
                if (width.lengthSquared() > 1e-9F) {
                    width.normalize().mul(size * (.28F + .72F * (float) fraction));
                    float a = glow * segment / 5F, b = glow * (float) fraction;
                    ambientVertex(vertices, matrix, start.x + width.x, start.y + width.y, start.z + width.z, red, green, blue, a);
                    ambientVertex(vertices, matrix, end.x + width.x, end.y + width.y, end.z + width.z, red, green, blue, b);
                    ambientVertex(vertices, matrix, end.x - width.x, end.y - width.y, end.z - width.z, red, green, blue, b);
                    ambientVertex(vertices, matrix, start.x - width.x, start.y - width.y, start.z - width.z, red, green, blue, a);
                }
                start.set(end);
            }
            if (!HostFxVisibility.isVisible(clip, head.x, head.y, head.z, size * 1.55F)) continue;
            sight.set(eye).sub(head);
            width.set(sight).cross(0, 1, 0);
            if (width.lengthSquared() < 1e-9F) width.set(1, 0, 0);
            if (sight.lengthSquared() < 1e-9F) sight.set(0, 0, 1);
            up.set(width.normalize()).cross(sight).normalize();
            ambientGlint(vertices, matrix, head, width, up, size * 1.55F, size * 1.55F, red, green, blue, glow);
        }
    }

    private static void accretionPoint(Vector3f out, Quaternionf rotation, int index, double phase, double horizon) {
        var point = GuixuStarField.accretionPosition(index, phase, horizon);
        rotation.transform(out.set((float) point.x(), (float) point.y(), (float) point.z()));
    }

    /** 山海：共用一批頂點；背景、星塵與流星均有固定數量上限，不建立粒子實體。 */
    private static void drawAmbient(VertexConsumer vertices, Matrix4f matrix, Matrix4f clip, float[] camera,
                                    double seconds, double horizon, float density, float brightness, boolean degraded,
                                    List<GuixuStellarEnd.GravityWave> stellarWaves) {
        int[] counts = {GuixuStarField.skyCount(density, degraded),
                degraded ? 0 : Math.round(GuixuStarField.ORBIT_COUNT * Math.max(0.25F, Math.min(1F, density))),
                degraded ? 0 : GuixuStarField.METEOR_COUNT,
                horizon > 0 ? GuixuStarField.volumeCount(density, degraded) : 0,
                horizon > 0 ? GuixuStarField.dustCount(density, degraded) : 0};
        float light = (0.55F + 0.45F * brightness) * (degraded ? 0.55F : 1.0F);
        Vector3f center = new Vector3f(), view = new Vector3f(), right = new Vector3f();
        Vector3f up = new Vector3f(), width = new Vector3f();
        for (int kind = 0; kind < counts.length; kind++) {
            for (int i = 0; i < counts[kind]; i++) {
                var star = switch (kind) {
                    case 0 -> GuixuStarField.sampleSky(i, seconds);
                    case 1 -> GuixuStarField.sampleOrbit(i, seconds);
                    case 2 -> GuixuStarField.sampleMeteor(i, seconds);
                    case 3 -> GuixuStarField.sampleVolume(i, seconds, horizon);
                    default -> GuixuStarField.sampleDust(i, seconds, horizon);
                };
                float alpha = star.alpha() * light;
                if (alpha <= 0.001F) continue;
                GuixuStellarEnd.StarBend bend = null;
                if (!stellarWaves.isEmpty()) {
                    bend = strongestBend(stellarWaves, camera, star.headX(), star.headY(), star.headZ());
                    var tail = kind == 1 || kind == 2
                            ? strongestBend(stellarWaves, camera, star.tailX(), star.tailY(), star.tailZ()) : bend;
                    if (bend != null || tail != null) star = refract(star, bend, tail);
                }
                // These stars enter SceneColor before the Kerr pass. Rays that escape
                // still see the sky when the camera is inside the apparent shadow radius.
                if (!visible(clip, star)) continue;
                center.set((float) star.headX(), (float) star.headY(), (float) star.headZ());
                view.set(camera[0], camera[1], camera[2]).sub(center);
                if (kind >= 3) {
                    float distanceSquared = view.lengthSquared();
                    if (distanceSquared <= 1.5F * 1.5F) continue;
                    if (distanceSquared < 5.5F * 5.5F) alpha *= ((float) Math.sqrt(distanceSquared) - 1.5F) / 4F;
                }
                if (view.lengthSquared() < 1.0e-8F) view.set(0, 0, 1);
                view.normalize();
                right.set(-view.z, 0, view.x);
                if (right.lengthSquared() < 1.0e-8F) right.set(1, 0, 0);
                right.normalize();
                up.set(right).cross(view).normalize();
                if (kind == 4) {
                    // 四層漸淡菱形形成柔和星塵，不使用覆蓋整個視野的大型發光面。
                    for (int shell = 0; shell < 4; shell++) {
                        float size = star.size() * (1 - shell * .23F);
                        ambientGlint(vertices, matrix, center, right, up, size, size,
                                .46F, .40F, .82F, alpha * (.08F + shell * .06F));
                    }
                    continue;
                }
                float red = star.warm() ? 1F : 0.55F;
                float green = star.warm() ? 0.82F : 0.80F;
                float blue = star.warm() ? 0.58F : 1F;
                if (kind == 1 || kind == 2) {
                    width.set((float) star.tailX(), (float) star.tailY(), (float) star.tailZ()).sub(center).cross(view);
                    if (width.lengthSquared() < 1.0e-8F) width.set(right);
                    width.normalize().mul(star.size() * 0.5F);
                    ambientVertex(vertices, matrix, (float) star.tailX() + width.x, (float) star.tailY() + width.y,
                            (float) star.tailZ() + width.z, red, green, blue, 0);
                    ambientVertex(vertices, matrix, center.x + width.x, center.y + width.y, center.z + width.z,
                            red, green, blue, alpha * 0.8F);
                    ambientVertex(vertices, matrix, center.x - width.x, center.y - width.y, center.z - width.z,
                            red, green, blue, alpha * 0.8F);
                    ambientVertex(vertices, matrix, (float) star.tailX() - width.x, (float) star.tailY() - width.y,
                            (float) star.tailZ() - width.z, red, green, blue, 0);
                }
                if (kind == 1 || kind == 2 || (kind == 3 ? i % 31 == 0 : i % 17 == 0)) {
                    ambientGlint(vertices, matrix, center, right, up, star.size() * 2.6F, star.size() * 0.16F,
                            red, green, blue, alpha * 0.7F);
                    ambientGlint(vertices, matrix, center, right, up, star.size() * 0.16F, star.size() * 2.6F,
                            red, green, blue, alpha * 0.7F);
                }
                ambientGlint(vertices, matrix, center, right, up, star.size() * 0.7F, star.size() * 0.7F,
                        0.95F, 0.97F, 1F, alpha);
                if (bend != null && bend.strength() > .012F) {
                    width.set(view).cross(center);
                    if (width.lengthSquared() < 1e-8F) width.set(right);
                    width.normalize();
                    up.set(width).cross(view);
                    float stretch = star.size() * (1.35F + 7.7F * bend.strength());
                    ambientGlint(vertices, matrix, center, width, up, stretch, star.size() * .29F,
                            .51F, .74F, 1F, Math.min(1, alpha * (1.3F * bend.strength())));
                }
            }
        }
    }

    private static GuixuStellarEnd.StarBend strongestBend(List<GuixuStellarEnd.GravityWave> waves,
                                                         float[] camera, double x, double y, double z) {
        GuixuStellarEnd.StarBend strongest = null;
        for (var wave : waves) {
            var bend = wave.bend(camera, x, y, z);
            if (bend != null && (strongest == null || bend.strength() > strongest.strength())) strongest = bend;
        }
        return strongest;
    }

    private static GuixuStarField.Sample refract(GuixuStarField.Sample star, GuixuStellarEnd.StarBend head,
                                                GuixuStellarEnd.StarBend tail) {
        double headX = star.headX(), headY = star.headY(), headZ = star.headZ();
        double tailX = star.tailX(), tailY = star.tailY(), tailZ = star.tailZ();
        if (head != null) {
            headX += head.x();
            headY += head.y();
            headZ += head.z();
        }
        if (tail != null) {
            tailX += tail.x();
            tailY += tail.y();
            tailZ += tail.z();
        }
        return new GuixuStarField.Sample(headX, headY, headZ, tailX, tailY, tailZ, star.size(), star.alpha(), star.warm());
    }

    /** 重用迴圈中的向量，避免每個星芒頂點再分配 Vector3f。 */
    static void ambientGlint(VertexConsumer vertices, Matrix4f matrix, Vector3f center,
                                     Vector3f right, Vector3f up, float horizontal, float vertical,
                                     float red, float green, float blue, float alpha) {
        ambientVertex(vertices, matrix, center.x + right.x * horizontal, center.y + right.y * horizontal,
                center.z + right.z * horizontal, red, green, blue, alpha);
        ambientVertex(vertices, matrix, center.x + up.x * vertical, center.y + up.y * vertical,
                center.z + up.z * vertical, red, green, blue, alpha);
        ambientVertex(vertices, matrix, center.x - right.x * horizontal, center.y - right.y * horizontal,
                center.z - right.z * horizontal, red, green, blue, alpha);
        ambientVertex(vertices, matrix, center.x - up.x * vertical, center.y - up.y * vertical,
                center.z - up.z * vertical, red, green, blue, alpha);
    }

    static void ambientVertex(VertexConsumer vertices, Matrix4f matrix, float x, float y, float z,
                                      float red, float green, float blue, float alpha) {
        vertices.vertex(matrix, x, y, z).color(red, green, blue, alpha).endVertex();
    }

    private static boolean visible(Matrix4f clip, GuixuStarField.Sample star) {
        float x = (float) ((star.headX() + star.tailX()) * 0.5);
        float y = (float) ((star.headY() + star.tailY()) * 0.5);
        float z = (float) ((star.headZ() + star.tailZ()) * 0.5);
        float trail = (float) Math.max(Math.abs(star.headX() - star.tailX()),
                Math.max(Math.abs(star.headY() - star.tailY()), Math.abs(star.headZ() - star.tailZ()))) * 0.5F;
        return HostFxVisibility.isVisible(clip, x, y, z, trail + star.size() * 2.6F);
    }

    /** 沿主黑洞的盘法线画有螺旋亮纹的双极外流，沿用星场加性层。 */
    static void drawJets(VertexConsumer vertices, Matrix4f matrix, Matrix4f clip, float[] camera, Direction facing,
                         double seconds, double horizon, float strength) {
        Quaternionf rotation = diskRotation(facing);
        Vector3f axis = rotation.transform(new Vector3f(0, 1, 0));
        Matrix4f jetMatrix = new Matrix4f(matrix).rotate(rotation);
        var frustum = new FrustumIntersection(new Matrix4f(clip).rotate(rotation), false);
        Vector3f view = new Vector3f(camera);
        float alignment = axis.dot(view) / Math.max(view.length(), 1e-4F);
        double phase = seconds * (9.0 * HORIZON_OMEGA);
        var start = new JetSection();
        var end = new JetSection();
        for (int sign = -1; sign <= 1; sign += 2) {
            float base = sign * (float) (horizon + .5);
            if (!jetVisible(frustum, base, base + sign * 28F)) continue;
            // 内段仍在加速，适度减弱失束侧的衰减，让两极都可以看见。
            double shift = GuixuQuasarOptics.jetShift(alignment * sign * 0.72);
            float blueShift = Math.clamp((float) (shift * 0.76), 0.0F, 1.0F);
            boolean contiguous = false;
            for (int segment = 0; segment < 20; segment++) {
                float t0 = segment / 20F, t1 = (segment + 1) / 20F;
                float y0 = sign * (float) (horizon + .5 + t0 * 28), y1 = sign * (float) (horizon + .5 + t1 * 28);
                if (!jetVisible(frustum, y0, y1)) {
                    contiguous = false;
                    continue;
                }
                if (!contiguous) start.sample(t0, y0, seconds, strength, phase, shift);
                end.sample(t1, y1, seconds, strength, phase, shift);
                for (int facet = 0; facet < 8; facet++) {
                    int next = (facet + 1) & 7;
                    start.vertex(vertices, jetMatrix, facet, blueShift);
                    start.vertex(vertices, jetMatrix, next, blueShift);
                    end.vertex(vertices, jetMatrix, next, blueShift);
                    end.vertex(vertices, jetMatrix, facet, blueShift);
                }
                JetSection previous = start;
                start = end;
                end = previous;
                contiguous = true;
            }
        }
    }

    private static boolean jetVisible(FrustumIntersection frustum, float y0, float y1) {
        // Every octagonal section has radius <= .77 in jet-local coordinates.
        return frustum.testAab(-.78F, Math.min(y0, y1), -.78F, .78F, Math.max(y0, y1), .78F);
    }

    private static final class JetSection {
        float distance, radius;
        final float[] alpha = new float[4]; // opposite vertices share a crest

        void sample(float t, float distance, double seconds, float strength, double phase, double shift) {
            this.distance = distance;
            radius = .15F + .62F * (float) Math.sqrt(t);
            double knots = .72 + .28 * Math.cos(seconds * 4.5 - t * 21.0);
            double emission = JET_POWER * strength * Math.sin(t * Math.PI) * knots;
            for (int i = 0; i < alpha.length; i++) {
                double crest = .5 + .5 * Math.cos(i * Math.PI * .5 + 2 * (t * 16.0 - phase));
                alpha[i] = GuixuQuasarOptics.beamedLight(emission * (.22 + 1.08 * crest * crest), shift);
            }
        }

        void vertex(VertexConsumer vertices, Matrix4f matrix, int facet, float blueShift) {
            vertices.vertex(matrix, radius * JET_X[facet], distance, radius * JET_X[(facet + 2) & 7])
                    .color(.68F - .34F * blueShift, .48F + .43F * blueShift, 1F, alpha[facet & 3]).endVertex();
        }
    }

}
