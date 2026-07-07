package com.dishanhai.gt_shanhai.api.pack;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 单个 AE 包的构建结果 */
public class DShanhaiPack {

    public final String id;
    public final String name;
    public final List<String> lore;
    public final String cellType;
    public final long itemCount;

    private String nbt;
    private final String nbtHash;
    private final boolean fromCache;

    // 包内物品明细（用于统计和过滤）
    public final List<PackItemEntry> entries;

    DShanhaiPack(String id, String name, List<String> lore, String cellType,
                 List<PackItemEntry> entries, String nbt, boolean fromCache) {
        this.id = id;
        this.name = name;
        this.lore = lore;
        this.cellType = cellType;
        this.entries = entries;
        this.itemCount = entries.stream().mapToLong(e -> e.amount).sum();
        this.nbt = nbt;
        this.fromCache = fromCache;
        this.nbtHash = hash(nbt);
    }

    // ========== 公开 API ==========

    /** 获取 NBT 字符串 */
    public String nbt() { return nbt; }
    /** 获取 NBT SHA-256 哈希 */
    public String nbtHash() { return nbtHash; }
    /** 是否来自缓存 */
    public boolean fromCache() { return fromCache; }
    /** 物品种类数 */
    public int typeCount() { return entries.size(); }

    /** 获取包内所有物品的 ItemStack */
    public List<ItemStack> toItemStacks() {
        return entries.stream().map(e -> {
            var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(e.itemId));
            return new ItemStack(item, (int) Math.min(e.amount, 64));
        }).collect(Collectors.toList());
    }

    /** 合并另一个包 */
    public DShanhaiPack merge(DShanhaiPack other) {
        if (other == null) return this;
        var merged = new ArrayList<>(entries);
        merged.addAll(other.entries);
        // rebuild with no cache
        return DShanhaiPackRegistry.rebuild(id, name + "+" + other.name,
                mergeLore(lore, other.lore), cellType, merged);
    }

    private static List<String> mergeLore(List<String> a, List<String> b) {
        var r = new ArrayList<>(a);
        r.add("§7--- 合并 ---");
        r.addAll(b);
        return r;
    }

    // ========== 内部工具 ==========

    static String hash(String str) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(str.getBytes("UTF-8"));
            var sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(str.hashCode()); }
    }

    /** 解析 "Nx mod:id@innerId" */
    static PackItemEntry parseEntry(String str) {
        str = str.trim();
        var regex = Pattern.compile("^(\\d+)\\s*x\\s*(.+)$");
        var m = regex.matcher(str);
        long amount;
        String idPart;
        if (m.find()) {
            amount = Long.parseLong(m.group(1));
            idPart = m.group(2).trim();
        } else {
            amount = 1;
            idPart = str;
        }
        int at = idPart.indexOf('@');
        String itemId, innerId;
        if (at != -1) {
            itemId = idPart.substring(0, at);
            innerId = idPart.substring(at + 1);
        } else {
            itemId = idPart;
            innerId = null;
        }
        // 提取模组 ID
        String modId = itemId.contains(":") ? itemId.split(":", 2)[0] : "minecraft";
        return new PackItemEntry(amount, itemId, innerId, modId);
    }

    public static class PackItemEntry {
        public final long amount;
        public final String itemId;
        public final String innerId;
        public final String modId;

        PackItemEntry(long amount, String itemId, String innerId, String modId) {
            this.amount = amount;
            this.itemId = itemId;
            this.innerId = innerId;
            this.modId = modId;
        }

        public String key() { return itemId + (innerId != null ? "@" + innerId : ""); }

        PackItemEntry merge(long extra) {
            return new PackItemEntry(amount + extra, itemId, innerId, modId);
        }
    }
}
