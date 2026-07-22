package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StarRailMEOutputMatrixPerformanceSourceTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "part", "StarRailMEOutputMatrixPartMachine.java");

    @Test
    void coolingOnlyScansBackOffInsteadOfRescanningEveryTick() throws IOException {
        String source = Files.readString(SOURCE);
        String ticking = extractBlock(source, "public TickRateModulation tickingRequest(");
        String flushByMode = extractBlock(source, "private int flushByMode(");
        String smallest = extractBlock(source, "private boolean flushSmallestCandidate(");
        String shouldBackoff = extractBlock(source, "private boolean shouldBackoffCoolingOnlyScan()");

        assertTrue(source.contains("COOLING_SCAN_BACKOFF_TICKS"),
                "星轨输出矩阵在全 key 冷却时应有短 backoff，避免每 tick 重扫失败缓冲");
        assertTrue(ticking.contains("shouldBackoffCoolingOnlyScan()")
                        && ticking.contains("coolingOnlyBackoffTicks()"),
                "只有 flush 确认本轮无输出且全为冷却扫描后，ticker 才能进入短冷却");
        assertTrue(flushByMode.contains("lastFlushScannedKeys++")
                        && flushByMode.contains("recordCoolingSkip(cooldownUntil)"),
                "普通 flush 扫描必须记录冷却跳过数量");
        assertTrue(smallest.contains("lastFlushScannedKeys++")
                        && smallest.contains("recordCoolingSkip(cooldownUntil)"),
                "小堆优先模式也必须共享同一套冷却扫描统计");
        assertTrue(shouldBackoff.contains("lastFlushCoolingSkippedKeys >= lastFlushScannedKeys")
                        && shouldBackoff.contains("failedKeyCooldownUntil.size() >= buffer.size()"),
                "backoff 只能在扫描到的 key 全是冷却且失败表覆盖整个 buffer 时触发");
    }

    private static String extractBlock(String source, String declaration) {
        int start = source.indexOf(declaration);
        assertTrue(start >= 0, "缺少方法声明: " + declaration);
        int openBrace = source.indexOf('{', start);
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) {
                return source.substring(openBrace, i + 1);
            }
        }
        throw new AssertionError("方法体未闭合: " + declaration);
    }
}
