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
                () -> assertTrue(shop.contains("openSmoothTextOverlay(g, cartMotion.value()")),
                () -> assertTrue(shop.contains("hole[0], hole[1], hole[2], hole[3], true")),
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

    @Test
    void liquidGlassLayerIsWiredEndToEnd() throws Exception {
        String shop = read("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java");
        String blur = read("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopGaussBlur.java");
        String fsh = read("src/main/resources/assets/gt_shanhai/shaders/program/shop_gauss_blur.fsh");
        String program = read("src/main/resources/assets/gt_shanhai/shaders/program/shop_gauss_blur.json");

        assertAll(
                // shader 与 program json 必须成对声明，缺任一个 Java 侧的空检查都会判定不可用
                () -> assertTrue(fsh.contains("uniform vec4 GlassTint")),
                () -> assertTrue(fsh.contains("uniform vec4 GlassParams")),
                () -> assertTrue(fsh.contains("uniform vec4 GlassEdge")),
                () -> assertTrue(program.contains("\"GlassTint\"")),
                () -> assertTrue(program.contains("\"GlassParams\"")),
                () -> assertTrue(program.contains("\"GlassEdge\"")),
                () -> assertTrue(blur.contains("\"GlassTint\"")),
                () -> assertTrue(blur.contains("\"GlassParams\"")),
                () -> assertTrue(blur.contains("\"GlassEdge\"")),
                // 边缘折射只在第一趟，玻璃层叠加只在最后一趟，避免两趟各做一遍
                () -> assertTrue(fsh.contains("if (firstPass && GlassEdge.x > 0.0)")),
                // 工作区按 core.autocrlf 可能是 CRLF，换行归一后再比
                () -> assertTrue(fsh.replace("\r\n", "\n").contains("if (lastPass) {\n        rgb = applyGlass(")),
                // scissor 必须成对：开在 process 前，关在 finally 里
                () -> assertTrue(blur.contains("RenderSystem.enableScissor(")),
                () -> assertTrue(blur.contains("RenderSystem.disableScissor()")),
                () -> assertTrue(blur.indexOf("RenderSystem.enableScissor(") < blur.indexOf("post.process(partialTick)")),
                () -> assertTrue(blur.indexOf("post.process(partialTick)") < blur.indexOf("RenderSystem.disableScissor()")),
                // 点击波不加玻璃层；各调用点用对应的预设
                () -> assertTrue(blur.contains("ShopGlassStyle.NONE, partialTick")),
                () -> assertTrue(shop.contains("ShopGlassStyle.FLIGHT")),
                () -> assertTrue(shop.contains("ShopGlassStyle.SWAP")),
                () -> assertTrue(shop.contains("ShopGlassStyle.LOADING")),
                () -> assertTrue(shop.contains("ShopGlassStyle.OVERLAY"))
        );
    }

    @Test
    void topLayerIsDrawnAfterTheBlurInsteadOfBeingBlurred() throws Exception {
        String shop = read("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java");
        String blur = read("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopGaussBlur.java");
        String renderTooltips = shop.substring(shop.indexOf("protected void renderTooltips("));
        renderTooltips = renderTooltips.substring(0, renderTooltips.indexOf("private void drawTopLayer("));
        String post = blur.substring(blur.indexOf("public static void onScreenRenderPost("));
        post = post.substring(0, post.indexOf("private static void runTopLayer("));

        String postFinal = post;
        String renderTooltipsFinal = renderTooltips;
        assertAll(
                // 后处理读的是整张主画面：tooltip 必须登记到 Post 里、模糊之后再画，Screen.render 里不能直接画
                () -> assertTrue(renderTooltipsFinal.contains("ShopGaussBlur.deferTopLayer(this")),
                () -> assertFalse(renderTooltipsFinal.contains("renderComponentTooltip")),
                () -> assertTrue(shop.contains("private void drawTooltipLayer(")),
                // 先跑模糊，再画上层；上层在 finally 里，模糊失败也不丢 tooltip
                () -> assertTrue(postFinal.indexOf("runBlurPasses(event)") >= 0),
                () -> assertTrue(postFinal.indexOf("runBlurPasses(event)") < postFinal.indexOf("runTopLayer(event")),
                () -> assertTrue(postFinal.contains("finally")),
                () -> assertTrue(blur.contains("public static void clearTopLayer()")),
                () -> assertTrue(shop.contains("ShopGaussBlur.clearTopLayer()")),
                // 横幅/撤销按钮/拖拽图标走同一条上层，且要高于前景图层的 Z
                () -> assertTrue(shop.contains("private void drawScaledTopLayer(")),
                () -> assertTrue(shop.contains("TOP_LAYER_Z = 600.0f")),
                () -> assertFalse(shop.contains("renderFlashMessagesOnTop")),
                () -> assertTrue(shop.contains("drawFlashStack(g, now)")),
                // 横幅不再让模糊下沿跟着抬起
                () -> assertFalse(shop.contains("top + panelHeight - 28")),
                // 弹入期间挖洞和面板同一条缩放曲线
                () -> assertTrue(shop.contains("popHoleRect(")),
                () -> assertTrue(shop.contains("GuiMotion.panelScale(")),
                () -> assertTrue(read("src/main/java/com/dishanhai/gt_shanhai/client/gui/scaled/GuiRenderUtil.java")
                        .contains("scaleAt(g, cx, cy, popScale(t));"))
        );
    }

    @Test
    void motionAxisIsWiredEndToEnd() throws Exception {
        String blur = read("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopGaussBlur.java");
        String fsh = read("src/main/resources/assets/gt_shanhai/shaders/program/shop_gauss_blur.fsh");
        String program = read("src/main/resources/assets/gt_shanhai/shaders/program/shop_gauss_blur.json");

        assertAll(
                // shader 与 program json 必须成对声明，缺任一个 Java 侧的空检查都会判定不可用
                () -> assertTrue(fsh.contains("uniform vec4 MotionAxis")),
                () -> assertTrue(program.contains("\"MotionAxis\"")),
                () -> assertTrue(blur.contains("\"MotionAxis\"")),
                // 趟倍率在 radius<0.5 提前返回之后才乘，V 趟半径被压成 0 时玻璃层仍然执行
                () -> assertTrue(fsh.indexOf("if (!waveActive && radius < 0.5)")
                        < fsh.indexOf("float axisRadius = radius * (firstPass ? MotionAxis.x : MotionAxis.y);")),
                () -> assertTrue(blur.contains("public static void requestDirectional(")),
                // 守衛可註冊，不再寫死商店界面
                () -> assertTrue(blur.contains("public static void allowScreen(")),
                () -> assertTrue(blur.contains("static boolean isAllowed(Screen screen)")),
                () -> assertFalse(blur.contains("if (!(event.getScreen() instanceof ShopScreen)) return;")),
                // 点击波 / 第二矩形固定 (1,1)；主请求带自己的方向轴；"NONE, partialTick" 相邻关系保持
                () -> assertTrue(blur.contains("NO_WAVE, axisX, axisY, glass, partialTick")),
                () -> assertTrue(blur.contains("NO_WAVE, 1f, 1f, glass2, partialTick")),
                () -> assertTrue(blur.contains("wave, 1f, 1f, ShopGlassStyle.NONE, partialTick")),
                () -> assertTrue(blur.contains("ShopGlassStyle.NONE, partialTick")),
                // MotionAxis 缺失要走既有的降级路径，不能悄悄当成已就绪
                () -> assertTrue(blur.contains("|| motionAxisUniform == null")),
                // 三条取样分支都读 axisRadius，换页的方向模糊才不会退化成各向同性
                () -> assertTrue(fsh.contains("if (axisRadius < 0.5) {")),
                () -> assertTrue(fsh.contains("max(1.0, axisRadius / 10.0)")),
                () -> assertTrue(fsh.contains("max(axisRadius / 2.5, stepPx)")),
                () -> assertTrue(fsh.contains("oneTexel * axisRadius;")),
                () -> assertFalse(fsh.contains("oneTexel * radius;"))
        );
    }
}
