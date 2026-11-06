package com.dishanhai.gt_shanhai.client.renderer.machine;

import com.dishanhai.gt_shanhai.common.machine.part.cloud.StellarCloudLibraryStructure;
import java.util.ArrayList;
import java.util.List;

/** 山海：星律雲核的純數學幾何；固定 540 個發光四邊形，不建立世界粒子。 */
public final class StellarCloudCoreGeometry {
    public record Point(double x, double y, double z) {}
    public record Quad(Point a, Point b, Point c, Point d, float red, float green, float blue, float alpha) {}
    private static final int SEGMENTS = 64;
    private static final double TAU = Math.PI * 2;

    private StellarCloudCoreGeometry() {}

    /** 以控制器方塊原點為基準；中心落在其後八格、上三格的方塊中心。 */
    public static Point anchor(int backX, int backZ) {
        return new Point(.5 + backX * StellarCloudLibraryStructure.CORE_Z,
                .5 + StellarCloudLibraryStructure.CORE_Y - StellarCloudLibraryStructure.CONTROLLER_Y,
                .5 + backZ * StellarCloudLibraryStructure.CORE_Z);
    }

    public static List<Quad> frame(double time) {
        if (!Double.isFinite(time)) throw new IllegalArgumentException("Non-finite cloud core time");
        List<Quad> result = new ArrayList<>(540);
        double radius = .72 + .055 * Math.sin(time * 1.6);
        Point top = new Point(0, radius, 0), bottom = new Point(0, -radius, 0);
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2 + time * .35, b = a + Math.PI / 2;
            Point p = new Point(Math.cos(a) * radius, 0, Math.sin(a) * radius);
            Point q = new Point(Math.cos(b) * radius, 0, Math.sin(b) * radius);
            result.add(new Quad(top, p, q, q, .72f, .95f, 1, .66f));
            result.add(new Quad(bottom, q, p, p, .35f, .75f, 1, .58f));
        }
        for (int ring = 0; ring < 3; ring++) {
            for (int segment = 0; segment < SEGMENTS; segment++) {
                double a = segment * TAU / SEGMENTS, b = (segment + 1) * TAU / SEGMENTS;
                double r = 1.45 + ring * .24;
                float red = ring == 1 ? .62f : .12f, green = ring == 1 ? .38f : .84f;
                float alpha = (float) (.30 + .24 * (.5 + .5 * Math.sin(a * 4 - time * 2 + ring)));
                band(result, ring, a, b, r, .028, time, red, green, 1, alpha);
                band(result, ring, a, b, r, .13, time, red, green, 1, .055f);
            }
        }
        for (int ring = 0; ring < 3; ring++) {
            for (int segment = 0; segment < 32; segment++) {
                band(result, ring, segment * TAU / 32, (segment + 1) * TAU / 32,
                        .88, .018, -time * .6, .25f, .92f, 1, .28f);
            }
        }
        for (int i = 0; i < 16; i++) {
            double angle = i * TAU / 16 + time * (.4 + (i % 3) * .13);
            Point p = orbit(i % 3, angle, 2.05, time);
            double size = .035 + .015 * (.5 + .5 * Math.sin(time * 3 + i));
            mote(result, p, size);
        }
        float alpha = (float) (.16 + .07 * Math.sin(time * 1.6));
        for (int side : new int[]{-1, 1}) {
            double start = side * .8, end = side * 2.9;
            result.add(new Quad(new Point(-.035, start, 0), new Point(.035, start, 0),
                    new Point(.035, end, 0), new Point(-.035, end, 0), .3f, .8f, 1, alpha));
            result.add(new Quad(new Point(0, start, -.035), new Point(0, start, .035),
                    new Point(0, end, .035), new Point(0, end, -.035), .3f, .8f, 1, alpha));
        }
        return List.copyOf(result);
    }

    private static Point orbit(int ring, double angle, double radius, double time) {
        double x = radius * Math.cos(angle), y = radius * Math.sin(angle);
        double tilt = .55 + ring * .85;
        double tiltedY = y * Math.cos(tilt), z = y * Math.sin(tilt);
        double spin = time * (ring == 1 ? -.22 : .17) + ring * TAU / 3;
        return new Point(x * Math.cos(spin) + z * Math.sin(spin), tiltedY,
                -x * Math.sin(spin) + z * Math.cos(spin));
    }

    private static void band(List<Quad> quads, int ring, double a, double b, double radius,
                             double width, double time, float red, float green, float blue, float alpha) {
        quads.add(new Quad(orbit(ring, a, radius - width / 2, time),
                orbit(ring, a, radius + width / 2, time), orbit(ring, b, radius + width / 2, time),
                orbit(ring, b, radius - width / 2, time), red, green, blue, alpha));
    }

    private static void mote(List<Quad> quads, Point p, double s) {
        double x = p.x, y = p.y, z = p.z;
        quads.add(new Quad(new Point(x-s,y-s,z), new Point(x+s,y-s,z), new Point(x+s,y+s,z),
                new Point(x-s,y+s,z), .6f, .95f, 1, .7f));
        quads.add(new Quad(new Point(x-s,y,z-s), new Point(x+s,y,z-s), new Point(x+s,y,z+s),
                new Point(x-s,y,z+s), .6f, .95f, 1, .7f));
        quads.add(new Quad(new Point(x,y-s,z-s), new Point(x,y+s,z-s), new Point(x,y+s,z+s),
                new Point(x,y-s,z+s), .6f, .95f, 1, .7f));
    }
}
