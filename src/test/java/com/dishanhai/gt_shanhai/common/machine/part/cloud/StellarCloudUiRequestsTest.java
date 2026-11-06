package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static com.dishanhai.gt_shanhai.common.machine.part.cloud.StellarCloudUiContract.*;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：UI 請求去重不得重跑搬移動作，過期與改靶封包不得繞過閘門。 */
class StellarCloudUiRequestsTest {
    @Test void repeatedRequestReplaysReceiptAndConflictingOrOldRequestsNeverExecute() {
        var requests = new StellarCloudUiRequests();
        var calls = new AtomicInteger();
        UUID first = new UUID(0, 1), other = new UUID(0, 2);
        var result = requests.execute(1, ActionKind.WITHDRAW, first, () -> {
            calls.incrementAndGet(); return "gt_shanhai.cloud.status.withdrawn";
        });
        assertEquals(ResultKind.SUCCESS, result.kind());
        assertEquals(result, requests.execute(1, ActionKind.WITHDRAW, first, () -> { fail(); return ""; }));
        assertEquals(ResultKind.REJECTED, requests.execute(1, ActionKind.WITHDRAW, other, () -> { fail(); return ""; }).kind());
        assertEquals(ResultKind.WAITING, requests.execute(2, ActionKind.WITHDRAW, other,
                () -> "gt_shanhai.cloud.status.withdraw_queued").kind());
        assertEquals(ResultKind.REJECTED, requests.execute(1, ActionKind.WITHDRAW, first, () -> { fail(); return ""; }).kind());
        assertEquals(ResultKind.REJECTED, requests.execute(0, ActionKind.UPLOAD, null, () -> { fail(); return ""; }).kind());
        assertEquals(1, calls.get());
    }

    @Test void failuresAndRecoveryAreReceiptsRatherThanOptimisticSuccess() {
        var requests = new StellarCloudUiRequests();
        assertEquals(ResultKind.REJECTED, requests.execute(1, ActionKind.UPLOAD, null,
                () -> "gt_shanhai.cloud.status.denied").kind());
        assertEquals(ResultKind.RECOVERY, requests.execute(2, ActionKind.UPLOAD, null,
                () -> "gt_shanhai.cloud.status.recovery").kind());
        assertEquals(ResultKind.SUCCESS, requests.execute(3, ActionKind.SET_ENABLED, null,
                () -> "gui.gt_shanhai.stellar_cloud.settings_applied").kind());
    }
}
