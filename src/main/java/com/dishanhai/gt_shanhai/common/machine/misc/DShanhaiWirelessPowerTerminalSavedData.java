package com.dishanhai.gt_shanhai.common.machine.misc;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局可受电机器登记表——任意 GTCEu 机器（不限模组）只要暴露能量胶囊，
 * 加载进世界时就由 {@link com.dishanhai.gt_shanhai.mixin.DShanhaiAutoPowerRegistryMixin}
 * 自动登记，卸载时自动注销，无需玩家手动放置专用终端方块。
 * 发电机每 tick 遍历这张表往每个登记坐标的能量胶囊直接注能，不受距离/维度限制。
 */
public class DShanhaiWirelessPowerTerminalSavedData extends SavedData {
    private static final String DATA_NAME = "gt_shanhai_wireless_power_terminals";

    public static final class Entry {
        public final String dimension;
        public final BlockPos pos;

        public Entry(String dimension, BlockPos pos) {
            this.dimension = dimension;
            this.pos = pos;
        }

        public String key() {
            return dimension + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
        }
    }

    private final Map<String, Entry> terminals = new LinkedHashMap<>();

    public static DShanhaiWirelessPowerTerminalSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                DShanhaiWirelessPowerTerminalSavedData::load,
                DShanhaiWirelessPowerTerminalSavedData::new,
                DATA_NAME);
    }

    public static DShanhaiWirelessPowerTerminalSavedData load(CompoundTag tag) {
        DShanhaiWirelessPowerTerminalSavedData data = new DShanhaiWirelessPowerTerminalSavedData();
        ListTag list = tag.getList("terminals", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            String dimension = entryTag.getString("dim");
            if (dimension.isEmpty()) continue;
            BlockPos pos = new BlockPos(entryTag.getInt("x"), entryTag.getInt("y"), entryTag.getInt("z"));
            Entry entry = new Entry(dimension, pos);
            data.terminals.put(entry.key(), entry);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Entry entry : terminals.values()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("dim", entry.dimension);
            entryTag.putInt("x", entry.pos.getX());
            entryTag.putInt("y", entry.pos.getY());
            entryTag.putInt("z", entry.pos.getZ());
            list.add(entryTag);
        }
        tag.put("terminals", list);
        return tag;
    }

    public void register(ResourceKey<Level> dimension, BlockPos pos) {
        if (dimension == null || pos == null) return;
        Entry entry = new Entry(dimension.location().toString(), pos.immutable());
        terminals.put(entry.key(), entry);
        setDirty();
    }

    public void unregister(ResourceKey<Level> dimension, BlockPos pos) {
        if (dimension == null || pos == null) return;
        Entry entry = new Entry(dimension.location().toString(), pos.immutable());
        if (terminals.remove(entry.key()) != null) setDirty();
    }

    /** 返回登记表快照（防御性拷贝），避免调用方遍历期间登记表被并发 register/unregister 结构性修改而抛 ConcurrentModificationException。 */
    public Collection<Entry> getAll() {
        return new ArrayList<>(terminals.values());
    }
}
