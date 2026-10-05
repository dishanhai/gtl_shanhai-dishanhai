package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingTreeVirtualPresenceGraphGuardSourceTest {

    private static final Path MIXIN_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "CraftingTreeNodeVirtualPresenceMixin.java");
    private static final Path ACCESS_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "CraftingTreeNodeVirtualPresenceAccess.java");
    private static final Path EXECUTOR_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "MaxFastExecutorVirtualPresenceMixin.java");
    private static final Path MIXIN_CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");

    @Test
    void presenceSubtreesRejectEveryMaxFastGraphEntryPoint() throws Exception {
        String source = Files.readString(MIXIN_SOURCE);
        String access = Files.readString(ACCESS_SOURCE);
        String executor = Files.readString(EXECUTOR_SOURCE);
        String config = Files.readString(MIXIN_CONFIG);

        assertTrue(Files.exists(ACCESS_SOURCE), "需要一个可由子节点递归调用的在场子树访问接口");
        assertTrue(access.contains("gtShanhai$containsPresenceInputInSubtree"),
                "访问接口必须暴露在场子树判断");
        assertTrue(source.contains("gtlcore$tryMaxFastAggregation"),
                "PresenceInput 子树不得进入 MAX_FAST 聚合图");
        assertTrue(source.contains("gTLCore$tryMaxFastCycleCandidateGraph"),
                "PresenceInput 子树不得进入 MAX_FAST cycle candidate graph");
        assertTrue(source.contains("gTLCore$tryMaxFastCandidateGraph"),
                "PresenceInput 子树不得进入 MAX_FAST candidate graph");
        assertTrue(source.contains("gtShanhai$containsPresenceInputInSubtree()"),
                "三个 GRAPH 入口必须共用同一子树判定");
        assertTrue(source.contains("buildChildPatterns()"),
                "GRAPH 判定发生在程序编译前，必须先建立子节点才能看到根节点下的 PresenceInput");
        assertTrue(source.contains("presence-scan begin") && source.contains("presence-scan end"),
                "Presence 子树扫描必须记录开始和结束，才能区分扫描卡死与执行卡死");
        assertTrue(source.contains("request-enter path=legacy")
                        && source.contains("request-enter path=fast")
                        && source.contains("request-enter path=ultra_fast")
                        && source.contains("request-enter path=max_fast"),
                "普通、FAST、ULTRA_FAST、MAX_FAST 请求入口都必须可观测");
        assertTrue(source.contains("VirtualCraftingPresenceState.hasPresence")
                        && source.contains("cancellable = true"),
                "PresenceInput 已在网络中时必须只做存在性检查并取消普通抽取");
        assertTrue(source.contains("gtShanhai$fallbackPresenceSubtreeToLegacy")
                        && source.contains("request(inventory, requestedAmount, containerItems)"),
                "所有快速请求入口必须回退到原生 request，避免 MAX_FAST/ULTRA_FAST 前缀 MODULATE 抽取");
        assertTrue(executor.contains("root-enter") && executor.contains("root-exit"),
                "MAX_FAST root fallback 必须记录执行进入和退出，才能定位执行线程卡死");
        assertTrue(executor.contains("method = \"execute\""),
                "MAX_FAST root execute 直达聚合入口必须单独守卫");
        assertTrue(executor.contains("root.legacyRequest(inventory, requestedAmount, containerItems)"),
                "Presence root 必须回退到 AE2 原生 request，绕过 MAX_FAST 的 MODULATE 前缀抽取");
        assertTrue(!executor.contains("executeStack("),
                "Presence root 不得通过 executeStack 重新进入 MAX_FAST 前缀抽取");
        assertTrue(config.contains("\"MaxFastExecutorVirtualPresenceMixin\""));
        assertTrue(config.contains("\"CraftingTreeNodeVirtualPresenceMixin\""));
    }
}
