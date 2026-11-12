package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.testutil.MixinConfigs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MolecularAssemblerPortOutputMultiplierSourceTest {

    private static final Path MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "MolecularAssemblerPortOutputMultiplierMixin.java");
    @Test
    void portPublishesCachedEffectivePatternsButPushesSourcePatterns() throws Exception {
        String source = Files.readString(MIXIN);

        assertTrue(source.contains("@Mixin(value = MEMolecularAssemblerIOPartMachine.class"));
        assertTrue(source.contains("method = \"getAvailablePatterns\""));
        assertTrue(source.contains("VirtualPatternEncodingHelper.rewritePatternOutputMultiplier"));
        assertTrue(source.contains("gtShanhai$effectiveToSource"));
        assertTrue(source.contains("@ModifyVariable("));
        assertTrue(source.contains("\"pushPattern\""));
        assertTrue(source.contains("gtShanhai$sourcePattern(patternDetails)"),
                "端口接单前必须把 AE 可见倍增视图还原成源样板，避免实际倍率再次叠乘");
        assertFalse(source.contains("patternSlotMap.put("),
                "端口不能改写 GTLCore 源样板映射");
    }

    @Test
    void graphPreflightAndCapacityUseTheSameSourcePatternAsPush() throws Exception {
        String source = Files.readString(MIXIN);
        int preflight = source.indexOf("\"gtlcore$canProduceGraphPattern\"");
        int capacity = source.indexOf("\"gtlcore$graphCapacity\"");
        assertTrue(preflight >= 0 && capacity >= 0,
                "GRAPH 必须在检查槽位和计算原始产出队列容量前还原源样板");

        int start = source.lastIndexOf("@ModifyVariable(", Math.min(preflight, capacity));
        int end = source.indexOf("\n    }", Math.max(preflight, capacity));
        assertTrue(start >= 0 && end >= 0);
        String hook = source.substring(start, end);
        assertTrue(hook.contains("\"pushPattern\""));
        assertTrue(hook.contains("at = @At(\"HEAD\")"));
        assertTrue(hook.contains("argsOnly = true, ordinal = 0, require = 3"));
        assertTrue(hook.contains("return gtShanhai$sourcePattern(patternDetails);"));
    }

    @Test
    void newerGraphDiagnosticsUseSourceSlotsAndRemainOptional() throws Exception {
        String source = Files.readString(MIXIN);
        int target = source.indexOf("\"gtlcore$graphSharedToolProblem\"");
        assertTrue(target >= 0);
        int start = source.lastIndexOf("@ModifyVariable(", target);
        int end = source.indexOf("\n    }", target);
        assertTrue(start >= 0 && end >= 0);
        String hook = source.substring(start, end);
        assertTrue(hook.contains("\"gtlcore$graphBlockReason\""));
        assertTrue(hook.contains("\"gtlcore$graphBlockDetail\""));
        assertTrue(hook.contains("\"gtlcore$graphBlockMessage\""));
        assertTrue(hook.contains("require = 0"));
        assertTrue(hook.contains("return gtShanhai$sourcePattern(patternDetails);"));
    }

    @Test
    void portUsesReadOnlyHostMultiplierAndRefreshesOnlyOnChange() throws Exception {
        String source = Files.readString(MIXIN);

        assertTrue(source.contains("getOffsetTimer() % 40L"));
        assertTrue(source.contains("if (detected == gtShanhai$cachedHostOutputMultiplier) return;"));
        assertTrue(source.contains("OutputMultiplierResolver.resolveHostOutputMultiplier("));
        assertTrue(source.contains("ICraftingProvider.requestUpdate"));
        assertTrue(source.contains("gtShanhai$outputMultiplierModeEnabled"));
        assertTrue(source.contains("gtShanhai$toggleOutputMultiplierMode"));
        assertTrue(source.contains("gtShanhai$refreshOutputMultiplierNow"));
        assertFalse(source.contains("IntInputWidget"),
                "分子端口倍率只能读取万象核心，不允许手动输入");
        assertFalse(source.contains("module.getHostOutputMultiplier()"),
                "分子端口倍率必须走通用 resolver，不允许硬编码原初主机读取路径");
    }

    @Test
    void portMixinIsRegistered() throws Exception {
        assertTrue(MixinConfigs.readAll().contains("MolecularAssemblerPortOutputMultiplierMixin"));
    }
}
