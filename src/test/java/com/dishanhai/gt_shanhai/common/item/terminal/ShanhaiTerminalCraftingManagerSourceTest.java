package com.dishanhai.gt_shanhai.common.item.terminal;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void submitFailuresDoNotBlockPartialBuildConfirmation() throws IOException {
        String source = Files.readString(MANAGER);

        assertTrue(source.contains("boolean submitted"),
                "每个任务必须记录是否已成功提交，不能用 Session.phase 覆盖单项状态");
        assertTrue(source.contains("if (item.submitted) continue;"),
                "重复确认不得重复提交首轮已经成功的任务");
        assertTrue(source.contains("item.submitted = true"),
                "只有 submitJob 成功后才能标记任务已提交");
        assertTrue(source.contains("session.phase = Phase.READY_TO_BUILD"),
                "提交阶段结束后必须直接允许按现有材料施工");
        assertTrue(source.contains("未成功提交 AE 合成任务"),
                "即使没有 AE 任务提交成功，也不能把施工入口卡死");
        assertTrue(source.contains("缺材料位置跳过"));
        assertFalse(source.contains("if (submitted == 0) return false"),
                "submitJob 全失败时不得回到下单确认失败");
        assertFalse(source.contains("materials.shortages(currentPlan"),
                "已下单会话不得再做全量材料到齐校验");
    }

    @Test
    void terminalInteractionNoLongerBlocksOnAeRetryState() throws IOException {
        String behavior = Files.readString(BEHAVIOR);
        String source = Files.readString(MANAGER);

        assertFalse(behavior.contains("Phase.RETRY_CALCULATING"),
                "终端普通右击不得再进入失败任务重算等待流程");
        assertFalse(behavior.contains("confirmSubmit"),
                "终端交互层不得再把施工绑定到 AE 下单确认");
        assertFalse(behavior.contains("refreshBuildReadiness"),
                "终端交互层不得再用材料到齐刷新阻塞施工");
        assertTrue(source.contains("!item.submitted\n                    && item.future != null && !item.future.isDone()"),
                "重算阶段只等待未提交任务的 Future");
        assertTrue(source.contains("当前无可用合成流程"),
                "重试时暂时不可合成的任务不得让整个 Session 永久等待");
        assertTrue(source.contains("失败任务的合成方案已就绪"),
                "重算完成后必须回到可补单状态");
    }

    @Test
    void serverTickOnlyScansActiveCalculationsWithoutSnapshotAllocation() throws IOException {
        String source = Files.readString(MANAGER);

        assertTrue(source.contains("ACTIVE_CALCULATIONS"),
                "完成但尚未施工的会话不得继续进入每 tick 轮询");
        assertTrue(source.contains("for (Map.Entry<UUID, Session> entry : ACTIVE_CALCULATIONS.entrySet())"),
                "活动计算应直接使用 ConcurrentHashMap 的弱一致性迭代器");
        assertFalse(source.contains("new ArrayList<>(SESSIONS.values())"),
                "终端等待计算期间不得每 tick 分配 Session 快照");
        assertFalse(source.contains("session.items.stream().anyMatch("),
                "终端等待 Future 时不得每 tick 分配 Stream 管线");
        assertTrue(source.contains("ACTIVE_CALCULATIONS.remove(session.terminalId, session)"),
                "结束或替换计算时必须按 Session 身份移除，避免误删新会话");
    }
}
