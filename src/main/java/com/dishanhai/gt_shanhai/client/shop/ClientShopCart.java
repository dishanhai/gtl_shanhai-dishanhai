package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.common.shop.ShopEntry;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopCartEditPacket;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端购物车快照缓存（山海署名，仅客户端）。
 *
 * <p>购物车按稳定身份 ID（见 {@code ShopEntry#getStableId}）索引，跨快照/跨重登有效——
 * 不能用 entryKey（每次 {@code ShopConfig#publish} 都会重排）。服务端 {@code ShopCartSyncPacket}
 * 推送后写入这里，供 {@code ShopScreen} 购物车面板读取。</p>
 *
 * <p>{@link #add}/{@link #remove}/{@link #setAmount} 三个操作各自：先本地乐观改缓存（界面立刻有反馈），
 * 再发编辑包给服务端；服务端处理完总会回推一份权威快照整体覆盖校正（见 {@code ShopCartEditPacket#apply}），
 * 不需要调用方自己对齐。</p>
 */
public final class ClientShopCart {

    /** 单项结算结果（见 {@link #applyPurchaseResult}）：done>=requested=成功，0&lt;done&lt;requested=部分，done<=0=失败。 */
    public static final class Result {
        public final long requested;
        public final long done;
        public final String reason;
        public final long atMs;

        Result(long requested, long done, String reason, long atMs) {
            this.requested = requested;
            this.done = done;
            this.reason = reason;
            this.atMs = atMs;
        }

        public boolean ok() {
            return done >= requested && requested > 0L;
        }

        public boolean partial() {
            return done > 0L && done < requested;
        }
    }

    private static Map<String, Long> items = new LinkedHashMap<>();
    private static boolean synced = false;
    /** stableId → 最近一次结算结果，只用于购物车面板短暂展示，不参与持久化/服务端权威状态。 */
    private static final Map<String, Result> results = new LinkedHashMap<>();

    private ClientShopCart() {}

    /** 应用服务端全量快照（权威覆盖）。 */
    public static void apply(Map<String, Long> newItems) {
        items = newItems != null ? new LinkedHashMap<>(newItems) : new LinkedHashMap<>();
        synced = true;
    }

    public static boolean isSynced() {
        return synced;
    }

    public static boolean contains(String stableId) {
        return stableId != null && items.containsKey(stableId);
    }

    public static long getAmount(String stableId) {
        if (stableId == null) return 0L;
        Long v = items.get(stableId);
        return v == null ? 0L : v;
    }

    /** 购物车全部候选（副本，保序=加入顺序）。 */
    public static Map<String, Long> getAll() {
        return new LinkedHashMap<>(items);
    }

    public static int size() {
        return items.size();
    }

    /** 加入购物车（已存在则忽略，不覆盖数量）；本地乐观更新 + 发编辑包。 */
    public static void add(String stableId, long amount) {
        if (stableId == null || stableId.isBlank() || amount <= 0L) return;
        items.putIfAbsent(stableId, amount);
        ShanhaiNetwork.CHANNEL.sendToServer(new ShopCartEditPacket(ShopCartEditPacket.Action.ADD, stableId, amount));
    }

    /** 覆盖写候选购买次数（面板里的数量调整用）；本地乐观更新 + 发编辑包。 */
    public static void setAmount(String stableId, long amount) {
        if (stableId == null || stableId.isBlank()) return;
        if (amount <= 0L) items.remove(stableId);
        else items.put(stableId, amount);
        ShanhaiNetwork.CHANNEL.sendToServer(
                new ShopCartEditPacket(ShopCartEditPacket.Action.SET_AMOUNT, stableId, amount));
    }

    /** 从购物车移除（面板里点删除 / 结算成功后清理）；本地乐观更新 + 发编辑包。 */
    public static void remove(String stableId) {
        if (stableId == null) return;
        items.remove(stableId);
        results.remove(stableId);
        ShanhaiNetwork.CHANNEL.sendToServer(new ShopCartEditPacket(ShopCartEditPacket.Action.REMOVE, stableId, 0L));
    }

    /**
     * 服务端结算结果回执落地（见 {@code ShopCartPurchaseResultPacket}）：按 entryKey 反查该商品的 stableId
     * 更新购物车数量并记录结果——{@code done>=requested} 全部成交→从购物车移除；{@code 0<done<requested} 部分
     * 成交→数量改为剩余未购次数；{@code done<=0} 完全失败→数量原样保留，只更新失败原因供原地重试（见反馈：
     * 结算后要能看清哪个买成了哪个失败了，不能全部无条件清空购物车）。
     */
    public static void applyPurchaseResult(long entryKey, long requested, long done, String reason) {
        ShopEntry entry = ClientShopCatalog.get(entryKey);
        String stableId = entry != null ? entry.getStableId() : null;
        if (stableId == null) return;
        results.put(stableId, new Result(requested, done, reason, System.currentTimeMillis()));
        if (done >= requested && requested > 0L) {
            items.remove(stableId);
            ShanhaiNetwork.CHANNEL.sendToServer(new ShopCartEditPacket(ShopCartEditPacket.Action.REMOVE, stableId, 0L));
        } else if (done > 0L) {
            long remain = requested - done;
            items.put(stableId, remain);
            ShanhaiNetwork.CHANNEL.sendToServer(new ShopCartEditPacket(ShopCartEditPacket.Action.SET_AMOUNT, stableId, remain));
        }
    }

    /** 该 stableId 最近一次结算结果；未结算过/已被清理返回 null。 */
    public static Result getResult(String stableId) {
        return stableId == null ? null : results.get(stableId);
    }
}
