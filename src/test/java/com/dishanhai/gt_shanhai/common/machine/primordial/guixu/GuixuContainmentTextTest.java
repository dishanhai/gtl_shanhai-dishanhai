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
        assertEquals("遏制场：未安装", GuixuContainmentText.hostSummary(null, 0));
        assertEquals("遏制场：坍缩锁定｜剩余 3 秒（主机与模组停机）",
                GuixuContainmentText.hostSummary(field(Status.CLOSED, 100.0F), 3));
        assertEquals("遏制场：关闭（投入黑洞种子开启）",
                GuixuContainmentText.hostSummary(field(Status.CLOSED, 100.0F), 0));
        assertEquals("遏制场：遏制中｜稳定度 100｜并行 ×4 耗时 ×0.5 增产 +5 耗电 ×0.5 数量 ×2",
                GuixuContainmentText.hostSummary(field(Status.CONTAINED, 100.0F), 0));
        assertEquals("遏制场：遏制中｜稳定度 10｜并行 ×16 耗时 ×0.125 增产 +5 耗电 ×0.5 数量 ×2",
                GuixuContainmentText.hostSummary(field(Status.CONTAINED, 10.0F), 0));
        assertEquals("遏制场：失稳｜稳定度 -450｜产出吞噬机率 50.0%",
                GuixuContainmentText.hostSummary(field(Status.UNSTABLE, -450.0F), 0));
    }

    @Test
    void moduleLinesShowConsumptionAndCountdown() {
        List<String> contained = GuixuContainmentText.moduleLines(field(Status.CONTAINED, 100.0F), 0);
        assertTrue(contained.contains("状态：遏制中"));
        assertTrue(contained.contains("稳定度：100 / 100"));
        assertTrue(contained.contains("时空流体：每秒 10,000 mB（消耗倍率 ×1）"));
        assertTrue(contained.contains("催化计数：0 / 30"));
        assertTrue(contained.contains("投入「黑洞坍缩器」可安全关闭（不锁定）"));

        List<String> unstable = GuixuContainmentText.moduleLines(field(Status.UNSTABLE, -450.0F), 0);
        assertTrue(unstable.contains("产出吞噬机率：50.0%｜距坍缩 450 秒"));

        List<String> locked = GuixuContainmentText.moduleLines(field(Status.CLOSED, 100.0F), 12);
        assertTrue(locked.contains("归墟坍缩锁定中：剩余 12 秒，暂不接受种子"));
        assertFalse(locked.contains("投入「黑洞坍缩器」可安全关闭（不锁定）"));
    }
}
