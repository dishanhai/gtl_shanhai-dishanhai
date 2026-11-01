package com.dishanhai.gt_shanhai.client.shop;

import net.minecraft.resources.ResourceLocation;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

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
    private static Map<String, Long> periodUsed = new LinkedHashMap<>();
    private static Map<String, Long> personalRemainingUses = new LinkedHashMap<>();
    private static BigInteger wirelessEu = BigInteger.ZERO; // 玩家绑定的无线电网 EU 余额（gtladditions/gtmthings）
    private static boolean hasBoundAeNetwork = false; // 玩家当前是否有绑定的在线 AE 网络（商店终端/FTBQ提交器，见 ShopAeNetwork）
    private static int memberTier = -1; // 付费会员档位（-1=未购买），永久买断，见 ShopMembership
    private static boolean synced = false;

    /** 山海：獨立保存確認值，本地樂觀預覽不能抹掉真正的交易差額。 */
    public record BalanceChange(ResourceLocation currency, BigInteger amount, long confirmedAtMs) {}
    private static Map<ResourceLocation, BigInteger> confirmedCurrencies = new LinkedHashMap<>();
    private static BigInteger confirmedDigital = BigInteger.ZERO;
    private static final ArrayDeque<BalanceChange> balanceChanges = new ArrayDeque<>();
    private static final int MAX_BALANCE_CHANGES = 64;
    private static final long BALANCE_CHANGE_FRESH_MS = 2500L;

    private ClientWalletAccount() {}

    /** 退出世界/换服时清空，避免上一个存档的余额在下一次同步到达前被短暂展示（见 ClientInit 登出钩子）。 */
    public static void clear() {
        currencies = new LinkedHashMap<>();
        digital = BigInteger.ZERO;
        purchaseCounts = new LinkedHashMap<>();
        periodAnchors = new LinkedHashMap<>();
        periodUsed = new LinkedHashMap<>();
        personalRemainingUses = new LinkedHashMap<>();
        wirelessEu = BigInteger.ZERO;
        hasBoundAeNetwork = false;
        memberTier = -1;
        synced = false;
        confirmedCurrencies.clear();
        confirmedDigital = BigInteger.ZERO;
        balanceChanges.clear();
    }

    /** 应用服务端全量快照（权威覆盖）。 */
    public static void apply(Map<ResourceLocation, BigInteger> newCurrencies, BigInteger newDigital,
                              Map<String, Long> newPurchaseCounts, Map<String, Long> newPeriodAnchors,
                              BigInteger newWirelessEu, boolean newHasBoundAeNetwork, int newMemberTier,
                              Map<String, Long> newPeriodUsed) {
        apply(newCurrencies, newDigital, newPurchaseCounts, newPeriodAnchors, newWirelessEu,
                newHasBoundAeNetwork, newMemberTier, newPeriodUsed, null);
    }

    public static void apply(Map<ResourceLocation, BigInteger> newCurrencies, BigInteger newDigital,
                              Map<String, Long> newPurchaseCounts, Map<String, Long> newPeriodAnchors,
                              BigInteger newWirelessEu, boolean newHasBoundAeNetwork, int newMemberTier,
                              Map<String, Long> newPeriodUsed, Map<String, Long> newPersonalRemainingUses) {
        personalRemainingUses = newPersonalRemainingUses != null
                ? new LinkedHashMap<>(newPersonalRemainingUses) : new LinkedHashMap<>();
        periodUsed = newPeriodUsed != null ? new LinkedHashMap<>(newPeriodUsed) : new LinkedHashMap<>();
        currencies = newCurrencies != null ? new LinkedHashMap<>(newCurrencies) : new LinkedHashMap<>();
        digital = newDigital != null ? newDigital : BigInteger.ZERO;
        purchaseCounts = newPurchaseCounts != null ? new LinkedHashMap<>(newPurchaseCounts) : new LinkedHashMap<>();
        periodAnchors = newPeriodAnchors != null ? new LinkedHashMap<>(newPeriodAnchors) : new LinkedHashMap<>();
        wirelessEu = newWirelessEu != null ? newWirelessEu : BigInteger.ZERO;
        hasBoundAeNetwork = newHasBoundAeNetwork;
        memberTier = newMemberTier;
        recordConfirmedBalances(System.currentTimeMillis());
        synced = true;
    }

    private static void recordConfirmedBalances(long nowMs) {
        if (synced) {
            var ids = new LinkedHashSet<>(confirmedCurrencies.keySet());
            ids.addAll(currencies.keySet());
            for (ResourceLocation id : ids) {
                recordBalanceChange(id, getCurrency(id).subtract(
                        confirmedCurrencies.getOrDefault(id, BigInteger.ZERO)), nowMs);
            }
            recordBalanceChange(com.dishanhai.gt_shanhai.common.shop.WalletAccountAPI.SPARK,
                    digital.subtract(confirmedDigital), nowMs);
        }
        confirmedCurrencies = new LinkedHashMap<>(currencies);
        confirmedDigital = digital;
    }

    private static void recordBalanceChange(ResourceLocation id, BigInteger amount, long nowMs) {
        if (amount.signum() == 0) return;
        if (balanceChanges.size() >= MAX_BALANCE_CHANGES) balanceChanges.removeFirst();
        balanceChanges.addLast(new BalanceChange(id, amount, nowMs));
    }

    /** 商店取走近期確認事件；不在商店時累積的舊交易不會在下次開啟時重播。 */
    public static List<BalanceChange> drainBalanceChanges(long nowMs) {
        List<BalanceChange> result = new ArrayList<>();
        while (!balanceChanges.isEmpty()) {
            BalanceChange change = balanceChanges.removeFirst();
            if (nowMs - change.confirmedAtMs() <= BALANCE_CHANGE_FRESH_MS) result.add(change);
        }
        return result;
    }

    /** 付费会员档位（-1=未购买任何档位），见 {@link com.dishanhai.gt_shanhai.common.shop.ShopMembership}。 */
    public static int getMemberTier() {
        return memberTier;
    }

    /** 某商品条目的已购买次数（key 见 {@code WalletAccountAPI#purchaseKey(ShopEntry)}），未同步/未买过为 0。 */
    public static long getPurchaseCount(String key) {
        if (key == null) return 0L;
        Long v = purchaseCounts.get(key);
        return v == null ? 0L : v;
    }

    /** 已同步時讀個人餘額；尚未交易的商品使用目錄預設額度。 */
    public static long getRemainingUses(com.dishanhai.gt_shanhai.common.shop.ShopEntry entry) {
        if (!entry.isLimited()) return -1L;
        return personalRemainingUses.getOrDefault(entry.getStableId(), entry.getRemainingUses());
    }

    /** 某商品条目周期限购窗口的开窗锚点 gameTime（见 ShopPeriodLimiter）；从未消费过/窗口未开返回 -1。 */
    public static long getPeriodAnchor(String key) {
        if (key == null) return -1L;
        Long v = periodAnchors.get(key);
        return v == null ? -1L : v;
    }

    /** 周期限购仍按商品 ID 与分类聚合，与稳定 ID 的购买统计分开。 */
    public static long getPeriodAnchor(com.dishanhai.gt_shanhai.common.shop.ShopEntry entry) {
        return getPeriodAnchor(periodKey(entry));
    }

    /** 某商品条目在当前周期窗口内的已用次数（见 ShopPeriodLimiter）；窗口是否已过期由调用方按锚点判断，未同步/未买过为 0。 */
    public static long getPeriodUsed(String key) {
        if (key == null) return 0L;
        Long v = periodUsed.get(key);
        return v == null ? 0L : v;
    }

    public static long getPeriodUsed(com.dishanhai.gt_shanhai.common.shop.ShopEntry entry) {
        return getPeriodUsed(periodKey(entry));
    }

    private static String periodKey(com.dishanhai.gt_shanhai.common.shop.ShopEntry entry) {
        return entry == null ? null : com.dishanhai.gt_shanhai.common.shop.WalletAccountAPI.purchaseKey(
                entry.getGoodsId(), entry.getCategory());
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
