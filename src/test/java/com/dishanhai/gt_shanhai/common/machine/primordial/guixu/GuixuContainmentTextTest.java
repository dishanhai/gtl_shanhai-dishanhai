package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuContainmentField.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GuixuContainmentTextTest {

    private static GuixuContainmentField field(Status status, float stability) {
        GuixuContainmentField field = new GuixuContainmentField();
        field.restore(status.ordinal(), stability, 1L, 0);
        return field;
    }

    @Test
    void hostSummaryCoversEveryState() {
        assertEquals("遏制場：未安裝", GuixuContainmentText.hostSummary(null, 0));
        assertEquals("遏制場：坍縮鎖定｜剩餘 3 秒（主機與模組停機）",
                GuixuContainmentText.hostSummary(field(Status.CLOSED, 100.0F), 3));
        assertEquals("遏制場：關閉（投入黑洞種子開啟）",
                GuixuContainmentText.hostSummary(field(Status.CLOSED, 100.0F), 0));
        assertEquals("遏制場：遏制中｜穩定度 100｜並行 ×4 耗時 ×0.5 增產 +5 耗電 ×0.5 數量 ×2",
                GuixuContainmentText.hostSummary(field(Status.CONTAINED, 100.0F), 0));
        assertEquals("遏制場：遏制中｜穩定度 10｜並行 ×16 耗時 ×0.125 增產 +5 耗電 ×0.5 數量 ×2",
                GuixuContainmentText.hostSummary(field(Status.CONTAINED, 10.0F), 0));
        assertEquals("遏制場：失穩｜穩定度 -450｜產出吞噬機率 50.0%",
                GuixuContainmentText.hostSummary(field(Status.UNSTABLE, -450.0F), 0));
    }

    @Test
    void moduleLinesShowConsumptionAndCountdown() {
        List<String> contained = GuixuContainmentText.moduleLines(field(Status.CONTAINED, 100.0F), 0);
        assertTrue(contained.contains("狀態：遏制中"));
        assertTrue(contained.contains("穩定度：100 / 100"));
        assertTrue(contained.contains("時空流體：每秒 10,000 mB（消耗倍率 ×1）"));
        assertTrue(contained.contains("催化計數：0 / 30"));
        assertTrue(contained.contains("投入「黑洞坍縮器」可安全關閉（不鎖定）"));

        List<String> unstable = GuixuContainmentText.moduleLines(field(Status.UNSTABLE, -450.0F), 0);
        assertTrue(unstable.contains("產出吞噬機率：50.0%｜距坍縮 450 秒"));

        List<String> locked = GuixuContainmentText.moduleLines(field(Status.CLOSED, 100.0F), 12);
        assertTrue(locked.contains("歸墟坍縮鎖定中：剩餘 12 秒，暫不接受種子"));
        assertFalse(locked.contains("投入「黑洞坍縮器」可安全關閉（不鎖定）"));
    }
}
