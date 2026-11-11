package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.testutil.MixinConfigs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GtlCoreGraphPresenceCompatibilitySourceTest {

    private static final Path BUILD_GRADLE = Path.of("build.gradle");
    private static final Path POLICY_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "GtlCoreGraphPresenceDispatchPolicyMixin.java");
    private static final Path CAPTURE_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "GtlCoreGraphPresenceCandidateCaptureMixin.java");
    private static final Path ADAPTER_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "GtlCoreGraphPresenceExecutionAdapterMixin.java");
    private static final Path BRIDGE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "GraphPresenceDispatchBridge.java");
    private static final Path MIXINS = ADAPTER_MIXIN.getParent();

    @Test
    void buildUsesTheGraphCompatibleGtlCoreDependency() throws Exception {
        String build = Files.readString(BUILD_GRADLE);
        assertTrue(build.contains("implementation files('libs/gtlcore-1.2.3.2-fix4.jar')"),
                "GRAPH 回归使用仓库固定的 fix4 JAR");
        assertTrue(!build.contains("implementation files('libs/gtlcore-1.2.3.2-fix2.jar')"),
                "不能继续以 fix2 编译并误判独立 GRAPH 路径");
    }

    @Test
    void graphBindingComparesPhysicalOutputsWithoutSyntheticPresenceReturns() throws Exception {
        String adapter = Files.readString(ADAPTER_MIXIN);
        String config = MixinConfigs.readAll();
        String fingerprint = Files.readString(MIXINS.resolve("GtlCoreGraphPresenceFingerprintMixin.java"));
        String stateReturns = Files.readString(MIXINS.resolve("GtlCoreGraphPresenceStateReturnsMixin.java"));
        assertTrue(config.contains("\"GtlCoreGraphPresenceFingerprintMixin\""));
        assertTrue(config.contains("\"GtlCoreGraphPresenceFingerprintMixin$Binding\""));
        assertTrue(config.contains("\"GtlCoreGraphPresenceStateReturnsMixin\""));
        assertTrue(fingerprint.contains("GraphPresenceDispatchBridge.physicalRemainder"));
        assertTrue(fingerprint.contains("of(Lappeng/api/crafting/IPatternDetails;)Ljava/lang/String;"));
        assertTrue(adapter.contains("GraphPresenceDispatchBridge.physicalRemainder"));
        assertTrue(stateReturns.contains("VirtualPatternEncodingHelper.isPresenceInput"));
    }

    @Test
    void graphSummaryMixinsAreRegisteredForBothSupportedSummaryPaths() throws Exception {
        String config = MixinConfigs.readAll();
        assertTrue(config.contains("\"GtlCoreGraphPresenceSummaryMixin\""));
        assertTrue(config.contains("\"GtlCoreGraphPresenceSummaryMixin$Legacy\""));
    }

    @Test
    void graphMixinsAreRegisteredAndCarryPresenceSemantics() throws Exception {
        String config = MixinConfigs.readAll();
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
        assertTrue(capture.contains("method = \"step\""));
        assertTrue(capture.contains("GraphPresenceDispatchBridge.clear()"));
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
