package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.common.shop.ShopActionOutcome;
import com.dishanhai.gt_shanhai.network.ShopActionPacket;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 详情页买卖回执的收件匣：封包处理器只往这里放，由商店界面每帧取走、对上自己还在等的请求号。
 *
 * <p>不由封包直接调界面：回执到达时商店可能已关闭，或被子界面（自选奖励、货币中心）盖住；
 * 同一个商店界面回来后再消化。界面重建后新实例没有等待中的请求号，旧回执自然对不上被丢掉。
 * 太久没被取走的按 {@link #TTL_MS} 丢弃，容量满了丢最旧的。</p>
 */
public final class ClientShopActionResults {

    public record Result(int requestId, ShopActionPacket.Action action, long entryKey, long requested, long done,
                         ShopActionOutcome outcome, String reason, long receivedAtMs) {}

    /** 山海：合成方案結果與買賣分開收取，避免互相消耗回執。 */
    public record AutoCraftResult(int requestId, int planned, int total, long receivedAtMs) {}

    public static final int CAPACITY = 64;
    public static final long TTL_MS = 10_000L;

    private static final ArrayDeque<Result> INBOX = new ArrayDeque<>();
    private static final ArrayDeque<AutoCraftResult> AUTO_CRAFT_INBOX = new ArrayDeque<>();
    private static int lastRequestId;

    private ClientShopActionResults() {}

    /** 下一个请求号，1..Integer.MAX 循环；0 留给「不要回执」。 */
    public static synchronized int nextRequestId() {
        lastRequestId = lastRequestId >= Integer.MAX_VALUE || lastRequestId < 0 ? 1 : lastRequestId + 1;
        return lastRequestId;
    }

    public static synchronized void offer(Result result) {
        if (result == null) return;
        if (INBOX.size() >= CAPACITY) INBOX.pollFirst();
        INBOX.addLast(result);
    }

    /** 按到达顺序交给 {@code sink} 并清空；超过 {@link #TTL_MS} 的直接丢掉。回调在锁外执行。 */
    public static void drain(long nowMs, Consumer<Result> sink) {
        List<Result> fresh;
        synchronized (ClientShopActionResults.class) {
            if (INBOX.isEmpty()) return;
            fresh = new ArrayList<>(INBOX.size());
            for (Result r : INBOX) {
                if (nowMs - r.receivedAtMs() <= TTL_MS) fresh.add(r);
            }
            INBOX.clear();
        }
        for (Result r : fresh) sink.accept(r);
    }

    public static synchronized void clear() {
        INBOX.clear();
        AUTO_CRAFT_INBOX.clear();
    }

    public static synchronized void offerAutoCraft(AutoCraftResult result) {
        if (result == null || result.requestId() == 0) return;
        if (AUTO_CRAFT_INBOX.size() >= CAPACITY) AUTO_CRAFT_INBOX.pollFirst();
        AUTO_CRAFT_INBOX.addLast(result);
    }

    public static void drainAutoCraft(long nowMs, Consumer<AutoCraftResult> sink) {
        List<AutoCraftResult> fresh;
        synchronized (ClientShopActionResults.class) {
            if (AUTO_CRAFT_INBOX.isEmpty()) return;
            // 方案計算與閱讀用料可能很久；保留到原商店消化，斷線 clear、容量上限與 requestId 防止殘留誤用。
            fresh = new ArrayList<>(AUTO_CRAFT_INBOX);
            AUTO_CRAFT_INBOX.clear();
        }
        for (AutoCraftResult result : fresh) sink.accept(result);
    }
}
