package com.dishanhai.gt_shanhai.common.shop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 山海商店服务端权威目录快照。列表和索引完整构建后一次性发布，读取方不会观察到半加载目录。
 */
public final class ShopCatalogSnapshot {

    public static final int DEFAULT_CHUNK_BYTES = 48 * 1024;
    public static final int DEFAULT_MAX_CHUNK_ENTRIES = 16;
    public static final int MAX_ENTRY_BYTES = 1024 * 1024;

    public record Descriptor(String category, boolean hidden, String linkKey,
                             String displayName, List<String> goodsIds, int payloadBytes, String stableId) {
        public Descriptor {
            category = category == null || category.isBlank() ? ShopEntry.DEFAULT_CATEGORY : category;
            linkKey = linkKey == null ? "" : linkKey;
            displayName = displayName == null ? "" : displayName;
            goodsIds = goodsIds == null ? List.of() : List.copyOf(goodsIds);
            payloadBytes = Math.max(1, payloadBytes);
            stableId = stableId == null ? "" : stableId;
        }
    }

    /** 不依赖注册表和 ItemStack 的纯布局结果，供单测、客户端清单和服务端快照共用。 */
    public static final class Layout {
        private final List<ShopCatalogManifest.Stub> stubs;
        private final Map<String, List<Long>> groupKeys;
        private final List<List<Long>> chunks;
        private final Map<String, Long> linkKeyToEntryKey;
        private final List<String> topCategories;
        private final Map<String, List<String>> subCategories;

        private Layout(List<ShopCatalogManifest.Stub> stubs,
                       Map<String, List<Long>> groupKeys,
                       List<List<Long>> chunks,
                       Map<String, Long> linkKeyToEntryKey,
                       List<String> topCategories,
                       Map<String, List<String>> subCategories) {
            this.stubs = List.copyOf(stubs);
            this.groupKeys = immutableListMap(groupKeys);
            List<List<Long>> chunkCopy = new ArrayList<>(chunks.size());
            for (List<Long> chunk : chunks) chunkCopy.add(List.copyOf(chunk));
            this.chunks = List.copyOf(chunkCopy);
            this.linkKeyToEntryKey = Map.copyOf(linkKeyToEntryKey);
            this.topCategories = List.copyOf(topCategories);
            this.subCategories = immutableStringListMap(subCategories);
        }

        public List<ShopCatalogManifest.Stub> stubs() { return stubs; }
        public List<List<Long>> chunks() { return chunks; }
        public Map<String, Long> linkKeyToEntryKey() { return linkKeyToEntryKey; }
        public List<String> topCategories() { return topCategories; }

        public List<Long> groupKeys(String top, String sub) {
            return groupKeys.getOrDefault(groupKey(top, sub), List.of());
        }

        public List<String> subCategories(String top) {
            return subCategories.getOrDefault(top == null ? "" : top, List.of());
        }
    }

    private final long revision;
    private final boolean ready;
    private final List<ShopEntry> entries;
    private final Map<Long, ShopEntry> entriesByKey;
    private final IdentityHashMap<ShopEntry, Long> keysByEntry;
    private final Map<String, ShopEntry> entriesByStableId;
    private final Layout layout;
    private final ShopCatalogManifest manifest;
    private final Map<Integer, List<ShopCatalogEntryPayload>> payloadChunks;

    private ShopCatalogSnapshot(long revision, boolean ready, List<ShopEntry> entries,
                                Map<Long, ShopEntry> entriesByKey,
                                IdentityHashMap<ShopEntry, Long> keysByEntry,
                                Map<String, ShopEntry> entriesByStableId,
                                Layout layout, ShopCatalogManifest manifest,
                                Map<Integer, List<ShopCatalogEntryPayload>> payloadChunks) {
        this.revision = revision;
        this.ready = ready;
        this.entries = List.copyOf(entries);
        this.entriesByKey = Map.copyOf(entriesByKey);
        this.keysByEntry = new IdentityHashMap<>(keysByEntry);
        this.entriesByStableId = Map.copyOf(entriesByStableId);
        this.layout = layout;
        this.manifest = manifest;
        Map<Integer, List<ShopCatalogEntryPayload>> chunks = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<ShopCatalogEntryPayload>> entry : payloadChunks.entrySet()) {
            chunks.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.payloadChunks = Map.copyOf(chunks);
    }

    public static ShopCatalogSnapshot empty() {
        Layout layout = layout(List.of(), DEFAULT_CHUNK_BYTES, DEFAULT_MAX_CHUNK_ENTRIES);
        return new ShopCatalogSnapshot(0L, false, List.of(), Map.of(), new IdentityHashMap<>(),
                Map.of(), layout, ShopCatalogManifest.empty(), Map.of());
    }

    public static ShopCatalogSnapshot build(long revision, List<ShopEntry> source) {
        List<ShopEntry> entries = source == null ? List.of() : List.copyOf(source);
        List<ShopCatalogEntryPayload> payloads = new ArrayList<>(entries.size());
        List<Descriptor> descriptors = new ArrayList<>(entries.size());
        Map<Long, ShopEntry> byKey = new LinkedHashMap<>();
        IdentityHashMap<ShopEntry, Long> byEntry = new IdentityHashMap<>();
        Map<String, ShopEntry> byStableId = new LinkedHashMap<>();

        for (int index = 0; index < entries.size(); index++) {
            long key = index;
            ShopEntry entry = entries.get(index);
            String json = ShopEntryJsonCodec.toPayload(entry);
            ShopCatalogEntryPayload payload = new ShopCatalogEntryPayload(key, json);
            if (payload.estimatedUtf8Bytes() > MAX_ENTRY_BYTES) {
                throw new IllegalArgumentException("商品负载超过 1 MiB: entryKey=" + key);
            }
            List<String> goodsIds = new ArrayList<>();
            for (ShopEntry.GoodsStack goods : entry.getGoodsList()) {
                if (goods != null && goods.id() != null) goodsIds.add(goods.id().toString());
            }
            payloads.add(payload);
            descriptors.add(new Descriptor(entry.getCategory(), entry.isHidden() || !entry.isStructurallyValid(), entry.getLinkKey(),
                    entry.goodsDisplayName(), goodsIds, payload.estimatedUtf8Bytes(), entry.getStableId()));
            byKey.put(key, entry);
            byEntry.put(entry, key);
            byStableId.put(entry.getStableId(), entry);
        }

        Layout layout = layout(descriptors, DEFAULT_CHUNK_BYTES, DEFAULT_MAX_CHUNK_ENTRIES);
        Map<Long, ShopCatalogEntryPayload> payloadByKey = new LinkedHashMap<>();
        for (ShopCatalogEntryPayload payload : payloads) payloadByKey.put(payload.entryKey(), payload);
        Map<Integer, List<ShopCatalogEntryPayload>> chunks = new LinkedHashMap<>();
        for (int chunkId = 0; chunkId < layout.chunks().size(); chunkId++) {
            List<ShopCatalogEntryPayload> chunk = new ArrayList<>();
            for (Long key : layout.chunks().get(chunkId)) chunk.add(payloadByKey.get(key));
            chunks.put(chunkId, chunk);
        }
        ShopCatalogManifest manifest = new ShopCatalogManifest(revision, true, layout.stubs());
        return new ShopCatalogSnapshot(revision, true, entries, byKey, byEntry, byStableId, layout, manifest, chunks);
    }

    public static Layout layout(List<Descriptor> descriptors, int maxChunkBytes, int maxChunkEntries) {
        List<Descriptor> safe = descriptors == null ? List.of() : List.copyOf(descriptors);
        int byteLimit = Math.max(1, maxChunkBytes);
        int entryLimit = Math.max(1, maxChunkEntries);
        List<List<Long>> chunks = new ArrayList<>();
        Map<Long, Integer> chunkByKey = new LinkedHashMap<>();
        List<Long> current = new ArrayList<>();
        int currentBytes = 0;
        for (int index = 0; index < safe.size(); index++) {
            Descriptor descriptor = safe.get(index);
            if (!current.isEmpty() && (current.size() >= entryLimit
                    || currentBytes + descriptor.payloadBytes() > byteLimit)) {
                chunks.add(current);
                current = new ArrayList<>();
                currentBytes = 0;
            }
            long key = index;
            current.add(key);
            currentBytes += descriptor.payloadBytes();
            chunkByKey.put(key, chunks.size());
        }
        if (!current.isEmpty()) chunks.add(current);

        List<ShopCatalogManifest.Stub> stubs = new ArrayList<>(safe.size());
        Map<String, List<Long>> groups = new LinkedHashMap<>();
        Map<String, Long> links = new LinkedHashMap<>();
        Set<String> tops = new LinkedHashSet<>();
        Map<String, LinkedHashSet<String>> subs = new LinkedHashMap<>();
        for (int index = 0; index < safe.size(); index++) {
            long key = index;
            Descriptor descriptor = safe.get(index);
            String top = categoryTop(descriptor.category());
            String sub = categorySub(descriptor.category());
            stubs.add(new ShopCatalogManifest.Stub(key, top, sub, descriptor.hidden(),
                    chunkByKey.getOrDefault(key, -1), descriptor.linkKey(), descriptor.displayName(), descriptor.goodsIds(),
                    descriptor.stableId()));
            if (!descriptor.linkKey().isEmpty()) links.putIfAbsent(descriptor.linkKey(), key);
            if (descriptor.hidden()) continue;
            tops.add(top);
            subs.computeIfAbsent(top, ignored -> new LinkedHashSet<>());
            if (!sub.isEmpty()) subs.get(top).add(sub);
            addGroupKey(groups, top, "", key);
            if (!sub.isEmpty()) addGroupKey(groups, top, sub, key);
        }

        Map<String, List<String>> subLists = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : subs.entrySet()) {
            subLists.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return new Layout(stubs, groups, chunks, links, new ArrayList<>(tops), subLists);
    }

    public long revision() { return revision; }
    public boolean ready() { return ready; }
    public List<ShopEntry> entries() { return entries; }
    public ShopCatalogManifest manifest() { return manifest; }
    public List<String> topCategories() { return layout.topCategories(); }
    public List<String> subCategories(String top) { return layout.subCategories(top); }

    public ShopEntry resolve(long entryKey) {
        return entriesByKey.get(entryKey);
    }

    public long keyOf(ShopEntry entry) {
        Long key = keysByEntry.get(entry);
        return key == null ? -1L : key;
    }

    public ShopEntry findByLinkKey(String linkKey) {
        if (linkKey == null || linkKey.isBlank()) return null;
        Long key = layout.linkKeyToEntryKey().get(linkKey);
        return key == null ? null : resolve(key);
    }

    /** 按稳定身份 ID 查找条目（跨快照有效，见 {@link ShopEntry#getStableId}）；未找到返回 null。 */
    public ShopEntry resolveByStableId(String stableId) {
        if (stableId == null || stableId.isBlank()) return null;
        return entriesByStableId.get(stableId);
    }

    public List<ShopEntry> entriesOfGroup(String top, String sub) {
        List<Long> keys = layout.groupKeys(top, sub);
        if (keys.isEmpty()) return List.of();
        List<ShopEntry> result = new ArrayList<>(keys.size());
        for (Long key : keys) {
            ShopEntry entry = resolve(key);
            if (entry != null) result.add(entry);
        }
        return List.copyOf(result);
    }

    public List<ShopCatalogEntryPayload> chunk(int chunkId) {
        return payloadChunks.getOrDefault(chunkId, List.of());
    }

    private static void addGroupKey(Map<String, List<Long>> groups, String top, String sub, long key) {
        groups.computeIfAbsent(groupKey(top, sub), ignored -> new ArrayList<>()).add(key);
    }

    private static String groupKey(String top, String sub) {
        return (top == null ? "" : top) + '\u0000' + (sub == null ? "" : sub);
    }

    private static String categoryTop(String category) {
        int split = category == null ? -1 : category.indexOf('/');
        return split < 0 ? (category == null ? ShopEntry.DEFAULT_CATEGORY : category) : category.substring(0, split);
    }

    private static String categorySub(String category) {
        int split = category == null ? -1 : category.indexOf('/');
        return split < 0 ? "" : category.substring(split + 1);
    }

    private static Map<String, List<Long>> immutableListMap(Map<String, List<Long>> source) {
        Map<String, List<Long>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<Long>> entry : source.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, List<String>> immutableStringListMap(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }
}
