package com.dishanhai.gt_shanhai.api.ae;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Stable AEKey NBT codec used by SDA virtual cells and ME disk related storage. */
public final class DShanhaiAEKeyCodec {
    public static final String TAG_KEYS = "keys";
    public static final String TAG_AMTS = "amts";
    public static final String TAG_HASH = "h";
    public static final String TAG_KEY = "key";
    public static final String TAG_AMOUNT = "amount";

    private DShanhaiAEKeyCodec() {}

    public static CompoundTag toNormalizedTag(AEKey key) {
        if (key == null) return new CompoundTag();
        CompoundTag tag = normalizeTag(key.toTagGeneric());
        if (tag.contains("tag", Tag.TAG_COMPOUND) && tag.getCompound("tag").isEmpty()) {
            tag.remove("tag");
        }
        return tag;
    }

    public static AEKey fromNormalizedTag(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return null;
        CompoundTag copy = tag.copy();
        if (copy.contains("tag", Tag.TAG_COMPOUND) && copy.getCompound("tag").isEmpty()) {
            copy.remove("tag");
        }
        return AEKey.fromTagGeneric(copy);
    }

    public static String stableHash(AEKey key) {
        return sha256(canonicalString(toNormalizedTag(key)));
    }

    public static CompoundTag writeHashedEntry(AEKey key, long amount) {
        CompoundTag entry = new CompoundTag();
        CompoundTag keyTag = toNormalizedTag(key);
        entry.putString(TAG_HASH, sha256(canonicalString(keyTag)));
        entry.put(TAG_KEY, keyTag);
        entry.putLong(TAG_AMOUNT, Math.max(0L, amount));
        return entry;
    }

    public static AEKey readHashedEntryKey(CompoundTag entry) {
        if (entry == null || !entry.contains(TAG_KEY, Tag.TAG_COMPOUND)) return null;
        return fromNormalizedTag(entry.getCompound(TAG_KEY));
    }

    public static long readHashedEntryAmount(CompoundTag entry) {
        if (entry == null) return 0L;
        if (entry.contains(TAG_AMOUNT, Tag.TAG_ANY_NUMERIC)) return entry.getLong(TAG_AMOUNT);
        return 0L;
    }

    public static AEKey itemKey(String id) {
        ResourceLocation resourceLocation = parseId(id);
        if (resourceLocation == null) return null;
        Item item = ForgeRegistries.ITEMS.getValue(resourceLocation);
        return item != null ? AEItemKey.of(item) : null;
    }

    public static AEKey fluidKey(String id) {
        ResourceLocation resourceLocation = parseId(id);
        if (resourceLocation == null) return null;
        Fluid fluid = ForgeRegistries.FLUIDS.getValue(resourceLocation);
        return fluid != null ? AEFluidKey.of(fluid) : null;
    }

    public static CompoundTag normalizeTag(CompoundTag source) {
        if (source == null) return new CompoundTag();
        CompoundTag result = new CompoundTag();
        List<String> keys = new ArrayList<>(source.getAllKeys());
        Collections.sort(keys);
        for (String key : keys) {
            Tag value = source.get(key);
            if (value instanceof CompoundTag) {
                result.put(key, normalizeTag((CompoundTag) value));
            } else if (value instanceof ListTag) {
                result.put(key, normalizeList((ListTag) value));
            } else if (value != null) {
                result.put(key, value.copy());
            }
        }
        return result;
    }

    private static ListTag normalizeList(ListTag source) {
        ListTag result = new ListTag();
        for (int i = 0; i < source.size(); i++) {
            Tag value = source.get(i);
            if (value instanceof CompoundTag) {
                result.add(normalizeTag((CompoundTag) value));
            } else if (value instanceof ListTag) {
                result.add(normalizeList((ListTag) value));
            } else if (value != null) {
                result.add(value.copy());
            }
        }
        return result;
    }

    private static String canonicalString(Tag tag) {
        if (tag instanceof CompoundTag) {
            CompoundTag compound = (CompoundTag) tag;
            List<String> keys = new ArrayList<>(compound.getAllKeys());
            Collections.sort(keys);
            StringBuilder builder = new StringBuilder();
            builder.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) builder.append(',');
                String key = keys.get(i);
                builder.append(key).append(':').append(canonicalString(compound.get(key)));
            }
            builder.append('}');
            return builder.toString();
        }
        if (tag instanceof ListTag) {
            ListTag list = (ListTag) tag;
            StringBuilder builder = new StringBuilder();
            builder.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) builder.append(',');
                builder.append(canonicalString(list.get(i)));
            }
            builder.append(']');
            return builder.toString();
        }
        return tag != null ? tag.toString() : "";
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                int v = b & 255;
                if (v < 16) builder.append('0');
                builder.append(Integer.toHexString(v));
            }
            return builder.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static ResourceLocation parseId(String id) {
        return id != null ? ResourceLocation.tryParse(id.trim()) : null;
    }
}
