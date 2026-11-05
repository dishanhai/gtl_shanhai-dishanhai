package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 山海：覆蓋配置器自繪圖示與初始化後才建立的重新命名控件。 */
class StellarPatternClearGlassSourceTest {
    private static String source(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai", relative));
    }

    @Test
    void refundKeepsItsOriginalConfiguratorAndSkinsBothIconAndOuterTab() throws IOException {
        String machine = source("common/machine/part/RecipeTypePatternBufferPartMachine.java");
        String page = source("common/machine/part/ui/RecipeThreadConfigurationPage.java");
        assertTrue(machine.contains("StellarGlassTexture.configurator(parentConfigurators.get(PARENT_REFUND))"));
        assertTrue(page.contains("getConfiguratorPanel().setTexture(StellarGlassTexture.panel())"));
        assertTrue(page.contains("tab.setBackground(StellarGlassTexture.panel())"));
    }

    @Test
    void smokeGlassUsesLibrarySurfacesAndReadableForeground() throws IOException {
        String texture = source("common/machine/part/ui/StellarGlassTexture.java");
        assertTrue(texture.contains("GlassPanelTexture.smokePanel()"));
        assertTrue(texture.contains("GlassPanelTexture.smokeButtonOverlay()"));
        assertTrue(texture.contains("GlassColors.SMOKE"));
        assertTrue(texture.contains("GuiTextures.BUTTON"));
    }

    @Test
    void renameIsStyledAfterItsInheritedControlsAreCreated() throws IOException {
        String machine = source("common/machine/part/RecipeTypePatternBufferPartMachine.java");
        assertTrue(machine.contains("StellarGlassTexture.renameButton("));
        String texture = source("common/machine/part/ui/StellarGlassTexture.java");
        assertTrue(texture.contains("super.initWidget()"));
        assertTrue(texture.contains("getWidgetsByType(SwitchWidget.class)"));
        assertTrue(texture.contains("GuiTextures.VANILLA_BUTTON"));
        assertTrue(texture.contains("setTexture("));
    }

    @Test
    void allStellarInventorySlotsAndFancyRootUseTheClearSurface() throws IOException {
        String panel = source("common/machine/part/ui/RecipeThreadPanelWidget.java");
        int start = panel.indexOf("void applyStellarGlassSlots(");
        int end = panel.indexOf("private static boolean hasPatternOverlay", start);
        assertTrue(panel.substring(start, end).contains("inventory.setSlotBackground(StellarGlassTexture.slot())"));
        assertTrue(panel.substring(start, end).contains("slot.setBackgroundTexture(StellarGlassTexture.slot())"));
        String page = source("common/machine/part/ui/RecipeThreadConfigurationPage.java");
        assertTrue(page.contains("StellarGlassTexture.root().draw("));
        assertTrue(page.contains("StellarGlassTexture.textColor()"));
    }

    @Test
    void switchTexturesAreReadOnlyAndClientOnlyWhileNewControlsAreRestyled() throws IOException {
        String mixin = Files.readString(Path.of("src/main/resources/gt_shanhai.ldlib.mixin.json"));
        assertTrue(mixin.indexOf("accessor.StellarSwitchWidgetAccessor") > mixin.indexOf("\"client\""));
        String accessor = source("mixin/accessor/StellarSwitchWidgetAccessor.java");
        assertTrue(accessor.contains("@Accessor(\"baseTexture\")"));
        assertTrue(accessor.contains("@Accessor(\"pressedTexture\")"));
        String panel = source("common/machine/part/ui/RecipeThreadPanelWidget.java");
        assertTrue(panel.contains("STELLAR_STYLED_CONTROLS.put(widget, neutral)"));
        assertTrue(panel.contains("widget instanceof SwitchWidget toggle"));
        assertTrue(panel.contains("widget.setBackground(StellarGlassTexture.adapt(widget.getBackgroundTexture()))"));
        String machine = source("common/machine/part/RecipeTypePatternBufferPartMachine.java");
        assertTrue(machine.contains("RecipeThreadPanelWidget.stellarRoot(176, 180)"));
    }

    @Test
    void clearButtonTextRetainsSelectionAndStatusColors() throws IOException {
        String texture = source("common/machine/part/ui/StellarGlassTexture.java");
        assertTrue(texture.contains("StellarGlassText.forSmokeGlass(original)"));
    }
}
