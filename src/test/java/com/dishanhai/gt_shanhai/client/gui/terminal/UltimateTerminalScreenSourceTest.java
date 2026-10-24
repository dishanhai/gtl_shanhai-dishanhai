package com.dishanhai.gt_shanhai.client.gui.terminal;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：終極終端設定介面必須沿用玻璃 Screen 的動效、模糊生命週期，且不得截斷文字或回退 ModularUI。 */
class UltimateTerminalScreenSourceTest {
    private static final Path ROOT = Path.of("src/main/java/com/dishanhai/gt_shanhai/");

    @Test void screenSharesGlassMotionAndCleansUp() throws Exception {
        String screen = Files.readString(ROOT.resolve("client/gui/terminal/UltimateTerminalScreen.java"));
        assertTrue(screen.contains("extends ScaledScreen"));
        assertTrue(screen.contains("GlassPanelTexture.root()"));
        assertTrue(screen.contains("GlassBlur.requestPage"));
        assertTrue(screen.contains("GlassBlur.warmUp()"));
        String shared = Files.readString(ROOT.resolve("client/gui/terminal/TerminalGlassUi.java"));
        assertTrue(shared.contains("reduceMotion()"));
        assertTrue(screen.contains("reduced()"));
        assertTrue(screen.contains("void removed()"));
        assertTrue(screen.contains("ShopGaussBlur.clear()"));
        assertTrue(screen.contains("ShopGaussBlur.clearTopLayer()"));
        String rendering = screen.substring(screen.indexOf("void renderScaledBackground"), screen.indexOf("private float slideOffset"));
        assertFalse(rendering.contains("warmUp()"));
        for (String forbidden : new String[]{"TextType.HIDE", "LEFT_HIDE", ".substring(", "trimText(", "plainSubstrByWidth("})
            assertFalse(screen.contains(forbidden), forbidden);
        assertTrue(Files.readString(ROOT.resolve("client/ClientInit.java"))
                .contains("instanceof com.dishanhai.gt_shanhai.client.gui.terminal.UltimateTerminalScreen"));
    }

    @Test void screenCoversEverySettingTheOldUiExposed() throws Exception {
        String screen = Files.readString(ROOT.resolve("client/gui/terminal/UltimateTerminalScreen.java"));
        for (String field : new String[]{"MIRROR", "REPLACE", "ABSOLUTE_REPLACE", "AE_MODE", "NO_CHAMBER", "DISMANTLE", "MODULE_CHECK"})
            assertTrue(screen.contains("Field." + field), field);
        for (String key : new String[]{"mirror", "replace", "absolute_replace", "ae_mode", "no_chamber", "dismantle", "module_check"})
            assertTrue(screen.contains("\"" + key + "\""), key);
        assertTrue(screen.contains("Field.REPEAT"));
        assertTrue(screen.contains("Field.REPLACEMENT"));
        // 讀取只能用副本：Config.get 會補寫 Version / UUID。
        assertTrue(screen.contains(".copy()"));
    }

    @Test void behaviorOpensScreenInsteadOfModularUi() throws Exception {
        String behavior = Files.readString(ROOT.resolve("common/item/ShanhaiUltimateTerminalBehavior.java"));
        assertTrue(behavior.contains("UltimateTerminalScreenOpener.open"));
        assertFalse(behavior.contains("ModularUI"));
        assertFalse(behavior.contains("IItemUIFactory"));
        assertFalse(behavior.contains("BlockMapSelectorWidget"));
    }

    @Test void packetRevalidatesHeldItemAndTier() throws Exception {
        String packet = Files.readString(ROOT.resolve("network/UltimateTerminalConfigPacket.java"));
        assertTrue(packet.contains("DShanhaiItems.ULTIMATE_TERMINAL"));
        assertTrue(packet.contains("BlockMap.tierBlockMap"));
        assertTrue(packet.contains("ShanhaiTerminalCraftingManager.clear"));
        assertTrue(Files.readString(ROOT.resolve("network/ShanhaiNetwork.java"))
                .contains("UltimateTerminalConfigPacket.class"));
    }
}
