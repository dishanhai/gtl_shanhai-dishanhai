package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import java.util.Locale;

/** 山海歸墟外緣吸積帶：保留 51–57 格結構間隙，以微翹曲的雙面薄帶替代發光管。 */
public final class HostFxBeamRingGeom {
    private HostFxBeamRingGeom() {}
    public static final float RING_R = 54F;
    public static final float TUBE_R = 3F;
    /** 舊欄位名保留：現在表示徑向寬度，不是管徑。 */
    public static final float DIAMETER = 6F;
    public static final float CENTER_Y = 0F;
    public static final float OUTER_BAND_INNER_FACE = 58.5F;
    public static final int SEGMENTS = 192;
    public static final int FACETS = 16;
    public static final float[] GLOW_RGB = {1F, 1F, 1F};

    public interface Sink {
        void vertex(float x, float y, float z, float nx, float ny, float nz,
                    float u, float v, float alpha, float azimuthDeg);
    }
    public static int triangleCount() { return SEGMENTS * FACETS * 2; }
    public static int vertexCount() { return triangleCount() * 3; }
    public static float innerEdge() { return GuixuAccretionFlow.INNER_RADIUS; }
    public static float outerEdge() { return GuixuAccretionFlow.OUTER_RADIUS; }

    /**
     * 沿用舊入口名，每格輸出兩個三角形；spinDeg 由上層矩陣處理，網格不重複自轉。
     * UV0 = (歸一化半徑, 角度)，無須貼圖。
     */
    public static int emitTube(Sink sink, float radiusScale, float alpha, float tick, float spinDeg) {
        float seconds = tick / 20F;
        for (int i = 0; i < SEGMENTS; i++) {
            float a = (float) (2 * Math.PI * i / SEGMENTS);
            float b = (float) (2 * Math.PI * (i + 1) / SEGMENTS);
            for (int j = 0; j < FACETS; j++) {
                float s = j / (float) FACETS, t = (j + 1) / (float) FACETS;
                vertex(sink, s, a, seconds, radiusScale, alpha);
                vertex(sink, t, a, seconds, radiusScale, alpha);
                vertex(sink, t, b, seconds, radiusScale, alpha);
                vertex(sink, s, a, seconds, radiusScale, alpha);
                vertex(sink, t, b, seconds, radiusScale, alpha);
                vertex(sink, s, b, seconds, radiusScale, alpha);
            }
        }
        return vertexCount();
    }
    private static void vertex(Sink sink, float s, float angle, float seconds, float scale, float alpha) {
        float radius = RING_R + (s - .5F) * DIAMETER * scale;
        float x = radius * (float) Math.cos(angle), z = radius * (float) Math.sin(angle);
        sink.vertex(x, CENTER_Y + GuixuAccretionFlow.height(s, angle, seconds), z,
                0, 1, 0, s, angle, alpha, (float) Math.toDegrees(angle));
    }
    public static String stats() {
        return String.format(Locale.ROOT, "吸積流 r=%.2f..%.2f 厚度≤%.2f / %d×%d / 單遍暖白流紋",
                innerEdge(), outerEdge(), GuixuAccretionFlow.HALF_HEIGHT * 2, SEGMENTS, FACETS);
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
