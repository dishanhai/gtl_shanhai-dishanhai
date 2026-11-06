package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：設定頁和 AE 身份頁抽離後仍透過既有動作與伺服端狀態呈現。 */
class StellarCloudSecondaryUiTest {
    private static final Path JAVA = Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/part");

    @Test
    void configuratorKeepsExistingEnableAndPreviewCallbacksBehindThePresentation() throws Exception {
        String machine = Files.readString(JAVA.resolve("RecipeTypePatternBufferPartMachine.java"));
        assertTrue(machine.contains("StellarCloudConfiguratorUi.create("));
        String ui = Files.readString(JAVA.resolve("cloud/StellarCloudConfiguratorUi.java"));
        assertTrue(ui.contains("new PatternCycleWidget("), "Keep the existing server-synchronized preview");
        assertTrue(ui.contains("!click.isRemote"), "Client clicks cannot announce an applied setting");
        assertTrue(ui.contains("StellarCloudUiWidgets.text("));
        assertFalse(ui.contains("markDirty()"), "Presentation cannot own persistence or supply rules");
        assertTrue(machine.contains("cloudEnabled = enabled; cloudScannedRevision = Long.MIN_VALUE; markDirty();"));
    }

    @Test
    void aeActionsUseTheSharedServerResultControlsAndStillReachExistingBindingChecks() throws Exception {
        String machine = Files.readString(JAVA.resolve("cloud/StellarCloudAECoreMachine.java"));
        assertTrue(machine.contains("StellarCloudAECoreUi.create("));
        assertTrue(machine.contains("changeBinding(player, bind)"));
        String ui = Files.readString(JAVA.resolve("cloud/StellarCloudAECoreUi.java"));
        assertTrue(ui.contains("StellarCloudUiWidgets.action("));
        assertTrue(ui.contains("StellarCloudUiWidgets.text("));
        assertFalse(ui.contains("setOwningPlayer"));
        assertFalse(ui.contains("markDirty()"));
        assertTrue(machine.contains("menu.getModularUI().holder != this"));
        assertTrue(machine.contains("!allowed(server)"));
    }

    @Test
    void aeFramePreservesFancyNavigationWithSmokeTitleSideTabsAndConfigurationPanel() throws Exception {
        String machine = Files.readString(JAVA.resolve("cloud/StellarCloudAECoreMachine.java"));
        assertTrue(machine.contains("StellarCloudAECoreUi.createUI(this, player)"));
        String ui = Files.readString(JAVA.resolve("cloud/StellarCloudAECoreUi.java"));
        assertTrue(ui.contains("extends FancyMachineUIWidget"));
        assertTrue(ui.contains("super.setupFancyUI(provider, hasInventory)"));
        assertTrue(ui.contains("getTitleBar()") && ui.contains("getSideTabsWidget()"));
        assertTrue(ui.contains("getConfiguratorPanel().setTexture("));
        assertTrue(ui.contains("setLeftButtonTexture(") && ui.contains("setRightButtonTexture("));
        assertFalse(ui.contains("StellarGlassTexture."), "AE frame uses fixed smoke independently of the stellar theme toggle");
    }
}
