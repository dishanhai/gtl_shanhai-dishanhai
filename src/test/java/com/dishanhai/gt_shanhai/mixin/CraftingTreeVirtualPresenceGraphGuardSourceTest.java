package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingTreeVirtualPresenceGraphGuardSourceTest {

    private static final Path MIXIN_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "CraftingTreeNodeVirtualPresenceMixin.java");
    private static final Path EXECUTOR_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "MaxFastExecutorVirtualPresenceMixin.java");
    private static final Path MIXIN_CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");

    @Test
    void presenceNodesStayOnMaxFast() throws Exception {
        String source = Files.readString(MIXIN_SOURCE);
        String config = Files.readString(MIXIN_CONFIG);

        assertTrue(config.contains("\"CraftingTreeNodeVirtualPresenceMixin\""));
        assertFalse(config.contains("\"MaxFastExecutorVirtualPresenceMixin\""),
                "整單 legacyRequest 會離開 MAX_FAST");
        assertFalse(Files.exists(EXECUTOR_SOURCE),
                "不得再把根節點改走 legacyRequest");
        assertFalse(source.contains("gtlcore$tryMaxFastAggregation"),
                "在場子樹不得關閉 MAX_FAST 聚合");
        assertFalse(source.contains("gTLCore$tryMaxFastCycleCandidateGraph"),
                "在場子樹不得關閉 cycle candidate graph");
        assertFalse(source.contains("gTLCore$tryMaxFastCandidateGraph"),
                "在場子樹不得關閉 candidate graph");
        assertFalse(source.contains("buildChildPatterns()"),
                "在場判斷不得提前展開 AE2 樣板樹");
        assertFalse(source.contains("legacyRequest"),
                "在場節點不得回退到 AE2 request");
        assertTrue(source.contains("gtlcore$runMaxFastPrefix"),
                "MAX_FAST 前綴必須把已在場的輸入記為滿足");
        assertTrue(source.contains("setReturnValue(0L)"),
                "已在場時前綴必須回傳 0，呼叫端才會跳過後續製作");
        assertTrue(source.contains("VirtualCraftingPresenceState.hasPresence"),
                "在場檢查必須走 SIMULATE");
        assertTrue(source.contains("gtlcore$handlePausing"),
                "進入在場短路前必須保留暫停檢查");
        assertTrue(source.contains("inventory.addStackBytes"),
                "在場短路必須保留計算字節統計");
        assertTrue(source.contains("this.parentInput.getMultiplier()"),
                "未在場時只請求樣板自身需求量");
        assertTrue(source.contains("priority = 1500"),
                "節點修正必須晚於 GTLCore 才能命中其新增方法");
    }
}
