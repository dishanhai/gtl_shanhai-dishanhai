package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import appeng.api.stacks.AEItemKey;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 玩家提交解锁状态；商品 stableId 与阶段分类路径共用同一份存档。 */
public final class ShopSubmissionSavedData extends SavedData {
    private static final String DATA_NAME = "gt_shanhai_shop_submissions";
    private static final String TAG_PLAYERS = "players";
    private static final String TAG_UUID = "uuid";
    private static final String TAG_KEYS = "keys";
    private final Map<UUID, Set<String>> unlocked = new LinkedHashMap<>();

    public static ShopSubmissionSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                ShopSubmissionSavedData::load, ShopSubmissionSavedData::new, DATA_NAME);
    }

    public static ShopSubmissionSavedData load(CompoundTag tag) {
        ShopSubmissionSavedData data = new ShopSubmissionSavedData();
        ListTag players = tag.getList(TAG_PLAYERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag player = players.getCompound(i);
            try {
                UUID uuid = UUID.fromString(player.getString(TAG_UUID));
                ListTag keys = player.getList(TAG_KEYS, Tag.TAG_STRING);
                Set<String> set = new LinkedHashSet<>();
                for (int j = 0; j < keys.size(); j++) set.add(keys.getString(j));
                data.unlocked.put(uuid, set);
            } catch (Exception ignored) {}
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag players = new ListTag();
        for (Map.Entry<UUID, Set<String>> entry : unlocked.entrySet()) {
            CompoundTag player = new CompoundTag();
            player.putString(TAG_UUID, entry.getKey().toString());
            ListTag keys = new ListTag();
            for (String key : entry.getValue()) keys.add(StringTag.valueOf(key));
            player.put(TAG_KEYS, keys);
            players.add(player);
        }
        tag.put(TAG_PLAYERS, players);
        return tag;
    }

    public boolean has(ServerPlayer player, String key) {
        return player != null && key != null && unlocked.getOrDefault(player.getUUID(), Set.of()).contains(key);
    }

    public boolean mark(ServerPlayer player, String key) {
        if (player == null || key == null || key.isBlank()) return false;
        Set<String> keys = unlocked.computeIfAbsent(player.getUUID(), ignored -> new LinkedHashSet<>());
        if (!keys.add(key)) return false;
        setDirty();
        return true;
    }

    public Set<String> keys(ServerPlayer player) {
        return player == null ? Set.of() : Set.copyOf(unlocked.getOrDefault(player.getUUID(), Set.of()));
    }

    public void clearKey(String key) {
        if (key == null || key.isBlank()) return;
        boolean changed = false;
        for (Set<String> keys : unlocked.values()) changed |= keys.remove(key);
        if (changed) setDirty();
    }

    /** 兼容旧调用：提交默认只检查背包和精妙背包，不抽取 AE。 */
    public boolean submit(ServerPlayer player, String key, java.util.List<ExchangeEntry.Ingredient> requirements) {
        return submit(player, key, requirements, false, false);
    }

    /**
     * 检查并扣除提交物品；扣除顺序与商品购买的实物成本一致，AE 模式开启时才抽取绑定网络。
     * 先完成所有材料的模拟核对，再执行扣除，避免只提交了一部分材料。
     */
    public boolean submit(ServerPlayer player, String key, java.util.List<ExchangeEntry.Ingredient> requirements,
                          boolean aeMode, boolean backpackMode) {
        if (has(player, key)) return true;
        if (requirements == null || requirements.isEmpty()) return mark(player, key);
        for (ExchangeEntry.Ingredient requirement : requirements) {
            if (requirement == null || requirement.isFluid || requirement.makeUnitStack().isEmpty()) return false;
            ItemStack unit = requirement.makeUnitStack();
            long have = carriedCount(player, unit, backpackMode);
            if (aeMode) {
                AEItemKey keyInAe = AEItemKey.of(unit);
                long ae = keyInAe == null ? 0L : ShopAeNetwork.availableForPlayer(player, keyInAe);
                have = saturatingAdd(have, ae);
            }
            if (have < requirement.count) return false;
        }
        for (ExchangeEntry.Ingredient requirement : requirements) {
            ItemStack unit = requirement.makeUnitStack();
            long remaining = deductCarried(player, unit, requirement.count, backpackMode);
            if (remaining > 0L && aeMode) {
                AEItemKey keyInAe = AEItemKey.of(unit);
                if (keyInAe != null) ShopAeNetwork.extractForPlayer(player, keyInAe, remaining);
            }
        }
        return mark(player, key);
    }

    private static long carriedCount(ServerPlayer player, ItemStack unit, boolean backpackMode) {
        long inventory = ShopPurchase.countItem(player, unit.getItem(), unit.getTag());
        long backpack = ShopBackpack.countItem(player, unit.getItem(), unit.getTag());
        return backpackMode ? saturatingAdd(backpack, inventory) : saturatingAdd(inventory, backpack);
    }

    private static long deductCarried(ServerPlayer player, ItemStack unit, long amount, boolean backpackMode) {
        long remaining = amount;
        if (backpackMode) {
            remaining = deductBackpack(player, unit, remaining);
            remaining = deductInventory(player, unit, remaining);
        } else {
            remaining = deductInventory(player, unit, remaining);
            remaining = deductBackpack(player, unit, remaining);
        }
        return remaining;
    }

    private static long deductInventory(ServerPlayer player, ItemStack unit, long amount) {
        if (amount <= 0L) return 0L;
        long take = Math.min(amount, ShopPurchase.countItem(player, unit.getItem(), unit.getTag()));
        if (take > 0L) ShopPurchase.removeItems(player, unit.getItem(),
                (int) Math.min(Integer.MAX_VALUE, take), unit.getTag());
        return amount - take;
    }

    private static long deductBackpack(ServerPlayer player, ItemStack unit, long amount) {
        if (amount <= 0L) return 0L;
        long take = Math.min(amount, ShopBackpack.countItem(player, unit.getItem(), unit.getTag()));
        if (take > 0L) ShopBackpack.removeItems(player, unit.getItem(), take, unit.getTag());
        return amount - take;
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
