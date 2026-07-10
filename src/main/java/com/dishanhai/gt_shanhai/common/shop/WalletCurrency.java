package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 钱包虚拟货币账本读写工具。
 *
 * <p>余额以虚拟数值存放在钱包 {@link ItemStack} 自身 NBT 的 {@value #BALANCES_KEY} 复合标签下，
 * 结构：{ "货币itemId": 数量Long }，例如 { "dishanhai:dog_coins": 500L }。按币种分别记账。</p>
 *
 * <p><b>关键约束：所有写操作都<em>原地修改</em>钱包 NBT，绝不替换 ItemStack 对象。</b>
 * 因为 LDLib {@code HeldItemUIFactory.HeldItemHolder.isInvalid()} 用
 * {@code ItemStack.matches(手持, held快照)} 判断界面是否失效，而 held 与手持是同一对象引用，
 * 原地改 NBT 时 matches(自己,自己)=true，界面保持打开；一旦替换对象则界面会被强制关闭。</p>
 *
 * <p>纯原版实现，无外部模组耦合。数值为 long，累加封顶 {@link Long#MAX_VALUE}。</p>
 */
public final class WalletCurrency {

    /** 钱包 NBT 中存放余额表的根键。 */
    public static final String BALANCES_KEY = "ShanhaiShopBalances";

    private WalletCurrency() {}

    /** 读取钱包中某货币的余额；无该货币返回 0。 */
    public static long get(ItemStack wallet, ResourceLocation currency) {
        if (wallet == null || wallet.isEmpty() || currency == null) {
            return 0L;
        }
        CompoundTag tag = wallet.getTag();
        if (tag == null || !tag.contains(BALANCES_KEY)) {
            return 0L;
        }
        return tag.getCompound(BALANCES_KEY).getLong(currency.toString());
    }

    /**
     * 给钱包某货币增加余额（delta 可负）。原地修改 NBT。
     * 结果封顶 {@link Long#MAX_VALUE}，且不会低于 0（扣到 0 时清除该键保持 NBT 干净）。
     * 返回写入后的新余额。
     */
    public static long add(ItemStack wallet, ResourceLocation currency, long delta) {
        if (wallet == null || wallet.isEmpty() || currency == null || delta == 0L) {
            return get(wallet, currency);
        }
        CompoundTag balances = wallet.getOrCreateTagElement(BALANCES_KEY);
        String key = currency.toString();
        long current = balances.getLong(key);
        long next;
        if (delta > 0L && current > Long.MAX_VALUE - delta) {
            next = Long.MAX_VALUE; // 防溢出封顶
        } else {
            next = current + delta;
        }
        if (next <= 0L) {
            balances.remove(key);
            next = 0L;
        } else {
            balances.putLong(key, next);
        }
        return next;
    }

    /**
     * 尝试从钱包扣除指定金额。余额不足则不扣款返回 false；成功返回 true。
     */
    public static boolean tryDeduct(ItemStack wallet, ResourceLocation currency, long cost) {
        if (cost <= 0L) {
            return true;
        }
        if (get(wallet, currency) < cost) {
            return false;
        }
        add(wallet, currency, -cost);
        return true;
    }

    /** 读取钱包所有币种余额（有序，键为货币ID）。 */
    public static Map<ResourceLocation, Long> getAll(ItemStack wallet) {
        Map<ResourceLocation, Long> result = new LinkedHashMap<>();
        if (wallet == null || wallet.isEmpty()) {
            return result;
        }
        CompoundTag tag = wallet.getTag();
        if (tag == null || !tag.contains(BALANCES_KEY)) {
            return result;
        }
        CompoundTag balances = tag.getCompound(BALANCES_KEY);
        for (String key : balances.getAllKeys()) {
            result.put(new ResourceLocation(key), balances.getLong(key));
        }
        return result;
    }
}
