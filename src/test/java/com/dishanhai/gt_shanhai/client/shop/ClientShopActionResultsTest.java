package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.common.shop.ShopActionOutcome;
import com.dishanhai.gt_shanhai.network.ShopActionPacket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientShopActionResultsTest {

    @AfterEach
    void tearDown() {
        ClientShopActionResults.clear();
    }

    private static ClientShopActionResults.Result result(int id, long at) {
        return new ClientShopActionResults.Result(id, ShopActionPacket.Action.BUY, 1L, 1L, 1L,
                ShopActionOutcome.SUCCESS, "", at);
    }

    @Test
    void requestIdsAreNeverZeroAndDoNotRepeatBackToBack() {
        int prev = ClientShopActionResults.nextRequestId();
        assertNotEquals(0, prev);
        for (int i = 0; i < 1_000; i++) {
            int id = ClientShopActionResults.nextRequestId();
            assertTrue(id > 0, "0 留给「不要回执」");
            assertNotEquals(prev, id);
            prev = id;
        }
    }

    @Test
    void drainHandsOverInArrivalOrderAndEmptiesTheInbox() {
        ClientShopActionResults.offer(result(1, 100L));
        ClientShopActionResults.offer(result(2, 110L));
        List<Integer> seen = new ArrayList<>();
        ClientShopActionResults.drain(200L, r -> seen.add(r.requestId()));
        assertEquals(List.of(1, 2), seen);
        seen.clear();
        ClientShopActionResults.drain(300L, r -> seen.add(r.requestId()));
        assertTrue(seen.isEmpty());
    }

    @Test
    void staleResultsAreDroppedAfterTheirTtl() {
        ClientShopActionResults.offer(result(1, 0L));
        ClientShopActionResults.offer(result(2, ClientShopActionResults.TTL_MS));
        List<Integer> seen = new ArrayList<>();
        ClientShopActionResults.drain(ClientShopActionResults.TTL_MS + 1L, r -> seen.add(r.requestId()));
        assertEquals(List.of(2), seen);
    }

    @Test
    void capacityKeepsTheNewestResults() {
        int total = ClientShopActionResults.CAPACITY + 5;
        for (int i = 1; i <= total; i++) ClientShopActionResults.offer(result(i, 0L));
        List<Integer> seen = new ArrayList<>();
        ClientShopActionResults.drain(0L, r -> seen.add(r.requestId()));
        assertEquals(ClientShopActionResults.CAPACITY, seen.size());
        assertEquals(6, seen.get(0));
        assertEquals(total, seen.get(seen.size() - 1));
    }

    @Test
    void clearDropsPendingResults() {
        ClientShopActionResults.offer(result(1, 0L));
        ClientShopActionResults.clear();
        List<Integer> seen = new ArrayList<>();
        ClientShopActionResults.drain(0L, r -> seen.add(r.requestId()));
        assertTrue(seen.isEmpty());
    }

    @Test
    void autoCraftResultsAreIndependentAndSurviveLongConfirmationViewing() {
        ClientShopActionResults.offer(result(20, 100L));
        ClientShopActionResults.offerAutoCraft(new ClientShopActionResults.AutoCraftResult(0, 1, 1, 100L));
        ClientShopActionResults.offerAutoCraft(new ClientShopActionResults.AutoCraftResult(21, 0, 1, 0L));
        ClientShopActionResults.offerAutoCraft(new ClientShopActionResults.AutoCraftResult(22, 1, 2, 100L));
        List<Integer> seen = new ArrayList<>();
        ClientShopActionResults.drainAutoCraft(1_000_000L, r -> seen.add(r.requestId()));
        assertEquals(List.of(21, 22), seen, "方案回執保留到返回商店，不能在閱讀確認窗期間過期");
        seen.clear();
        ClientShopActionResults.drain(200L, r -> seen.add(r.requestId()));
        assertEquals(List.of(20), seen);
    }

    @Test
    void autoCraftInboxIsBoundedAndClearedOnDisconnect() {
        for (int i = 1; i <= ClientShopActionResults.CAPACITY + 2; i++) {
            ClientShopActionResults.offerAutoCraft(new ClientShopActionResults.AutoCraftResult(i, 1, 1, 0L));
        }
        List<Integer> seen = new ArrayList<>();
        ClientShopActionResults.drainAutoCraft(1L, r -> seen.add(r.requestId()));
        assertEquals(ClientShopActionResults.CAPACITY, seen.size());
        assertEquals(3, seen.get(0));
        ClientShopActionResults.offerAutoCraft(new ClientShopActionResults.AutoCraftResult(100, 1, 1, 0L));
        ClientShopActionResults.clear();
        seen.clear();
        ClientShopActionResults.drainAutoCraft(1L, r -> seen.add(r.requestId()));
        assertTrue(seen.isEmpty());
    }
}
