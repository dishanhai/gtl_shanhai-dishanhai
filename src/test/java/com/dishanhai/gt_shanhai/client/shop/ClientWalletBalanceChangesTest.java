package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.common.shop.WalletAccountAPI;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：動畫只能來自已確認的餘額差額。 */
class ClientWalletBalanceChangesTest {
    private static final ResourceLocation COIN = new ResourceLocation("gt_shanhai", "test_coin");

    @AfterEach void clear() { ClientWalletAccount.clear(); }

    private void apply(Map<ResourceLocation, BigInteger> currencies, BigInteger spark) {
        ClientWalletAccount.apply(currencies, spark, Map.of(), Map.of(), BigInteger.ZERO, false, -1, Map.of());
    }

    @Test void firstSnapshotAndRepeatedSnapshotsAreSilent() {
        apply(Map.of(COIN, BigInteger.TEN), BigInteger.valueOf(1000));
        apply(Map.of(COIN, BigInteger.TEN), BigInteger.valueOf(1000));
        assertTrue(ClientWalletAccount.drainBalanceChanges(System.currentTimeMillis()).isEmpty());
    }

    @Test void authoritativeDifferencesKeepExactSignIncludingZeroAndNewCurrencies() {
        BigInteger huge = BigInteger.TEN.pow(80);
        apply(Map.of(COIN, BigInteger.TEN), huge);
        apply(Map.of(), huge.add(BigInteger.valueOf(1000)));
        var changes = ClientWalletAccount.drainBalanceChanges(System.currentTimeMillis());
        assertEquals(2, changes.size());
        assertEquals(BigInteger.TEN.negate(), changes.stream().filter(c -> c.currency().equals(COIN)).findFirst().orElseThrow().amount());
        assertEquals(BigInteger.valueOf(1000), changes.stream().filter(c -> c.currency().equals(WalletAccountAPI.SPARK)).findFirst().orElseThrow().amount());
        apply(Map.of(COIN, huge), huge);
        changes = ClientWalletAccount.drainBalanceChanges(System.currentTimeMillis());
        assertEquals(huge, changes.stream().filter(c -> c.currency().equals(COIN)).findFirst().orElseThrow().amount());
        assertEquals(BigInteger.valueOf(-1000), changes.stream().filter(c -> c.currency().equals(WalletAccountAPI.SPARK)).findFirst().orElseThrow().amount());
    }

    @Test void optimisticPreviewDoesNotHideOrReplayConfirmedChanges() {
        apply(Map.of(COIN, BigInteger.TEN), BigInteger.valueOf(100));
        ClientWalletAccount.optimisticAddDigital(BigInteger.valueOf(1000));
        ClientWalletAccount.optimisticAddCurrency(COIN, BigInteger.TEN.negate());
        assertTrue(ClientWalletAccount.drainBalanceChanges(System.currentTimeMillis()).isEmpty());
        apply(Map.of(), BigInteger.valueOf(1100));
        var changes = ClientWalletAccount.drainBalanceChanges(System.currentTimeMillis());
        assertEquals(2, changes.size());
        assertTrue(changes.stream().anyMatch(c -> c.amount().equals(BigInteger.valueOf(1000))));
        apply(Map.of(), BigInteger.valueOf(1100));
        assertTrue(ClientWalletAccount.drainBalanceChanges(System.currentTimeMillis()).isEmpty());
    }

    @Test void rejectedPreviewIsSilentAndClearStartsANewBaseline() {
        apply(Map.of(), BigInteger.TEN);
        ClientWalletAccount.optimisticAddDigital(BigInteger.valueOf(-5));
        apply(Map.of(), BigInteger.TEN);
        assertTrue(ClientWalletAccount.drainBalanceChanges(System.currentTimeMillis()).isEmpty());
        apply(Map.of(), BigInteger.ZERO);
        ClientWalletAccount.clear();
        apply(Map.of(), BigInteger.valueOf(9999));
        assertTrue(ClientWalletAccount.drainBalanceChanges(System.currentTimeMillis()).isEmpty());
    }

    @Test void staleChangesAreDiscardedAndPendingHistoryIsBounded() {
        apply(Map.of(), BigInteger.ZERO);
        for (int i = 1; i <= 200; i++) apply(Map.of(), BigInteger.valueOf(i));
        var changes = ClientWalletAccount.drainBalanceChanges(System.currentTimeMillis());
        assertFalse(changes.isEmpty());
        assertTrue(changes.size() <= 64);
        assertTrue(ClientWalletAccount.drainBalanceChanges(System.currentTimeMillis()).isEmpty());
        apply(Map.of(), BigInteger.ZERO);
        assertTrue(ClientWalletAccount.drainBalanceChanges(System.currentTimeMillis() + 10_000).isEmpty());
    }
}
