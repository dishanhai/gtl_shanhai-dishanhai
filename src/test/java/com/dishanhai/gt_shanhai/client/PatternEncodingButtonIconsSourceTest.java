package com.dishanhai.gt_shanhai.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 山海：验证编码终端和 JEI 入口的图标接入。 */
class PatternEncodingButtonIconsSourceTest {
    private static final Path SOURCE = Path.of("src/main/java/com/dishanhai/gt_shanhai");

    @Test
    void terminalButtonsUseTheirOwnEncodingIcons() throws IOException {
        String toggles = Files.readString(SOURCE.resolve("mixin/PatternWrapControlScreenMixin.java"));
        String upload = Files.readString(SOURCE.resolve("mixin/EaepGtlCoreUploadButtonLayoutMixin.java"));

        String button = Files.readString(SOURCE.resolve("client/gui/PatternEncodingIconButton.java"));
        assertTrue(button.contains("super(0, 0, SIZE, SIZE,") && button.contains("SIZE = 8;"), "整行按钮都是 8×8");
        for (String state : new String[] { "wrap.auto", "wrap.on", "wrap.off", "recipe_id.on", "recipe_id.off",
                "byproduct.on", "byproduct.off", "virtual_provider.on", "virtual_provider.off" }) {
            assertTrue(toggles.contains("\"" + state + "\""), state);
        }
        assertTrue(toggles.contains("getResource(texture).isPresent() ? new GraphicIcon(texture) : fallback"),
                "缺少状态贴图的旧版 SH-Better-UI 应继续使用原有图标");
        assertTrue(upload.contains("Appearance(gtShanhai$compactCloudIcon,"), "云端上传");
        assertTrue(upload.contains("textures/gui/cloud_upload_compact.png"), "云端上传按钮的 8 像素图标");
    }

    @Test
    void jeiButtonsUseDistinctSvgIcons() throws IOException {
        String jei = Files.readString(SOURCE.resolve("integration/jei/JeiPatternQuickEncodeButtons.java"));

        assertTrue(jei.contains("patternIcon = new RecipeButtonSvgIcon(\"quick_encode\")"), "快速编码");
        assertTrue(jei.contains("recipeTypeIcon = new RecipeButtonSvgIcon(\"recipe_batch\")"), "按配方类型编码");
        assertTrue(jei.contains("cloudIcon = new RecipeButtonSvgIcon(\"cloud_upload\")"), "云端上传");
        assertTrue(jei.contains("bookmarkIcon = new RecipeButtonSvgIcon(\"bookmark\")"), "书签收藏");
        assertTrue(jei.contains("byproductIcon = new RecipeButtonSvgIcon(\"byproduct_upload\")"), "副产物上传");
    }

    @Test
    void jeiIconsUseAe2ToolbarBackgroundWithAnInset() throws IOException {
        String icon = Files.readString(SOURCE.resolve("integration/jei/RecipeButtonSvgIcon.java"));

        assertTrue(icon.contains("Icon.TOOLBAR_BUTTON_BACKGROUND.getBlitter()"));
        assertTrue(icon.contains("(SIZE - 2) / RecipeButtonSvgMesh.VIEW_SIZE"));
        assertTrue(icon.contains("emit(vertices, matrix, quad.a(), x + 1, y + 1)"), "图标内缩，留出底板边框");
    }
}
