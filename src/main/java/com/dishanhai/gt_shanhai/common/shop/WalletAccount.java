package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 钱包账户数据对象（山海署名）。
 *
 * <p>双账本并存：① 各币种余额 {@link #currencyBalances}（BigInteger，商店按币种结算用）；
 * ② 数字余额 {@link #digitalBalance}（单一 BigInteger 价值池，名"星火"，兑换/多物品流体用）。
 * 两者按币值互转（见 {@link WalletAccountAPI}）。</p>
 *
 * <p>数值一律 BigInteger（真无限，不封顶）。NBT 序列化用 {@code toByteArray()} 存字节数组，
 * 参照 {@link com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData.CellData}。</p>
 *
 * <p>该对象仅存于服务端 {@link WalletAccountSavedData}，按玩家本体 UUID 索引，
 * 不再写入钱包 ItemStack NBT。</p>
 */
public class WalletAccount {

    private static final String TAG_CURRENCIES = "currencies";
    private static final String TAG_DIGITAL = "digital";
    private static final String TAG_PURCHASES = "purchases";
    private static final String TAG_PERIOD_WINDOW = "periodWindow";
    private static final String TAG_PERIOD_USED = "periodUsed";
    private static final String TAG_MEMBER_TIER = "memberTier";
    private static final String TAG_BANK_DEPOSIT = "bankDeposit";
    private static final String TAG_BANK_DEPOSIT_MS = "bankDepositMs";
    private static final String TAG_BANK_DEBT = "bankDebt";
    private static final String TAG_BANK_DEBT_MS = "bankDebtMs";

    /** 币种 → 余额（BigInteger，保序）。 */
    private final Map<ResourceLocation, BigInteger> currencyBalances = new LinkedHashMap<>();
    /** 数字余额（星火）。 */
    private BigInteger digitalBalance = BigInteger.ZERO;
    /** 付费会员档位（-1=未购买任何档位，0/1/2=青铜/白银/黄金），永久买断，见 {@link ShopMembership}/{@link WalletAccountAPI#buyMemberTier}。 */
    private int memberTier = -1;
    /** 定期存款本金（含已结算利息，见 {@link ShopBank}）。 */
    private BigInteger bankDeposit = BigInteger.ZERO;
    /** 定期存款上次结息时刻（{@link System#currentTimeMillis}）；0=从未记账。 */
    private long bankDepositLastMs = 0L;
    /** 贷款欠款本金（含已结算利息）。 */
    private BigInteger bankDebt = BigInteger.ZERO;
    /** 贷款欠款上次结息时刻；0=从未记账。 */
    private long bankDebtLastMs = 0L;
    /** 商品条目 key（见 {@link WalletAccountAPI#purchaseKey}）→ 累计已购买次数，展示用，非结算依据。 */
    private final Map<String, Long> purchaseCounts = new LinkedHashMap<>();
    /** 商品条目 key → 该玩家当前周期限购窗口的开窗锚点 gameTime（见 {@link ShopPeriodLimiter}），
     *  在玩家<b>首次消费</b>该商品时打下，配合 {@link #periodUsed} 判断窗口是否还在有效期内；无锚点=从未开窗。 */
    private final Map<String, Long> periodWindow = new LinkedHashMap<>();
    /** 商品条目 key → 该玩家在 {@link #periodWindow} 锚点开出的窗口内已用掉的次数；窗口过期（当前 gameTime
     *  超出 锚点+周期长度）就代表这份计数已作废，视为 0——不会主动清零，靠读取时判断过期，见 {@link ShopPeriodLimiter}。 */
    private final Map<String, Long> periodUsed = new LinkedHashMap<>();

    // ===== 币种余额 =====

    /** 读某币种余额，无则 0。 */
    public BigInteger getCurrency(ResourceLocation currency) {
        if (currency == null) return BigInteger.ZERO;
        BigInteger v = currencyBalances.get(currency);
        return v == null ? BigInteger.ZERO : v;
    }

    /** 覆盖写某币种余额；≤0 时移除该键保持干净。 */
    public void setCurrency(ResourceLocation currency, BigInteger value) {
        if (currency == null) return;
        if (value == null || value.signum() <= 0) {
            currencyBalances.remove(currency);
        } else {
            currencyBalances.put(currency, value);
        }
    }

    /** 只读视图（副本，避免外部改动内部表）。 */
    public Map<ResourceLocation, BigInteger> getCurrencyBalances() {
        return new LinkedHashMap<>(currencyBalances);
    }

    // ===== 数字余额 =====

    public BigInteger getDigital() {
        return digitalBalance;
    }

    public void setDigital(BigInteger value) {
        digitalBalance = (value == null || value.signum() <= 0) ? BigInteger.ZERO : value;
    }

    // ===== 付费会员档位（永久买断，见 ShopMembership） =====

    public int getMemberTier() {
        return memberTier;
    }

    public void setMemberTier(int value) {
        memberTier = value;
    }

    // ===== 银行：定期存款 / 贷款（见 ShopBank） =====

    public BigInteger getBankDeposit() {
        return bankDeposit;
    }

    public void setBankDeposit(BigInteger value) {
        bankDeposit = (value == null || value.signum() <= 0) ? BigInteger.ZERO : value;
    }

    public long getBankDepositLastMs() {
        return bankDepositLastMs;
    }

    public void setBankDepositLastMs(long value) {
        bankDepositLastMs = value;
    }

    public BigInteger getBankDebt() {
        return bankDebt;
    }

    public void setBankDebt(BigInteger value) {
        bankDebt = (value == null || value.signum() <= 0) ? BigInteger.ZERO : value;
    }

    public long getBankDebtLastMs() {
        return bankDebtLastMs;
    }

    public void setBankDebtLastMs(long value) {
        bankDebtLastMs = value;
    }

    // ===== 已购买次数（展示用统计，不参与结算） =====

    public long getPurchaseCount(String key) {
        if (key == null) return 0L;
        Long v = purchaseCounts.get(key);
        return v == null ? 0L : v;
    }

    /** 原地累加（delta 可为负，理论上不会用到，封底到 0）；0 时移除该键保持干净。 */
    public void addPurchaseCount(String key, long delta) {
        if (key == null || delta == 0L) return;
        long next = getPurchaseCount(key) + delta;
        if (next <= 0L) purchaseCounts.remove(key);
        else purchaseCounts.put(key, next);
    }

    /** 只读视图（副本）。 */
    public Map<String, Long> getPurchaseCounts() {
        return new LinkedHashMap<>(purchaseCounts);
    }

    // ===== 周期限购（每玩家独立计数，见 ShopPeriodLimiter） =====

    /** key 当前窗口的开窗锚点 gameTime；从未消费过该商品（或窗口从未开过）返回 -1。 */
    public long getPeriodWindow(String key) {
        if (key == null) return -1L;
        Long v = periodWindow.get(key);
        return v == null ? -1L : v;
    }

    /** key 在 {@link #getPeriodWindow} 所记窗口内已用掉的次数。 */
    public long getPeriodUsed(String key) {
        if (key == null) return 0L;
        Long v = periodUsed.get(key);
        return v == null ? 0L : v;
    }

    /** 全部 key 的开窗锚点（副本，保序），供客户端展示"剩余刷新倒计时"用（见 WalletAccountSyncPacket）。 */
    public Map<String, Long> getPeriodWindows() {
        return new LinkedHashMap<>(periodWindow);
    }

    /** 覆盖写某 key 的周期窗口状态（新窗口开始时 window 变了，used 从 0 重新计）；used≤0 时移除保持干净。 */
    public void setPeriodState(String key, long window, long used) {
        if (key == null) return;
        if (used <= 0L) {
            periodWindow.remove(key);
            periodUsed.remove(key);
        } else {
            periodWindow.put(key, window);
            periodUsed.put(key, used);
        }
    }

    // ===== NBT =====

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        CompoundTag cur = new CompoundTag();
        for (Map.Entry<ResourceLocation, BigInteger> e : currencyBalances.entrySet()) {
            BigInteger v = e.getValue();
            if (v != null && v.signum() > 0) {
                cur.putByteArray(e.getKey().toString(), v.toByteArray());
            }
        }
        tag.put(TAG_CURRENCIES, cur);
        if (digitalBalance.signum() > 0) {
            tag.putByteArray(TAG_DIGITAL, digitalBalance.toByteArray());
        }
        if (memberTier >= 0) {
            tag.putInt(TAG_MEMBER_TIER, memberTier);
        }
        if (bankDeposit.signum() > 0) {
            tag.putByteArray(TAG_BANK_DEPOSIT, bankDeposit.toByteArray());
            tag.putLong(TAG_BANK_DEPOSIT_MS, bankDepositLastMs);
        }
        if (bankDebt.signum() > 0) {
            tag.putByteArray(TAG_BANK_DEBT, bankDebt.toByteArray());
            tag.putLong(TAG_BANK_DEBT_MS, bankDebtLastMs);
        }
        CompoundTag pur = new CompoundTag();
        for (Map.Entry<String, Long> e : purchaseCounts.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0L) pur.putLong(e.getKey(), e.getValue());
        }
        tag.put(TAG_PURCHASES, pur);
        CompoundTag pw = new CompoundTag();
        for (Map.Entry<String, Long> e : periodWindow.entrySet()) {
            pw.putLong(e.getKey(), e.getValue());
        }
        tag.put(TAG_PERIOD_WINDOW, pw);
        CompoundTag pu = new CompoundTag();
        for (Map.Entry<String, Long> e : periodUsed.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0L) pu.putLong(e.getKey(), e.getValue());
        }
        tag.put(TAG_PERIOD_USED, pu);
        return tag;
    }

    public static WalletAccount load(CompoundTag tag) {
        WalletAccount acc = new WalletAccount();
        CompoundTag cur = tag.getCompound(TAG_CURRENCIES);
        for (String key : cur.getAllKeys()) {
            byte[] bytes = cur.getByteArray(key);
            if (bytes.length > 0) {
                BigInteger v = new BigInteger(bytes);
                if (v.signum() > 0) acc.currencyBalances.put(new ResourceLocation(key), v);
            }
        }
        if (tag.contains(TAG_DIGITAL)) {
            byte[] bytes = tag.getByteArray(TAG_DIGITAL);
            if (bytes.length > 0) {
                BigInteger v = new BigInteger(bytes);
                if (v.signum() > 0) acc.digitalBalance = v;
            }
        }
        if (tag.contains(TAG_MEMBER_TIER)) {
            acc.memberTier = tag.getInt(TAG_MEMBER_TIER);
        }
        if (tag.contains(TAG_BANK_DEPOSIT)) {
            byte[] bytes = tag.getByteArray(TAG_BANK_DEPOSIT);
            if (bytes.length > 0) {
                BigInteger v = new BigInteger(bytes);
                if (v.signum() > 0) {
                    acc.bankDeposit = v;
                    acc.bankDepositLastMs = tag.getLong(TAG_BANK_DEPOSIT_MS);
                }
            }
        }
        if (tag.contains(TAG_BANK_DEBT)) {
            byte[] bytes = tag.getByteArray(TAG_BANK_DEBT);
            if (bytes.length > 0) {
                BigInteger v = new BigInteger(bytes);
                if (v.signum() > 0) {
                    acc.bankDebt = v;
                    acc.bankDebtLastMs = tag.getLong(TAG_BANK_DEBT_MS);
                }
            }
        }
        CompoundTag pur = tag.getCompound(TAG_PURCHASES);
        for (String key : pur.getAllKeys()) {
            long v = pur.getLong(key);
            if (v > 0L) acc.purchaseCounts.put(key, v);
        }
        CompoundTag pw = tag.getCompound(TAG_PERIOD_WINDOW);
        CompoundTag pu = tag.getCompound(TAG_PERIOD_USED);
        for (String key : pu.getAllKeys()) {
            long used = pu.getLong(key);
            if (used > 0L && pw.contains(key)) {
                acc.periodWindow.put(key, pw.getLong(key));
                acc.periodUsed.put(key, used);
            }
        }
        return acc;
    }
}
