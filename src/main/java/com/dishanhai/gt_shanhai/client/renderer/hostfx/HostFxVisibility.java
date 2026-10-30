package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import org.joml.Matrix4f;

/** 山海：以局部到裁剪空間矩陣測試包圍盒，無物件配置，不新增距離裁剪。 */
public final class HostFxVisibility {
    private HostFxVisibility() {}

    public static boolean isVisible(Matrix4f clip, float x, float y, float z, float radius) {
        float cx = clip.m00() * x + clip.m10() * y + clip.m20() * z + clip.m30();
        float cy = clip.m01() * x + clip.m11() * y + clip.m21() * z + clip.m31();
        float cz = clip.m02() * x + clip.m12() * y + clip.m22() * z + clip.m32();
        float cw = clip.m03() * x + clip.m13() * y + clip.m23() * z + clip.m33();
        return inside(cw + cx, clip.m03() + clip.m00(), clip.m13() + clip.m10(), clip.m23() + clip.m20(), radius)
                && inside(cw - cx, clip.m03() - clip.m00(), clip.m13() - clip.m10(), clip.m23() - clip.m20(), radius)
                && inside(cw + cy, clip.m03() + clip.m01(), clip.m13() + clip.m11(), clip.m23() + clip.m21(), radius)
                && inside(cw - cy, clip.m03() - clip.m01(), clip.m13() - clip.m11(), clip.m23() - clip.m21(), radius)
                && inside(cw + cz, clip.m03() + clip.m02(), clip.m13() + clip.m12(), clip.m23() + clip.m22(), radius)
                && inside(cw - cz, clip.m03() - clip.m02(), clip.m13() - clip.m12(), clip.m23() - clip.m22(), radius);
    }

    private static boolean inside(float distance, float x, float y, float z, float radius) {
        return distance + radius * (Math.abs(x) + Math.abs(y) + Math.abs(z)) >= 0;
    }
}
