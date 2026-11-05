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
        String libraryClient = java.nio.file.Files.readString(Path.of(
                "../SH-Better-UI/src/main/java/com/dishanhai/sh_better_ui/client/UiClient.java"));

        assertAll(
                () -> assertTrue(init.contains("GaussBlur.allowScreen(")),
                // 消费端只登记业务画面，独立库持有唯一 Post 与关闭清理。
                () -> assertFalse(init.contains("GaussBlur::onScreenRenderPost")),
                () -> assertTrue(libraryClient.contains("GaussBlur.onScreenRenderPost(event)")),
                () -> assertTrue(libraryClient.contains("onScreenClosing(ScreenEvent.Closing event)")),
                () -> assertTrue(init.contains("screen instanceof com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer")),
                () -> assertTrue(init.contains("ShanhaiRecipeEditorFactory.INSTANCE")),
                () -> assertFalse(blur.contains("ShanhaiRecipeEditorFactory")),
                () -> assertTrue(blur.contains("GaussBlur.requestDirectional(")),
                () -> assertTrue(blur.contains("GlassStyle.SWAP")),
                // 所有画面关闭均清请求；后处理没就绪就不登记请求。
                () -> assertTrue(libraryClient.contains("GaussBlur.clear()")),
                () -> assertTrue(blur.contains("!GaussBlur.isReady()")),
                // 共用同一条 PostChain，不另分配渲染目标
                () -> assertFalse(blur.contains("TextureTarget(")),
                () -> assertFalse(blur.contains("PostChain("))
        );
    }
}
