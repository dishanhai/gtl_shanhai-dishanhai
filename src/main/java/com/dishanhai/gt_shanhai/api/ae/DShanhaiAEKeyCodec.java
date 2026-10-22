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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable AEKey NBT codec used by SDA virtual cells and ME disk related storage. */
public final class DShanhaiAEKeyCodec {
    public static final String TAG_KEYS = "keys";
    public static final String TAG_AMTS = "amts";
    public static final String TAG_HASH = "h";
    public static final String TAG_KEY = "key";
    public static final String TAG_AMOUNT = "amount";

    /**
     * 正規化 NBT / SHA-256 結果快取上限。
     * AEKey 是不可變值物件，同一把 key 的正規化 tag 與雜湊在整個 JVM 生命週期內不會變化，
     * 命中快取即可省掉 normalizeTag + canonicalString + MessageDigest 三段成本。
     */
    private static final int CACHE_LIMIT = 4096;

    private static final Map<AEKey, CachedKey> CACHE = new BoundedKeyCache();
    private static final CachedKey EMPTY = new CachedKey(new CompoundTag());

    private DShanhaiAEKeyCodec() {}

    public static CompoundTag toNormalizedTag(AEKey key) {
        return cachedEntry(key).copyTag();
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
        return cachedEntry(key).hash();
    }

    public static CompoundTag writeHashedEntry(AEKey key, long amount) {
        CompoundTag entry = new CompoundTag();
        CachedKey cached = cachedEntry(key);
        entry.putString(TAG_HASH, cached.hash());
        entry.put(TAG_KEY, cached.copyTag());
        entry.putLong(TAG_AMOUNT, Math.max(0L, amount));
        return entry;
    }

    /** 取 key 的正規化結果與雜湊；null key 回傳空條目。 */
    public static CachedKey cachedEntry(AEKey key) {
        if (key == null) return EMPTY;
        CachedKey cached = CACHE.get(key);
        if (cached != null) return cached;
        CachedKey created = new CachedKey(normalizeForKey(key));
        CACHE.put(key, created);
        return created;
    }

    private static CompoundTag normalizeForKey(AEKey key) {
        CompoundTag tag = normalizeTag(key.toTagGeneric());
        if (tag.contains("tag", Tag.TAG_COMPOUND) && tag.getCompound("tag").isEmpty()) {
            tag.remove("tag");
        }
        return tag;
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

    /** 單一 key 的正規化 NBT（不可變持有）與其 SHA-256 雜湊。 */
    public static final class CachedKey {
        private final CompoundTag tag;
        private final String hash;

        private CachedKey(CompoundTag tag) {
            this.tag = tag;
            this.hash = sha256(canonicalString(tag));
        }

        public CompoundTag copyTag() {
            return tag.copy();
        }

        public String hash() {
            return hash;
        }
    }

    /**
     * 插入序保留、超過 {@link #CACHE_LIMIT} 就淘汰最舊項目的快取。
     * 存取皆 synchronized：寫入可能在伺服器執行緒，客戶端 tooltip 亦會讀取。
     */
    private static final class BoundedKeyCache extends LinkedHashMap<AEKey, CachedKey> {
        private BoundedKeyCache() {
            super(64, 0.75f, false);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<AEKey, CachedKey> eldest) {
            return size() > CACHE_LIMIT;
        }

        @Override
        public synchronized CachedKey get(Object key) {
            return super.get(key);
        }

        @Override
        public synchronized CachedKey put(AEKey key, CachedKey value) {
            return super.put(key, value);
        }
    }
}
