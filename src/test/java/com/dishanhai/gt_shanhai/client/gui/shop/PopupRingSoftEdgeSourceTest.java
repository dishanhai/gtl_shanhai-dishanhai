package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 弹层外圈的软边：外缘淡出、取样绕开 Hole、不画 Rect 内缘亮边，且不影响商店既有请求。 */
class PopupRingSoftEdgeSourceTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void shaderFadesTheRingEdgeAndKeepsPopupTextOutOfIt() throws Exception {
        String fsh = read("src/main/resources/assets/gt_shanhai/shaders/program/shop_gauss_blur.fsh");
        assertAll(
                // 所有取样都走 fetchUv，不再有直接夹进 Rect 的取样
                () -> assertTrue(fsh.contains("vec2 fetchUv(vec2 p, vec2 origin, vec2 lo, vec2 hi)")),
                () -> assertFalse(fsh.contains("texture(DiffuseSampler, clamp(")),
                // 软边只在 MotionAxis.z > 0 时生效，旧请求 z=0 行为不变
                () -> assertTrue(fsh.contains("MotionAxis.z > 0.0")),
                // Hole 内的取样点被推回 Hole 外缘，两个方向各一条
                () -> assertTrue(fsh.contains("p.x = origin.x < Hole.x ? Hole.x - oneTexel.x * 1.5 : Hole.z + oneTexel.x * 1.5;")),
                () -> assertTrue(fsh.contains("p.y = origin.y < Hole.y ? Hole.y - oneTexel.y * 1.5 : Hole.w + oneTexel.y * 1.5;")),
                // 外缘淡出到原图；软边下不画 Rect 内缘亮边
                () -> assertTrue(fsh.contains("smoothstep(0.0, MotionAxis.z, edgeD)")),
                () -> assertTrue(fsh.contains("rimEdgeD = 1.0e5;")),
                () -> assertTrue(fsh.contains("rimInward = vec2(0.0);")),
                () -> assertTrue(fsh.contains("applyGlass(rgb, k, rimEdgeD, rimInward, uv)"))
        );
    }

    @Test
    void featherIsPlumbedOnlyIntoTheExtraSlot() throws Exception {
        String blur = read("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopGaussBlur.java");
        String glass = read("src/main/kotlin/com/dishanhai/gt_shanhai/client/gui/glass/GlassBlur.kt");
        String style = read("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopGlassStyle.java");
        assertAll(
                () -> assertTrue(blur.contains("motionAxisUniform.set(axisX, axisY, featherPx, 0f);")),
                () -> assertTrue(blur.contains("NO_WAVE, 1f, 1f, glass2, partialTick, feather2")),
                () -> assertTrue(blur.contains("NO_WAVE, axisX, axisY, glass, partialTick, 0f")),
                () -> assertTrue(blur.contains("wave, 1f, 1f, ShopGlassStyle.NONE, partialTick, 0f")),
                // 旧的 11 参重载保持原签名，商店其它调用点不用改，软边默认关闭
                () -> assertTrue(blur.contains("holeX, holeY, holeW, holeH, glass, 0f);")),
                () -> assertTrue(blur.contains("extraFeather = 0f;")),
                // 弹层外圈走 POPUP 样式（无折射），并带软边宽度
                () -> assertTrue(glass.contains("POPUP_FEATHER_VIRTUAL * view.pixelScale")),
                () -> assertTrue(glass.contains("ShopGlassStyle.POPUP")),
                () -> assertTrue(style.contains("public static final ShopGlassStyle POPUP"))
        );
    }

    @Test
    void ringIsWiderThanTheSoftEdgeSoTheHoleEdgeStaysFullStrength() throws Exception {
        String glass = read("src/main/kotlin/com/dishanhai/gt_shanhai/client/gui/glass/GlassBlur.kt");
        float margin = constant(glass, "POPUP_MARGIN_VIRTUAL");
        float feather = constant(glass, "POPUP_FEATHER_VIRTUAL");
        // 13 点核有效半径约 4 倍模糊半径；外圈不够宽时外缘取样会全夹在矩形边上
        assertTrue(margin >= 4 * ShopGaussBlur.BLUR_RADIUS_VIRTUAL,
                "外圈宽度 " + margin + " 小于核有效半径");
        // Hole 外缘离 Rect 外缘正好 margin：软边宽度不超过它，此处淡出系数才是 1
        assertTrue(feather > 0 && feather <= margin,
                "软边宽度 " + feather + " 超过外圈 " + margin);
    }

    private static float constant(String source, String name) {
        int at = source.indexOf(name + " = ");
        assertTrue(at >= 0, name);
        int start = at + name.length() + 3;
        int end = source.indexOf('f', start);
        return Float.parseFloat(source.substring(start, end));
    }
}
