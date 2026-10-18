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
 *
 * <p>AE 模式还会带上可下单状态（0 未核对，1 有样板，2 无样板，3 未绑定网络）。
 * 这只表示绑定网络里有没有对应样板，不是合成计算的结果。</p>
 */
public final class ClientCostPreview {

    private static final int MAX_SLOTS = 256;

    private static final class Slot {
        final boolean aeMode;
        final Map<ResourceLocation, BigInteger> coins;
        final List<Long> items;
        final List<Long> fluids;
        final Map<ResourceLocation, Integer> coinOrder;
        final List<Integer> itemOrder;
        final List<Integer> fluidOrder;

        Slot(boolean aeMode, Map<ResourceLocation, BigInteger> coins, List<Long> items, List<Long> fluids,
             Map<ResourceLocation, Integer> coinOrder, List<Integer> itemOrder, List<Integer> fluidOrder) {
            this.aeMode = aeMode;
            this.coins = coins;
            this.items = items;
            this.fluids = fluids;
            this.coinOrder = coinOrder;
            this.itemOrder = itemOrder;
            this.fluidOrder = fluidOrder;
        }
    }

    /** LRU：访问序，超容量淘汰最久未使用的槽位（true=access-order）。 */
    private static final Map<Long, Slot> slots = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Slot> eldest) {
            return size() > MAX_SLOTS;
        }
    };
    private static final Map<String, Slot> stageSlots = new LinkedHashMap<>();

    private ClientCostPreview() {}

    /** 整体失效：登出/换服（ClientInit）与目录 revision 变更（ClientShopCatalog.applyManifest）时调用。
     *  entryKey 是快照内的位置下标，revision 一变就可能换主，旧槽位的「拥有/缺少」不能套在新商品上。 */
    public static void clear() {
        slots.clear();
        stageSlots.clear();
    }

    public static void clearStage() {
        stageSlots.clear();
    }

    public static void apply(long entryKey, boolean aeMode, Map<ResourceLocation, BigInteger> newCoins,
                              List<Long> newItems, List<Long> newFluids,
                              List<Integer> newCoinOrder, List<Integer> newItemOrder, List<Integer> newFluidOrder) {
        Map<ResourceLocation, BigInteger> coins = newCoins != null ? new LinkedHashMap<>(newCoins) : new LinkedHashMap<>();
        Map<ResourceLocation, Integer> coinOrder = new LinkedHashMap<>();
        int index = 0;
        for (ResourceLocation id : coins.keySet()) {
            if (newCoinOrder == null || index >= newCoinOrder.size()) break;
            Integer state = newCoinOrder.get(index++);
            coinOrder.put(id, state == null ? 0 : state);
        }
        slots.put(entryKey, new Slot(aeMode, coins,
                newItems != null ? newItems : List.of(),
                newFluids != null ? newFluids : List.of(),
                coinOrder,
                newItemOrder != null ? List.copyOf(newItemOrder) : List.of(),
                newFluidOrder != null ? List.copyOf(newFluidOrder) : List.of()));
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

    /** 幣種可下單狀態。未同步或 AE 模式對不上時回 0（界面不顯示）。 */
    public static int coinOrder(long checkEntryKey, boolean checkAeMode, ResourceLocation currency) {
        if (currency == null) return 0;
        Slot s = slots.get(checkEntryKey);
        if (s == null || s.aeMode != checkAeMode) return 0;
        Integer state = s.coinOrder.get(currency);
        return state == null ? 0 : state;
    }

    public static int itemOrder(long checkEntryKey, boolean checkAeMode, int index) {
        Slot s = slots.get(checkEntryKey);
        if (s == null || s.aeMode != checkAeMode || index < 0 || index >= s.itemOrder.size()) return 0;
        Integer state = s.itemOrder.get(index);
        return state == null ? 0 : state;
    }

    public static int fluidOrder(long checkEntryKey, boolean checkAeMode, int index) {
        Slot s = slots.get(checkEntryKey);
        if (s == null || s.aeMode != checkAeMode || index < 0 || index >= s.fluidOrder.size()) return 0;
        Integer state = s.fluidOrder.get(index);
        return state == null ? 0 : state;
    }

    public static void applyStage(String stagePath, boolean aeMode, List<Long> newItems) {
        if (stagePath == null || stagePath.isBlank()) return;
        stageSlots.put(stagePath, new Slot(aeMode, Map.of(), newItems != null ? List.copyOf(newItems) : List.of(),
                List.of(), Map.of(), List.of(), List.of()));
    }

    public static Long stageItemHave(String stagePath, boolean aeMode, int index) {
        Slot s = stageSlots.get(stagePath);
        if (s == null || s.aeMode != aeMode || index < 0 || index >= s.items.size()) return null;
        return s.items.get(index);
    }
}
