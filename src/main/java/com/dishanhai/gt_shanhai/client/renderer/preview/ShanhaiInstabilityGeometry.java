package com.dishanhai.gt_shanhai.client.renderer.preview;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;
import java.util.function.IntPredicate;

/** 山海：由 MB 表面取樣的不穩定特效幾何，無遊戲／OpenGL 依賴。 */
public final class ShanhaiInstabilityGeometry {
    public static final int MAX_NODES = 256;
    public static final int MAX_ARCS = 32;
    public static final int MAX_QUADS = 4096;
    public record Point(double x, double y, double z) {
        Point add(Point p) { return new Point(x + p.x, y + p.y, z + p.z); }
        Point scale(double k) { return new Point(x * k, y * k, z * k); }
        Point cross(Point p) { return new Point(y*p.z-z*p.y, z*p.x-x*p.z, x*p.y-y*p.x); }
        Point unit() { return scale(1 / Math.sqrt(x*x+y*y+z*z)); }
    }
    public record Node(int x, int y, int z, int nx, int ny, int nz, char symbol, int seed) {}
    public record Link(int from, int to) {}
    public record Quad(Point a, Point b, Point c, Point d, float red, float green, float blue, float alpha) {}
    public record Frame(List<Quad> quads, int arcs) {}

    private final List<Node> nodes;
    private final List<Link> links;

    private ShanhaiInstabilityGeometry(List<Node> nodes, List<Link> links) {
        this.nodes = List.copyOf(nodes);
        this.links = List.copyOf(links);
    }

    public static ShanhaiInstabilityGeometry fromMb(String mb) {
        List<String> lines = mb.lines().toList();
        if (lines.size() != 49) throw new IllegalArgumentException("Expected 49 MB slices");
        String[][] volume = new String[49][];
        for (int z = 0; z < 49; z++) {
            volume[z] = lines.get(z).split(",", -1);
            if (volume[z].length != 48) throw new IllegalArgumentException("Expected 48 MB rows");
            for (String row : volume[z]) {
                if (row.length() != 165) throw new IllegalArgumentException("Expected 165 MB columns");
            }
        }
        var buckets = new TreeMap<Integer, Node>();
        int[][] normals = {{0,1,0}, {0,-1,0}, {1,0,0}, {-1,0,0}, {0,0,1}, {0,0,-1}};
        for (int z = 0; z < 49; z++) for (int y = 0; y < 48; y++) for (int x = 0; x < 165; x++) {
            char symbol = volume[z][y].charAt(x);
            if (symbol == ' ' || symbol == 'a') continue;
            int hash = hash(x + y * 165 + z * 165 * 48);
            for (int side = 0; side < 6; side++) {
                int[] normal = normals[Math.floorMod(hash + side, 6)];
                if (solid(volume, x+normal[0], y+normal[1], z+normal[2])) continue;
                var node = new Node(x-82, y-24, z-48, normal[0], normal[1], normal[2], symbol, hash);
                int bucket = x/12 + y/12*14 + z/12*14*4;
                Node old = buckets.get(bucket);
                if (old == null || priority(symbol, hash) > priority(volume[old.z+48][old.y+24].charAt(old.x+82), old.seed)) {
                    buckets.put(bucket, node);
                }
                break;
            }
        }
        var nodes = new ArrayList<>(buckets.values());
        return fromNodes(nodes);
    }

    /** 山海：不同格式的結構表面取樣共用奈亞的近鄰連線規則。 */
    public static ShanhaiInstabilityGeometry fromNodes(List<Node> nodes) {
        if (nodes.size() > MAX_NODES) throw new IllegalArgumentException("MB exceeds FX node budget");
        var links = new ArrayList<Link>();
        var seen = new HashSet<Long>();
        for (int i = 0; i < nodes.size(); i++) {
            int nearest = -1;
            double best = 18*18 + 1;
            for (int j = 0; j < nodes.size(); j++) {
                Node a = nodes.get(i), b = nodes.get(j);
                double distance = (a.x-b.x)*(a.x-b.x) + (a.y-b.y)*(a.y-b.y) + (a.z-b.z)*(a.z-b.z);
                if (distance >= 9 && distance < best) { best = distance; nearest = j; }
            }
            if (nearest >= 0 && best <= 18*18) {
                int from = Math.min(i, nearest), to = Math.max(i, nearest);
                if (seen.add(((long) from << 32) | to)) links.add(new Link(from, to));
            }
        }
        return new ShanhaiInstabilityGeometry(nodes, links);
    }

    private static boolean solid(String[][] volume, int x, int y, int z) {
        return x >= 0 && x < 165 && y >= 0 && y < 48 && z >= 0 && z < 49 && volume[z][y].charAt(x) != ' ';
    }

    private static int priority(char symbol, int seed) {
        int priority = switch (symbol) { case '~' -> 3; case 'Q', 'D', '^', 'E' -> 2; default -> 1; };
        return priority * 1_000_000 + Math.floorMod(seed, 1_000_000);
    }

    private static int hash(int value) {
        value = (value ^ (value >>> 16)) * 0x7feb352d;
        value = (value ^ (value >>> 15)) * 0x846ca68b;
        return value ^ (value >>> 16);
    }

    private static double phase(int seed) { return (seed & 0xffff) / 65536.0; }

    public List<Node> nodes() {
        return nodes;
    }

    public List<Link> links() {
        return links;
    }

    public Frame frame(double seconds, long seed, IntPredicate present) {
        if (!Double.isFinite(seconds)) throw new IllegalArgumentException("Non-finite FX time");
        var quads = new ArrayList<Quad>(1200);
        double clock = seconds + phase(hash((int) (seed ^ (seed >>> 32)))) * 11;
        for (int index = 0; index < nodes.size(); index++) {
            if (!present.test(index)) continue;
            Node node = nodes.get(index);
            double phase = phase(node.seed) * Math.PI * 2;
            double pulse = 0.5 + 0.5 * Math.sin(clock * 3.2 - node.x * 0.065 + node.z * 0.11 + phase);
            double jitter = 0.16 * Math.sin(clock * 13 + phase) * pulse;
            Point normal = new Point(node.nx, node.ny, node.nz);
            Point u = normal.cross(Math.abs(node.ny) == 1 ? new Point(1,0,0) : new Point(0,1,0));
            Point v = normal.cross(u);
            Point center = center(node).add(u.scale(jitter));
            double radius = 0.65 + pulse * 0.7;
            float alpha = (float) (0.18 + 0.44 * pulse);
            for (int segment = 0; segment < 6; segment++) {
                double angle = phase + clock * (0.6 + phase * 0.05) + segment * Math.PI/3;
                Point a = circle(center, u, v, radius, angle);
                Point b = circle(center, u, v, radius, angle + 0.7);
                strip(quads, a, b, normal.cross(b.add(a.scale(-1))).unit().scale(0.045 + 0.055*pulse),
                        (float) (0.38 + 0.34*pulse), (float) (0.32 + 0.5*(1-pulse)), 1, alpha);
            }
            // 局部殘影斷片；不抖動真方塊或整個視角。
            if (pulse > 0.6) {
                Point shifted = center.add(u.scale(0.3*Math.sin(clock*9+phase))).add(normal.scale(0.15));
                strip(quads, shifted.add(u.scale(-1.2)), shifted.add(u.scale(1.2)), v.scale(0.075),
                        0.65f, 0.4f, 1, (float) ((pulse-0.6)*1.5));
            }
        }
        int arcs = 0;
        for (Link link : links) {
            if (arcs >= MAX_ARCS) break;
            if (!present.test(link.from) || !present.test(link.to)) continue;
            Node a = nodes.get(link.from), b = nodes.get(link.to);
            double cycle = clock / 1.9 + phase(a.seed ^ b.seed);
            double life = cycle - Math.floor(cycle);
            if (life >= 0.3) continue;
            float envelope = (float) Math.sin(life / 0.3 * Math.PI);
            if (envelope < 0.02) continue;
            appendArc(quads, center(a), center(b), clock, a.seed, b.seed, envelope);
            arcs++;
        }
        if (quads.size() > MAX_QUADS) throw new IllegalStateException("FX quad budget exceeded");
        return new Frame(List.copyOf(quads), arcs);
    }

    /** 山海：奈亞預覽與歸墟共用的八段折線電弧，紫色外暈＋藍白亮芯，雙交叉帶避免側看消失。 */
    public static void appendArc(List<Quad> quads, Point start, Point end, double clock,
                                 int seedA, int seedB, float envelope) {
        Point delta = end.add(start.scale(-1));
        if (delta.x*delta.x + delta.y*delta.y + delta.z*delta.z < 1e-10) return;
        Point axis = delta.unit();
        Point u = axis.cross(Math.abs(axis.y) < 0.9 ? new Point(0,1,0) : new Point(1,0,0)).unit();
        Point v = axis.cross(u);
        Point previous = start;
        for (int step = 1; step <= 8; step++) {
            double t = step / 8.0;
            double bend = Math.sin(Math.PI*t) * 0.8;
            Point next = start.add(delta.scale(t))
                    .add(u.scale(Math.sin(step*13.7 + clock*19 + seedA)*bend))
                    .add(v.scale(Math.cos(step*8.1 + clock*17 + seedB)*bend));
            strip(quads, previous, next, u.scale(0.12), 0.5f, 0.25f, 1, envelope*0.5f);
            strip(quads, previous, next, v.scale(0.12), 0.5f, 0.25f, 1, envelope*0.5f);
            strip(quads, previous, next, u.scale(0.035), 0.75f, 0.92f, 1, envelope*0.65f);
            strip(quads, previous, next, v.scale(0.035), 0.75f, 0.92f, 1, envelope*0.65f);
            previous = next;
        }
    }

    private static Point center(Node node) {
        return new Point(node.x + node.nx*0.55, node.y + node.ny*0.55, node.z + node.nz*0.55);
    }

    private static Point circle(Point center, Point u, Point v, double radius, double angle) {
        return center.add(u.scale(radius*Math.cos(angle))).add(v.scale(radius*Math.sin(angle)));
    }

    static void strip(List<Quad> out, Point a, Point b, Point halfWidth, float r, float g, float blue, float alpha) {
        out.add(new Quad(a.add(halfWidth), b.add(halfWidth), b.add(halfWidth.scale(-1)), a.add(halfWidth.scale(-1)),
                r, g, blue, alpha));
    }
}
