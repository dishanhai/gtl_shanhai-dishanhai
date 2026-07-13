package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.common.shop.ShopCatalogEntryPayload;
import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;
import com.dishanhai.gt_shanhai.common.shop.ShopEntryJsonCodec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 当前连接的山海商店客户端目录缓存。网络线程只提交纯 JSON 负载，ItemStack 实体化由客户端主线程预算泵完成。
 */
public final class ClientShopCatalog {

    /** 纯 revision/requestId/chunk 状态机，不依赖 Minecraft 对象。 */
    public static final class State {
        private long revision;
        private boolean ready;
        private long nextRequestId = 1L;
        private final Map<Integer, Long> requests = new LinkedHashMap<>();
        private final Set<Integer> receivedChunks = new LinkedHashSet<>();

        public boolean applyManifest(ShopCatalogManifest manifest) {
            long newRevision = manifest == null ? 0L : manifest.revision();
            boolean newReady = manifest != null && manifest.ready();
            if (revision == newRevision && ready == newReady) return false;
            revision = newRevision;
            ready = newReady;
            requests.clear();
            receivedChunks.clear();
            return true;
        }

        public long beginRequest(int chunkId) {
            if (!ready || chunkId < 0 || requests.containsKey(chunkId) || receivedChunks.contains(chunkId)) {
                return -1L;
            }
            long requestId = nextRequestId++;
            if (requestId <= 0L) {
                nextRequestId = 2L;
                requestId = 1L;
            }
            requests.put(chunkId, requestId);
            return requestId;
        }

        public boolean accept(long packetRevision, long requestId, int chunkId) {
            if (!ready || revision != packetRevision || requestId <= 0L || chunkId < 0) return false;
            Long expected = requests.get(chunkId);
            if (expected == null || expected.longValue() != requestId) return false;
            requests.remove(chunkId);
            receivedChunks.add(chunkId);
            return true;
        }

        public long revision() { return revision; }
        public boolean ready() { return ready; }
        public boolean hasChunk(int chunkId) { return receivedChunks.contains(chunkId); }
    }

    private record PendingChunk(List<ShopCatalogEntryPayload> entries, int index) {
        PendingChunk advance() { return new PendingChunk(entries, index + 1); }
        boolean done() { return index >= entries.size(); }
        ShopCatalogEntryPayload current() { return entries.get(index); }
    }

    private static final State STATE = new State();
    private static ShopCatalogManifest manifest = ShopCatalogManifest.empty();
    private static final Map<Long, ShopCatalogManifest.Stub> stubsByKey = new LinkedHashMap<>();
    private static final Map<String, List<Long>> groupKeys = new LinkedHashMap<>();
    private static final Map<String, Long> linkKeys = new LinkedHashMap<>();
    private static final List<String> topCategories = new ArrayList<>();
    private static final Map<String, List<String>> subCategories = new LinkedHashMap<>();
    private static final Map<Long, ShopEntry> entriesByKey = new LinkedHashMap<>();
    private static final IdentityHashMap<ShopEntry, Long> keysByEntry = new IdentityHashMap<>();
    private static final ArrayDeque<PendingChunk> pendingChunks = new ArrayDeque<>();

    private ClientShopCatalog() {}

    public static void applyManifest(ShopCatalogManifest next) {
        ShopCatalogManifest safe = next == null ? ShopCatalogManifest.empty() : next;
        boolean changed = STATE.applyManifest(safe);
        manifest = safe;
        if (changed) {
            entriesByKey.clear();
            keysByEntry.clear();
            pendingChunks.clear();
        }
        rebuildManifestIndexes();
    }

    public static long revision() { return STATE.revision(); }
    public static boolean ready() { return STATE.ready(); }
    public static List<String> topCategories() { return List.copyOf(topCategories); }

    public static List<String> subCategories(String top) {
        return subCategories.getOrDefault(top == null ? "" : top, List.of());
    }

    public static List<Long> keysOfGroup(String top, String sub) {
        return groupKeys.getOrDefault(groupKey(top, sub), List.of());
    }

    public static List<Long> searchKeys(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return List.of();
        List<Long> result = new ArrayList<>();
        for (ShopCatalogManifest.Stub stub : manifest.stubs()) {
            if (stub.hidden()) continue;
            StringBuilder haystack = new StringBuilder(stub.displayName()).append(' ');
            for (String goodsId : stub.goodsIds()) haystack.append(goodsId).append(' ');
            if (haystack.toString().toLowerCase(Locale.ROOT).contains(normalized)) result.add(stub.entryKey());
        }
        return List.copyOf(result);
    }

    public static ShopEntry get(long entryKey) { return entriesByKey.get(entryKey); }
    public static ShopCatalogManifest.Stub stub(long entryKey) { return stubsByKey.get(entryKey); }

    public static long keyOf(ShopEntry entry) {
        Long key = keysByEntry.get(entry);
        return key == null ? -1L : key;
    }

    public static long linkedEntryKey(String linkKey) {
        if (linkKey == null || linkKey.isBlank()) return -1L;
        return linkKeys.getOrDefault(linkKey, -1L);
    }

    public static long beginChunkRequest(int chunkId) {
        return STATE.beginRequest(chunkId);
    }

    public static boolean acceptChunk(long packetRevision, long requestId, int chunkId,
                                      List<ShopCatalogEntryPayload> payloads) {
        if (!STATE.accept(packetRevision, requestId, chunkId)) return false;
        pendingChunks.addLast(new PendingChunk(
                payloads == null ? List.of() : List.copyOf(payloads), 0));
        return true;
    }

    /** 在客户端主线程按纳秒预算把纯 JSON 负载实体化为 ShopEntry。 */
    public static int pumpMaterialization(long budgetNanos) {
        if (budgetNanos <= 0L || pendingChunks.isEmpty()) return 0;
        long deadline = System.nanoTime() + budgetNanos;
        int built = 0;
        while (!pendingChunks.isEmpty() && System.nanoTime() < deadline) {
            PendingChunk pending = pendingChunks.removeFirst();
            if (pending.done()) continue;
            ShopCatalogEntryPayload payload = pending.current();
            ShopEntry entry = ShopEntryJsonCodec.fromPayload(payload.json());
            if (entry != null && stubsByKey.containsKey(payload.entryKey())) {
                ShopEntry old = entriesByKey.put(payload.entryKey(), entry);
                if (old != null) keysByEntry.remove(old);
                keysByEntry.put(entry, payload.entryKey());
                built++;
            }
            PendingChunk advanced = pending.advance();
            if (!advanced.done()) pendingChunks.addFirst(advanced);
        }
        return built;
    }

    public static void clear() {
        applyManifest(ShopCatalogManifest.empty());
    }

    private static void rebuildManifestIndexes() {
        stubsByKey.clear();
        groupKeys.clear();
        linkKeys.clear();
        topCategories.clear();
        subCategories.clear();
        LinkedHashSet<String> tops = new LinkedHashSet<>();
        Map<String, LinkedHashSet<String>> subs = new LinkedHashMap<>();
        for (ShopCatalogManifest.Stub stub : manifest.stubs()) {
            stubsByKey.put(stub.entryKey(), stub);
            if (!stub.linkKey().isEmpty()) linkKeys.putIfAbsent(stub.linkKey(), stub.entryKey());
            if (stub.hidden()) continue;
            tops.add(stub.top());
            subs.computeIfAbsent(stub.top(), ignored -> new LinkedHashSet<>());
            if (!stub.sub().isEmpty()) subs.get(stub.top()).add(stub.sub());
            addGroupKey(stub.top(), "", stub.entryKey());
            if (!stub.sub().isEmpty()) addGroupKey(stub.top(), stub.sub(), stub.entryKey());
        }
        topCategories.addAll(tops);
        for (Map.Entry<String, LinkedHashSet<String>> entry : subs.entrySet()) {
            subCategories.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        for (Map.Entry<String, List<Long>> entry : new ArrayList<>(groupKeys.entrySet())) {
            groupKeys.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
    }

    private static void addGroupKey(String top, String sub, long key) {
        groupKeys.computeIfAbsent(groupKey(top, sub), ignored -> new ArrayList<>()).add(key);
    }

    private static String groupKey(String top, String sub) {
        return (top == null ? "" : top) + '\u0000' + (sub == null ? "" : sub);
    }
}
