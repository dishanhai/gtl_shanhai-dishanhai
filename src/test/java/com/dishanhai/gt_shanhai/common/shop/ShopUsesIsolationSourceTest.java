package com.dishanhai.gt_shanhai.common.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopUsesIsolationSourceTest {

    private String source(String path) throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai", path));
    }

    @Test
    void editorHasIndependentFieldsAndDoesNotSendUntouchedSaveBalance() throws Exception {
        String editor = source("client/gui/shop/ShopEntryEditScreen.java");
        assertTrue(editor.contains("Component.literal(\"存檔次數\")"));
        assertTrue(editor.contains("Component.literal(\"服務端次數\")"));
        assertTrue(editor.contains("this.limit = entry.getServerUses()"));
        assertTrue(editor.contains("saveUses != initialSaveUses"));
        assertTrue(editor.contains("pkt.withSaveUses(saveUses)"));
    }

    @Test
    void serverEditKeepsLatestSaveBalanceUnlessExplicitlyOverridden() throws Exception {
        String packet = source("network/ShopEditPacket.java");
        assertTrue(packet.contains("pkt.saveUses != null ? pkt.saveUses"));
        assertTrue(packet.contains("old != null ? old.getRemainingUses() : entry.getServerUses()"));
        assertFalse(packet.contains("if (ok && entry.isLimited())"));
    }

    @Test
    void hidingAnEntryCannotCopyConsumedSaveBalanceIntoServerConfig() throws Exception {
        String packet = source("network/ShopToggleHiddenPacket.java");
        assertTrue(packet.contains("old.getServerUses(), old.getDisplayIcons()"));
        assertTrue(packet.contains("updated.overrideRemainingUses(old.getRemainingUses())"));
    }

    @Test
    void resetIsPermissionProtectedAndPublishesFreshRevisionWithoutSavingConfig() throws Exception {
        String commands = source("command/DShanhaiCommands.java");
        int command = commands.indexOf("Commands.literal(\"重置次数\")");
        assertTrue(command >= 0);
        String node = commands.substring(command, commands.indexOf("Commands.literal(\"授权\")", command));
        assertTrue(node.contains("s.hasPermission(2)"));
        assertTrue(node.contains(".resetSaveUses(ctx.getSource().getServer())"));

        String config = source("common/shop/ShopConfig.java");
        int reset = config.indexOf("public static synchronized int resetSaveUses");
        String body = config.substring(reset, config.indexOf("public static synchronized void reload()", reset));
        assertTrue(body.contains("data.reset(entry)"));
        assertTrue(body.contains("publish(entries)"));
        assertFalse(body.contains("save();"));
    }
}
