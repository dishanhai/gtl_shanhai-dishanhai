package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 钱包账户存档（山海署名）。
 *
 * <p>账户按<b>玩家本体 UUID</b>（{@code player.getUUID()}）索引，存于 overworld 的 {@code DataStorage}，
 * 名 {@value #DATA_NAME}。余额不再写入钱包 ItemStack NBT —— 钱跟人不跟物，换手不带钱。</p>
 *
 * <p>结构与生命周期照抄 {@link com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData}：
 * {@link #get(MinecraftServer)} 惰性 computeIfAbsent，写操作后 {@link #setDirty()}。</p>
 */
public class WalletAccountSavedData extends SavedData {

    private static final String DATA_NAME = "gt_shanhai_wallet_accounts";
    private static final String TAG_ACCOUNTS = "accounts";
    private static final String TAG_UUID = "uuid";
    private static final String TAG_DATA = "data";

    /** 玩家UUID → 账户（保序）。 */
    private final Map<UUID, WalletAccount> accounts = new LinkedHashMap<>();

    public static WalletAccountSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                WalletAccountSavedData::load,
                WalletAccountSavedData::new,
                DATA_NAME);
    }

    public static WalletAccountSavedData load(CompoundTag tag) {
        WalletAccountSavedData data = new WalletAccountSavedData();
        ListTag list = tag.getList(TAG_ACCOUNTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID(TAG_UUID)) continue;
            data.accounts.put(entry.getUUID(TAG_UUID), WalletAccount.load(entry.getCompound(TAG_DATA)));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, WalletAccount> e : accounts.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_UUID, e.getKey());
            entry.put(TAG_DATA, e.getValue().save());
            list.add(entry);
        }
        tag.put(TAG_ACCOUNTS, list);
        return tag;
    }

    /** 读账户，无则 null（只读场景用，避免凭空建账）。 */
    public WalletAccount get(UUID uuid) {
        return uuid == null ? null : accounts.get(uuid);
    }

    /** 取账户，无则新建（写场景用）。 */
    public WalletAccount getOrCreate(UUID uuid) {
        return accounts.computeIfAbsent(uuid, k -> new WalletAccount());
    }
}
