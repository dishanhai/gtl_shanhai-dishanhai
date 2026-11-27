package com.dishanhai.gt_shanhai.client.renderer.machine;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** 山海：既有恒星表层外的流动星面、分叉光丝与向外扩散的波纹。 */
final class AetherophasicPlasma {
    // 恒星表层体素距球心最远约 26.36 格，装甲最远约 45.33 格。
    private static final double STELLAR_RADIUS = 26.55;
    private static final double CORONA_RADIUS = 46.8;
    private static final int MERIDIANS = 48;
    private static final int PARALLELS = 24;
    private static final Vec3[][] STAR_SPHERE = buildSphere(STELLAR_RADIUS);
    private static final Vec3[][] CORONA_SPHERE = buildSphere(CORONA_RADIUS);
    private static final Streamer[] STREAMERS = buildStreamers();
    private static final Vec3[] APERTURES = buildApertures();

    private AetherophasicPlasma() {}

    static void drawStar(VertexConsumer vertices, Matrix4f matrix, double progress) {
        int brightness = 166 + (int) (89 * progress);
        sphere(vertices, matrix, STAR_SPHERE, 0, brightness, brightness, brightness, 255);
    }

    static void drawCorona(VertexConsumer vertices, Matrix4f matrix, Vec3 camera, double seconds,
                           double progress, double completionAge) {
        double pulse = completionAge / 64;
        double flare = pulse >= 0 && pulse < 1 ? Math.sin(Math.PI * pulse) * (1 - pulse) : 0;
        sphere(vertices, matrix, CORONA_SPHERE, 4, 226, 239, 255,
                (int) Math.min(255, 57 + 198 * progress + 260 * flare));
        if (progress > 0) {
            double opacity = .20 + .80 * progress;
            for (Streamer streamer : STREAMERS) {
                ribbon(vertices, matrix, camera, seconds, streamer, 0, 1, 0, opacity);
                if (streamer.fork() != 0) {
                    ribbon(vertices, matrix, camera, seconds, streamer, .36, .86, streamer.fork(), opacity);
                }
            }
            wave(vertices, matrix, seconds / 14 % 1, .30, progress);
            wave(vertices, matrix, (seconds / 14 + .52) % 1, -.18, .60 * progress);
        }
        if (flare > 0) {
            wave(vertices, matrix, pulse, .08, 3.6);
        }
    }

    static void drawApertures(VertexConsumer vertices, Matrix4f matrix, Vec3 camera, double seconds, double progress,
                              Direction left, Direction up, Direction front) {
        Vec3 x = Vec3.atLowerCornerOf(left.getNormal());
        Vec3 y = Vec3.atLowerCornerOf(up.getNormal());
        Vec3 z = Vec3.atLowerCornerOf(front.getNormal());
        for (int i = 0; i < APERTURES.length; i++) {
            double charge = Mth.clamp(progress * APERTURES.length - i, 0, 1);
            if (charge == 0) break;
            Vec3 local = APERTURES[i];
            Vec3 radial = x.scale(local.x).add(y.scale(local.y)).add(z.scale(local.z));
            double phase = i * 2.39996323;
            double pulse = .5 + .5 * Math.sin(seconds * .94 + phase);
            double flash = pulse * pulse * pulse;
            Vec3 tangent = y.cross(radial).normalize();
            apertureGlow(vertices, matrix, camera, radial, flash, charge);
            double spread = 1.7 + 2.4 * flash;
            var plume = new Streamer(radial, tangent, radial.cross(tangent), 33.5, 15 + 14 * flash, spread,
                    phase, (i % 2 == 0 ? 1 : -1) * (5 + 8 * flash),
                    (i % 2 == 0 ? -1 : 1) * (2 + 3 * flash), true, false);
            ribbon(vertices, matrix, camera, seconds, plume, 0, 1, 0, charge);
            if (i % 3 == 0) {
                ribbon(vertices, matrix, camera, seconds, plume, .19, .81, plume.fork(), charge);
            }
        }
    }

    private static Vec3[] buildApertures() {
        // 结构 JSON 的 20 条视线可从装甲外直达恒星表层，坐标沿 LEFT / UP / FRONT。
        var apertures = new Vec3[20];
        int i = 0;
        for (int a : new int[]{-1, 1}) {
            for (int b : new int[]{-1, 1}) {
                apertures[i++] = new Vec3(11 * a, 4 * b, 0).normalize();
                apertures[i++] = new Vec3(0, 11 * a, 4 * b).normalize();
                apertures[i++] = new Vec3(4 * b, 0, 11 * a).normalize();
                apertures[i++] = new Vec3(a, b, 1).normalize();
                apertures[i++] = new Vec3(a, b, -1).normalize();
            }
        }
        return apertures;
    }

    private static void apertureGlow(VertexConsumer vertices, Matrix4f matrix, Vec3 camera, Vec3 radial,
                                     double flash, double charge) {
        Vec3 center = radial.scale(36.1);
        Vec3 view = camera.subtract(center).normalize();
        double facing = Math.max(0, view.dot(radial));
        double size = 6.5 + 2.3 * flash;
        Vec3 right = new Vec3(0, 1, 0).cross(view).normalize().scale(size);
        if (right.lengthSqr() == 0) right = new Vec3(size, 0, 0);
        Vec3 top = view.cross(right);
        int alpha = (int) ((83 + 122 * flash) * (.30 + .70 * facing) * charge);
        vertex(vertices, matrix, center.subtract(right).subtract(top), 6, 0, 255, 225, 192, alpha);
        vertex(vertices, matrix, center.add(right).subtract(top), 7, 0, 255, 225, 192, alpha);
        vertex(vertices, matrix, center.add(right).add(top), 7, 1, 255, 225, 192, alpha);
        vertex(vertices, matrix, center.subtract(right).add(top), 6, 1, 255, 225, 192, alpha);
    }

    private static Vec3[][] buildSphere(double radius) {
        var sphere = new Vec3[PARALLELS + 1][MERIDIANS + 1];
        for (int row = 0; row <= PARALLELS; row++) {
            double latitude = Math.PI * row / PARALLELS;
            for (int col = 0; col <= MERIDIANS; col++) {
                double longitude = 2 * Math.PI * col / MERIDIANS;
                sphere[row][col] = new Vec3(Math.cos(longitude) * Math.sin(latitude),
                        Math.cos(latitude), Math.sin(longitude) * Math.sin(latitude)).scale(radius);
            }
        }
        return sphere;
    }

    private static void sphere(VertexConsumer vertices, Matrix4f matrix, Vec3[][] sphere, float mode,
                               int red, int green, int blue, int alpha) {
        for (int row = 0; row < PARALLELS; row++) {
            float v0 = (float) row / PARALLELS;
            float v1 = (float) (row + 1) / PARALLELS;
            for (int col = 0; col < MERIDIANS; col++) {
                float u0 = mode + (float) col / MERIDIANS;
                float u1 = mode + (float) (col + 1) / MERIDIANS;
                vertex(vertices, matrix, sphere[row][col], u0, v0, red, green, blue, alpha);
                vertex(vertices, matrix, sphere[row][col + 1], u1, v0, red, green, blue, alpha);
                vertex(vertices, matrix, sphere[row + 1][col + 1], u1, v1, red, green, blue, alpha);
                vertex(vertices, matrix, sphere[row + 1][col], u0, v1, red, green, blue, alpha);
            }
        }
    }

    private record Streamer(Vec3 radial, Vec3 tangent, Vec3 binormal, double start, double length, double width,
                            double phase, double bend, double fork, boolean veil, boolean warm) {}

    private static Streamer[] buildStreamers() {
        var random = RandomSource.create(0xAE7E420FL);
        var streamers = new Streamer[222];
        for (int i = 0; i < streamers.length; i++) {
            double y = (i + .5) * 2 / streamers.length - 1;
            double azimuth = i * Math.PI * (3 - Math.sqrt(5)) + random.nextDouble() * .19;
            double horizontal = Math.sqrt(1 - y * y);
            Vec3 radial = new Vec3(horizontal * Math.cos(azimuth), y, horizontal * Math.sin(azimuth));
            Vec3 tangent = new Vec3(-Math.sin(azimuth), 0, Math.cos(azimuth));
            boolean veil = i % 7 == 0;
            boolean warm = i % 9 == 2;
            double start = warm ? 34 + 5 * random.nextDouble() : 38 + 4 * random.nextDouble();
            double length = veil ? 30 + 23 * random.nextDouble() : 11 + 23 * random.nextDouble();
            double width = veil ? 4 + 7 * random.nextDouble() : .27 + 1.16 * random.nextDouble();
            double fork = !veil && i % 3 == 0 ? (random.nextBoolean() ? 1 : -1) * (2 + 3 * random.nextDouble()) : 0;
            streamers[i] = new Streamer(radial, tangent, radial.cross(tangent), start, length, width,
                    random.nextDouble() * 6.283, (random.nextDouble() - .5) * (veil ? 49 : 16),
                    fork, veil, warm);
        }
        return streamers;
    }

    private static void ribbon(VertexConsumer vertices, Matrix4f matrix, Vec3 camera, double seconds,
                               Streamer streamer, double from, double to, double fork, double opacity) {
        int steps = fork == 0 ? 12 : 7;
        int mode = streamer.veil() ? 8 : 2;
        int red = streamer.warm() ? 255 : streamer.veil() ? 166 : 216;
        int green = streamer.warm() ? 212 : 234;
        int blue = streamer.warm() ? 149 : 255;
        Vec3 previousLeft = Vec3.ZERO;
        Vec3 previousRight = Vec3.ZERO;
        int previousAlpha = 0;
        float previousFlow = 0;
        double drift = seconds * .68 + streamer.phase();
        for (int i = 0; i <= steps; i++) {
            double q = (double) i / steps;
            double along = from + q * (to - from);
            double s = along * along;
            double radial = streamer.start() + along * streamer.length();
            double bend = s * (streamer.bend() + 3.5 * Math.sin(drift - 5 * along)) + fork * q * q;
            double curl = along * (1 + 3 * along) * Math.sin(streamer.phase() + along * 7 - seconds * .49);
            Vec3 point = streamer.radial().scale(radial).add(streamer.tangent().scale(bend))
                    .add(streamer.binormal().scale(curl));
            double spread = streamer.veil() ? .4 + 1.15 * along : 1 - .63 * q;
            double width = streamer.width() * spread * (fork == 0 ? 1 : .61);
            Vec3 side = streamer.radial().cross(camera.subtract(point)).normalize().scale(width);
            Vec3 left = point.subtract(side);
            Vec3 right = point.add(side);
            double life = .64 + .36 * Math.sin(drift * 1.32 - 6.3 * along);
            double envelope = Math.min(q * (fork == 0 ? 6 : 4), 1) * Math.pow(1 - q, streamer.veil() ? .9 : 1.2);
            int alpha = (int) (envelope * life * opacity * (streamer.veil() ? 115 : fork == 0 ? 205 : 123));
            float flow = (float) (along + streamer.phase());
            if (i > 0) {
                vertex(vertices, matrix, previousLeft, mode, previousFlow, red, green, blue, previousAlpha);
                vertex(vertices, matrix, previousRight, mode + 1, previousFlow, red, green, blue, previousAlpha);
                vertex(vertices, matrix, right, mode + 1, flow, red, green, blue, alpha);
                vertex(vertices, matrix, left, mode, flow, red, green, blue, alpha);
            }
            previousLeft = left;
            previousRight = right;
            previousAlpha = alpha;
            previousFlow = flow;
        }
    }

    private static void wave(VertexConsumer vertices, Matrix4f matrix, double progress, double tilt, double strength) {
        double radius = 42 + 51 * progress;
        double halfWidth = 2.5 + 6.5 * progress;
        for (int segment = 0; segment < 64; segment++) {
            waveVertex(vertices, matrix, segment, radius - halfWidth, 8, progress, tilt, strength);
            waveVertex(vertices, matrix, segment, radius + halfWidth, 9, progress, tilt, strength);
            waveVertex(vertices, matrix, segment + 1, radius + halfWidth, 9, progress, tilt, strength);
            waveVertex(vertices, matrix, segment + 1, radius - halfWidth, 8, progress, tilt, strength);
        }
    }

    private static void waveVertex(VertexConsumer vertices, Matrix4f matrix, int segment, double radius,
                                   float u, double progress, double tilt, double strength) {
        double angle = segment * Math.PI / 32;
        double x = Math.cos(angle), z = Math.sin(angle);
        Vec3 point = new Vec3(radius * x, radius * (tilt * x + .15 * z) + 2.5 * Math.sin(3 * angle), radius * z);
        double gaps = .51 + .31 * Math.sin(3 * angle + 1.7) + .18 * Math.cos(5 * angle);
        int alpha = (int) (95 * strength * Math.sin(Math.PI * progress) * (1 - progress) * gaps);
        vertex(vertices, matrix, point, u, (float) (angle * 3 / (2 * Math.PI)), 150, 195, 255, alpha);
    }

    private static void vertex(VertexConsumer vertices, Matrix4f matrix, Vec3 point, float u, float v,
                               int red, int green, int blue, int alpha) {
        vertices.vertex(matrix, (float) point.x, (float) point.y, (float) point.z)
                .uv(u, v).color(red, green, blue, alpha).endVertex();
    }
}
