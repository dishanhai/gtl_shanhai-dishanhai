package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.client.shop.ClientWalletAccount;
import com.dishanhai.gt_shanhai.common.shop.ShopCost;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 山海商店個人額度網路與結算接線回歸。 */
class ShopPlayerLimitSyncTest {
    @AfterEach void clear() { ClientWalletAccount.clear(); }

    @Test void snapshotRoundTripUsesOnlyThisPlayersBalancesAndClearsOnSwitch() throws Exception {
        ShopEntry entry = new ShopEntry(new ResourceLocation("minecraft:stone"), 1, "test", null,
                ShopCost.singleCoin(new ResourceLocation("minecraft:diamond"), 1L), "", 20L);
        WalletAccountSyncPacket sent = new WalletAccountSyncPacket(Map.of(), BigInteger.ZERO,
                Map.of(), Map.of(), BigInteger.ZERO, false, -1, Map.of(), Map.of(entry.getStableId(), 0L));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            sent.encode(buffer);
            WalletAccountSyncPacket decoded = new WalletAccountSyncPacket(buffer);
            assertEquals(0, buffer.readableBytes());
            var apply = WalletAccountSyncPacket.class.getDeclaredMethod("applyClient", WalletAccountSyncPacket.class);
            apply.setAccessible(true);
            apply.invoke(null, decoded);
            assertTrue(ClientWalletAccount.isSynced());
            assertEquals(0L, ClientWalletAccount.getRemainingUses(entry));
            assertEquals(20L, entry.getRemainingUses());
            ClientWalletAccount.clear();
            assertFalse(ClientWalletAccount.isSynced());
            ClientWalletAccount.apply(Map.of(), BigInteger.ZERO, Map.of(), Map.of(), BigInteger.ZERO,
                    false, -1, Map.of(), Map.of());
            assertEquals(20L, ClientWalletAccount.getRemainingUses(entry));
        } finally {
            buffer.release();
        }
    }

    @Test void settlementAndDisplayReadPersonalBalancesWithoutGlobalConsumption() throws Exception {
        Path root = Path.of("src/main/java/com/dishanhai/gt_shanhai");
        String purchase = Files.readString(root.resolve("common/shop/ShopPurchase.java"));
        assertTrue(purchase.contains("ShopLimitSavedData.clamp(player, entry, affordable)"));
        assertTrue(purchase.contains("ShopLimitSavedData.clamp(player, entry, sell)"));
        assertTrue(purchase.contains("ShopLimitSavedData.consume(player, entry, affordable)"));
        assertTrue(purchase.contains("ShopLimitSavedData.consume(player, entry, sell)"));
        assertFalse(purchase.contains("entry.consumeUses("));
        String action = Files.readString(root.resolve("network/ShopActionPacket.java"));
        assertFalse(action.contains("ShopCatalogStatePacket.broadcast("));
        assertFalse(action.contains("entry.getRemainingUses()"));
        String screen = Files.readString(root.resolve("client/gui/shop/ShopScreen.java"));
        assertTrue(screen.contains("ClientWalletAccount.getRemainingUses(selected)"));
        assertTrue(screen.contains("ClientWalletAccount.getRemainingUses(e)"));
        String wallet = Files.readString(root.resolve("common/shop/WalletAccountAPI.java"));
        assertTrue(wallet.contains("ShopLimitSavedData.get(server).getPlayerRemaining(uuid)"));
    }
}
