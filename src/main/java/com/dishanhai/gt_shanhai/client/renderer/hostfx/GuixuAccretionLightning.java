package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiInstabilityGeometry;
import com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiInstabilityGeometry.Point;
import com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiInstabilityGeometry.Quad;
import com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiInstabilityGeometry.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.IntPredicate;
import java.util.function.IntFunction;

/** 山海：由正式結構表面取樣的電弧；客戶端提供當幀存在狀態及旋轉後錨點。 */
public final class GuixuAccretionLightning {
    public static final int MAX_ARCS = 12;
    private final ShanhaiInstabilityGeometry geometry;

    private GuixuAccretionLightning(List<Node> nodes) {
        geometry = ShanhaiInstabilityGeometry.fromNodes(nodes);
    }

    /** loader 的 ShapeInfo 軸序為 [z][x][由上而下的 y]，輸出使用環心本地座標。 */
    public static GuixuAccretionLightning fromSlices(String[][] slices, int minX, int topY, int minZ) {
        if (slices.length == 0 || slices[0].length == 0 || slices[0][0].isEmpty())
            throw new IllegalArgumentException("Empty Guixu structure");
        int sizeX = slices[0].length, sizeY = slices[0][0].length();
        for (var plane : slices) {
            if (plane.length != sizeX) throw new IllegalArgumentException("Ragged Guixu structure");
            for (var column : plane) if (column.length() != sizeY) throw new IllegalArgumentException("Ragged Guixu column");
        }
        // 與奈亞同樣每個空間桶保留一個表面節點；大結構擴大桶而非截斷某一側。
        for (int bucketSize = 12; ; bucketSize += 4) {
            var buckets = new TreeMap<Long, Node>();
            int[][] normals = {{0,1,0},{0,-1,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}};
            for (int z = 0; z < slices.length; z++) for (int x = 0; x < sizeX; x++) for (int y = 0; y < sizeY; y++) {
                char symbol = slices[z][x].charAt(y);
                if (!solidSymbol(symbol)) continue;
                int seed = hash(x + y * sizeX + z * sizeX * sizeY);
                for (int side = 0; side < 6; side++) {
                    int[] normal = normals[Math.floorMod(seed + side, 6)];
                    if (solid(slices, x + normal[0], y + normal[1], z + normal[2])) continue;
                    var node = new Node(minX + x, topY - y, minZ + z, normal[0], -normal[1], normal[2], symbol, seed);
                    long bucket = ((long)(x / bucketSize) << 40) | ((long)(y / bucketSize) << 20) | z / bucketSize;
                    Node old = buckets.get(bucket);
                    if (old == null || priority(symbol, seed) > priority(old.symbol(), old.seed())) buckets.put(bucket, node);
                    break;
                }
            }
            if (buckets.size() <= ShanhaiInstabilityGeometry.MAX_NODES)
                return new GuixuAccretionLightning(new ArrayList<>(buckets.values()));
        }
    }

    private static boolean solidSymbol(char symbol) { return " MNP".indexOf(symbol) < 0; }
    private static boolean solid(String[][] slices, int x, int y, int z) {
        return z >= 0 && z < slices.length && x >= 0 && x < slices[0].length && y >= 0 && y < slices[0][0].length()
                && solidSymbol(slices[z][x].charAt(y));
    }
    private static long priority(char symbol, int seed) {
        int rank = switch (symbol) { case 'F', 'G', 'I', '~' -> 3; case 'E', 'J' -> 2; default -> 1; };
        return ((long)rank << 32) | Integer.toUnsignedLong(seed);
    }
    private static int hash(int value) {
        value = (value ^ (value >>> 16)) * 0x7feb352d;
        value = (value ^ (value >>> 15)) * 0x846ca68b;
        return value ^ (value >>> 16);
    }
    private static double phase(int seed) { return (seed & 0xffff) / 65536.0; }
    public List<Node> nodes() { return geometry.nodes(); }

    public ShanhaiInstabilityGeometry.Frame frame(double seconds, long seed, IntPredicate present) {
        return frame(seconds, seed, present, i -> {
            Node n = nodes().get(i);
            return new Point(n.x() + n.nx() * .55, n.y() + n.ny() * .55, n.z() + n.nz() * .55);
        });
    }

    /** 動態環／飛船在實際渲染位置連線；兩端缺件或旋轉後相距超過 18 格均不放電。 */
    public ShanhaiInstabilityGeometry.Frame frame(double seconds, long seed, IntPredicate present, IntFunction<Point> center) {
        if (!Double.isFinite(seconds)) throw new IllegalArgumentException("Non-finite FX time");
        var quads = new ArrayList<Quad>(MAX_ARCS * 32);
        double clock = seconds + phase(hash((int)(seed ^ (seed >>> 32)))) * 11;
        int arcs = 0;
        for (var link : geometry.links()) {
            if (arcs >= MAX_ARCS) break;
            if (!present.test(link.from()) || !present.test(link.to())) continue;
            Node a = nodes().get(link.from()), b = nodes().get(link.to());
            Point start = center.apply(link.from()), end = center.apply(link.to());
            double dx = start.x() - end.x(), dy = start.y() - end.y(), dz = start.z() - end.z();
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared < 1e-10 || distanceSquared > 18 * 18) continue;
            double cycle = clock / 1.9 + phase(a.seed() ^ b.seed());
            double life = cycle - Math.floor(cycle);
            if (life >= .3) continue;
            float envelope = (float) Math.sin(life / .3 * Math.PI);
            if (envelope < .02F) continue;
            ShanhaiInstabilityGeometry.appendArc(quads, start, end, clock, a.seed(), b.seed(), envelope);
            arcs++;
        }
        return new ShanhaiInstabilityGeometry.Frame(List.copyOf(quads), arcs);
    }
}
