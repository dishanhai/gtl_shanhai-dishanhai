package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.shop.ClientWalletAccount;
import com.dishanhai.sh_better_ui.client.gui.scaled.Spring;
import net.minecraft.resources.ResourceLocation;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** 山海餘額飄字：分筆錯峰，三個固定槽位，僅保存展示狀態。 */
final class ShopBalanceMotion {
    private static final long LIFETIME_MS = 1400L;
    private static final long SPACING_MS = 500L;
    private static final int MAX_PENDING = 32;
    private record Flight(ClientWalletAccount.BalanceChange change, long startMs, int lane) {}
    record View(ResourceLocation currency, BigInteger amount, float opacity, float rise, int lane) {
        String text() { return (amount.signum() > 0 ? "+" : "") + amount; }
    }
    private final ArrayDeque<Flight> flights = new ArrayDeque<>();
    private final Spring[] lanePositions = {
            new Spring(Spring.SCROLL), new Spring(Spring.SCROLL, 26f), new Spring(Spring.SCROLL, 52f)
    };

    void addAll(List<ClientWalletAccount.BalanceChange> changes, long nowMs) {
        prune(nowMs);
        for (var change : changes) {
            if (change.amount().signum() == 0) continue;
            // 滿員時替換最遠的等待項；已可見的動畫不會被突然抹掉。
            if (flights.size() >= MAX_PENDING) flights.removeLast();
            Flight previous = flights.peekLast();
            long start = previous == null ? nowMs : Math.max(nowMs, previous.startMs() + SPACING_MS);
            int lane = previous == null ? 0 : (previous.lane() + 1) % 3;
            flights.addLast(new Flight(change, start, lane));
        }
    }

    List<View> visible(long nowMs, boolean reduceMotion) {
        prune(nowMs);
        List<View> result = new ArrayList<>(3);
        for (Flight flight : flights) {
            long age = nowMs - flight.startMs();
            if (age < 0L) break;
            float alpha = reduceMotion ? 1f : Math.min(ShopNoticeMotion.ease(age / 140f),
                    1f - ShopNoticeMotion.ease((age - 1000L) / 400f));
            float rise = reduceMotion ? 0f : 12f * ShopNoticeMotion.ease(age / (float) LIFETIME_MS);
            result.add(new View(flight.change().currency(), flight.change().amount(), alpha, rise, flight.lane()));
        }
        return result;
    }

    private void prune(long nowMs) {
        while (!flights.isEmpty() && nowMs - flights.peekFirst().startMs() >= LIFETIME_MS) flights.removeFirst();
    }

    /** 換行高度改變時保留當前位置與速度，不把仍可見的飄字瞬移到新槽位。 */
    float laneOffset(int lane, int rowHeight, long nowMs, boolean reduceMotion) {
        Spring position = lanePositions[lane];
        float target = lane * (rowHeight + 4f);
        if (reduceMotion) position.snap(target);
        else {
            position.update(nowMs);
            position.setTarget(target, nowMs);
        }
        return position.value();
    }

    int pendingCount() { return flights.size(); }
    void clear() {
        flights.clear();
        for (int lane = 0; lane < lanePositions.length; lane++) lanePositions[lane].snap(lane * 26f);
    }
}
