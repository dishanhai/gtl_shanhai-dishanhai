package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StellarPatternGlassUiSourceTest {
    private static String source(String name) throws IOException {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/part", name));
    }

    @Test
    void physicalAndRemotePatternViewsKeepFancyNavigationWithGlassShell() throws IOException {
        String machine = source("RecipeTypePatternBufferPartMachine.java");
        assertTrue(machine.contains("return RecipeThreadConfigurationPage.createGlassUI(this, player)"));
        assertTrue(machine.contains("RecipeThreadConfigurationPage.createGlassUI(this, player, holder)"));
        assertTrue(machine.contains("pagination.createPaginationUI(catalystUIManager::toggleFor)"));
    }

    @Test
    void paginationRebuildsGlassSlotsWithoutChangingSlotIdentity() throws IOException {
        String pagination = source("CachedPatternPaginationUIManager.java");
        assertTrue(pagination.contains("new CachedPatternSlotWidget(")
                && pagination.contains("patternInventory, slotIndex, x, y"));
        assertTrue(pagination.contains("StellarGlassTexture.slot()"));
        assertTrue(pagination.contains("slot.setOnMiddleClick(() -> onMiddleClicked.accept(finalSlot))"));
    }

    @Test
    void dynamicCatalystAndMultiplierControlsReceiveGlassStyle() throws IOException {
        String machine = source("RecipeTypePatternBufferPartMachine.java");
        String panel = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/part/ui/RecipeThreadPanelWidget.java"));
        assertTrue(machine.contains("RecipeThreadPanelWidget.styleCatalystControls(this)"));
        assertTrue(machine.contains("RecipeThreadPanelWidget.styleStellarNumberInput(multiplierInput)"));
        assertTrue(panel.contains("getWidgetsByType(TankWidget.class)"));
        assertTrue(panel.contains("setBordered(false).setBackground(StellarGlassTexture.display())"));
        assertTrue(machine.contains("int uiHeight = rowsPerPage * 18 + 32;"));
    }

    @Test
    void stockConfigurationUsesGlassCellsAndNavigation() throws IOException {
        String stock = source("PatternBufferStockSlotWidget.java");
        String request = source("PatternBufferStockRequestConfigurator.java");
        assertTrue(stock.contains("StellarGlassTexture.display().draw"));
        assertTrue(stock.contains("pageUpWidget.setButtonTexture"));
        assertTrue(stock.contains("pageDownWidget.setButtonTexture"));
        assertTrue(stock.contains("extends AEDualConfigSlotWidget"));
        assertTrue(request.contains("setBordered(false).setBackground(StellarGlassTexture.display())"));
        assertTrue(request.contains("new GuiTextureGroup(StellarGlassTexture.button()"));
    }

    @Test
    void stellarTooltipsUseTheSharedGlassRenderer() throws IOException {
        String client = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/ClientInit.java"));
        String tooltip = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/gui/StellarGlassTooltip.java"));
        assertTrue(client.contains("StellarGlassTooltip::onTooltipPre"));
        assertTrue(tooltip.contains("GlassTooltip.render(event, StellarGlassTexture.neutral())"));
    }

    @Test
    void stellarPanelsUseTheSharedSmokeGlassSurfaces() throws IOException {
        String texture = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/part/ui/StellarGlassTexture.java"));
        String machine = source("RecipeTypePatternBufferPartMachine.java");
        assertTrue(texture.contains("GlassPanelTexture.smokePanel()"));
        assertTrue(texture.contains("GlassSurface.smokeControl("));
        assertTrue(texture.contains("GlassPanelTexture.smokeButtonOverlay()"));
        assertTrue(machine.contains("catalystUIManager.setBackground(StellarGlassTexture.floatingPanel())"));
        assertTrue(texture.contains("FLOATING_PANEL = themed(ROOT_GLASS, GlassPanelTexture.page())"));
        assertTrue(machine.contains("group.setBackground(StellarGlassTexture.page())"));
    }

    @Test
    void clientCanSwitchStellarBetweenBlueAndNeutralGlassWithoutChangingWidgets() throws IOException {
        String config = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/config/ShanhaiUiClientConfig.java"));
        String page = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/part/ui/RecipeThreadConfigurationPage.java"));
        String texture = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/part/ui/StellarGlassTexture.java"));
        String tooltip = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/gui/StellarGlassTooltip.java"));
        assertTrue(config.contains("stellarGlass"));
        assertTrue(texture.contains("StellarGlassTexture.toggle()"));
        assertTrue(page.contains("panel.attachConfigurators(StellarGlassTexture.appearanceConfigurator())"));
        assertTrue(page.contains("getSize().height - panel.getSize().height - 4"));
        assertTrue(page.contains("new ModularUI(176, 166, holder, player).widget(root)"));
        assertTrue(page.contains("GuiTextures.TITLE_BAR_BACKGROUND"));
        assertTrue(texture.contains("GuiTextures.BACKGROUND"));
        assertTrue(texture.contains("GuiTextures.BUTTON"));
        assertTrue(tooltip.contains("StellarGlassTexture.neutral()"));
        assertTrue(texture.contains("gui.gt_shanhai.stellar.ui_mode.neutral"));
        assertTrue(texture.contains("gui.gt_shanhai.stellar.ui_mode.blue"));
        assertTrue(texture.contains("GlassColors.SMOKE : GlassColors.DARK"));
        assertTrue(texture.contains("RecipeThreadPanelWidget.drawGlass("));
        assertTrue(texture.contains("GlassPanelTexture.page()"));
        assertTrue(texture.contains("GlassPanelTexture.button()"));
        assertTrue(config.contains("SPEC.save()"));
    }
}
