package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiInstabilityGeometry.Point;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuStarRenderer.ambientGlint;
import static com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuStarRenderer.ambientVertex;

/** 终场能量先沿喷口与中央光柱外传，再沿成型结构表面逐段点亮。 */
final class GuixuStellarChargeRenderer {
    private final VertexConsumer vertices;
    private final Matrix4f matrix;
    private final Vector3f camera, view = new Vector3f(), side = new Vector3f(), up = new Vector3f();
    private final Vector3f start = new Vector3f(), end = new Vector3f();
    private final Vector3f a = new Vector3f(), b = new Vector3f(), point = new Vector3f();
    private final double seconds, elapsed;

    GuixuStellarChargeRenderer(VertexConsumer vertices, Matrix4f matrix, float[] camera, double seconds) {
        this.vertices = vertices;
        this.matrix = matrix;
        this.camera = new Vector3f(camera[0], camera[1], camera[2]);
        this.seconds = seconds;
        this.elapsed = seconds - GuixuStellarEnd.STRUCTURE_START_SECONDS;
    }

    void feeds(float horizon, float spinSeconds) {
        if (horizon <= 0 || elapsed > GuixuStellarEnd.STRUCTURE_RING_FEED + .85) return;
        float spin = HostFxGeometry.ringDeg(spinSeconds);
        for (int arm = 0; arm < HostFxGeometry.MD_COUNT; arm++) {
            float[] nozzle = HostFxGeometry.mdNozzle(arm);
            float[] rotated = HostFxGeometry.rotateYDeg(spin, nozzle[0], nozzle[1], nozzle[2]);
            end.set(rotated[0], rotated[1], rotated[2]);
            start.set(end).normalize(horizon * 1.035F);
            feed(GuixuStellarEnd.STRUCTURE_RING_FEED);
        }
        for (int sign = -1; sign <= 1; sign += 2) {
            start.set(0, sign * horizon * 1.035F, 0);
            end.set(0, sign * GuixuStellarEnd.PILLAR_BASE, 0);
            feed(GuixuStellarEnd.STRUCTURE_SHAFT_FEED);
        }
    }

    private void feed(double arrival) {
        float progress = (float) Math.clamp(elapsed / arrival, 0, 1);
        float light = (float) (Math.clamp(elapsed / .25, 0, 1) * Math.clamp(1 - (elapsed - arrival) / .85, 0, 1));
        if (light <= .001F) return;
        a.set(start);
        b.set(start).lerp(end, progress);
        beam(.65F, .30F * light, .84F * light);
        a.set(start).lerp(end, Math.max(0, progress - .23F));
        beam(.22F, .16F * light, light);
        node(b.x, b.y, b.z, light * .88F, 1.7F);
    }

    void surface(GuixuStructureLightning.Surface surface) {
        if (surface == null) return;
        Point[] centers = surface.centers();
        double[] arrivals = new double[centers.length];
        for (int i = 0; i < centers.length; i++) {
            var node = surface.nodes().get(i);
            // 传播顺序取模板坐标，旋转中的环带和舰体会带着已点亮的部位转动。
            arrivals[i] = GuixuStellarEnd.structureArrival(node.x(), node.y(), node.z());
        }
        for (var link : surface.links()) {
            int from = link.from(), to = link.to();
            if (arrivals[from] > arrivals[to]) {
                from = link.to();
                to = link.from();
            }
            if (centers[from] != null && centers[to] != null) {
                connection(centers[from], centers[to], arrivals[from], arrivals[to]);
            }
        }
        for (int i = 0; i < centers.length; i++) {
            Point p = centers[i];
            if (p == null || elapsed <= arrivals[i]) continue;
            double since = elapsed - arrivals[i];
            float charge = (float) GuixuStellarEnd.structureCharge(seconds, arrivals[i]);
            float wave = (float) Math.sin(Math.PI * Math.clamp(since / 1.42, 0, 1));
            float steady = charge * (.29F + .10F * (float) Math.sin(seconds * 1.4 - arrivals[i] + i * .21));
            node((float) p.x(), (float) p.y(), (float) p.z(), steady + wave * .59F, .69F + wave * .75F);
        }
    }

    private void connection(Point from, Point to, double first, double last) {
        double since = elapsed - first;
        if (since <= 0) return;
        load(start, from);
        load(end, to);
        if (start.distanceSquared(end) > 18 * 18) return;
        double travel = Math.max(.28, last - first);
        float progress = (float) Math.clamp(since / travel, 0, 1);
        float front = (float) (Math.clamp(since / .22, 0, 1) * Math.clamp(1 - (since - travel) / .8, 0, 1));
        a.set(start);
        b.set(start).lerp(end, progress);
        beam(.13F + front * .19F, progress * .17F, progress * .19F + front * .57F);
    }

    private void beam(float width, float first, float last) {
        view.set(camera).sub(b);
        side.set(b).sub(a).cross(view);
        if (side.lengthSquared() < 1e-8F) return;
        side.normalize(width);
        vertex(a, -1, .39F, .73F, 1F, 0);
        vertex(a, 0, .48F, .82F, 1F, first);
        vertex(b, 0, 1F, .92F, .73F, last);
        vertex(b, -1, .39F, .73F, 1F, 0);
        vertex(a, 0, .48F, .82F, 1F, first);
        vertex(a, 1, .39F, .73F, 1F, 0);
        vertex(b, 1, .39F, .73F, 1F, 0);
        vertex(b, 0, 1F, .92F, .73F, last);
    }

    private void node(float x, float y, float z, float light, float size) {
        point.set(x, y, z);
        view.set(camera).sub(point);
        if (view.lengthSquared() < 1e-8F) view.set(0, 0, 1);
        view.normalize();
        side.set(view.z, 0, -view.x);
        if (side.lengthSquared() < 1e-8F) side.set(1, 0, 0);
        side.normalize();
        up.set(view).cross(side);
        ambientGlint(vertices, matrix, point, side, up, size * 2.4F, size * .18F, .36F, .74F, 1F, light);
        ambientGlint(vertices, matrix, point, side, up, size * .21F, size * 1.72F, 1F, .71F, .30F, light * .68F);
        ambientGlint(vertices, matrix, point, side, up, size * .67F, size * .57F, 1F, .96F, .86F, light);
    }

    private void vertex(Vector3f p, float offset, float red, float green, float blue, float alpha) {
        ambientVertex(vertices, matrix, p.x + side.x * offset, p.y + side.y * offset, p.z + side.z * offset,
                red, green, blue, alpha);
    }

    private static void load(Vector3f vector, Point point) {
        vector.set((float) point.x(), (float) point.y(), (float) point.z());
    }
}
