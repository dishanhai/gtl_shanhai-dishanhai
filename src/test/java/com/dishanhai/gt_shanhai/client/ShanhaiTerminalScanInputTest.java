package com.dishanhai.gt_shanhai.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** 山海：一次按下僅放行一次檢查，放開後可立即再次檢查。 */
class ShanhaiTerminalScanInputTest {

    @Test
    void heldAttackOnlyAllowsOneScan() {
        var press = new ShanhaiTerminalScanInput.PressState();
        assertTrue(press.begin());
        assertFalse(press.begin());
        assertFalse(press.begin());
    }

    @Test
    void releasingAllowsTheNextClickWithoutACooldown() {
        var press = new ShanhaiTerminalScanInput.PressState();
        for (int i = 0; i < 3; i++) {
            assertTrue(press.begin());
            assertFalse(press.begin());
            press.release();
        }
    }

    @Test
    void inputGateRunsBeforeVanillaMiningAndResetsMouseAndKeyboardBindings() throws Exception {
        Path source = Path.of("src/main/java/com/dishanhai/gt_shanhai/client/ShanhaiTerminalScanInput.java");
        assertTrue(Files.exists(source), "須在客戶端阻止按住時持續重送 START");
        String input = Files.readString(source);
        assertTrue(input.contains("value = Dist.CLIENT"));
        assertTrue(input.contains("InputEvent.InteractionKeyMappingTriggered"));
        assertTrue(input.contains("event.isAttack()"));
        assertTrue(input.contains("HitResult.Type.BLOCK"));
        assertTrue(input.contains("instanceof IMultiController"));
        assertTrue(input.contains("if (!PRESS.begin())"));
        assertTrue(input.contains("event.setCanceled(true)"));
        assertTrue(input.contains("event.setSwingHand(false)"));
        assertTrue(input.contains("matchesMouse(event.getButton())"));
        assertTrue(input.contains("matches(event.getKey(), event.getScanCode())"));
        assertTrue(input.contains("!minecraft.options.keyAttack.isDown()"));
    }
}
