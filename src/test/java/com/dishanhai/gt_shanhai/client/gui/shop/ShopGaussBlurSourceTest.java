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
                () -> assertTrue(blur.contains("requestExtra")),
                () -> assertTrue(blur.contains("requestSmooth")),
                () -> assertTrue(fsh.contains("uniform float Smooth")),
                () -> assertTrue(program.contains("\"Smooth\"")),
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
