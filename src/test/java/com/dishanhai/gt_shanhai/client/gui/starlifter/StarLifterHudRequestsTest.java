package com.dishanhai.gt_shanhai.client.gui.starlifter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StarLifterHudRequestsTest {
    private final StarLifterHudCache.PendingRequests requests = new StarLifterHudCache.PendingRequests();

    @Test
    void expiresEachReplyAgainstItsOwnSendTime() {
        requests.sent(1, 100);
        requests.sent(2, 350);
        requests.sent(3, 600);
        assertNull(requests.take(1, 801));
        assertEquals(350L, requests.take(2, 801));
        assertEquals(600L, requests.take(3, 1300));
    }

    @Test
    void removesOlderRepliesAndInvalidatesPendingRequestsOnTargetChange() {
        requests.sent(1, 100);
        requests.sent(2, 350);
        assertEquals(350L, requests.take(2, 400));
        assertNull(requests.take(1, 401));
        assertNull(requests.take(2, 402));
        requests.sent(3, 600);
        requests.clear();
        requests.sent(4, 710);
        assertNull(requests.take(3, 720));
        assertEquals(710L, requests.take(4, 730));
    }
}
