package com.dishanhai.gt_shanhai.common.ae2.quantum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.gtlcore.gtlcore.integration.ae2.crafting.CraftingPatternPower;
import org.junit.jupiter.api.Test;

class QuantumCraftingBulkPowerTest {

    private static final Path LOGIC = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "ae2", "quantum", "QuantumCraftingCPULogic.java");

    @Test
    void bulkDispatchUsesSinglePatternPowerLikeGtlCore() {
        assertEquals(4.0D, CraftingPatternPower.forCpu(4_000_000.0D, true, 1_000_000L));
        assertEquals(4_000_000.0D, CraftingPatternPower.forCpu(4_000_000.0D, false, 1_000_000L));
    }

    @Test
    void quantumCpuAppliesBulkPowerConversionBeforeEnergyCheck() throws IOException {
        String source = Files.readString(LOGIC);
        int conversion = source.indexOf("CraftingPatternPower.forCpu(");
        int energyCheck = source.indexOf("energyService.extractAEPower(patternPower");

        assertTrue(conversion >= 0 && conversion < energyCheck,
                "整批发配必须先换算为 GTLCore 批量电耗，再检查 AE 网络瞬时供能");
        assertTrue(source.contains("extractedAsBulk, extractedBulkAmount"),
                "电耗换算必须使用实际抽取容器时锁定的批量口径");
    }

    @Test
    void adaptiveBulkKeepsFullThroughputWhenInputsAreReady() {
        assertEquals(10_000_000L, QuantumCraftingCPULogic.limitBulkAmount(
                10_000_000L, 640_000_000L, 64L));
    }

    @Test
    void adaptiveBulkDispatchesTheLargestCurrentlyExecutableBatch() {
        assertEquals(2_500L, QuantumCraftingCPULogic.limitBulkAmount(
                10_000_000L, 160_031L, 64L));
        assertEquals(0L, QuantumCraftingCPULogic.limitBulkAmount(
                10_000_000L, 63L, 64L));
    }

    @Test
    void bulkDispatchUsesAdaptiveAmountForExtractionAndProgress() throws IOException {
        String source = Files.readString(LOGIC);

        assertTrue(source.contains("findAvailableBulkAmount((AEProcessingPattern) details, inventory"),
                "批量发配必须先按 CPU 当前原料计算最大可执行份数");
        assertTrue(source.contains("VirtualPatternEncodingHelper.isPresenceInput(input)"),
                "可复用的 presence 输入不能限制批次数");
        assertTrue(source.contains("expectedOutputs, extractedBulkAmount"),
                "产出等待量必须使用实际发配份数");
        assertTrue(source.contains("task.getValue().value -= extractedBulkAmount"),
                "任务进度必须扣除实际发配份数");
    }

}
