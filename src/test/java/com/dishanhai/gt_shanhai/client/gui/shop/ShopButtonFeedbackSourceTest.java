package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopButtonFeedbackSourceTest {

    private static final String SHOP = "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java";
    private static final String UTIL = "src/main/java/com/dishanhai/gt_shanhai/client/gui/scaled/GuiRenderUtil.java";

    @Test
    void everyButtonHitPressesTheButtonAndReleaseLiftsIt() throws Exception {
        String s = Files.readString(Path.of(SHOP));
        assertTrue(s.contains("private final ButtonFeedback buttonFeedback = new ButtonFeedback(hoverButtons);"),
                "按下态与 hover 共用同一张 hoverButtons 表");
        String hit = extractBlock(s, "private boolean buttonHit(");
        assertTrue(hit.contains("buttonFeedback.press(") && hit.contains("pressKey(x, y, w, h)"),
                "所有按钮点击都经 buttonHit，压下 key 只由几何决定");

        String released = extractBlock(s, "protected boolean universalMouseReleased(");
        int release = released.indexOf("buttonFeedback.release(");
        int tabDrag = released.indexOf("if (dragTabLevel != -1)");
        assertTrue(release >= 0 && release < tabDrag, "松开先结束按下态，再走拖拽分支");
        assertFalse(released.contains("return buttonFeedback.release"), "松开按钮不能吞掉拖拽的松开");
    }

    @Test
    void clickSoundPlaysEvenWhenMotionIsReduced() throws Exception {
        String wave = extractBlock(Files.readString(Path.of(SHOP)), "private void armClickWave(");
        int sound = wave.indexOf("ShopUiSounds.click()");
        int reduce = wave.indexOf("ClientShopUiSettings.reduceMotion()");
        int arm = wave.indexOf("clickWaveAtMs =");
        assertTrue(sound >= 0 && sound < reduce && reduce < arm, "先出声，减少动效时再跳过波纹");
    }

    @Test
    void buttonsDrawThroughTheFeedbackVisual() throws Exception {
        String s = Files.readString(Path.of(SHOP));
        String draw = extractBlock(s, "private void drawButton(");
        assertTrue(draw.contains("hoverButtons.follow("), "hover 弹簧照旧");
        assertTrue(draw.contains("my - frameMy"), "滚动区按内容坐标画，要换回屏幕坐标对上按下 key");
        assertTrue(draw.contains("buttonFeedback.visual(") && draw.contains("GuiRenderUtil.withButtonMotion("));
        String render = extractBlock(s, "int border, int textColor, long actionKey)");
        assertTrue(render.contains("buttonFeedback.visual(") && render.contains("GuiRenderUtil.withButtonMotion("));
    }

    @Test
    void frameDrivesPointerAndLifecycleCancelsStuckPresses() throws Exception {
        String s = Files.readString(Path.of(SHOP));
        String bg = extractBlock(s, "protected void renderScaledBackground(");
        assertTrue(bg.contains("frameMy = my;"));
        assertTrue(bg.contains("buttonFeedback.pointer(mx, my, nowMs)") && bg.contains("buttonFeedback.tick(nowMs)"));
        assertTrue(bg.contains("mouseHandler.isLeftPressed()"), "松开落到别的界面时由每帧兜底补松开");
        assertTrue(extractBlock(s, "protected void initScaled()").contains("buttonFeedback.cancel("));
        assertTrue(extractBlock(s, "public void removed()").contains("buttonFeedback.cancel("));
    }

    @Test
    void buttonMotionRestoresThePoseStack() throws Exception {
        String util = Files.readString(Path.of(UTIL));
        String motion = extractBlock(util, "public static void withButtonMotion(");
        assertTrue(motion.contains("finally") && motion.contains("popPose()"));
        assertTrue(util.contains("public static void drawPendingSweep("));
    }

    static String extractBlock(String source, String declaration) {
        int start = source.indexOf(declaration);
        assertTrue(start >= 0, "缺少方法声明: " + declaration);
        int openBrace = source.indexOf('{', start);
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) {
                return source.substring(openBrace, i + 1);
            }
        }
        throw new AssertionError("方法体未闭合: " + declaration);
    }
}
