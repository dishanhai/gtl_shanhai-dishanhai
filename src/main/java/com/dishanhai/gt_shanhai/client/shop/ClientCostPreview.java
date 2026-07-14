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
 * <p>按 entryKey 分槽缓存（非单槽覆盖）：详情页选中商品 + 购物车面板同时展开的多件商品都各自
 * 独立请求/缓存预览，互不覆盖（见反馈：购物车结算要同时显示多件商品的缺失/可用信息）。槽位数
 * 超过 {@link #MAX_SLOTS} 时按最久未使用淘汰，避免长时间浏览后无限增长。</p>
 *
 * <p>每个槽位记着自己是在哪个 AE 模式下抓的（{@link #matches} 会核对）——AE 模式切换后，
 * 所有槽位在往返刷新前都按"未知"处理（灰色，不瞎猜），避免显示切换前的余量。</p>
 */
public final class ClientCostPreview {

    private static final int MAX_SLOTS = 256;

    private static final class Slot {
        final boolean aeMode;
        final Map<ResourceLocation, BigInteger> coins;
        final List<Long> items;
        final List<Long> fluids;

        Slot(boolean aeMode, Map<ResourceLocation, BigInteger> coins, List<Long> items, List<Long> fluids) {
            this.aeMode = aeMode;
            this.coins = coins;
            this.items = items;
            this.fluids = fluids;
        }
    }

    /** LRU：访问序，超容量淘汰最久未使用的槽位（true=access-order）。 */
    private static final Map<Long, Slot> slots = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Slot> eldest) {
            return size() > MAX_SLOTS;
        }
    };

    private ClientCostPreview() {}

    public static void apply(long entryKey, boolean aeMode, Map<ResourceLocation, BigInteger> newCoins,
                              List<Long> newItems, List<Long> newFluids) {
        slots.put(entryKey, new Slot(aeMode,
                newCoins != null ? new LinkedHashMap<>(newCoins) : new LinkedHashMap<>(),
                newItems != null ? newItems : List.of(),
                newFluids != null ? newFluids : List.of()));
    }

    /** 该 entryKey 是否有对应这个 AE 模式状态的缓存；不匹配时任何 have 查询都应视为"未知"。 */
    public static boolean matches(long checkEntryKey, boolean checkAeMode) {
        Slot s = slots.get(checkEntryKey);
        return s != null && s.aeMode == checkAeMode;
    }

    /** 某币种当前可用总量（账户余额+背包+精妙背包+[AE模式]绑定AE）；未同步/不匹配返回 null。 */
    public static BigInteger coinHave(long checkEntryKey, boolean checkAeMode, ResourceLocation currency) {
        if (currency == null) return null;
        Slot s = slots.get(checkEntryKey);
        if (s == null || s.aeMode != checkAeMode) return null;
        return s.coins.get(currency);
    }

    /** 第 index 项物品成本当前可用总量（顺序对齐服务端 ShopCost#items()）；未同步/不匹配/越界返回 null。 */
    public static Long itemHave(long checkEntryKey, boolean checkAeMode, int index) {
        Slot s = slots.get(checkEntryKey);
        if (s == null || s.aeMode != checkAeMode || index < 0 || index >= s.items.size()) return null;
        return s.items.get(index);
    }

    /** 第 index 项流体成本当前可用总量（顺序对齐服务端 ShopCost#fluids()）；未同步/不匹配/越界返回 null。 */
    public static Long fluidHave(long checkEntryKey, boolean checkAeMode, int index) {
        Slot s = slots.get(checkEntryKey);
        if (s == null || s.aeMode != checkAeMode || index < 0 || index >= s.fluids.size()) return null;
        return s.fluids.get(index);
    }
}
