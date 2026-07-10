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

    /** 币种 → 余额（BigInteger，保序）。 */
    private final Map<ResourceLocation, BigInteger> currencyBalances = new LinkedHashMap<>();
    /** 数字余额（星火）。 */
    private BigInteger digitalBalance = BigInteger.ZERO;

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
        return acc;
    }
}
