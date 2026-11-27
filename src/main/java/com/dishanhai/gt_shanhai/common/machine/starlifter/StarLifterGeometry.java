package com.dishanhai.gt_shanhai.common.machine.starlifter;

import org.joml.Quaternionf;

import java.util.List;

/** 山海摘星者的局部空间网格。外环位于 XZ 平面，内环绕 XZ 平面内的轴翻转。 */
public final class StarLifterGeometry {
    public static final float DEFAULT_RADIUS = 68.0f;
    public static final double STAR_RADIUS = 0.435;
    public static final List<Ring> RINGS = List.of(
            new Ring(0.605, 0.104, 0.098, 39.0, 0.0, 1520, 0),
            new Ring(0.785, 0.130, 0.110, -43.0, 90.0, -2120, 0),
            new Ring(0.970, 0.154, 0.126, 12.0, 37.0, 2680, 0),
            new Ring(1.180, 0.175, 0.142, 0.0, 0.0, 0, -2780));
    public static final int OUTER_RING = RINGS.size() - 1;
    public static final double OUTER_RADIUS = RINGS.get(OUTER_RING).outerLimit();

    private static final double TAU = Math.PI * 2.0;
    public static final int COLLECTOR = 0;
    public static final int COLLECTOR_COUNT = 12;
    private static final int SEGMENTS_PER_BAY = 10;
    private static final int EQUIPMENT_SEGMENT = 7;
    private static final double COLLECTOR_FACE_OFFSET = 0.585;
    private static final double WINDOW_START = 0.25;
    private static final double WINDOW_END = 0.73;

    public enum Surface { BEAM, ARMOR, CERAMIC, SOCKET, CONDUIT, MAGNET, EXTRACTOR, STELLAR }

    public record Ring(double radius, double width, double height, double heading, double axisAzimuth,
                int spatialPeriod, int rimPeriod) {
        public double innerLimit() {
            return radius - 0.59 * width;
        }

        public double outerLimit() {
            return radius + (rimPeriod == 0 ? 0.59 : 0.745) * width;
        }

        public float spatialDegrees(double ticks) {
            return angleDegrees(ticks, spatialPeriod);
        }

        public float rimDegrees(double ticks) {
            return angleDegrees(ticks, rimPeriod);
        }

        public Quaternionf orientation(double ticks) {
            if (spatialPeriod == 0) {
                // 从 +Y 俯视，负角速度让右侧弧段朝 +Z 顺时针移动。
                return new Quaternionf().rotateY((float) Math.toRadians(rimDegrees(ticks)));
            }
            double azimuth = Math.toRadians(axisAzimuth);
            return new Quaternionf().rotateAxis((float) Math.toRadians(spatialDegrees(ticks)),
                            (float) Math.cos(azimuth), 0.0f, (float) Math.sin(azimuth))
                    .rotateY((float) Math.toRadians(heading))
                    .rotateX((float) (Math.PI / 2.0));
        }

        private static float angleDegrees(double ticks, int period) {
            return period == 0 ? 0.0f : (float) (360.0 * (ticks % Math.abs(period)) / period);
        }
    }

    public record Point(double x, double y, double z) {
        static Point onRing(double radius, double y, double angle) {
            return new Point(radius * Math.cos(angle), y, radius * Math.sin(angle));
        }
    }

    @FunctionalInterface
    public interface Mesh {
        void quad(Surface surface, Point a, Point b, Point c, Point d);

        default void band(Surface surface, double start, double end, double inner, double outer, double bottom, double top) {}
    }

    private StarLifterGeometry() {}

    static int segmentCount(int index) {
        return index == OUTER_RING ? 150 : COLLECTOR_COUNT * SEGMENTS_PER_BAY;
    }

    public static void ring(int index, Mesh mesh) {
        Ring ring = RINGS.get(index);
        double radius = ring.radius();
        double width = ring.width();
        double height = ring.height();
        boolean outerRing = index == OUTER_RING;
        int segmentCount = segmentCount(index);
        double step = TAU / segmentCount;
        for (int i = 0; i < segmentCount; i++) {
            double a = i * step;
            double b = (i + 1) * step;
            // 四道连续弦梁围出开放式箱形截面。
            for (int side = -1; side <= 1; side += 2) {
                for (int face = -1; face <= 1; face += 2) {
                    double rail = radius + side * width * 0.408;
                    double y = face * height * 0.385;
                    band(mesh, Surface.BEAM, a, b, rail - width * 0.089, rail + width * 0.089,
                            y - height * 0.112, y + height * 0.112, false);
                }
            }
            // 中心凹槽独立于承力梁，穿过轨道加强肋时仍保留在盒形截面内。
            band(mesh, Surface.SOCKET, a, b, radius - width * 0.103, radius + width * 0.103,
                    -height * 0.095, height * 0.095, false);
            double inner = radius - width * 0.047;
            double outer = radius + width * 0.047;
            band(mesh, Surface.CONDUIT, a, b, inner, outer, height * 0.095, height * 0.145, false);
            band(mesh, Surface.CONDUIT, a, b, inner, outer, -height * 0.145, -height * 0.095, false);

            int bay = i % SEGMENTS_PER_BAY;
            if (bay == 0 || bay == 5) {
                ribs(mesh, radius, width, height, a + step * 0.05, a + step * 0.88, bay == 0);
                if (outerRing && bay == 0) serviceNode(mesh, radius, width, height, a, step);
            } else if (bay >= 2 && bay <= 4) {
                double side = (i / 10 & 1) == 0 ? -1 : 1;
                armor(mesh, radius, width, height, a, step, side, bay);
            } else if (bay == EQUIPMENT_SEGMENT) {
                equipment(mesh, index, ring, a, step);
            }
        }
    }

    private static void serviceNode(Mesh mesh, double r, double w, double h, double a, double step) {
        band(mesh, Surface.ARMOR, a + step * 0.13, a + step * 0.79,
                r + w * 0.52, r + w * 0.686, -h * 0.25, h * 0.25, true);
        band(mesh, Surface.CERAMIC, a + step * 0.23, a + step * 0.69,
                r + w * 0.686, r + w * 0.729, -h * 0.18, h * 0.18, true);
        band(mesh, Surface.MAGNET, a + step * 0.32, a + step * 0.60,
                r + w * 0.729, r + w * 0.74, -h * 0.12, h * 0.12, true);
    }

    private static void ribs(Mesh mesh, double r, double w, double h, double a, double b, boolean reinforced) {
        Surface surface = reinforced ? Surface.ARMOR : Surface.BEAM;
        double side = reinforced ? 0.553 : 0.484;
        band(mesh, surface, a, b, r - w * side, r - w * 0.335, -h * 0.51, h * 0.51, true);
        band(mesh, surface, a, b, r + w * 0.335, r + w * side, -h * 0.51, h * 0.51, true);
        band(mesh, surface, a, b, r - w * 0.34, r + w * 0.34, h * 0.29, h * 0.375, true);
        band(mesh, surface, a, b, r - w * 0.34, r + w * 0.34, -h * 0.375, -h * 0.29, true);
    }

    private static void armor(Mesh mesh, double r, double w, double h, double a, double step,
                              double side, int bay) {
        double rail = r + w * 0.302 * side;
        double start = a + step * (bay == 2 ? 0.12 : 0.04);
        double end = a + step * (bay == 4 ? 0.74 : 0.96);
        band(mesh, Surface.ARMOR, start, end, rail - w * 0.136, rail + w * 0.136, h * 0.481, h * 0.563, true);
        band(mesh, Surface.ARMOR, start, end, rail - w * 0.136, rail + w * 0.136, -h * 0.563, -h * 0.481, true);
        if (bay == 3) {
            band(mesh, Surface.CERAMIC, a + step * 0.14, a + step * 0.86, rail - w * 0.10,
                    rail + w * 0.10, h * 0.563, h * 0.582, true);
            band(mesh, Surface.CERAMIC, a + step * 0.14, a + step * 0.86, rail - w * 0.10,
                    rail + w * 0.10, -h * 0.582, -h * 0.563, true);
        }
    }

    private static void equipment(Mesh mesh, int index, Ring ring, double a, double step) {
        double r = ring.radius();
        double w = ring.width();
        double h = ring.height();
        double start = a + step * 0.13;
        double end = a + step * 0.85;
        double direction = index == COLLECTOR ? -1.0 : 1.0;
        double root = r + direction * w * 0.413;
        band(mesh, Surface.SOCKET, start, end, root - w * 0.145, root + w * 0.145, -h * 0.36, h * 0.36, true);
        double window = r + direction * w * 0.57;
        Surface face = index == COLLECTOR ? Surface.EXTRACTOR : Surface.MAGNET;
        double inner = index == COLLECTOR ? collectorFaceRadius() : window - w * 0.015;
        band(mesh, face, a + step * WINDOW_START, a + step * WINDOW_END, inner, window + w * 0.015,
                -h * 0.25, h * 0.25, true);
    }

    public static double collectorFaceAngle(int index) {
        return (index * SEGMENTS_PER_BAY + EQUIPMENT_SEGMENT + (WINDOW_START + WINDOW_END) / 2.0) * TAU
                / (COLLECTOR_COUNT * SEGMENTS_PER_BAY);
    }

    public static double collectorFaceRadius() {
        var ring = RINGS.get(COLLECTOR);
        return ring.radius() - ring.width() * COLLECTOR_FACE_OFFSET;
    }

    private static void band(Mesh mesh, Surface surface, double a, double b,
                             double inner, double outer, double bottom, double top, boolean capped) {
        mesh.band(surface, a, b, inner, outer, bottom, top);
        Point aib = Point.onRing(inner, bottom, a), aob = Point.onRing(outer, bottom, a);
        Point ait = Point.onRing(inner, top, a), aot = Point.onRing(outer, top, a);
        Point bib = Point.onRing(inner, bottom, b), bob = Point.onRing(outer, bottom, b);
        Point bit = Point.onRing(inner, top, b), bot = Point.onRing(outer, top, b);
        mesh.quad(surface, aob, aot, bot, bob);
        mesh.quad(surface, aib, bib, bit, ait);
        mesh.quad(surface, ait, bit, bot, aot);
        mesh.quad(surface, aib, aob, bob, bib);
        if (capped) {
            mesh.quad(surface, aib, ait, aot, aob);
            mesh.quad(surface, bib, bob, bot, bit);
        }
    }

    public static void star(Mesh mesh) {
        // 六个立方体表面投影到球面，避免极点退化面。
        int tilesPerFace = 16;
        for (int face = 0; face < 6; face++) {
            for (int y = 0; y < tilesPerFace; y++) {
                for (int x = 0; x < tilesPerFace; x++) {
                    double left = x * 2.0 / tilesPerFace - 1.0;
                    double bottom = y * 2.0 / tilesPerFace - 1.0;
                    double right = (x + 1) * 2.0 / tilesPerFace - 1.0;
                    double top = (y + 1) * 2.0 / tilesPerFace - 1.0;
                    mesh.quad(Surface.STELLAR, cubeFace(face, left, bottom), cubeFace(face, right, bottom),
                            cubeFace(face, right, top), cubeFace(face, left, top));
                }
            }
        }
    }

    private static Point cubeFace(int face, double u, double v) {
        double s = STAR_RADIUS / Math.sqrt(1.0 + u*u + v*v);
        return switch (face) {
            case 0 -> new Point(s, s*u, s*v);
            case 1 -> new Point(-s, -s*u, s*v);
            case 2 -> new Point(s*v, s, s*u);
            case 3 -> new Point(s*v, -s, -s*u);
            case 4 -> new Point(s*u, s*v, s);
            default -> new Point(-s*u, s*v, -s);
        };
    }
}
