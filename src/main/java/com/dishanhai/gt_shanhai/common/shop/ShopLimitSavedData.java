package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 商店永久限購存檔（山海署名）。預設額度按商品 stableId，實際餘額按玩家 UUID + stableId 保存，存於
 * overworld 的 {@code DataStorage}，名 {@value #DATA_NAME}。
 *
 * <p>編輯器「存檔次數」是此存檔每位玩家的預設額度，「服務端次數」是新存檔與重置的基準。
 * 舊版只有全服剩餘量，不能還原各玩家消耗；升級時以服務端基準重新建立個人額度。
 * 購買與出售扣同一玩家的餘額，不改商品目錄。明確修改存檔額度或重置時清除該商品的個人餘額。</p>
 *
 * <p>结构与生命周期照抄 {@link WalletAccountSavedData}：{@link #get(MinecraftServer)} 惰性
 * computeIfAbsent，写操作后 {@link #setDirty()}。</p>
 */
public class ShopLimitSavedData extends SavedData {

    private static final String DATA_NAME = "gt_shanhai_shop_limits";
    private static final String TAG_ENTRIES = "entries";
    private static final String TAG_ID = "id";
    private static final String TAG_REMAINING = "remaining";

    /** 商品 stableId → 此存檔每位玩家的預設額度（保序）。 */
    private final Map<String, Long> remaining = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Long>> playerRemaining = new LinkedHashMap<>();

    public static ShopLimitSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                ShopLimitSavedData::load,
                ShopLimitSavedData::new,
                DATA_NAME);
    }

    public static ShopLimitSavedData load(CompoundTag tag) {
        ShopLimitSavedData data = new ShopLimitSavedData();
        if (tag.getInt("version") < 2) {
            data.setDirty();
            return data;
        }
        ListTag list = tag.getList(TAG_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.contains(TAG_ID)) continue;
            data.remaining.put(entry.getString(TAG_ID), entry.getLong(TAG_REMAINING));
        }
        ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag player = players.getCompound(i);
            if (!player.hasUUID("uuid")) continue;
            Map<String, Long> balances = new LinkedHashMap<>();
            ListTag balancesTag = player.getList(TAG_ENTRIES, Tag.TAG_COMPOUND);
            for (int j = 0; j < balancesTag.size(); j++) {
                CompoundTag balance = balancesTag.getCompound(j);
                if (balance.contains(TAG_ID)) balances.put(balance.getString(TAG_ID),
                        Math.max(0L, balance.getLong(TAG_REMAINING)));
            }
            data.playerRemaining.put(player.getUUID("uuid"), balances);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("version", 2);
        ListTag list = new ListTag();
        for (Map.Entry<String, Long> e : remaining.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString(TAG_ID, e.getKey());
            entry.putLong(TAG_REMAINING, e.getValue());
            list.add(entry);
        }
        tag.put(TAG_ENTRIES, list);
        ListTag players = new ListTag();
        for (Map.Entry<UUID, Map<String, Long>> player : playerRemaining.entrySet()) {
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID("uuid", player.getKey());
            ListTag balances = new ListTag();
            for (Map.Entry<String, Long> balance : player.getValue().entrySet()) {
                CompoundTag balanceTag = new CompoundTag();
                balanceTag.putString(TAG_ID, balance.getKey());
                balanceTag.putLong(TAG_REMAINING, balance.getValue());
                balances.add(balanceTag);
            }
            playerTag.put(TAG_ENTRIES, balances);
            players.add(playerTag);
        }
        tag.put("players", players);
        return tag;
    }

    /** 讀此存檔的商品預設額度；尚未初始化時返回 null。 */
    public Long get(String stableId) {
        return stableId == null ? null : remaining.get(stableId);
    }

    /** 寫入商品預設額度；明確重設既有玩家時需另外呼叫 resetPlayers。 */
    public void set(String stableId, long value) {
        if (stableId == null) return;
        remaining.put(stableId, value < 0L ? -1L : value);
        setDirty();
    }

    /** 已有記錄（含 0 / 不限）優先；新條目一律從服務端值初始化，不沿用靜態目錄的舊存檔餘量。 */
    public void applyTo(ShopEntry entry) {
        Long saved = get(entry.getStableId());
        if (saved == null) {
            saved = entry.getServerUses();
            set(entry.getStableId(), saved);
        }
        entry.overrideRemainingUses(saved);
    }

    public void reset(ShopEntry entry) {
        set(entry.getStableId(), entry.getServerUses());
        resetPlayers(entry.getStableId());
        entry.overrideRemainingUses(entry.getServerUses());
    }

    /** 個人餘額；未交易過的玩家使用此存檔預設額度，-1 表示不限。 */
    public long remaining(UUID player, ShopEntry entry) {
        Long configured = get(entry.getStableId());
        long baseline = configured != null ? configured : entry.getRemainingUses();
        if (baseline < 0L) return -1L;
        Map<String, Long> balances = playerRemaining.get(player);
        return balances == null ? baseline : balances.getOrDefault(entry.getStableId(), baseline);
    }

    /** 購買與出售共用；不修改全服目錄，也不借用展示用的累計購買統計。 */
    public void consume(UUID player, ShopEntry entry, long amount) {
        if (player == null || amount <= 0L) return;
        long before = remaining(player, entry);
        if (before < 0L) return;
        playerRemaining.computeIfAbsent(player, key -> new LinkedHashMap<>())
                .put(entry.getStableId(), Math.max(0L, before - amount));
        setDirty();
    }

    public Map<String, Long> getPlayerRemaining(UUID player) {
        Map<String, Long> balances = playerRemaining.get(player);
        return balances == null ? new LinkedHashMap<>() : new LinkedHashMap<>(balances);
    }

    public void resetPlayers(String stableId) {
        for (Map<String, Long> balances : playerRemaining.values()) balances.remove(stableId);
        playerRemaining.values().removeIf(Map::isEmpty);
        setDirty();
    }

    public static long remaining(ServerPlayer player, ShopEntry entry) {
        MinecraftServer server = player == null ? null : player.getServer();
        return server == null ? (entry.isLimited() ? 0L : -1L)
                : get(server).remaining(player.getUUID(), entry);
    }

    public static long clamp(ServerPlayer player, ShopEntry entry, long times) {
        long balance = remaining(player, entry);
        return balance < 0L ? times : Math.min(times, balance);
    }

    public static void consume(ServerPlayer player, ShopEntry entry, long amount) {
        if (player != null && player.getServer() != null) {
            get(player.getServer()).consume(player.getUUID(), entry, amount);
        }
    }
}
