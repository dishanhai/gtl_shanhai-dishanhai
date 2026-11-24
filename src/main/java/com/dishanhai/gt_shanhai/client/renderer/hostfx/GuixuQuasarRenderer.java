// Author: dishanhai
package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import static com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuStarRenderer.ambientVertex;

/** 超级类星体的发光盘面和近场透镜像，沿用归墟天体的深度与加性批次。 */
final class GuixuQuasarRenderer {
    private static final double[] DISK_RADII = {
            GuixuQuasarOptics.INNER_EDGE, 1.24, 1.39, 1.57, 1.80, 2.10, 2.48, 2.97, 3.61, 4.43, 5.45
    };
    private static final float[] ARC_EDGES = {0, .19F, .40F, .60F, .80F, 1F};
    private static final float[] ARC_EMISSION = {0, .18F, .89F, .50F, .12F, 0};
    private static final float[] RING_RADII = {1.001F, 1.017F, 1.032F, 1.067F, 1.14F};
    private static final float[] RING_EMISSION = {0, .46F, .19F, .055F, 0};
    private static final int SAMPLE_SIZE = 5;
    // 与天体球面的顶点缓冲一样，只在渲染线程一次绘制中复用，不跨帧保存采样值。
    private static float[] firstRow = new float[73 * SAMPLE_SIZE];
    private static float[] secondRow = new float[73 * SAMPLE_SIZE];

    private final Matrix4f matrix;
    private final Vector3f center, sight, diskX, diskY, pole;
    private final Vector3f skyRight = new Vector3f(), skyUp = new Vector3f();
    private final Vector3f point = new Vector3f();
    private final double seconds, phase;
    private final float radius, power, flare, reach, distance, disruption, tideX, tideY, tideHeight;

    GuixuQuasarRenderer(Matrix4f matrix, Vector3f center, Vector3f view, Quaternionf axis, double seconds,
                       GuixuStellarEnd.Star star, GuixuStellarEnd.QuasarIgnition ignition) {
        this.matrix = matrix;
        this.center = center;
        this.seconds = seconds - GuixuStellarEnd.QUASAR_ENTRY_SECONDS;
        this.phase = star.phase();
        this.radius = star.solidRadius();
        this.disruption = star.disruption();
        this.power = star.brightness() * ignition.disk() * (1 - .46F * disruption);
        this.flare = ignition.flare();
        this.reach = ignition.jetReach();
        distance = view.length();
        diskX = new Vector3f(1, 0, 0).rotate(axis);
        diskY = new Vector3f(0, 0, -1).rotate(axis);
        pole = new Vector3f(0, 1, 0).rotate(axis);
        float inverseDistanceToHole = -1F / center.length();
        tideX = diskX.dot(center) * inverseDistanceToHole;
        tideY = diskY.dot(center) * inverseDistanceToHole;
        tideHeight = pole.dot(center) * inverseDistanceToHole;
        sight = distance > 1e-4F ? new Vector3f(view).div(distance) : new Vector3f(pole);
    }

    void draw(VertexConsumer vertices, int segments) {
        if (power <= .001F) return;
        int rowSize = Math.max(segments + 1, RING_RADII.length + ARC_EDGES.length) * SAMPLE_SIZE;
        if (firstRow.length < rowSize) {
            firstRow = new float[rowSize];
            secondRow = new float[rowSize];
        }
        double viewX = sight.dot(diskX), viewY = sight.dot(diskY);
        float[] inner = firstRow, outer = secondRow;
        diskRow(inner, DISK_RADII[0], segments, viewX, viewY);
        for (int band = 1; band < DISK_RADII.length; band++) {
            diskRow(outer, DISK_RADII[band], segments, viewX, viewY);
            for (int i = 0; i < segments; i++) {
                emitQuad(vertices, inner, outer, i * SAMPLE_SIZE, (i + 1) * SAMPLE_SIZE);
            }
            float[] swap = inner;
            inner = outer;
            outer = swap;
        }
        if (distance > radius) drawLensedImages(vertices, segments);
    }

    private void diskRow(float[] row, double r, int segments, double viewX, double viewY) {
        double shear = seconds * GuixuQuasarOptics.angularSpeed(r);
        double spiral = 5.4 * Math.log(r);
        double front = 1.23 + reach * 2.60;
        double heating = 1 + 1.25 * flare * Math.max(0, 1 - 2.6 * Math.abs(r - front));
        double outerFade = Math.clamp((DISK_RADII[DISK_RADII.length - 1] - r) / .95, 0, 1);
        double radialFlux = GuixuQuasarOptics.diskFlux(r);
        double heatedFlux = radialFlux * outerFade * heating;
        double temperature = Math.sqrt(radialFlux) / Math.sqrt(r / 1.57);
        double exposed = disruption * GuixuStellarEnd.ease(1.35, 4.1, r);
        for (int i = 0; i <= segments; i++) {
            double angle = i * Math.TAU / segments;
            double sin = Math.sin(angle), cos = Math.cos(angle);
            double flow = angle - shear;
            double filament = .5 + .5 * Math.cos(3 * flow + spiral + phase);
            double eddy = .5 + .5 * Math.cos(8 * flow - 4.1 * r + 2 * phase);
            double shift = GuixuQuasarOptics.diskShift(r, -sin * viewX + cos * viewY);
            double facingTide = Math.max(0, cos * tideX + sin * tideY);
            double diskRadius = r * radius * (1 - .14 * exposed * (1 - facingTide));
            float height = (float) (.035 * r * Math.sin(angle * 2 + phase) + .018 * (eddy - .5)
                    + .33 * r * exposed * facingTide * tideHeight);
            point.set(center).fma((float) (diskRadius * cos), diskX).fma((float) (diskRadius * sin), diskY)
                    .fma(height * radius, pole);
            double density = 1 - exposed * (.73 + .26 * facingTide);
            double flux = heatedFlux * (.32 + .52 * filament + .16 * eddy) * density;
            store(row, i * SAMPLE_SIZE, temperature * shift, GuixuQuasarOptics.beamedLight(power * flux * 2.15, shift));
        }
    }

    private void drawLensedImages(VertexConsumer vertices, int segments) {
        skyRight.set(pole).cross(sight);
        if (skyRight.lengthSquared() < 1e-8F) skyRight.set(diskX);
        skyRight.normalize();
        skyUp.set(sight).cross(skyRight);
        float inclination = Math.clamp(pole.dot(sight), -1F, 1F);
        float offset = GuixuStellarEnd.limbOffset(radius, distance);
        float limb = GuixuStellarEnd.limbRadius(radius, distance);
        boolean arc = Math.abs(inclination) < .998F;
        double sinInclination = Math.sqrt(1 - inclination * inclination);
        float[] from = firstRow, to = secondRow;
        lensRow(from, 0, offset, limb, inclination, sinInclination, arc);
        for (int i = 0; i < segments; i++) {
            lensRow(to, (i + 1) * Math.TAU / segments, offset, limb, inclination, sinInclination, arc);
            for (int edge = 1; edge < RING_RADII.length; edge++) {
                emitLensQuad(vertices, from, to, edge);
            }
            if (arc) {
                for (int edge = 1; edge < ARC_EDGES.length; edge++) {
                    emitLensQuad(vertices, from, to, edge + RING_RADII.length);
                }
            }
            float[] swap = from;
            from = to;
            to = swap;
        }
    }

    private void lensRow(float[] row, double angle, float offset, float limb, float inclination,
                         double sinInclination, boolean arc) {
        double sin = Math.sin(angle), cos = Math.cos(angle);
        double shift = GuixuQuasarOptics.diskShift(1.65, -cos * sinInclination);
        double filament = .75 + .25 * Math.cos(4 * angle - seconds * 1.8 + phase);
        double gain = 1.65 + .5 * flare;
        for (int edge = 0; edge < RING_RADII.length; edge++) {
            lensPoint(offset, limb, RING_RADII[edge], sin, cos);
            store(row, edge * SAMPLE_SIZE, 1.04 * shift,
                    GuixuQuasarOptics.beamedLight(power * (double) RING_EMISSION[edge] * filament * gain, shift));
        }
        if (!arc) return;
        double width = GuixuQuasarOptics.arcWidth(inclination, sin);
        for (int edge = 0; edge < ARC_EDGES.length; edge++) {
            lensPoint(offset, limb, 1.033 + ARC_EDGES[edge] * width, sin, cos);
            double emission = ARC_EMISSION[edge] * Math.min(1, width * 2) * (.75 + .25 * inclination * sin);
            store(row, (RING_RADII.length + edge) * SAMPLE_SIZE, .94 * shift,
                    GuixuQuasarOptics.beamedLight(power * emission * filament * gain, shift));
        }
    }

    private void lensPoint(float offset, float limb, double scale, double sin, double cos) {
        point.set(center).fma(offset, sight).fma((float) (limb * scale * cos), skyRight)
                .fma((float) (limb * scale * sin), skyUp);
    }

    private void store(float[] row, int sample, double temperature, float light) {
        row[sample] = point.x;
        row[sample + 1] = point.y;
        row[sample + 2] = point.z;
        row[sample + 3] = (float) Math.clamp(temperature, 0, 1.25);
        row[sample + 4] = light;
    }

    private void emitQuad(VertexConsumer vertices, float[] inner, float[] outer, int from, int to) {
        emit(vertices, inner, from);
        emit(vertices, inner, to);
        emit(vertices, outer, to);
        emit(vertices, outer, from);
    }

    private void emitLensQuad(VertexConsumer vertices, float[] from, float[] to, int edge) {
        int inner = (edge - 1) * SAMPLE_SIZE, outer = edge * SAMPLE_SIZE;
        emit(vertices, from, inner);
        emit(vertices, to, inner);
        emit(vertices, to, outer);
        emit(vertices, from, outer);
    }

    private void emit(VertexConsumer vertices, float[] row, int sample) {
        float heat = row[sample + 3];
        float red = 1F - .21F * Math.max(0, heat - .78F);
        float green = Math.min(1F, .23F + .76F * heat);
        float blue = Math.min(1F, .09F + .83F * heat * heat);
        ambientVertex(vertices, matrix, row[sample], row[sample + 1], row[sample + 2], red, green, blue, row[sample + 4]);
    }
}
