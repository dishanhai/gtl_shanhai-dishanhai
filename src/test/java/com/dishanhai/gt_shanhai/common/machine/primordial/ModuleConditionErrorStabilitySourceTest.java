package com.dishanhai.gt_shanhai.common.machine.primordial;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleConditionErrorStabilitySourceTest {

    private static final Path LOGIC = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "primordial", "PrimordialModuleRecipeLogic.java");
    private static final Path ENGINE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "api", "DShanhaiRecipeEngine.java");

    @Test
    void singleSuccessfulCandidateCannotClearAnotherCandidatesFailure() throws IOException {
        String source = Files.readString(LOGIC);
        int methodStart = source.indexOf("private boolean checkModuleCondition(GTRecipe recipe)");
        int methodEnd = source.indexOf("private void cacheModuleConditionTrue", methodStart);
        assertTrue(methodStart >= 0 && methodEnd > methodStart);

        String method = source.substring(methodStart, methodEnd);
        assertFalse(method.contains("clearConditionError()"),
                "单个成功候选不得清空其他候选写入的失败提示");
        assertFalse(method.contains("getPassTooltip()"),
                "成功条件不是机器错误，不应覆盖失败状态");
    }

    @Test
    void candidateBatchOwnsErrorClearingAndCachedFailuresKeepDetails() throws IOException {
        String source = Files.readString(LOGIC);

        assertTrue(source.contains("boolean moduleConditionFailed = false"),
                "候选集合必须聚合本轮是否存在条件失败");
        assertTrue(source.contains("if (!moduleConditionFailed)"),
                "只有整批候选均未失败时才允许清空错误");
        assertTrue(source.contains("CachedModuleConditionFailure"),
                "失败缓存必须同时保存到期时间和详细诊断");
        assertFalse(source.contains("mod.setModuleConditionError(\"配方条件未满足\")"),
                "缓存命中不得把详细错误降级成通用文本");
    }

    @Test
    void lookupReappliesCoilTemperatureGate() throws IOException {
        String source = Files.readString(LOGIC);
        int start = source.indexOf("protected Set<GTRecipe> lookupRecipeIterator()");
        int end = source.indexOf("private ShanhaiHeatGate.Outcome shanhai$gateOutcome", start);
        assertTrue(start >= 0 && end > start);
        String method = source.substring(start, end);
        assertTrue(method.contains("shanhai$extraMountGateAllows(recipe)"),
                "样板并入的候选也必须再过额外挂载闸门，否则无线圈仍能跑 3000K 配方");
        assertTrue(method.contains("cachedGateMountContents"),
                "挂载槽变化必须丢掉上一轮放行结果");
        assertTrue(source.contains("instanceof net.minecraft.nbt.NumericTag"),
                "炉温必须直接读 NBT，不能走会被温度绕过改成 0 的 getInt");
    }

    @Test
    void recipeReloadClearsStaticModuleRequirements() throws IOException {
        String source = Files.readString(ENGINE);
        int resetStart = source.indexOf("public static void resetRecipeStats()");
        int resetEnd = source.indexOf("public static void trackRecipeForCache", resetStart);
        assertTrue(resetStart >= 0 && resetEnd > resetStart);
        assertTrue(source.substring(resetStart, resetEnd).contains("ModuleLevelCondition.clearRequirements()"),
                "每次配方重载必须清空上一轮静态模块条件");
    }
}
