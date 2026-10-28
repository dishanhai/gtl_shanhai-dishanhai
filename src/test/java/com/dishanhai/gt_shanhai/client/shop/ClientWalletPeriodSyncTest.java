package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.common.shop.ShopCost;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;
import com.dishanhai.gt_shanhai.common.shop.WalletAccountAPI;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ClientWalletPeriodSyncTest {
    @AfterEach void clear() { ClientWalletAccount.clear(); }

    @Test void periodSnapshotUsesTheServerKeyWhilePurchaseCountUsesStableId() {
        ShopEntry entry = new ShopEntry(new ResourceLocation("minecraft:stone"), 1,
                "test/category", null, new ShopCost(BigInteger.ZERO, null, null), "");
        String periodKey = WalletAccountAPI.purchaseKey(entry.getGoodsId(), entry.getCategory());
        String purchaseKey = WalletAccountAPI.purchaseKey(entry);
        assertNotEquals(periodKey, purchaseKey);
        ClientWalletAccount.apply(Map.of(), BigInteger.ZERO, Map.of(purchaseKey, 90L),
                Map.of(periodKey, 100L), BigInteger.ZERO, false, -1, Map.of(periodKey, 50L));
        assertEquals(90L, ClientWalletAccount.getPurchaseCount(purchaseKey));
        assertEquals(100L, ClientWalletAccount.getPeriodAnchor(entry));
        assertEquals(50L, ClientWalletAccount.getPeriodUsed(entry));
    }

    @Test void detailAndLimitRejectionUseTheAuthoritativeSnapshot() throws Exception {
        String screen = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java"));
        String packet = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/network/ShopActionPacket.java"));
        assertTrue(screen.contains("ClientWalletAccount.getPeriodAnchor(selected)"));
        assertTrue(screen.contains("ClientWalletAccount.getPeriodUsed(selected)"));
        assertTrue(screen.contains("!ClientWalletAccount.isSynced()"));
        int buy = packet.indexOf("if (!cheat && entry.isPeriodLimited() && remainingPeriodBefore <= 0L)");
        int sell = packet.indexOf("ShopPeriodLimiter.remaining(player, entry, periodKey) <= 0L", packet.indexOf("private static void doSell("));
        assertTrue(buy >= 0 && sell > buy);
        assertTrue(packet.substring(buy, packet.indexOf("return;", buy)).contains("WalletAccountAPI.sync(player)"));
        assertTrue(packet.substring(sell, packet.indexOf("return;", sell)).contains("WalletAccountAPI.sync(player)"));
    }
}
