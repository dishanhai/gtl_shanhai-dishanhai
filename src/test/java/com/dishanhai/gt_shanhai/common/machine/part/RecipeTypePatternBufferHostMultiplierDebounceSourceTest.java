package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宿主输出倍率轮询防抖守卫。
 * <p>
 * 增殖核心等倍率模块在工作↔空闲转换间倍率会瞬时跳变（idle=10 / working=1000），
 * 每次跳变经 pollOutputMultiplierHostState → applyOutputMultiplierSettings 触发
 * 全量样板重编码（spark 实测单窗口累计 930ms+ 尖峰）。防抖要求连续两次轮询读到
 * 同一新值才应用；本测试守住状态机的三个关键分支，防止回归。
 */
class RecipeTypePatternBufferHostMultiplierDebounceSourceTest {

    private static final Path MACHINE = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "machine", "part", "RecipeTypePatternBufferPartMachine.java");

    @Test
    void hostMultiplierPollDebouncesTransientFlips() throws IOException {
        String source = Files.readString(MACHINE);
        int pollStart = source.indexOf("private void pollOutputMultiplierHostState()");
        assertTrue(pollStart >= 0, "轮询方法必须存在");
        int pollEnd = source.indexOf("private int makeOutputMultiplierPatternCacheKey", pollStart);
        String poll = source.substring(pollStart, pollEnd);

        assertTrue(source.contains("private int pendingDetectedHostOutputMultiplier = Integer.MIN_VALUE;"),
                "防抖 pending 字段必须存在");
        assertTrue(poll.contains("detected != pendingDetectedHostOutputMultiplier"),
                "新值必须先进 pending，第二次轮询确认后才应用（连续两次一致）");
        assertTrue(poll.contains("lastDetectedHostOutputMultiplier != Integer.MIN_VALUE"),
                "首次同步必须绕过防抖立即应用，保持模式开启时的即时性");
        int equalBranch = poll.indexOf("detected == lastDetectedHostOutputMultiplier");
        int equalBranchEnd = poll.indexOf("return;", equalBranch);
        assertTrue(equalBranch >= 0
                        && poll.substring(equalBranch, equalBranchEnd).contains("pendingDetectedHostOutputMultiplier"),
                "读值回落到已应用值时必须复位 pending，否则交替翻转会被残留 pending 误确认");
    }

    @Test
    void manualHostSyncKeepsPendingAligned() throws IOException {
        String source = Files.readString(MACHINE);
        int syncStart = source.indexOf("public void syncOutputMultiplierFromHost()");
        assertTrue(syncStart >= 0, "手动同步方法必须存在");
        int syncEnd = source.indexOf("public void syncOutputMultiplierFromPattern()", syncStart);
        assertTrue(source.substring(syncStart, syncEnd).contains("pendingDetectedHostOutputMultiplier = multiplier;"),
                "UI 直连同步路径必须同时复位 pending，避免残留旧值干扰后续防抖判定");
    }
}
