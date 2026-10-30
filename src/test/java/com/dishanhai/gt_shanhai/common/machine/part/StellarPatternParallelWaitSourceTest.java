package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：監視必須先評估並行和主機進度，不能因原料未減少直接報警。 */
class StellarPatternParallelWaitSourceTest {
    private static final Path MAIN = Path.of("src/main/java/com/dishanhai/gt_shanhai");

    @Test void watchEvaluatesCapacityAndProgressBeforeSettingWarning() throws Exception {
        String source = Files.readString(MAIN.resolve("common/machine/part/StellarPatternStuckWatch.java"));
        assertTrue(source.contains("StellarPatternWaitPolicy"), "缺少並行等待策略");
        assertTrue(source.indexOf("evaluateParallelWait(machine, watch")
                < source.indexOf("machine.gtShanhai$setPatternSlotStuckWarning(watch.slot"));
        assertTrue(source.contains("getProgress()"));
        assertTrue(source.contains("WildcardPatternRecipeTypeBinding.collectHostRecipeTypes(List.of(controller))"));
        assertTrue(source.contains("parallelAssessment"), "完整告警必須保留評估依據");
    }

    @Test void allocationsAreObservedWithoutChangingTheParallelLimitFormula() throws Exception {
        String source = Files.readString(MAIN.resolve("api/machine/SelectableRecipeTypeSetRecipeLogic.java"));
        assertTrue(source.contains("gtShanhai$getStuckWatchParallelLimit()"));
        assertTrue(source.contains("gtShanhai$getLastAllocatedParallel()"));
        assertTrue(source.contains("saturatedMultiply(getMachine().getRecipeLogicMaxParallel(), getLogicThreadMultiplier())"));
    }

    @Test void fullReportsClearWithTheirSlotSnapshots() throws Exception {
        String source = Files.readString(MAIN.resolve("common/machine/part/RecipeTypePatternBufferPartMachine.java"));
        assertTrue(source.contains("stuckRuntimeReports.remove(slot)"));
        assertTrue(source.contains("stuckRuntimeReports.clear()"));
        assertTrue(source.contains("gtShanhai$buildStuckReport()"));
    }

    @Test void originalAggregateHostsObserveSuccessfulAllocationsInsteadOfRawOutputMetadata() throws Exception {
        Path core = MAIN.resolve("mixin/StellarPatternCoreParallelObservationMixin.java");
        Path add = MAIN.resolve("mixin/StellarPatternAddParallelObservationMixin.java");
        Path input = MAIN.resolve("mixin/StellarPatternInputParallelObservationMixin.java");
        assertTrue(Files.exists(core) && Files.exists(add) && Files.exists(input), "原版聚合主機缺少真實分配量觀測");
        String coreSource = Files.readString(core);
        assertFalse(coreSource.contains("method = \"getRecipe\""), "GTLA 對 getRecipe 使用 @Overwrite，不能直接注入");
        assertTrue(coreSource.contains("findAndHandleRecipe") && coreSource.contains("onRecipeFinish"));
        assertTrue(Files.readString(input).contains("getRealParallels()"));
        assertTrue(Files.readString(input).contains("cir.getReturnValue()"));
        assertTrue(Files.readString(add).contains("allocation.getParallels()"));
        String watch = Files.readString(MAIN.resolve("common/machine/part/StellarPatternStuckWatch.java"));
        assertFalse(watch.contains("batch.parallels"), "聚合raw output的parallels不是實際分配量");
    }

    @Test void independentRecipeModulesAndVisionCoreExposeTheirOwnAllocationSemantics() throws Exception {
        String module = Files.readString(MAIN.resolve("common/machine/primordial/PrimordialModuleRecipeLogic.java"));
        assertTrue(module.contains("public long gtShanhai$getStuckWatchParallelLimit()"),
                "逐配方獨立並行的模組必須以跨配方容量評估，不能冒用共享池");
        assertTrue(module.contains("stellarLastAllocatedRecipes = recipeList.size()"));
        String vision = Files.readString(MAIN.resolve("common/machine/primordial/guixu/GuixuVisionCoreRecipeLogic.java"));
        assertTrue(vision.contains("public long gtShanhai$getLastAllocatedParallel()"));
    }

    @Test void completeWarningUsesUnclippedStrongMatchDetails() throws Exception {
        String watch = Files.readString(MAIN.resolve("common/machine/part/StellarPatternStuckWatch.java"));
        String machine = Files.readString(MAIN.resolve("common/machine/part/RecipeTypePatternBufferPartMachine.java"));
        assertTrue(watch.contains("String strong = machine.gtShanhai$fullStrongMatchReason(slot)"),
                "完整報告不能重用裁到180字的摘要");
        assertTrue(machine.contains("return gtShanhai$strongMatchReason(slot, 0)"));
    }
}
