package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import java.util.Locale;

/** 归墟激光环的局部薄带网格，保留 51–57 格的结构间隙。 */
public final class HostFxBeamRingGeom {
    private HostFxBeamRingGeom() {}
    static final float WIDTH = GuixuAccretionFlow.OUTER_RADIUS - GuixuAccretionFlow.INNER_RADIUS;
    public static final float OUTER_BAND_INNER_FACE = 58.5F;
    static final int SEGMENTS = 192;
    static final int RADIAL_CELLS = 4;
    static final int FALLBACK_SEGMENTS = 96;
    static final int FALLBACK_RADIAL_CELLS = 12;
    public static final float[] GLOW_RGB = {1F, 1F, 1F};

    record Knot(float x, float z, float radial, float angle) {}

    interface QuadSink {
        void quad(int a, int b, int c, int d);
    }

    record Mesh(Knot[] knots, int columnSize) {
        void forEachQuad(QuadSink sink) {
            for (int column = 0; column < knots.length - columnSize; column += columnSize) {
                for (int row = 0; row < columnSize - 1; row++) {
                    int a = column + row;
                    int d = a + columnSize;
                    sink.quad(a, a + 1, d + 1, d);
                }
            }
        }
    }

    public static float innerEdge() { return GuixuAccretionFlow.INNER_RADIUS; }
    public static float outerEdge() { return GuixuAccretionFlow.OUTER_RADIUS; }

    static Mesh mesh(int segments, int radialCells) {
        int columnSize = radialCells + 1;
        Knot[] knots = new Knot[(segments + 1) * columnSize];
        for (int i = 0; i <= segments; i++) {
            double angle = 2 * Math.PI * i / segments;
            // 两列共享精确的位置，保留相差 2π 的 UV 供各自的片元插值。
            float cos = i == segments ? 1 : (float) Math.cos(angle);
            float sin = i == segments ? 0 : (float) Math.sin(angle);
            for (int j = 0; j <= radialCells; j++) {
                float s = j / (float) radialCells;
                float radius = innerEdge() + WIDTH * s;
                knots[i * columnSize + j] = new Knot(radius * cos, radius * sin, s, (float) angle);
            }
        }
        return new Mesh(knots, columnSize);
    }

    public static String stats() {
        return String.format(Locale.ROOT, "激光环 r=%.2f..%.2f 厚度≤%.2f / %d×%d / 单遍静态网格",
                innerEdge(), outerEdge(), GuixuAccretionFlow.HALF_HEIGHT * 2, SEGMENTS, RADIAL_CELLS);
    }
    /** 比對既有台帳包圍盒；機器與輻條交叉位置交由深度測試遮擋。 */
    public static String selfCheck() {
        if (outerEdge() >= OUTER_BAND_INNER_FACE) return "吸積帶超出外側結構間隙";
        for (var block : HostFxGeometry.hideTargets()) {
            double x = block.getX(), z = block.getZ(), radius = Math.hypot(x, z);
            boolean band = Math.abs(radius - HostFxGeometry.INNER_R) <= HostFxGeometry.INNER_TOLERANCE;
            boolean ship = !band && radius > HostFxGeometry.SHIP_RADIAL_CENTER - 4.5;
            if (!band && !ship) continue;
            double min = Math.hypot(Math.max(0, Math.abs(x) - .5), Math.max(0, Math.abs(z) - .5));
            double max = Math.hypot(Math.abs(x) + .5, Math.abs(z) + .5);
            if (max >= innerEdge() && min <= outerEdge()
                    && Math.abs(block.getY() - HostFxGeometry.RING_Y) <= GuixuAccretionFlow.HALF_HEIGHT + .5)
                return "吸積帶與內環／飛船包圍盒相交：" + block;
        }
        return null;
    }
    public static String selfCheckReading() { return stats() + "；外帶內沿=" + OUTER_BAND_INNER_FACE; }
}
