package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.AdvancedSearchUtil;
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
        /** 在途 chunk 请求超过这个时长仍无回包（如被服务端限流静默丢弃）就允许换新 requestId 重发，
         *  否则该 chunk 会永久滞留在 requests 里、格子空白到下一次 revision 变更才自愈。 */
        private static final long PENDING_RETRY_MS = 5_000L;

        private long revision;
        private boolean ready;
        private long nextRequestId = 1L;
        private final Map<Integer, Long> requests = new LinkedHashMap<>();
        private final Map<Integer, Long> requestedAtMs = new LinkedHashMap<>();
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
            requestedAtMs.clear();
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

        /** 无时钟版本（测试/兼容）：在途请求永不判超时，保持原「同 chunk 只发一次」语义。 */
        public long beginRequest(int chunkId) {
            return beginRequest(chunkId, Long.MIN_VALUE);
        }

        /** @param nowMillis 调用方注入的单调毫秒时钟（Long.MIN_VALUE = 无时钟，不做超时重发） */
        public long beginRequest(int chunkId, long nowMillis) {
            if (!ready || chunkId < 0 || receivedChunks.contains(chunkId)) {
                return -1L;
            }
            if (requests.containsKey(chunkId)) {
                Long at = requestedAtMs.get(chunkId);
                boolean timedOut = nowMillis != Long.MIN_VALUE && at != null
                        && at.longValue() != Long.MIN_VALUE && nowMillis - at.longValue() >= PENDING_RETRY_MS;
                if (!timedOut) return -1L;
                // 超时重发：换新 requestId 覆盖，迟到的旧回包会因 requestId 不匹配被 accept() 拒收
            }
            long requestId = nextRequestId++;
            if (requestId <= 0L) {
                nextRequestId = 2L;
                requestId = 1L;
            }
            requests.put(chunkId, requestId);
            requestedAtMs.put(chunkId, nowMillis);
            return requestId;
        }

        public boolean accept(long packetRevision, long requestId, int chunkId) {
            if (!ready || revision != packetRevision || requestId <= 0L || chunkId < 0) return false;
            Long expected = requests.get(chunkId);
            if (expected == null || expected.longValue() != requestId) return false;
            requests.remove(chunkId);
            requestedAtMs.remove(chunkId);
            receivedChunks.add(chunkId);
            return true;
        }

        public long revision() { return revision; }
        public boolean ready() { return ready; }
        public boolean hasChunk(int chunkId) { return receivedChunks.contains(chunkId); }

        public void forgetChunk(int chunkId) {
            receivedChunks.remove(chunkId);
            requests.remove(chunkId);
            requestedAtMs.remove(chunkId);
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
    private static final Map<String, Long> stableIdToKey = new LinkedHashMap<>();
    // 前置任务反向索引：FTBQ 任务 ID（十六进制）→ 以它为前置的商品 entryKey 列表（保持 manifest 顺序）。
    // 供任务书那侧的「前往商店」按钮反查（见 FtbViewQuestPanelShopButtonMixin）；隐藏条目不入索引。
    private static final Map<String, List<Long>> prereqQuestKeys = new LinkedHashMap<>();
    private static final List<String> topCategories = new ArrayList<>();
    private static final Map<String, List<String>> subCategories = new LinkedHashMap<>();   // key=top          -> 二级选项
    private static final Map<String, List<String>> subCategories2 = new LinkedHashMap<>();  // key=top\0sub      -> 三级选项
    private static final Map<String, List<String>> subCategories3 = new LinkedHashMap<>();  // key=top\0sub\0sub2-> 四级选项
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
            // entryKey 是快照内位置下标，revision 一变就可能换主：旧 revision 的花费预览槽位
            // 不能留给新商品顶用（服务端对过期 revision 的预览请求已静默丢弃，这里清掉即闭环）
            ClientCostPreview.clear();
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

    public static List<String> subCategories2(String top, String sub) {
        return subCategories2.getOrDefault(pathKey(top, sub), List.of());
    }

    public static List<String> subCategories3(String top, String sub, String sub2) {
        return subCategories3.getOrDefault(pathKey(top, sub, sub2), List.of());
    }

    public static List<Long> keysOfGroup(String top, String sub, String sub2, String sub3) {
        return groupKeys.getOrDefault(groupKey(top, sub, sub2, sub3), List.of());
    }

    public static List<Long> searchKeys(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return List.of();
        List<Long> result = new ArrayList<>();
        for (ShopCatalogManifest.Stub stub : manifest.stubs()) {
            if (stub.hidden()) continue;
            StringBuilder searchText = new StringBuilder(stub.displayName()).append(' ');
            for (String goodsId : stub.goodsIds()) searchText.append(goodsId).append(' ');
            if (AdvancedSearchUtil.match(searchText.toString(), normalized)) result.add(stub.entryKey());
        }
        return List.copyOf(result);
    }

    public static ShopEntry get(long entryKey) { return entriesByKey.get(entryKey); }
    public static ShopCatalogManifest.Stub stub(long entryKey) { return stubsByKey.get(entryKey); }
    public static List<ShopCatalogManifest.Stub> stubs() { return manifest.stubs(); }

    public static long keyOf(ShopEntry entry) {
        Long key = keysByEntry.get(entry);
        return key == null ? -1L : key;
    }

    public static long linkedEntryKey(String linkKey) {
        if (linkKey == null || linkKey.isBlank()) return -1L;
        return linkKeys.getOrDefault(linkKey, -1L);
    }

    /**
     * 反查以该 FTBQ 任务为前置的商品 entryKey 列表（按 manifest 顺序，无命中返回空表）。
     * 只依赖全量 stub，不要求对应 chunk 已加载，所以玩家没逛过的分类也查得到。
     */
    public static List<Long> keysOfPrerequisiteQuest(String questHexId) {
        if (questHexId == null || questHexId.isBlank()) return List.of();
        return prereqQuestKeys.getOrDefault(questHexId.trim(), List.of());
    }

    /** 按稳定身份 ID 查找该条目在当前快照里的 entryKey（跨快照有效，未找到返回 -1；供购物车解析用）。 */
    public static long keyOfStableId(String stableId) {
        if (stableId == null || stableId.isBlank()) return -1L;
        return stableIdToKey.getOrDefault(stableId, -1L);
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
            long requestId = STATE.beginRequest(chunkId, net.minecraft.Util.getMillis());
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
        stableIdToKey.clear();
        prereqQuestKeys.clear();
        topCategories.clear();
        subCategories.clear();
        subCategories2.clear();
        subCategories3.clear();
        LinkedHashSet<String> tops = new LinkedHashSet<>();
        Map<String, LinkedHashSet<String>> subs = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> subs2 = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> subs3 = new LinkedHashMap<>();
        for (ShopCatalogManifest.Stub stub : manifest.stubs()) {
            stubsByKey.put(stub.entryKey(), stub);
            if (!stub.linkKey().isEmpty()) linkKeys.putIfAbsent(stub.linkKey(), stub.entryKey());
            if (!stub.stableId().isEmpty()) stableIdToKey.put(stub.stableId(), stub.entryKey());
            if (stub.hidden()) continue;
            if (!stub.prereqQuestId().isEmpty()) {
                prereqQuestKeys.computeIfAbsent(stub.prereqQuestId(), ignored -> new ArrayList<>())
                        .add(stub.entryKey());
            }
            String top = stub.top(), sub = stub.sub(), sub2 = stub.sub2(), sub3 = stub.sub3();
            tops.add(top);
            subs.computeIfAbsent(top, ignored -> new LinkedHashSet<>());
            if (!sub.isEmpty()) subs.get(top).add(sub);
            subs2.computeIfAbsent(pathKey(top, sub), ignored -> new LinkedHashSet<>());
            if (!sub.isEmpty() && !sub2.isEmpty()) subs2.get(pathKey(top, sub)).add(sub2);
            subs3.computeIfAbsent(pathKey(top, sub, sub2), ignored -> new LinkedHashSet<>());
            if (!sub.isEmpty() && !sub2.isEmpty() && !sub3.isEmpty()) subs3.get(pathKey(top, sub, sub2)).add(sub3);
            addGroupKey(top, "", "", "", stub.entryKey());
            if (!sub.isEmpty()) addGroupKey(top, sub, "", "", stub.entryKey());
            if (!sub.isEmpty() && !sub2.isEmpty()) addGroupKey(top, sub, sub2, "", stub.entryKey());
            if (!sub.isEmpty() && !sub2.isEmpty() && !sub3.isEmpty()) addGroupKey(top, sub, sub2, sub3, stub.entryKey());
        }
        // 应用服务端下发的显式排序（拖拽页签后落地，见 ShopConfig#moveCategoryTo）；order-key 跟
        // pathKey/顶级空串完全对齐，未出现在排序表里的分类按发现顺序追加在末尾，见 applyOrder。
        topCategories.addAll(applyOrder("", new ArrayList<>(tops)));
        for (Map.Entry<String, LinkedHashSet<String>> entry : subs.entrySet()) {
            subCategories.put(entry.getKey(), applyOrder(entry.getKey(), new ArrayList<>(entry.getValue())));
        }
        for (Map.Entry<String, LinkedHashSet<String>> entry : subs2.entrySet()) {
            subCategories2.put(entry.getKey(), applyOrder(entry.getKey(), new ArrayList<>(entry.getValue())));
        }
        for (Map.Entry<String, LinkedHashSet<String>> entry : subs3.entrySet()) {
            subCategories3.put(entry.getKey(), applyOrder(entry.getKey(), new ArrayList<>(entry.getValue())));
        }
        for (Map.Entry<String, List<Long>> entry : new ArrayList<>(groupKeys.entrySet())) {
            groupKeys.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        for (Map.Entry<String, List<Long>> entry : new ArrayList<>(prereqQuestKeys.entrySet())) {
            prereqQuestKeys.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
    }

    private static void addGroupKey(String top, String sub, String sub2, String sub3, long key) {
        groupKeys.computeIfAbsent(groupKey(top, sub, sub2, sub3), ignored -> new ArrayList<>()).add(key);
    }

    private static void applyRemainingUses(ShopEntry entry, long target) {
        if (entry == null || target < 0L) return;
        long current = entry.getRemainingUses();
        if (current >= 0L && target < current) entry.consumeUses(current - target);
    }

    private static String groupKey(String top, String sub, String sub2, String sub3) {
        return (top == null ? "" : top) + '\u0000' + (sub == null ? "" : sub)
                + '\u0000' + (sub2 == null ? "" : sub2) + '\u0000' + (sub3 == null ? "" : sub3);
    }

    // pathKey 用 "/" 拼接（不是 NUL）：必须跟 ShopConfig#discoveredCategoriesAt 对 parentPath 的
    // "/" 切分格式完全一致，manifest.categoryOrder() 下发的 key 才能在这里直接命中，见 applyOrder。
    private static String pathKey(String top, String sub) {
        return (top == null ? "" : top) + '/' + (sub == null ? "" : sub);
    }

    private static String pathKey(String top, String sub, String sub2) {
        return pathKey(top, sub) + '/' + (sub2 == null ? "" : sub2);
    }

    /** 按 manifest 下发的显式排序重排 discovered：排序表里没有的分类按原发现顺序追加在末尾。 */
    private static List<String> applyOrder(String orderKey, List<String> discovered) {
        List<String> order = manifest.categoryOrder().getOrDefault(orderKey, List.of());
        if (order.isEmpty()) return List.copyOf(discovered);
        List<String> result = new ArrayList<>(discovered.size());
        for (String c : order) if (discovered.contains(c)) result.add(c);
        for (String c : discovered) if (!result.contains(c)) result.add(c);
        return List.copyOf(result);
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
