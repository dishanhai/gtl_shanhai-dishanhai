package com.dishanhai.gt_shanhai.network;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopCatalogSyncSourceTest {

    @Test
    void newServerPacketsAppendAfterChunksAndStayClientBound() throws Exception {
        String network = source("network/ShanhaiNetwork.java");
        int chunk = network.indexOf("ShopCatalogChunkPacket.class");
        int manifest = network.indexOf("ShopCatalogManifestPacket.class");
        int state = network.indexOf("ShopCatalogStatePacket.class");

        assertAll(
                () -> assertTrue(chunk >= 0),
                () -> assertTrue(manifest > chunk),
                () -> assertTrue(state > manifest),
                () -> assertTrue(registration(network, manifest).contains("NetworkDirection.PLAY_TO_CLIENT")),
                () -> assertTrue(registration(network, state).contains("NetworkDirection.PLAY_TO_CLIENT")),
                () -> assertTrue(network.contains("PROTOCOL_VERSION = \"3\"")));
    }

    @Test
    void staleCatalogRequestsReceiveLatestManifestBeforeMutation() throws Exception {
        String chunk = source("network/ShopCatalogChunkRequestPacket.java");
        String action = source("network/ShopActionPacket.java");
        String edit = source("network/ShopEditPacket.java");
        String manifest = source("network/ShopCatalogManifestPacket.java");
        int chunkLimit = chunk.indexOf("ShopCatalogManifestPacket.allowChunkRequest(player)");
        int chunkSnapshot = chunk.indexOf("ShopCatalogSnapshot snapshot = ShopConfig.snapshot()");
        int actionStale = action.indexOf("current.revision() != pkt.catalogRevision");
        int actionReply = action.indexOf("ShopCatalogManifestPacket.sendLatestIfAllowed(", actionStale);
        int undoException = action.indexOf("pkt.action != Action.UNDO_DELETE", actionReply);
        int undoExceptionOpen = action.indexOf('{', undoException);
        int undoExceptionReturn = action.indexOf("return;", undoExceptionOpen);
        int undoExceptionClose = action.indexOf('}', undoExceptionOpen);
        int undoBranch = action.indexOf("if (pkt.action == Action.UNDO_DELETE)");
        int editStale = edit.indexOf("current.revision() != pkt.catalogRevision");
        int editReply = edit.indexOf("ShopCatalogManifestPacket.sendLatestIfAllowed(", editStale);
        int editReturn = edit.indexOf("return;", editReply);
        int editAdd = edit.indexOf("ShopConfig.addEntry(entry)");
        int editReplace = edit.indexOf("ShopConfig.replaceEntry(old, entry)");

        assertAll(
                () -> assertTrue(manifest.contains("new WeakHashMap<")),
                () -> assertTrue(manifest.contains("sendLatestIfAllowed")),
                () -> assertTrue(manifest.contains("allowChunkRequest")),
                () -> assertTrue(chunkLimit >= 0),
                () -> assertTrue(chunkLimit < chunkSnapshot),
                () -> assertTrue(chunk.contains("ShopCatalogManifestPacket.sendLatestIfAllowed(")),
                () -> assertTrue(chunk.contains("ShopConfig.chunk(snapshot.revision(), packet.chunkId)")),
                () -> assertTrue(actionStale >= 0),
                () -> assertTrue(actionReply > actionStale),
                () -> assertTrue(undoException > actionReply),
                () -> assertTrue(undoExceptionOpen > undoException),
                () -> assertTrue(undoExceptionReturn > undoExceptionOpen),
                () -> assertTrue(undoExceptionClose > undoExceptionReturn),
                () -> assertTrue(undoBranch > undoExceptionClose),
                () -> assertTrue(action.contains("if (pkt.action != Action.DEPOSIT)")),
                () -> assertTrue(editStale >= 0),
                () -> assertTrue(editReply > editStale),
                () -> assertTrue(editReturn > editReply),
                () -> assertTrue(editAdd > editReturn),
                () -> assertTrue(editReplace > editReturn),
                () -> assertFalse(chunk.contains("ShopCatalogManifestPacket.sendTo(")),
                () -> assertFalse(action.contains("ShopCatalogManifestPacket.sendTo(")),
                () -> assertFalse(edit.contains("ShopCatalogManifestPacket.sendTo(")));
    }

    @Test
    void structuralPublishBroadcastsManifestAndChunksRebuildLivePayloads() throws Exception {
        String config = source("common/shop/ShopConfig.java");
        int publish = config.indexOf("private static void publish");
        int assign = config.indexOf("snapshot = built", publish);
        int loaded = config.indexOf("loaded = true", assign);
        int broadcast = config.indexOf("ShopCatalogManifestPacket.broadcast(built.manifest())", loaded);

        assertAll(
                () -> assertTrue(publish >= 0),
                () -> assertTrue(assign > publish),
                () -> assertTrue(loaded > assign),
                () -> assertTrue(broadcast > loaded),
                () -> assertTrue(config.contains("current.chunk(chunkId)")),
                () -> assertTrue(config.contains("current.resolve(payload.entryKey())")),
                () -> assertTrue(config.contains("ShopEntryJsonCodec.toPayload(entry)")),
                () -> assertTrue(config.contains("ShopCatalogSnapshot.DEFAULT_MAX_CHUNK_ENTRIES")));
    }

    @Test
    void permanentStateBroadcastIsSeparateFromStructuralAndPeriodicRefresh() throws Exception {
        String action = source("network/ShopActionPacket.java");
        String edit = source("network/ShopEditPacket.java");
        String refresh = source("network/ShopRefreshPacket.java");
        String wallet = source("network/WalletAccountSyncPacket.java");
        String client = source("client/shop/ClientShopCatalog.java");

        assertAll(
                () -> assertTrue(action.contains("long remainingUsesBefore = entry.getRemainingUses()")),
                () -> assertTrue(action.contains("ShopCatalogStatePacket.broadcast(")),
                () -> assertTrue(action.contains("ShopConfig.keyOf(entry)")),
                () -> assertFalse(action.contains("ShopRefreshPacket.sendTo")),
                () -> assertFalse(edit.contains("ShopRefreshPacket.sendTo")),
                () -> assertTrue(refresh.contains("instanceof com.dishanhai.gt_shanhai.client.gui.shop.ExchangeScreen")),
                () -> assertFalse(refresh.contains("instanceof com.dishanhai.gt_shanhai.client.gui.shop.ShopScreen")),
                () -> assertTrue(wallet.contains("purchaseCounts")),
                () -> assertTrue(wallet.contains("periodAnchors")),
                () -> assertFalse(wallet.contains("remainingUses")),
                () -> assertTrue(client.contains("STATE.applyRemainingUses")),
                () -> assertTrue(client.contains("entry.consumeUses(current - target)")),
                () -> assertTrue(client.contains("STATE.remainingUses(payload.entryKey())")));
    }

    private static String registration(String source, int classIndex) {
        int end = source.indexOf(");", classIndex);
        return end < 0 ? "" : source.substring(classIndex, end);
    }

    private static String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/" + relative));
    }
}
