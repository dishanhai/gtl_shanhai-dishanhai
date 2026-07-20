package com.dishanhai.gt_shanhai.common.item.terminal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ShanhaiTerminalCraftingManagerSourceTest {

    private static final Path MANAGER = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "item", "terminal", "ShanhaiTerminalCraftingManager.java");
    private static final Path BEHAVIOR = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "item", "ShanhaiUltimateTerminalBehavior.java");

    @Test
    void partialSubmitKeepsFailedWorkItemsRetryable() throws IOException {
        String source = Files.readString(MANAGER);

        assertTrue(source.contains("boolean submitted"),
                "每个任务必须记录是否已成功提交，不能用 Session.phase 覆盖单项状态");
        assertTrue(source.contains("if (item.submitted) continue;"),
                "补单不得重复提交首轮已经成功的任务");
        assertTrue(source.contains("item.submitted = true"),
                "只有 submitJob 成功后才能标记任务已提交");
        assertTrue(source.contains("RETRY_CALCULATING"),
                "部分失败后必须有独立的失败任务重算阶段");
        assertTrue(source.contains("startPendingCalculations(session, player, ae)"),
                "材料到齐后的普通右击必须重新计算失败任务");
        assertTrue(source.contains("session.phase = usable == 0 ? Phase.SUBMITTED : Phase.READY_TO_SUBMIT"),
                "失败任务重算成功后必须回到补单确认态，不能继续停在已提交态");
        assertTrue(source.contains("if (hasPendingItems(session)) {\n            startPendingCalculations(session, player, ae);\n            return false;"),
                "仍有失败任务时不得仅因建筑材料齐全就进入施工");
    }

    @Test
    void retryCalculationIsHandledAsAWorkingSession() throws IOException {
        String behavior = Files.readString(BEHAVIOR);
        String source = Files.readString(MANAGER);

        assertTrue(behavior.contains("Phase.RETRY_CALCULATING"),
                "终端不能把失败任务重算阶段误判成未扫描");
        assertTrue(source.contains("!item.submitted\n                    && item.future != null && !item.future.isDone()"),
                "重算阶段只等待未提交任务的 Future");
        assertTrue(source.contains("当前无可用合成流程"),
                "重试时暂时不可合成的任务不得让整个 Session 永久等待");
        assertTrue(source.contains("失败任务的合成方案已就绪"),
                "重算完成后必须回到可补单状态");
    }
}
