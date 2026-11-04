package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaintenanceHatchGlassUiSourceTest {
    private static final Path MACHINE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "part", "DShanhaiMaintenanceHatchMachine.java");
    private static final Path PAGE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "part", "ui", "RecipeThreadConfigurationPage.java");
    private static final Path PANEL = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "part", "ui", "RecipeThreadPanelWidget.java");

    @Test
    void glassSlotsCoverInventoryAndRebuiltFancyPages() throws IOException {
        String page = Files.readString(PAGE);
        String panel = Files.readString(PANEL);
        assertTrue(panel.contains("inventory.setSlotBackground(Renderer.SLOT)"),
                "背包和快捷栏需通过槽背景 API 一同替换");
        assertTrue(panel.contains("slot.setBackgroundTexture(Renderer.SLOT)"),
                "机器物品槽需替换原生槽底，而不是在槽后面叠玻璃");
        assertTrue(page.contains("super.setupFancyUI(provider, hasInventory)")
                        && page.contains("RecipeThreadPanelWidget.applyGlassSlots(this)"),
                "切页重建后也必须重新应用玻璃槽底");
        assertTrue(panel.contains("applyGlassSlots(this)"),
                "独立线程仓根节点也必须应用背包玻璃槽底");
    }

    @Test
    void maintenanceAndRecipeThreadPagesShareTheSameGlassBackdrop() throws IOException {
        String machine = Files.readString(MACHINE);
        String page = Files.readString(PAGE);
        String panel = Files.readString(PANEL);

        assertTrue(page.contains("RecipeThreadPanelWidget.drawGlass(graphics"),
                "配方线程舱玻璃绘制器应作为共享材质入口");
        assertTrue(page.contains("setTabTexture(GlassPanelTexture.page())")
                        && page.contains("setTabHoverTexture(GlassPanelTexture.display())")
                        && page.contains("setTabPressedTexture(GlassPanelTexture.panel())"),
                "Fancy 侧栏页签必须使用玻璃普通、悬停和选中材质");
        assertTrue(page.contains("setLeftButtonTexture(new GuiTextureGroup(GlassPanelTexture.button()")
                        && page.contains("setRightButtonTexture(new GuiTextureGroup(GlassPanelTexture.button()"),
                "Fancy 侧栏滚动按钮必须使用玻璃材质");
        assertTrue(panel.contains("ShopTheme.GLASS.rootShadow")
                        && panel.contains("ShopTheme.GLASS.backdrop")
                        && panel.contains("ShopTheme.GLASS.box"),
                "线程舱根面板必须直接复用商店 GLASS 根板的阴影、模糊和边框");
        assertTrue(machine.contains("return RecipeThreadConfigurationPage.createUI(this, player)"),
                "终焉聚合枢纽应复用统一的 Fancy 玻璃 UI 根节点");
        assertFalse(machine.contains("scrollGroup.setBackground(GuiTextures.DISPLAY)"),
                "维护舱主面板不能再被不透明 DISPLAY 底色盖住");
        assertFalse(machine.contains("group.setBackground(GuiTextures.BACKGROUND_INVERSE)"),
                "维护舱主面板不能再叠原版不透明背景");
    }
}
