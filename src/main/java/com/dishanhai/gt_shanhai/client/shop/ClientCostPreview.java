package com.dishanhai.gt_shanhai.client.shop;

import net.minecraft.resources.ResourceLocation;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端花费预览缓存（山海署名，仅客户端）：{@code ShopCostPreviewPacket} 推来的只读余量
 * （币种/物品/流体各自当前可用总量），供 ShopScreen「花费预览」格子染色 + tooltip 补充"拥有/缺少"。
 *
 * <p>只缓存最近一次响应，用 {@link #matches} 校验是否对应当前选中商品 + AE 模式——选中商品切换/
 * AE 模式切换后，往返完成前渲染端一律按"未知"处理（灰色，不瞎猜），避免显示上一件商品的余量。</p>
 */
public final class ClientCostPreview {

    private static long entryKey = -1L;
    private static boolean aeMode = false;
    private static Map<ResourceLocation, BigInteger> coins = new LinkedHashMap<>();
    private static List<Long> items = List.of();
    private static List<Long> fluids = List.of();

    private ClientCostPreview() {}

    public static void apply(long newEntryKey, boolean newAeMode, Map<ResourceLocation, BigInteger> newCoins,
                              List<Long> newItems, List<Long> newFluids) {
        entryKey = newEntryKey;
        aeMode = newAeMode;
        coins = newCoins != null ? new LinkedHashMap<>(newCoins) : new LinkedHashMap<>();
        items = newItems != null ? newItems : List.of();
        fluids = newFluids != null ? newFluids : List.of();
    }

    /** 当前缓存是否对应这件商品 + 这个 AE 模式状态；不匹配时任何 have 查询都应视为"未知"。 */
    public static boolean matches(long checkEntryKey, boolean checkAeMode) {
        return entryKey == checkEntryKey && aeMode == checkAeMode;
    }

    /** 某币种当前可用总量（账户余额+背包+精妙背包+[AE模式]绑定AE）；未同步/不匹配返回 null。 */
    public static BigInteger coinHave(long checkEntryKey, boolean checkAeMode, ResourceLocation currency) {
        if (!matches(checkEntryKey, checkAeMode) || currency == null) return null;
        return coins.get(currency);
    }

    /** 第 index 项物品成本当前可用总量（顺序对齐服务端 ShopCost#items()）；未同步/不匹配/越界返回 null。 */
    public static Long itemHave(long checkEntryKey, boolean checkAeMode, int index) {
        if (!matches(checkEntryKey, checkAeMode) || index < 0 || index >= items.size()) return null;
        return items.get(index);
    }

    /** 第 index 项流体成本当前可用总量（顺序对齐服务端 ShopCost#fluids()）；未同步/不匹配/越界返回 null。 */
    public static Long fluidHave(long checkEntryKey, boolean checkAeMode, int index) {
        if (!matches(checkEntryKey, checkAeMode) || index < 0 || index >= fluids.size()) return null;
        return fluids.get(index);
    }
}
