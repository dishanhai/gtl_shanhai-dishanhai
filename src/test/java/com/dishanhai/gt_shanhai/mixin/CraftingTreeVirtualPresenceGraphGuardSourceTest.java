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
        assertTrue(executor.contains("method = \"execute\""),
                "MAX_FAST root execute 直达聚合入口必须单独守卫");
        assertTrue(executor.contains("root.legacyRequest(inventory, requestedAmount, containerItems)"),
                "Presence root 必须回退到 AE2 legacyRequest，不能再进入 MAX_FAST prefix 抽取");
        assertTrue(!executor.contains("executeChild(root, inventory, requestedAmount, containerItems, metrics)"),
                "Presence root 不得回退到仍会执行 MAX_FAST prefix 的 executeChild");
        assertTrue(source.contains("IdentityHashMap"),
                "Presence 子树扫描必须按节点身份去重，避免递归图导致计算线程无限扫描");
        assertTrue(source.contains("IdentityHashMap<CraftingTreeNodeVirtualPresenceAccess, Boolean>"),
                "身份去重表必须使用 Mixin 访问接口类型，避免 Mixin 声明类无法转换为目标类");
        assertTrue(source.contains("VirtualCraftingPresenceState.hasPresence"),
                "PresenceInput 必须只用 SIMULATE 检查存在，不得进入 MODULATE 抽取");
        assertTrue(source.contains("gtlcore$handlePausing"),
                "取消原生 request 前必须保留 AE2/GTLCore 的暂停检查");
        assertTrue(source.contains("inventory.addStackBytes"),
                "取消原生 request 前必须保留 AE2 的计算字节统计");
        assertTrue(config.contains("\"MaxFastExecutorVirtualPresenceMixin\""));
        assertTrue(config.contains("\"CraftingTreeNodeVirtualPresenceMixin\""));
    }
}
