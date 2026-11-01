package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 山海商店永久限購的玩家隔離與遷移回歸。 */
class ShopPlayerLimitTest {
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private ShopEntry entry(long limit) {
        return new ShopEntry(new ResourceLocation("minecraft:stone"), 1, "test", null,
                ShopCost.singleCoin(new ResourceLocation("minecraft:diamond"), 1L), "", limit);
    }

    private long remaining(ShopLimitSavedData data, UUID player, ShopEntry entry) throws Exception {
        return data.remaining(player, entry);
    }

    private void consume(ShopLimitSavedData data, UUID player, ShopEntry entry, long amount) throws Exception {
        data.consume(player, entry, amount);
    }

    @Test void transactionsArePersonalAndDoNotMutateCatalog() throws Exception {
        ShopLimitSavedData data = new ShopLimitSavedData();
        ShopEntry entry = entry(20L);
        data.applyTo(entry);
        consume(data, alice, entry, 7L);
        consume(data, alice, entry, 3L); // 購買及出售走同一個額度記帳入口
        assertEquals(10L, remaining(data, alice, entry));
        assertEquals(20L, remaining(data, bob, entry));
        assertEquals(20L, entry.getRemainingUses());
        consume(data, alice, entry, Long.MAX_VALUE);
        assertEquals(0L, remaining(data, alice, entry));
    }

    @Test void personalBalancesSurviveSaveAndStayWithinTheirWorld() throws Exception {
        ShopEntry entry = entry(20L);
        ShopLimitSavedData data = new ShopLimitSavedData();
        data.applyTo(entry);
        consume(data, alice, entry, 20L);
        consume(data, bob, entry, 4L);
        ShopLimitSavedData restored = ShopLimitSavedData.load(data.save(new CompoundTag()));
        assertEquals(0L, remaining(restored, alice, entry));
        assertEquals(16L, remaining(restored, bob, entry));
        assertEquals(20L, remaining(new ShopLimitSavedData(), alice, entry));
    }

    @Test void legacyGlobalConsumptionDoesNotBecomeEveryPlayersConsumption() throws Exception {
        ShopEntry entry = entry(20L);
        CompoundTag legacy = new CompoundTag();
        CompoundTag oldEntry = new CompoundTag();
        oldEntry.putString("id", entry.getStableId());
        oldEntry.putLong("remaining", 0L);
        ListTag entries = new ListTag();
        entries.add(oldEntry);
        legacy.put("entries", entries);
        ShopLimitSavedData data = ShopLimitSavedData.load(legacy);
        data.applyTo(entry);
        assertEquals(20L, entry.getRemainingUses());
        assertEquals(20L, remaining(data, alice, entry));
        assertEquals(20L, remaining(data, bob, entry));
    }

    @Test void resetClearsAllPlayersForOnlyThisEntry() throws Exception {
        ShopLimitSavedData data = new ShopLimitSavedData();
        ShopEntry first = entry(20L);
        ShopEntry second = entry(20L);
        data.applyTo(first);
        data.applyTo(second);
        consume(data, alice, first, 20L);
        consume(data, bob, first, 8L);
        consume(data, alice, second, 3L);
        data.reset(first);
        assertEquals(20L, remaining(data, alice, first));
        assertEquals(20L, remaining(data, bob, first));
        assertEquals(17L, remaining(data, alice, second));
    }

    @Test void unlimitedAndNonPositiveTransactionsDoNotCreateUsage() throws Exception {
        ShopLimitSavedData data = new ShopLimitSavedData();
        ShopEntry unlimited = entry(-1L);
        consume(data, alice, unlimited, Long.MAX_VALUE);
        assertEquals(-1L, remaining(data, alice, unlimited));
        ShopEntry finite = entry(20L);
        consume(data, alice, finite, -1L);
        consume(data, alice, finite, 0L);
        assertEquals(20L, remaining(data, alice, finite));
        Map<?, ?> snapshot = data.getPlayerRemaining(alice);
        assertTrue(snapshot.isEmpty());
    }

    @Test void normalEditKeepsProgressWhileExplicitQuotaEditResetsIt() throws Exception {
        ShopLimitSavedData data = new ShopLimitSavedData();
        ShopEntry entry = entry(20L);
        data.applyTo(entry);
        consume(data, alice, entry, 7L);
        data.set(entry.getStableId(), 20L); // 一般商品編輯沿用原預設額度
        assertEquals(13L, remaining(data, alice, entry));
        data.set(entry.getStableId(), 30L);
        data.resetPlayers(entry.getStableId());
        data.applyTo(entry);
        assertEquals(30L, remaining(data, alice, entry));
        assertEquals(30L, remaining(data, bob, entry));
        consume(data, alice, entry, 5L);
        ShopLimitSavedData restored = ShopLimitSavedData.load(data.save(new CompoundTag()));
        restored.applyTo(entry);
        assertEquals(25L, remaining(restored, alice, entry));
        assertEquals(30L, remaining(restored, bob, entry));
    }

    @Test void snapshotsCannotChangeAnotherPlayersBalanceOrTheSavedBalance() throws Exception {
        ShopLimitSavedData data = new ShopLimitSavedData();
        ShopEntry entry = entry(20L);
        consume(data, alice, entry, 4L);
        Map<?, ?> snapshot = data.getPlayerRemaining(alice);
        snapshot.clear();
        assertEquals(16L, remaining(data, alice, entry));
        assertEquals(20L, remaining(data, bob, entry));
        Map<?, ?> bobSnapshot = data.getPlayerRemaining(bob);
        assertTrue(bobSnapshot.isEmpty());
    }

    @Test void finiteSaveQuotaCanOverrideUnlimitedServerAndResetRestoresUnlimited() throws Exception {
        ShopLimitSavedData data = new ShopLimitSavedData();
        ShopEntry entry = entry(-1L);
        data.set(entry.getStableId(), 30L);
        data.applyTo(entry);
        consume(data, alice, entry, 5L);
        ShopLimitSavedData restored = ShopLimitSavedData.load(data.save(new CompoundTag()));
        restored.applyTo(entry);
        assertEquals(25L, remaining(restored, alice, entry));
        assertEquals(30L, remaining(restored, bob, entry));
        restored.reset(entry);
        assertEquals(-1L, remaining(restored, alice, entry));
        assertEquals(-1L, remaining(restored, bob, entry));
    }
}
