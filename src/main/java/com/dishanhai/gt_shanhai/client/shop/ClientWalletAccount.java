package com.dishanhai.gt_shanhai.client.shop;

import net.minecraft.resources.ResourceLocation;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端钱包账户快照缓存（山海署名，仅客户端）。
 *
 * 余额已不在钱包 ItemStack NBT，服务端 {@code WalletAccountSyncPacket} 推送后写入这里，
 * 供 tooltip / ShopScreen / CurrencyAtmScreen 读取。只缓存当前玩家一份，后续同步覆盖。
 *
 * {@link #optimisticAddCurrency}/{@link #optimisticAddDigital} 供界面按下即时改缓存做乐观预览，
 * 服务端权威快照回来后整体覆盖校正。</p>
 */
public final class ClientWalletAccount {

    private static Map<ResourceLocation, BigInteger> currencies = new LinkedHashMap<>();
    private static BigInteger digital = BigInteger.ZERO;
    private static Map<String, Long> purchaseCounts = new LinkedHashMap<>();
    private static Map<String, Long> periodAnchors = new LinkedHashMap<>();
    private static BigInteger wirelessEu = BigInteger.ZERO; // 玩家绑定的无线电网 EU 余额（gtladditions/gtmthings）
    private static boolean hasBoundAeNetwork = false; // 玩家当前是否有绑定的在线 AE 网络（商店终端/FTBQ提交器，见 ShopAeNetwork）
    private static boolean synced = false;

    private ClientWalletAccount() {}

    /** 应用服务端全量快照（权威覆盖）。 */
    public static void apply(Map<ResourceLocation, BigInteger> newCurrencies, BigInteger newDigital,
                              Map<String, Long> newPurchaseCounts, Map<String, Long> newPeriodAnchors,
                              BigInteger newWirelessEu, boolean newHasBoundAeNetwork) {
        currencies = newCurrencies != null ? new LinkedHashMap<>(newCurrencies) : new LinkedHashMap<>();
        digital = newDigital != null ? newDigital : BigInteger.ZERO;
        purchaseCounts = newPurchaseCounts != null ? new LinkedHashMap<>(newPurchaseCounts) : new LinkedHashMap<>();
        periodAnchors = newPeriodAnchors != null ? new LinkedHashMap<>(newPeriodAnchors) : new LinkedHashMap<>();
        wirelessEu = newWirelessEu != null ? newWirelessEu : BigInteger.ZERO;
        hasBoundAeNetwork = newHasBoundAeNetwork;
        synced = true;
    }

    /** 某商品条目的已购买次数（key 见 {@code WalletAccountAPI#purchaseKey}），未同步/未买过为 0。 */
    public static long getPurchaseCount(String key) {
        if (key == null) return 0L;
        Long v = purchaseCounts.get(key);
        return v == null ? 0L : v;
    }

    /** 某商品条目周期限购窗口的开窗锚点 gameTime（见 ShopPeriodLimiter）；从未消费过/窗口未开返回 -1。 */
    public static long getPeriodAnchor(String key) {
        if (key == null) return -1L;
        Long v = periodAnchors.get(key);
        return v == null ? -1L : v;
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

    /** 玩家绑定的无线电网 EU 余额（gtladditions/gtmthings，见 ShopWirelessEu），未同步为 0。 */
    public static BigInteger getWirelessEu() {
        return wirelessEu;
    }

    /**
     * 玩家当前是否有绑定的在线 AE 网络（商店终端/FTBQ提交器，见 ShopAeNetwork）。
     * 服务端在打开商店/账户变动时推送快照，可能有极短滞后；供「AE模式」开关按下时瞬间校验用。
     */
    public static boolean hasBoundAeNetwork() {
        return hasBoundAeNetwork;
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

    public static void optimisticAddWirelessEu(BigInteger delta) {
        if (delta == null || delta.signum() == 0) return;
        BigInteger next = wirelessEu.add(delta);
        wirelessEu = next.signum() < 0 ? BigInteger.ZERO : next;
    }
}
