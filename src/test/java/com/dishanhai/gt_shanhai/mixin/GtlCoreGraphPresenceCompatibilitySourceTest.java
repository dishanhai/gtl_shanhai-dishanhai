package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GtlCoreGraphPresenceCompatibilitySourceTest {

    private static final Path BUILD_GRADLE = Path.of("build.gradle");
    private static final Path CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");
    private static final Path POLICY_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "GtlCoreGraphPresenceDispatchPolicyMixin.java");
    private static final Path CAPTURE_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "GtlCoreGraphPresenceCandidateCaptureMixin.java");
    private static final Path ADAPTER_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "GtlCoreGraphPresenceExecutionAdapterMixin.java");
    private static final Path BRIDGE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "GraphPresenceDispatchBridge.java");

    @Test
    void buildUsesTheGraphCompatibleGtlCoreDependency() throws Exception {
        String build = Files.readString(BUILD_GRADLE);
        assertTrue(build.contains("gtlcore-1.2.3.2-fix3.jar"),
                "GRAPH 修复必须以 fix3 API 为编译基线");
        assertTrue(!build.contains("implementation files('libs/gtlcore-1.2.3.2-fix2.jar')"),
                "不能继续以 fix2 编译并误判独立 GRAPH 路径");
    }

    @Test
    void graphMixinsAreRegisteredAndCarryPresenceSemantics() throws Exception {
        String config = Files.readString(CONFIG);
        String policy = Files.readString(POLICY_MIXIN);
        String capture = Files.readString(CAPTURE_MIXIN);
        String adapter = Files.readString(ADAPTER_MIXIN);
        String bridge = Files.readString(BRIDGE);

        assertTrue(config.contains("\"GtlCoreGraphPresenceDispatchPolicyMixin\""));
        assertTrue(config.contains("\"GtlCoreGraphPresenceCandidateCaptureMixin\""));
        assertTrue(config.contains("\"GtlCoreGraphPresenceExecutionAdapterMixin\""));
        assertTrue(policy.contains("GtlDispatchPolicy"));
        assertTrue(policy.contains("configuration"));
        assertTrue(policy.contains("reusable"));
        assertTrue(capture.contains("GtlPatternCatalog$CandidateCapture"));
        assertTrue(capture.contains("enterInput"));
        assertTrue(capture.contains("configuration"));
        assertTrue(capture.contains("reusable"));
        assertTrue(adapter.contains("GtlExecutionAdapter"));
        assertTrue(adapter.contains("isPresenceInput"));
        assertTrue(adapter.contains("@Local IPatternDetails.IInput input"),
                "执行匹配必须按当前槽位判断，不能把同 AEKey 的普通耗材一起标成 reusable");
        assertTrue(!adapter.contains("enterPresencePattern"),
                "执行匹配不得按整张 pattern 的 key 集合污染普通输入槽位");
        assertTrue(bridge.contains("isPresenceInput"));
        assertTrue(bridge.contains("isPresenceKey"));
        assertTrue(bridge.contains("ThreadLocal"));
    }
}
