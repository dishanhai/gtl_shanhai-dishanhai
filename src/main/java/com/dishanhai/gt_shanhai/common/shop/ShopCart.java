package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 玩家购物车数据对象（山海署名）：稳定身份 ID（见 {@link ShopEntry#getStableId}）→ 候选购买次数（保序，
 * 展示顺序=加入顺序）。只存"待结算候选"，不代表已购买、不参与实际扣款——真正购买仍走既有的
 * {@code ShopActionPacket#BUY}，结算=按候选清单逐项各自单独发起购买请求。
 *
 * <p>该对象仅存于服务端 {@link ShopCartSavedData}，按玩家本体 UUID 索引，跨重登保留
 * （见反馈：购物车要跨开关面板/重登保留）。</p>
 */
public class ShopCart {

    private static final String TAG_ITEMS = "items";
    private static final String TAG_STABLE_ID = "stableId";
    private static final String TAG_AMOUNT = "amount";

    /** stableId → 候选购买次数（保序）。 */
    private final Map<String, Long> items = new LinkedHashMap<>();

    public long getAmount(String stableId) {
        if (stableId == null) return 0L;
        Long v = items.get(stableId);
        return v == null ? 0L : v;
    }

    public boolean contains(String stableId) {
        return stableId != null && items.containsKey(stableId);
    }

    /** 加入购物车（已存在则忽略，保留原有数量——数量调整走 {@link #setAmount}）。 */
    public void addIfAbsent(String stableId, long amount) {
        if (stableId == null || stableId.isBlank() || amount <= 0L) return;
        items.putIfAbsent(stableId, amount);
    }

    /** 覆盖写候选购买次数（购物车面板的数量调整用）；≤0 视为移除。 */
    public void setAmount(String stableId, long amount) {
        if (stableId == null || stableId.isBlank()) return;
        if (amount <= 0L) items.remove(stableId);
        else items.put(stableId, amount);
    }

    public void remove(String stableId) {
        if (stableId != null) items.remove(stableId);
    }

    /** 只读视图（副本，保序）。 */
    public Map<String, Long> getItems() {
        return new LinkedHashMap<>(items);
    }

    // ===== NBT =====

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Map.Entry<String, Long> e : items.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0L) continue;
            CompoundTag entry = new CompoundTag();
            entry.putString(TAG_STABLE_ID, e.getKey());
            entry.putLong(TAG_AMOUNT, e.getValue());
            list.add(entry);
        }
        tag.put(TAG_ITEMS, list);
        return tag;
    }

    public static ShopCart load(CompoundTag tag) {
        ShopCart cart = new ShopCart();
        ListTag list = tag.getList(TAG_ITEMS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String stableId = entry.getString(TAG_STABLE_ID);
            long amount = entry.getLong(TAG_AMOUNT);
            if (!stableId.isBlank() && amount > 0L) cart.items.put(stableId, amount);
        }
        return cart;
    }
}
