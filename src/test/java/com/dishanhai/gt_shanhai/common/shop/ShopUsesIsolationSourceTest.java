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
        assertTrue(editor.contains("Component.literal(\"存档次数\")"));
        assertTrue(editor.contains("Component.literal(\"服务端次数\")"));
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
        assertTrue(packet.contains("if (pkt.saveUses != null)"));
        assertTrue(packet.contains("limits.resetPlayers(entry.getStableId())"));
        assertTrue(packet.contains("WalletAccountAPI.sync(online)"));
    }

    @Test
    void hidingAnEntryCannotCopyConsumedSaveBalanceIntoServerConfig() throws Exception {
        String packet = source("network/ShopToggleHiddenPacket.java");
        String config = source("common/shop/ShopConfig.java");
        assertTrue(packet.contains("ShopConfig.batchSetHidden(List.of(old.getStableId()), hidden)"));
        assertTrue(config.contains("updated.set(i, copyEntry(old, old.getCategory(), hidden))"));
        assertTrue(config.contains("old.getServerUses(), old.getDisplayIcons()"));
        assertTrue(config.contains("old.getDiscountStartMs(), old.getDiscountEndMs(), old.getSubmissionItems()"));
        assertTrue(config.contains("copy.overrideRemainingUses(old.getRemainingUses())"));
    }

    @Test
    void restoredSaveDefaultsArePublishedForTheClientFallback() throws Exception {
        String config = source("common/shop/ShopConfig.java");
        int sync = config.indexOf("public static synchronized void syncLimitsFromSave");
        String body = config.substring(sync, config.indexOf("public static synchronized int resetSaveUses", sync));
        int restore = body.indexOf("data.applyTo(entry)");
        assertTrue(restore >= 0);
        assertTrue(body.indexOf("publish(entries)") > restore,
                "回填存檔個人預設額度後必須重建序列化目錄，避免客戶端沿用服務端基準");
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
        assertTrue(body.contains("WalletAccountAPI.sync(player)"));
        assertFalse(body.contains("save();"));
    }
}
