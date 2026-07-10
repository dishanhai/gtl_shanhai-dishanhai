package com.dishanhai.gt_shanhai.client.shop;

import net.minecraft.resources.ResourceLocation;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端钱包账户快照缓存（山海署名，仅客户端）。
 *
 * <p>余额已不在钱包 ItemStack NBT，服务端 {@code WalletAccountSyncPacket} 推送后写入这里，
 * 供 tooltip / ShopScreen / CurrencyAtmScreen 读取。只缓存当前玩家一份，后续同步覆盖。</p>
 *
 * <p>{@link #optimisticAddCurrency}/{@link #optimisticAddDigital} 供界面按下即时改缓存做乐观预览，
 * 服务端权威快照回来后整体覆盖校正。</p>
 */
public final class ClientWalletAccount {

    private static Map<ResourceLocation, BigInteger> currencies = new LinkedHashMap<>();
    private static BigInteger digital = BigInteger.ZERO;
    private static boolean synced = false;

    private ClientWalletAccount() {}

    /** 应用服务端全量快照（权威覆盖）。 */
    public static void apply(Map<ResourceLocation, BigInteger> newCurrencies, BigInteger newDigital) {
        currencies = newCurrencies != null ? new LinkedHashMap<>(newCurrencies) : new LinkedHashMap<>();
        digital = newDigital != null ? newDigital : BigInteger.ZERO;
        synced = true;
    }

    public static boolean isSynced() {
        return synced;
    }

    public static BigInteger getCurrency(ResourceLocation currency) {
        if (currency == null) return BigInteger.ZERO;
        BigInteger v = currencies.get(currency);
        return v == null ? BigInteger.ZERO : v;
    }

    public static BigInteger getDigital() {
        return digital;
    }

    /** 全部币种余额（副本，保序）。 */
    public static Map<ResourceLocation, BigInteger> getAll() {
        return new LinkedHashMap<>(currencies);
    }

    // ===== 乐观预览：本地就地改缓存，服务端快照回来覆盖校正 =====

    public static void optimisticAddCurrency(ResourceLocation currency, BigInteger delta) {
        if (currency == null || delta == null || delta.signum() == 0) return;
        BigInteger next = getCurrency(currency).add(delta);
        if (next.signum() <= 0) currencies.remove(currency);
        else currencies.put(currency, next);
    }

    public static void optimisticAddDigital(BigInteger delta) {
        if (delta == null || delta.signum() == 0) return;
        BigInteger next = digital.add(delta);
        digital = next.signum() < 0 ? BigInteger.ZERO : next;
    }
}
