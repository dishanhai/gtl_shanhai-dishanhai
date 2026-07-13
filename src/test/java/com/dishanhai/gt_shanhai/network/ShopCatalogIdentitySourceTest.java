package com.dishanhai.gt_shanhai.network;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopCatalogIdentitySourceTest {

    @Test
    void actionsAndEditorsUseRevisionPlusServerEntryKey() throws Exception {
        String action = source("network/ShopActionPacket.java");
        String edit = source("network/ShopEditPacket.java");
        String client = source("client/gui/shop/ShopScreen.java")
                + source("client/gui/shop/ShopEntryEditScreen.java")
                + source("client/gui/shop/RewardChoiceScreen.java")
                + source("client/gui/shop/FtbqRewardChoiceScreen.java");

        assertAll(
                () -> assertTrue(action.contains("catalogRevision")),
                () -> assertTrue(action.contains("entryKey")),
                () -> assertTrue(action.contains("ShopConfig.resolve")),
                () -> assertTrue(action.contains("this.catalogRevision = buf.readLong();")),
                () -> assertTrue(action.contains("this.entryKey = buf.readLong();")),
                () -> assertTrue(action.contains("buf.writeLong(catalogRevision);")),
                () -> assertTrue(action.contains("buf.writeLong(entryKey);")),
                () -> assertFalse(action.contains("locate(")),
                () -> assertTrue(edit.contains("catalogRevision")),
                () -> assertTrue(edit.contains("oldEntryKey")),
                () -> assertTrue(edit.contains("ShopConfig.resolve")),
                () -> assertTrue(edit.contains("this.catalogRevision = buf.readLong();")),
                () -> assertTrue(edit.contains("this.oldEntryKey = buf.readLong();")),
                () -> assertTrue(edit.contains("buf.writeLong(catalogRevision);")),
                () -> assertTrue(edit.contains("buf.writeLong(oldEntryKey);")),
                () -> assertFalse(edit.contains("locate(")),
                () -> assertFalse(client.contains("ShopConfig.getEntries().indexOf")));
    }

    @Test
    void addRejectsStaleRevisionAndNonSentinelOldKeyBeforeMutation() throws Exception {
        String edit = source("network/ShopEditPacket.java");
        int addBranch = edit.indexOf("if (pkt.action == Action.ADD)");
        int revisionGuard = edit.indexOf("current.revision() != pkt.catalogRevision");
        int manifestReply = edit.indexOf("ShopCatalogManifestPacket.sendLatestIfAllowed(");
        int keyGuard = edit.indexOf("pkt.oldEntryKey != -1L", addBranch);
        int mutation = edit.indexOf("ShopConfig.addEntry(entry)", addBranch);

        assertAll(
                () -> assertTrue(addBranch >= 0),
                () -> assertTrue(revisionGuard >= 0),
                () -> assertTrue(revisionGuard < addBranch),
                () -> assertTrue(manifestReply > revisionGuard),
                () -> assertTrue(manifestReply < mutation),
                () -> assertTrue(keyGuard > addBranch),
                () -> assertTrue(mutation > revisionGuard),
                () -> assertTrue(mutation > keyGuard));
    }

    @Test
    void wireFormatUsesProtocolVersionThree() throws Exception {
        String network = source("network/ShanhaiNetwork.java");
        assertTrue(network.contains("PROTOCOL_VERSION = \"3\""));
    }

    private static String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/" + relative));
    }
}
