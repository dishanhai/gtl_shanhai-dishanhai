package com.dishanhai.gt_shanhai.common.shop;

import com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 商店商品跳转用的稳定身份。同物品 ID、不同 NBT 必须落到不同键。
 * 超级磁盘阵列会丢掉电量、实例 UUID 和运行时统计，避免用过的磁盘对不上商店模板。
 */
public final class ShopGoodsIdentity {

    private static final String SUPER_DISK_ARRAY = "gt_shanhai:super_disk_array";

    private ShopGoodsIdentity() {}

    public static String key(String itemId, CompoundTag nbt) {
        String id = itemId == null ? "" : itemId.trim().toLowerCase(Locale.ROOT);
        CompoundTag stable = normalize(id, nbt);
        if (stable == null || stable.isEmpty()) return id + "\u0000";
        return id + "\u0000" + sha256(canonical(stable));
    }

    static CompoundTag normalize(String itemId, CompoundTag nbt) {
        if (nbt == null || nbt.isEmpty()) return null;
        if (!SUPER_DISK_ARRAY.equals(itemId)) return nbt;
        CompoundTag copy = nbt.copy();
        copy.remove("internalCurrentPower");
        copy.remove("RepairCost");
        copy.remove(SuperDiskArrayInventory.TAG_UUID);
        copy.remove(SuperDiskArrayInventory.TAG_RUNTIME_UUID);
        copy.remove(SuperDiskArrayInventory.TAG_TOTAL);
        copy.remove(SuperDiskArrayInventory.TAG_TYPES);
        if (copy.contains("display", Tag.TAG_COMPOUND)) {
            CompoundTag display = copy.getCompound("display").copy();
            display.remove("Lore");
            if (display.isEmpty()) copy.remove("display");
            else copy.put("display", display);
        }
        return copy.isEmpty() ? null : copy;
    }

    private static String canonical(Tag tag) {
        StringBuilder out = new StringBuilder();
        appendCanonical(out, tag);
        return out.toString();
    }

    private static void appendCanonical(StringBuilder out, Tag tag) {
        if (tag == null) {
            out.append('0');
            return;
        }
        out.append(tag.getId()).append(':');
        if (tag instanceof CompoundTag compound) {
            List<String> names = new ArrayList<>(compound.getAllKeys());
            Collections.sort(names);
            out.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) out.append(',');
                String name = names.get(i);
                out.append(name.length()).append(':').append(name).append('=');
                appendCanonical(out, compound.get(name));
            }
            out.append('}');
            return;
        }
        if (tag instanceof ListTag list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(',');
                appendCanonical(out, list.get(i));
            }
            out.append(']');
            return;
        }
        if (tag instanceof LongArrayTag longs) {
            appendLongs(out, longs.getAsLongArray());
            return;
        }
        if (tag instanceof IntArrayTag ints) {
            out.append('[');
            int[] values = ints.getAsIntArray();
            for (int i = 0; i < values.length; i++) {
                if (i > 0) out.append(',');
                out.append(values[i]);
            }
            out.append(']');
            return;
        }
        if (tag instanceof ByteArrayTag bytes) {
            out.append('[');
            byte[] values = bytes.getAsByteArray();
            for (int i = 0; i < values.length; i++) {
                if (i > 0) out.append(',');
                out.append(values[i]);
            }
            out.append(']');
            return;
        }
        out.append(tag.getAsString());
    }

    private static void appendLongs(StringBuilder out, long[] values) {
        out.append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(',');
            out.append(values[i]);
        }
        out.append(']');
    }

    private static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
