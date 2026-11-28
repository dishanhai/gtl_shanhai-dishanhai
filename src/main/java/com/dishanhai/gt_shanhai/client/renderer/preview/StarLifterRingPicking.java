package com.dishanhai.gt_shanhai.client.renderer.preview;

import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterGeometry;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterGeometry.Point;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterStructure;
import org.joml.Intersectionf;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** Picks the same baked ring surfaces used for drawing, in each ring's current local coordinates. */
final class StarLifterRingPicking {
    private static final int SECTORS = 32;
    private static final float EPSILON = 1e-6f;
    private static final Vector2f INTERSECTION = new Vector2f();

    private StarLifterRingPicking() {}

    static int pick(Vector3f origin, Vector3f direction, StarLifterTerminalPreview.View view) {
        float radius = (float) StarLifterGeometry.OUTER_RADIUS + .02f;
        if (!Intersectionf.intersectRaySphere(origin, direction, new Vector3f(), radius * radius, INTERSECTION)) return -1;
        float start = Math.max(0, INTERSECTION.x);
        float end = INTERSECTION.y;
        float star = (float) StarLifterGeometry.STAR_RADIUS * StarLifterGeometry.photosphereScale(view.reserveFraction());
        float nearest = Intersectionf.intersectRaySphere(origin, direction, new Vector3f(), star * star, INTERSECTION)
                ? INTERSECTION.x : end;
        var heading = new Quaternionf().rotateY((float) Math.toRadians(-view.heading()));
        heading.transform(origin);
        heading.transform(direction);
        int hit = -1;
        for (int index = 0; index < StarLifterGeometry.RINGS.size(); index++) {
            if (view.isolate() && index != view.ring()) continue;
            var rotation = StarLifterGeometry.RINGS.get(index).orientation(view.ticks()).conjugate();
            var localOrigin = rotation.transform(new Vector3f(origin));
            var localDirection = rotation.transform(new Vector3f(direction));
            for (var sector : Meshes.RINGS.get(index)) {
                if (!Intersectionf.intersectRayAab(localOrigin, localDirection, sector.min, sector.max, INTERSECTION)
                        || INTERSECTION.x > nearest) continue;
                for (var face : sector.faces) {
                    float distance = face.intersect(localOrigin, localDirection);
                    if (distance >= 0 && distance < nearest) {
                        nearest = distance;
                        hit = index;
                    }
                }
            }
        }
        return hit >= 0 && !view.isolate() && fixedBefore(origin, direction, start, nearest) ? -1 : hit;
    }

    private static boolean fixedBefore(Vector3f origin, Vector3f direction, float start, float end) {
        float radius = StarLifterGeometry.DEFAULT_RADIUS;
        var point = new Vector3f(origin).fma(start, direction).mul(radius).add(.5f, .5f, .5f);
        int x = (int) Math.floor(point.x), y = (int) Math.floor(point.y), z = (int) Math.floor(point.z);
        int dx = direction.x > 0 ? 1 : -1, dy = direction.y > 0 ? 1 : -1, dz = direction.z > 0 ? 1 : -1;
        float tx = boundary(point.x, x, direction.x);
        float ty = boundary(point.y, y, direction.y);
        float tz = boundary(point.z, z, direction.z);
        float limit = (end - start) * radius;
        for (float at = 0; at < limit;) {
            char symbol = StarLifterStructure.previewSymbol(x, y, z);
            if (StarLifterStructure.isFixedPreviewBlock(symbol)) {
                if (symbol != 'M') return true;
                float half = (float) com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterInnerLatchBlock.HALF_WIDTH;
                var min = new Vector3f(x + .5f - half, y, z + .5f - half);
                var max = new Vector3f(x + .5f + half, y + 1, z + .5f + half);
                if (Intersectionf.intersectRayAab(point, direction, min, max, INTERSECTION) && INTERSECTION.x < limit) return true;
            }
            if (tx <= ty && tx <= tz) {
                at = tx;
                x += dx;
                tx += Math.abs(1 / direction.x);
            } else if (ty <= tz) {
                at = ty;
                y += dy;
                ty += Math.abs(1 / direction.y);
            } else {
                at = tz;
                z += dz;
                tz += Math.abs(1 / direction.z);
            }
        }
        return false;
    }

    private static float boundary(float position, int block, float direction) {
        return Math.abs(direction) < EPSILON ? Float.POSITIVE_INFINITY
                : ((direction > 0 ? block + 1 : block) - position) / direction;
    }

    private record Face(Vector3f a, Vector3f b, Vector3f c, Vector3f d) {
        float intersect(Vector3f origin, Vector3f direction) {
            float first = Intersectionf.intersectRayTriangle(origin, direction, a, b, c, EPSILON);
            float second = Intersectionf.intersectRayTriangle(origin, direction, a, c, d, EPSILON);
            return first < 0 ? second : second < 0 ? first : Math.min(first, second);
        }
    }

    private static final class Sector {
        final Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        final Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
        final List<Face> faces = new ArrayList<>();

        void add(Face face) {
            faces.add(face);
            min.min(face.a).min(face.b).min(face.c).min(face.d);
            max.max(face.a).max(face.b).max(face.c).max(face.d);
        }
    }

    private static final class Meshes {
        static final List<List<Sector>> RINGS = bake();

        private static List<List<Sector>> bake() {
            var rings = new ArrayList<List<Sector>>();
            for (int index = 0; index < StarLifterGeometry.RINGS.size(); index++) {
                var sectors = new ArrayList<Sector>(SECTORS);
                for (int i = 0; i < SECTORS; i++) sectors.add(new Sector());
                StarLifterGeometry.ring(index, (surface, a, b, c, d) -> {
                    double angle = Math.atan2(a.z() + b.z() + c.z() + d.z(), a.x() + b.x() + c.x() + d.x());
                    int sector = Math.floorMod((int) Math.floor(angle * SECTORS / (Math.PI * 2)), SECTORS);
                    sectors.get(sector).add(new Face(vector(a), vector(b), vector(c), vector(d)));
                });
                sectors.removeIf(sector -> sector.faces.isEmpty());
                rings.add(sectors);
            }
            return rings;
        }
    }

    private static Vector3f vector(Point point) {
        return new Vector3f((float) point.x(), (float) point.y(), (float) point.z());
    }
}
