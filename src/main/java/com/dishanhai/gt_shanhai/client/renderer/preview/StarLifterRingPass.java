package com.dishanhai.gt_shanhai.client.renderer.preview;

import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterGeometry;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterInstallation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3d;

final class StarLifterRingPass {
    static final double RANGE = 8;
    private static final double PASS_SPEED = 0.035;
    private static final int OUTER_SERVICE_BAYS = 15;
    private static final double OUTER_BAY_ARC = 2 * Math.PI / OUTER_SERVICE_BAYS;
    private static final double SERVICE_NODE_ANGLE = OUTER_BAY_ARC * 0.046;

    private StarLifterRingPass() {}

    static Vec3 crossing(StarLifterInstallation installation, Vec3 listener, long tick) {
        double scale = installation.radius();
        Vec3 relative = listener.subtract(installation.center()).scale(1 / scale);
        double radius = relative.length();
        double pad = RANGE / scale;
        if (radius < StarLifterGeometry.RINGS.getFirst().innerLimit() - pad
                || radius > StarLifterGeometry.OUTER_RADIUS + pad + .02) return null;
        double elapsed = tick - installation.startTick();
        if (elapsed < 1) return null;

        Quaternionf heading = new Quaternionf().rotationY((float) Math.toRadians(installation.heading()));
        Vec3 nearest = null;
        double closest = RANGE;
        for (int i = 0; i < StarLifterGeometry.RINGS.size(); i++) {
            var ring = StarLifterGeometry.RINGS.get(i);
            boolean outer = i == StarLifterGeometry.OUTER_RING;
            Quaternionf orientation = new Quaternionf(heading).mul(ring.orientation(elapsed));
            Vector3d point = nearest(orientation, ring, outer, relative);
            double distance = point.distance(relative.x, relative.y, relative.z) * scale;
            if (distance >= closest) continue;
            // Use the same listener position at both times: walking up to the ring alone is not a pass.
            Quaternionf previous = new Quaternionf(heading).mul(ring.orientation(elapsed - 1));
            double before = nearest(previous, ring, outer, relative).distance(relative.x, relative.y, relative.z) * scale;
            if (before < RANGE || before - distance < PASS_SPEED) continue;
            closest = distance;
            nearest = installation.center().add(point.x * scale, point.y * scale, point.z * scale);
        }
        return nearest;
    }

    private static Vector3d nearest(Quaternionf orientation, StarLifterGeometry.Ring ring, boolean outer, Vec3 eye) {
        var local = orientation.transformInverse(new Vector3d(eye.x, eye.y, eye.z));
        double radial = Math.hypot(local.x, local.z);
        double angle = Math.atan2(local.z, local.x);
        if (outer) {
            // A circular rim has no moving silhouette. Its repeating raised service nodes do.
            angle -= Math.IEEEremainder(angle - SERVICE_NODE_ANGLE, OUTER_BAY_ARC);
            radial = ring.outerLimit();
        } else {
            radial = Math.clamp(radial, ring.innerLimit(), ring.outerLimit());
        }
        double halfHeight = ring.height() * .583;
        local.set(radial * Math.cos(angle), Math.clamp(local.y, -halfHeight, halfHeight), radial * Math.sin(angle));
        return orientation.transform(local);
    }
}
