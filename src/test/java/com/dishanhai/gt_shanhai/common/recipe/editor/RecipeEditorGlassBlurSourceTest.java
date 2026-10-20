package com.dishanhai.gt_shanhai.common.recipe.editor;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RecipeEditorGlassBlurSourceTest {

    @Test
    void facadeIsWiredIntoClientInitAndOnlyAllowsTheEditorScreen() throws IOException {
        String init = GlassSources.read(GlassSources.JAVA.resolve(Path.of("client", "ClientInit.java")));
        String blur = GlassSources.read(GlassSources.KOTLIN.resolve(
                Path.of("client", "gui", "glass", "GlassBlur.kt")));

        assertAll(
                () -> assertTrue(init.contains("GlassBlur.install();")),
                // 守衛登記要在商店的 Post 监听之后，关闭监听走方法引用（Forge 要靠方法引用解析事件类型）
                () -> assertTrue(init.indexOf("ShopGaussBlur::onScreenRenderPost") >= 0),
                () -> assertTrue(init.indexOf("ShopGaussBlur::onScreenRenderPost")
                        < init.indexOf("GlassBlur.install();")),
                () -> assertTrue(init.contains("GlassBlur::onScreenClosing")),
                () -> assertTrue(blur.contains("screen is ModularUIGuiContainer")),
                () -> assertTrue(blur.contains("ShanhaiRecipeEditorFactory.INSTANCE")),
                () -> assertTrue(blur.contains("fun onScreenClosing(event: ScreenEvent.Closing)")),
                () -> assertTrue(blur.contains("ShopGaussBlur.requestDirectional(")),
                () -> assertTrue(blur.contains("ShopGlassStyle.SWAP")),
                // 只在编辑器界面关闭时收尾；后处理没就绪就不登记请求
                () -> assertTrue(blur.contains("if (isEditorScreen(event.screen)) ShopGaussBlur.clear()")),
                () -> assertTrue(blur.contains("!ShopGaussBlur.isReady()")),
                // 共用同一条 PostChain，不另分配渲染目标
                () -> assertFalse(blur.contains("TextureTarget(")),
                () -> assertFalse(blur.contains("PostChain("))
        );
    }
}
