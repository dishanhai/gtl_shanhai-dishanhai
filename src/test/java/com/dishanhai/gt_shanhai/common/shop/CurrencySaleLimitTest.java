package com.dishanhai.gt_shanhai.common.shop;

import com.dishanhai.gt_shanhai.client.shop.ClientWalletAccount;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencySaleLimitTest {

    private static final ResourceLocation COPPER = new ResourceLocation("dishanhai:copper_coin");
    private static final ResourceLocation GOLD = new ResourceLocation("dishanhai:gold_coin");

    @AfterEach
    void clearClientWallet() {
        ClientWalletAccount.clear();
    }

    @Test
    void limitsOnlyPositiveFaceValuesThroughPlatinum() {
        assertFalse(CurrencySaleLimit.isLimited(-1L));
        assertFalse(CurrencySaleLimit.isLimited(0L));
        assertTrue(CurrencySaleLimit.isLimited(1L));
        assertTrue(CurrencySaleLimit.isLimited(256L));
        assertFalse(CurrencySaleLimit.isLimited(257L));
        assertFalse(CurrencySaleLimit.isLimited(1024L));
    }

    @Test
    void doublesTheBaseQuotaPerTierAndSaturatesWithoutOverflow() {
        long base = CurrencySaleLimit.DEFAULT_LIMIT;
        assertEquals(4096L, CurrencySaleLimit.tierLimit(1L, base));
        assertEquals(8192L, CurrencySaleLimit.tierLimit(4L, base));
        assertEquals(16384L, CurrencySaleLimit.tierLimit(16L, base));
        assertEquals(32768L, CurrencySaleLimit.tierLimit(64L, base));
        assertEquals(65536L, CurrencySaleLimit.tierLimit(256L, base));
        assertEquals(0L, CurrencySaleLimit.tierLimit(256L, 0L));
        assertEquals(Long.MAX_VALUE, CurrencySaleLimit.tierLimit(256L, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, CurrencySaleLimit.tierLimit(1024L, 0L));
    }

    @Test
    void defaultWindowExpiresAfterExactly180RealSeconds() {
        long start = 1_800_000_000_000L;
        assertEquals(180L, CurrencySaleLimit.DEFAULT_SECONDS);
        assertEquals(1L, CurrencySaleLimit.millisUntilRefresh(start + 179_999L, start, CurrencySaleLimit.DEFAULT_SECONDS));
        assertEquals(0L, CurrencySaleLimit.millisUntilRefresh(start + 180_000L, start, CurrencySaleLimit.DEFAULT_SECONDS));
        assertEquals(180_000L, CurrencySaleLimit.millisUntilRefresh(start - 1000L, start, CurrencySaleLimit.DEFAULT_SECONDS));
    }

    @Test
    void keepsCurrencyAndProductWindowsIndependentAcrossSaveReload() {
        WalletAccount wallet = new WalletAccount();
        String currencyKey = CurrencySaleLimit.key(COPPER);
        String productKey = WalletAccountAPI.purchaseKey(COPPER, "");
        wallet.setPeriodState(currencyKey, 100L, 12L);
        wallet.setPeriodState(productKey, 101L, 2L);
        wallet.setPeriodState("currency-sale:" + GOLD, 102L, 99L);

        WalletAccount restored = WalletAccount.load(wallet.save());
        assertEquals(100L, restored.getPeriodWindow(currencyKey));
        assertEquals(12L, restored.getPeriodUsed(currencyKey));
        assertEquals(2L, restored.getPeriodUsed(productKey));
        assertEquals(-1L, restored.getPeriodWindow(CurrencySaleLimit.key(GOLD)));
        assertEquals(-1L, WalletAccount.load(new CompoundTag()).getPeriodWindow(currencyKey));
    }

    @Test
    void displaysCurrentQuotaUntilTheExactRefreshTime() {
        String key = CurrencySaleLimit.key(COPPER);
        ClientWalletAccount.apply(Map.of(), BigInteger.ZERO, Map.of(), Map.of(key, 100_000L),
                BigInteger.ZERO, false, -1, Map.of(key, 12L));
        ClientWalletAccount.applyCurrencySalePolicy(15L, 3L, 102_999L);

        assertEquals(120L, ClientWalletAccount.remainingCurrencySale(GOLD, 64L, 102_999L));
        assertEquals(3L, ClientWalletAccount.remainingCurrencySale(COPPER, 1L, 102_999L));
        assertEquals(1L, ClientWalletAccount.currencySaleRefreshMillis(COPPER, 102_999L));
        assertEquals(15L, ClientWalletAccount.remainingCurrencySale(COPPER, 1L, 103_000L));
        assertEquals(0L, ClientWalletAccount.currencySaleRefreshMillis(COPPER, 103_000L));

        ClientWalletAccount.applyCurrencySalePolicy(10L, 4L, 103_000L);
        assertEquals(0L, ClientWalletAccount.remainingCurrencySale(COPPER, 1L, 103_000L));
        assertEquals(10L, ClientWalletAccount.remainingCurrencySale(COPPER, 1L, 104_000L));
    }
}
