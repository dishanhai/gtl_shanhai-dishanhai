package com.dishanhai.gt_shanhai.common.recipe.editor;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RecipeEditorGlassStageSourceTest {

    @Test
    void slideBlurIsDrivenByTheStageAnimation() throws IOException {
        String panel = GlassSources.read(GlassSources.JAVA.resolve(
                Path.of("common", "recipe", "editor", "ShanhaiRecipeEditorPanel.java")));
        String page = GlassSources.read(GlassSources.KOTLIN.resolve(
                Path.of("common", "recipe", "editor", "GlassStagePage.kt")));

        assertAll(
                () -> assertTrue(panel.contains(
                        "GlassBlur.requestPage(graphics, viewport, GlassMotion.pageSlide(host.animation.linear(now)));")),
                () -> assertTrue(panel.contains("GlassBlur.warmUp();")),
                // 预热只能放在客户端 tick：在渲染途中建 PostChain 会让当帧剩余绘制写进默认 framebuffer
                () -> assertTrue(panel.contains("if (blurWarmed) return;")),
                () -> assertTrue(panel.substring(panel.indexOf("public void updateScreen()"),
                        panel.indexOf("void syncBlastChrome(")).contains("warmBlurOnce();")),
                () -> assertFalse(panel.substring(panel.indexOf("public void drawInBackground("),
                        panel.indexOf("private void warmBlurOnce()")).contains("warmBlurOnce();")),
                // 主请求必须先于子部件登记：ShopGaussBlur.request* 会清掉第二槽位（弹层模糊）
                () -> assertTrue(panel.indexOf("requestSlideBlur(graphics, now);")
                        < panel.indexOf("super.drawInBackground(graphics, mouseX, mouseY, partialTicks);")),
                // 只在换页进行中请求；视口取静止的内容栏
                () -> assertTrue(panel.contains("if (!host.animation.moving(now)) return;")),
                () -> assertTrue(panel.contains("getPositionY() + BODY_Y,")),
                () -> assertTrue(panel.contains("MAIN_W, BODY_H);")),
                // 页面容器在 Kotlin：Panel 里没有内部类，内容页按内容栏裁剪
                () -> assertFalse(panel.contains("class StagePage")),
                () -> assertTrue(panel.contains("new GlassStagePage(MAIN_X, BODY_Y, MAIN_W, BODY_H,")),
                () -> assertTrue(page.contains("graphics.enableScissor(")),
                () -> assertTrue(page.contains("graphics.pose().translate(slide, 0f, 0f)")),
                () -> assertTrue(page.contains("graphics.disableScissor()")),
                // 共用商店那条 PostChain，不另分配渲染目标
                () -> assertFalse(panel.contains("TextureTarget(")),
                () -> assertFalse(panel.contains("PostChain("))
        );
    }
}
