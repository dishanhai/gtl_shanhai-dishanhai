package com.dishanhai.gt_shanhai.client.shop;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端「AE 网络余额」预览缓存（山海署名，仅客户端）：{@code CurrencyAeBalancePacket} 推来的
 * 只读余量，供 {@code CurrencyAtmScreen} 在「从 AE 抽取」按钮上方显示，好让玩家知道该输多少。
 *
 * <p>按币种分槽缓存；未请求过/往返未回的币种查询返回 null（界面按"未知/查询中"处理，不瞎猜 0）。</p>
 */
public final class ClientAeCurrencyBalance {

    private static final Map<ResourceLocation, Long> balances = new HashMap<>();

    private ClientAeCurrencyBalance() {}

    public static void apply(ResourceLocation currency, long available) {
        if (currency == null) return;
        balances.put(currency, available);
    }

    /** 该币种上一次往返查到的 AE 网络可抽量；未同步过返回 null。 */
    public static Long get(ResourceLocation currency) {
        if (currency == null) return null;
        return balances.get(currency);
    }
}
