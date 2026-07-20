package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VirtualPatternEncodingHelperTest {

    @Test
    void preservesSelectedConsumableInputsDuringVirtualProviderRewrite() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/item/VirtualPatternEncodingHelper.java"));

        assertFalse(source.contains("List<GenericStack> rewritten = createVirtualInputs(recipe);"));
        assertTrue(source.contains("rewriteInputsPreservingSelections(inputs, recipe)"));
    }

    @Test
    void encodingRestoresNonConsumableInputsOmittedByGtceu() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/item/VirtualPatternEncodingHelper.java"));

        assertTrue(source.contains("findMatchingRecipeForEncoding(inputs, outputs)"),
                "普通样板编码必须使用允许 GTCEu 省略不消耗输入的专用反查路径");
        assertTrue(source.contains("allowOmittedNonConsumables"),
                "编码反查和星律运行时严格反推必须显式隔离");
        assertTrue(source.contains("GenericStack missingInput = createVirtualItemInput(sample, amount)"));
        assertTrue(source.contains("rewritten.add(missingInput)"),
                "编码器删除的模具等不消耗物品必须主动补成虚拟供应器");
        assertTrue(source.contains("rewritten.add(new GenericStack(fluidKeyOf(sample), VIRTUAL_FLUID_MARKER_AMOUNT))"),
                "编码器删除的不消耗流体必须主动补成虚拟标记");
    }

    @Test
    void omittedIntegratedCircuitIsRestoredRawAndNeverWrapped() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/item/VirtualPatternEncodingHelper.java"));

        assertTrue(source.contains("IntCircuitBehaviour.isIntegratedCircuit(sample)"),
                "缺失编程电路必须有独立分支");
        assertTrue(source.contains("return new GenericStack(AEItemKey.of(sample), amount)"),
                "编程电路只能按原物品补回样板");
        assertFalse(source.contains("createBoundProvider(sample)"),
                "编程电路在任何情况下都不得直接传入供应器创建逻辑");
    }

    @Test
    void existingVirtualFluidMarkerRemainsIdempotentDuringReencoding() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/item/VirtualPatternEncodingHelper.java"));

        assertTrue(source.contains("isNonConsumable(content)\n"
                        + "                        && input.amount() == VIRTUAL_FLUID_MARKER_AMOUNT"),
                "已编码的不消耗流体标记必须能再次匹配原配方，不能追加重复标记后拒绝改写");
    }

    @Test
    void untypedEncodingRejectsCandidatesFromDifferentRecipeTypes() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/item/VirtualPatternEncodingHelper.java"));

        assertTrue(source.contains("haveMultipleRecipeTypes(matches)"),
                "没有类型上下文时不能在不同配方类型之间按注册顺序猜测");
        assertTrue(source.contains("encoding type context required"),
                "跨配方类型歧义必须记录为需要类型上下文，而不是改变供应器目标");
    }

    @Test
    void virtualTargetsRemainReusablePlanningDependencies() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/item/VirtualPatternEncodingHelper.java"));

        assertTrue(source.contains("new PresenceInput("),
                "虚拟目标必须进入合成树，缺失时才能由 AE 递归合成补齐");
        assertTrue(source.contains("return template[0].what();"),
                "在场输入必须声明返还自身，规划阶段才能复用而不是按每次合成消耗");
        assertFalse(source.contains("if (analysis.targetFor(input) != null) {\n                    continue;"),
                "不得继续在规划输入中直接删除虚拟目标");
    }

    @Test
    void bulkDispatchDoesNotMultiplyReusableVirtualTargets() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/item/VirtualPatternEncodingHelper.java"));

        assertTrue(source.contains("virtualTargetSink.accept(virtualTarget.what(), Math.max(1L, virtualTarget.amount()));"),
                "不消耗目标只需一份在场数量，不能乘整批下单次数");
        assertFalse(source.contains("virtualTarget.amount()), patternMultiplier"),
                "批量次数不得放大虚拟库存并持久化到样板总成");
    }

    @Test
    void virtualProviderTargetIsDeserializedOnlyOncePerAnalysis() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/item/VirtualPatternEncodingHelper.java"));
        int start = source.indexOf("private static GenericStack getVirtualProviderTarget");
        int end = source.indexOf("private static GenericStack getIntegratedCircuitTarget", start);
        String method = source.substring(start, end);

        assertTrue(method.contains("VirtualItemProviderHelper.isProviderItem(provider)"),
                "包裹反查应先做常量时间的物品类型判断");
        assertFalse(method.contains("VirtualItemProviderHelper.isBoundProvider(provider)"),
                "isBoundProvider 会提前解包一次，随后 getTarget 会重复反序列化 NBT");
        assertTrue(method.contains("VirtualItemProviderHelper.getTarget(provider)"),
                "确认包裹物品后只读取一次目标");
    }

    @Test
    void multipliedOutputsUseShapeFallbackAndOrderedDisambiguation() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/item/VirtualPatternEncodingHelper.java"));

        assertTrue(source.contains("scaledCandidates(outputBag)"),
                "完整输出数量未命中后必须按输出键形状召回倍率候选");
        assertTrue(source.contains("detectRecipeOutputMultiplier"),
                "候选必须校验所有普通输出具有同一整数倍率");
        assertTrue(source.contains("matchesOrderedRecipeOutputs"),
                "同输入输出袋多候选必须使用样板输出顺序消歧");
        assertTrue(source.contains("RecipeCalculationHelper.INSTANCE.isRecipeCycleContainerItem"),
                "倍率规则必须与 GTLAdd 伪神模式的循环容器判定一致");
        assertTrue(source.contains("exactSelection.ambiguous"),
                "精确数量候选已经歧义时不得继续回退到其他基础数量猜配方");
    }

    @Test
    void authoritativeTypeMergesEquivalentInputOutputCandidates() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/item/VirtualPatternEncodingHelper.java"));

        assertTrue(source.contains("if (requiredType != null)"));
        assertTrue(source.contains("return selectCanonicalTypeScopedCandidate(matches)"),
                "权威类型域内的同输入输出候选必须合并为规范候选");
        assertTrue(source.indexOf("return selectCanonicalTypeScopedCandidate(matches)")
                        < source.indexOf("GTRecipe orderedMatch = null;"),
                "类型域内合并必须先于输出顺序消歧，不能继续只放行主输出顺序");
    }

    @Test
    void authoritativeTypeCanResolveASecondaryOutputOfOneMultiOutputRecipe() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/item/VirtualPatternEncodingHelper.java"));

        assertTrue(source.contains("if (requiredType != null)"));
        assertTrue(source.contains("index.partialCandidates(outputBag)"),
                "完整输出袋未命中时，权威类型域必须按任一次要输出键召回多产物配方");
        assertTrue(source.contains("detectPartialRecipeOutputMultiplier"),
                "次要输出仍必须校验相对完整配方的统一整数倍率");
        assertTrue(source.contains("haveEquivalentRecipeInputsAndOutputs"),
                "多个部分输出候选只有完整输入输出等价时才允许合并");
        assertTrue(source.indexOf("index.partialCandidates(outputBag)")
                        > source.indexOf("index.scaledCandidates(outputBag)"),
                "部分输出召回只能作为完整输出和完整形状均失败后的末级回退");
    }

    @Test
    void stellarRecipeInferenceUsesAvailableInventoryForOmittedCatalysts() throws IOException {
        String helperSource = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/item/VirtualPatternEncodingHelper.java"));
        String machineSource = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/machine/part/RecipeTypePatternBufferPartMachine.java"));
        String searchSource = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/item/RecipeTypePatternSearchHelper.java"));

        assertTrue(helperSource.contains("availableCatalystInputs"),
                "反推器必须显式接收星律当前可用库存，不能无条件省略催化剂");
        assertTrue(helperSource.contains("availableInputMatchesItemCatalyst"),
                "缺失的物品催化剂必须由库存中的真实物品补足");
        assertTrue(helperSource.contains("availableInputMatchesFluidCatalyst"),
                "缺失的流体催化剂必须由库存中的真实流体补足");
        assertTrue(helperSource.contains("!IntCircuitBehaviour.isIntegratedCircuit"),
                "编程电路即使 chance==0 也不得被当作可缺省催化剂");
        assertTrue(helperSource.contains("VirtualItemProviderHelper.getTarget"),
                "样板写入虚拟供应器时，反推器必须按其目标物品匹配原配方催化剂");
        assertTrue(machineSource.contains("gtShanhai$getPatternInferenceInputs"),
                "星律必须把库存拉取和共享催化仓内容传给反推器");
        assertTrue(machineSource.contains("getStock()"),
                "库存上下文必须读取当前真实可见库存，而不是只读取配置键");
        assertTrue(searchSource.contains("inferenceInventoryFingerprint"),
                "反推缓存必须感知库存变化，补入模块后才能重新推断");
    }

    @Test
    void authoritativeTypeOnlyMergesCandidatesWithEquivalentFullInputs() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/item/VirtualPatternEncodingHelper.java"));

        int equivalentGuard = source.indexOf("if (!haveEquivalentRecipeInputsAndOutputs");
        int ambiguousReturn = source.indexOf("return RecipeSelection.AMBIGUOUS", equivalentGuard);
        assertTrue(equivalentGuard >= 0 && ambiguousReturn > equivalentGuard,
                "同类型候选若仅催化模块不同，不能固定选择配方 ID 第一项");
    }
}
