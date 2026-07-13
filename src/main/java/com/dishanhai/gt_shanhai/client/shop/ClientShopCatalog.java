package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.common.shop.ShopCatalogEntryPayload;
import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;
import com.dishanhai.gt_shanhai.common.shop.ShopEntryJsonCodec;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopCatalogChunkRequestPacket;

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
        private final Map<Long, Long> remainingUsesByEntry = new LinkedHashMap<>();

        public boolean applyManifest(ShopCatalogManifest manifest) {
            long newRevision = manifest == null ? 0L : manifest.revision();
            boolean newReady = manifest != null && manifest.ready();
            if (revision == newRevision && ready == newReady) return false;
            boolean revisionChanged = revision != newRevision;
            revision = newRevision;
            ready = newReady;
            requests.clear();
            receivedChunks.clear();
            if (revisionChanged) remainingUsesByEntry.clear();
            return true;
        }

        public boolean applyRemainingUses(long packetRevision, long entryKey, long remainingUses) {
            if (!ready || revision != packetRevision || entryKey < 0L || remainingUses < 0L) return false;
            Long current = remainingUsesByEntry.get(entryKey);
            if (current != null && remainingUses >= current.longValue()) return false;
            remainingUsesByEntry.put(entryKey, remainingUses);
            return true;
        }

        public long remainingUses(long entryKey) {
            Long remaining = remainingUsesByEntry.get(entryKey);
            return remaining == null ? -1L : remaining.longValue();
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

        public void forgetChunk(int chunkId) {
            receivedChunks.remove(chunkId);
            requests.remove(chunkId);
        }
    }

    private record PendingChunk(int chunkId, List<ShopCatalogEntryPayload> entries, int index) {
        PendingChunk advance() { return new PendingChunk(chunkId, entries, index + 1); }
        boolean done() { return index >= entries.size(); }
        ShopCatalogEntryPayload current() { return entries.get(index); }
    }

    private static final int MAX_CACHED_CHUNKS = 8;
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
    private static final LinkedHashMap<Integer, Set<Long>> cachedChunkKeys =
            new LinkedHashMap<>(16, 0.75F, true);
    private static Set<Integer> pinnedChunks = Set.of();

    private ClientShopCatalog() {}

    public static boolean applyManifest(ShopCatalogManifest next) {
        ShopCatalogManifest safe = next == null ? ShopCatalogManifest.empty() : next;
        boolean changed = STATE.applyManifest(safe);
        manifest = safe;
        if (changed) {
            entriesByKey.clear();
            keysByEntry.clear();
            pendingChunks.clear();
            cachedChunkKeys.clear();
            pinnedChunks = Set.of();
        }
        rebuildManifestIndexes();
        return changed;
    }

    public static boolean applyRemainingUses(long revision, long entryKey, long remainingUses) {
        if (!STATE.applyRemainingUses(revision, entryKey, remainingUses)) return false;
        ShopEntry entry = entriesByKey.get(entryKey);
        applyRemainingUses(entry, STATE.remainingUses(entryKey));
        return true;
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

    /** 请求可视范围涉及但尚未收到的 chunk，并把这些块固定在 LRU 中。 */
    public static void ensureLoadedRange(List<Long> keys, int fromInclusive, int toExclusive) {
        if (keys == null || keys.isEmpty() || !STATE.ready()) {
            pinnedChunks = Set.of();
            return;
        }
        int from = Math.max(0, Math.min(fromInclusive, keys.size()));
        int to = Math.max(from, Math.min(toExclusive, keys.size()));
        LinkedHashSet<Integer> needed = new LinkedHashSet<>();
        for (int i = from; i < to; i++) {
            ShopCatalogManifest.Stub stub = stubsByKey.get(keys.get(i));
            if (stub != null && stub.chunkId() >= 0) needed.add(stub.chunkId());
        }
        pinnedChunks = Set.copyOf(needed);
        for (Integer chunkId : needed) {
            if (cachedChunkKeys.containsKey(chunkId)) cachedChunkKeys.get(chunkId); // access-order touch
            long requestId = STATE.beginRequest(chunkId);
            if (requestId > 0L) {
                ShanhaiNetwork.CHANNEL.sendToServer(
                        new ShopCatalogChunkRequestPacket(STATE.revision(), requestId, chunkId));
            }
        }
        evictOverflow();
    }

    public static boolean acceptChunk(long packetRevision, long requestId, int chunkId,
                                      List<ShopCatalogEntryPayload> payloads) {
        if (!STATE.accept(packetRevision, requestId, chunkId)) return false;
        pendingChunks.addLast(new PendingChunk(chunkId,
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
            if (pending.done()) {
                finishChunk(pending);
                continue;
            }
            ShopCatalogEntryPayload payload = pending.current();
            ShopEntry entry = ShopEntryJsonCodec.fromPayload(payload.json());
            if (entry != null && stubsByKey.containsKey(payload.entryKey())) {
                long target = STATE.remainingUses(payload.entryKey());
                applyRemainingUses(entry, target);
                ShopEntry old = entriesByKey.put(payload.entryKey(), entry);
                if (old != null) keysByEntry.remove(old);
                keysByEntry.put(entry, payload.entryKey());
                built++;
            }
            PendingChunk advanced = pending.advance();
            if (advanced.done()) finishChunk(advanced);
            else pendingChunks.addFirst(advanced);
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

    private static void applyRemainingUses(ShopEntry entry, long target) {
        if (entry == null || target < 0L) return;
        long current = entry.getRemainingUses();
        if (current >= 0L && target < current) entry.consumeUses(current - target);
    }

    private static String groupKey(String top, String sub) {
        return (top == null ? "" : top) + '\u0000' + (sub == null ? "" : sub);
    }

    private static void finishChunk(PendingChunk pending) {
        LinkedHashSet<Long> keys = new LinkedHashSet<>();
        for (ShopCatalogEntryPayload payload : pending.entries()) keys.add(payload.entryKey());
        cachedChunkKeys.put(pending.chunkId(), Set.copyOf(keys));
        evictOverflow();
    }

    private static void evictOverflow() {
        if (cachedChunkKeys.size() <= MAX_CACHED_CHUNKS) return;
        var iterator = cachedChunkKeys.entrySet().iterator();
        while (cachedChunkKeys.size() > MAX_CACHED_CHUNKS && iterator.hasNext()) {
            Map.Entry<Integer, Set<Long>> eldest = iterator.next();
            if (pinnedChunks.contains(eldest.getKey())) continue;
            for (Long key : eldest.getValue()) {
                ShopEntry removed = entriesByKey.remove(key);
                if (removed != null) keysByEntry.remove(removed);
            }
            STATE.forgetChunk(eldest.getKey());
            iterator.remove();
        }
    }
}
