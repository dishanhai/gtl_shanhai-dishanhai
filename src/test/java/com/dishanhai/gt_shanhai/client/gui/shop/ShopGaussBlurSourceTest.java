package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopGaussBlurSourceTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void postChainStaysOneChainAndCoversShopMotion() throws Exception {
        String shop = read("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java");
        String blur = read("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopGaussBlur.java");
        String clientInit = read("src/main/java/com/dishanhai/gt_shanhai/client/ClientInit.java");
        String fsh = read("src/main/resources/assets/gt_shanhai/shaders/program/shop_gauss_blur.fsh");
        String program = read("src/main/resources/assets/gt_shanhai/shaders/program/shop_gauss_blur.json");
        String post = read("src/main/resources/assets/gt_shanhai/shaders/post/shop_gauss_blur.json");

        assertAll(
                () -> assertTrue(Files.exists(Path.of(
                        "src/main/resources/assets/gt_shanhai/shaders/program/shop_gauss_blur.vsh"))),
                () -> assertTrue(shop.contains("ShopGaussBlur.request")),
                () -> assertTrue(shop.contains("ShopGaussBlur.requestExtra")),
                () -> assertTrue(shop.contains("openBlurredOverlay")),
                () -> assertTrue(shop.contains("requestContentGauss")),
                () -> assertTrue(shop.contains("requestPanelGauss")),
                () -> assertTrue(shop.contains("openSmoothTextOverlay")),
                () -> assertTrue(shop.contains("requestGroupSwitchGauss")),
                () -> assertTrue(shop.contains("ShopGaussBlur.requestSmooth")),
                () -> assertTrue(shop.contains("ShopGaussBlur.requestSmoothCells")),
                () -> assertTrue(shop.contains("openSmoothTextOverlay(g, cartOverlayOpenAtMs")),
                () -> assertTrue(shop.contains("ctxMenuW, menuH, true")),
                () -> assertTrue(shop.contains("CLICK_WAVE_TO_PX")),
                () -> assertTrue(shop.contains("ShopGaussBlur.requestWave")),
                () -> assertTrue(shop.contains("ShopGaussBlur.setWaveOrigin")),
                () -> assertTrue(shop.contains("requestClickWave")),
                () -> assertTrue(shop.contains("renderClickWaveRing")),
                () -> assertTrue(shop.contains("drawRingArc")),
                () -> assertTrue(blur.contains("requestExtra")),
                () -> assertTrue(blur.contains("requestSmooth")),
                () -> assertTrue(blur.contains("requestSmoothCells")),
                () -> assertTrue(blur.contains("requestWave")),
                () -> assertTrue(blur.contains("\"WaveThickness\"")),
                () -> assertTrue(blur.contains("\"WaveRefract\"")),
                () -> assertTrue(blur.contains("\"WaveGlow\"")),
                () -> assertTrue(fsh.contains("uniform float Smooth")),
                () -> assertTrue(fsh.contains("uniform float CellRects")),
                () -> assertTrue(fsh.contains("uniform int CellCount")),
                () -> assertTrue(fsh.contains("uniform float WaveOrigin")),
                () -> assertTrue(fsh.contains("uniform float WaveRadius")),
                () -> assertTrue(fsh.contains("uniform float WaveThickness")),
                () -> assertTrue(fsh.contains("uniform float WaveRefract")),
                () -> assertTrue(fsh.contains("uniform float WaveGlow")),
                // 纯色面板只做高斯看不出效果：波必须真的位移采样坐标
                () -> assertTrue(fsh.contains("baseUv -= dir * (WaveRefract * crest)")),
                // 波峰处 radius 恰好归零，不能跟着它提前 return，否则折射位移被整段跳过
                () -> assertTrue(fsh.contains("if (!waveActive && radius < 0.5)")),
                () -> assertTrue(program.contains("\"Smooth\"")),
                () -> assertTrue(program.contains("\"CellRects\"")),
                () -> assertTrue(program.contains("\"CellCount\"")),
                () -> assertTrue(program.contains("\"WaveOrigin\"")),
                () -> assertTrue(program.contains("\"WaveRadius\"")),
                () -> assertTrue(program.contains("\"WaveThickness\"")),
                () -> assertTrue(program.contains("\"WaveRefract\"")),
                () -> assertTrue(program.contains("\"WaveGlow\"")),
                () -> assertFalse(shop.contains("new RenderTarget")),
                () -> assertTrue(shop.contains("ShopGaussBlur.clear()")),
                () -> assertTrue(shop.contains("toFramebufferX")),
                () -> assertFalse(shop.contains("left + 6, top + 6, left + panelWidth - 6")),
                () -> assertTrue(blur.contains("new PostChain(")),
                () -> assertTrue(blur.contains("f_110009_")),
                () -> assertTrue(blur.contains("ScreenEvent.Render.Post")),
                () -> assertTrue(clientInit.contains("ShopGaussBlur::onScreenRenderPost")),
                () -> assertTrue(fsh.contains("uniform vec4 Rect")),
                () -> assertTrue(fsh.contains("uniform vec4 Hole")),
                () -> assertTrue(fsh.contains("uniform float Progress")),
                () -> assertTrue(program.contains("\"Rect\"")),
                () -> assertTrue(program.contains("\"Hole\"")),
                () -> assertTrue(post.contains("minecraft:main")),
                () -> assertTrue(post.contains("gt_shanhai:shop_gauss_blur")),
                () -> assertTrue(post.contains("\"BlurDir\", \"values\": [1.0, 0.0]")),
                () -> assertTrue(post.contains("\"BlurDir\", \"values\": [0.0, 1.0]"))
        );
    }
}
