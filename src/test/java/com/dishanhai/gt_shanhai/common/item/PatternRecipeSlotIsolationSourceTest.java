package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternRecipeSlotIsolationSourceTest {

    private static final Path SEARCH_HELPER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "RecipeTypePatternSearchHelper.java");
    private static final Path IDENTITY_MATCHER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "api", "PatternIdentityMatcher.java");
    private static final Path IDENTITY_GUARD = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "api", "PatternIdentityGuard.java");
    private static final Path IDENTITY_GUARD_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "PatternIdentityGuardMixin.java");
    private static final Path SELECTABLE_RECIPE_LOGIC = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "api", "machine", "SelectableRecipeTypeSetRecipeLogic.java");
    private static final Path MIXIN_CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");

    @Test
    void registersPatternIdentityGuardForBothInputHandlers() throws IOException {
        String config = Files.readString(MIXIN_CONFIG);

        assertTrue(config.contains("\"MEPatternBufferSlot2PatternAccessor\""),
                "样板身份守卫必须能读取槽位对应的处理样板");
        assertTrue(config.contains("\"PatternIdentityGuardMixin$ItemHandler\""),
                "物品输入匹配必须启用样板身份守卫");
        assertTrue(config.contains("\"PatternIdentityGuardMixin$FluidHandler\""),
                "流体输入匹配必须启用样板身份守卫");
    }

    @Test
    void virtualActivationCannotReplaceTheRequestedPatternSlot() throws IOException {
        String source = Files.readString(SEARCH_HELPER);
        int pinRequestedSlot = source.indexOf("handlePart.setLastRecipe2Slot(recipe, slot)");
        int probeSlot = source.indexOf("handlePart.handleRecipe(recipe, copyRecipeContents(recipe.inputs), true, false)");
        int rejectOtherSlot = source.indexOf("if (handledSlot != slot)");
        int bindRequestedSlot = source.indexOf("tryAddAndActiveMERhp(handlePart, recipe, slot)");

        assertTrue(pinRequestedSlot >= 0, "探测前必须先把候选配方钉到 AE 下单对应槽");
        assertTrue(probeSlot > pinRequestedSlot, "槽位探测必须在请求槽绑定之后执行且不得提前写缓存");
        assertTrue(rejectOtherSlot > probeSlot, "GTLCore 返回其他 active 槽时必须拒绝激活");
        assertTrue(bindRequestedSlot > rejectOtherSlot, "只有请求槽验证通过后才能注册宿主配方句柄");
        assertFalse(source.contains("tryAddAndActiveMERhp(handlePart, recipe, handledSlot)"),
                "不得把 GTLCore 任意返回的 active 槽升级为合法样板槽");
    }

    @Test
    void identityGuardAcceptsTheSinglePrimaryOutputRetainedByGtlcore() throws IOException {
        String source = Files.readString(IDENTITY_MATCHER).replace("\r\n", "\n");

        assertTrue(source.contains("!patternItems.isEmpty() && primaryItemOutputMatches"),
                "GTLCore 保留物品主产物时应只校验该物品输出");
        assertTrue(source.contains("!patternFluids.isEmpty() && primaryFluidOutputMatches"),
                "GTLCore 保留流体主产物时应只校验该流体输出");
        assertFalse(source.contains("patternItems)\n                && primaryFluidOutputMatches"),
                "GTLCore 只保留一个跨 capability 主产物，不得同时强求物品和流体输出");
        assertTrue(source.contains("if (contents == null || contents.isEmpty()) {\n            return false;"),
                "候选配方缺少样板所保留的输出 capability 时不得误判为匹配");
    }

    @Test
    void markedRecipeCacheScopesDuplicateRecipesToTheirPatternSlots() throws IOException {
        String source = Files.readString(SEARCH_HELPER);

        assertTrue(source.contains("PatternSlotScopedRecipe.scope(recipe, dimensionId, buffer.getPos(), slot)"),
                "星律槽位缓存必须把相同 GTRecipe 转换为槽位作用域身份");
        assertTrue(source.indexOf("PatternSlotScopedRecipe.scope")
                        < source.indexOf("new MarkedRecipeCacheEntry("),
                "槽位作用域配方必须进入缓存，不能每轮临时生成后丢失身份");
    }

    @Test
    void identityGuardRejectsGtlcoreFallbackToAnotherPatternSlot() throws IOException {
        String source = Files.readString(IDENTITY_GUARD);

        int sourceGuard = source.indexOf("if (scopedSourceRejects(handlerMachine, recipe, trySlot))");
        int fingerprintGuard = source.indexOf("PatternIdentityMatcher.matches");
        assertTrue(sourceGuard >= 0 && sourceGuard < fingerprintGuard,
                "GTLCore fallback 扫描必须先按总成坐标和槽号拒绝错误槽，再做普通样板指纹比较");
        assertTrue(source.contains("PatternSlotScopedRecipe.matchesSource"),
                "轻量来源守卫必须读取槽位作用域配方的维度、总成坐标和槽号");
    }

    @Test
    void scopedCandidateDoesNotRecheckTheRegisteredRecipesPrimaryOutput() throws IOException {
        String source = Files.readString(IDENTITY_GUARD);

        int sourceGuard = source.indexOf("if (scopedSourceRejects(handlerMachine, recipe, trySlot))");
        int scopedAcceptance = source.indexOf("if (PatternSlotScopedRecipe.isScoped(recipe))");
        int fingerprintGuard = source.indexOf("PatternIdentityMatcher.matches");
        assertTrue(sourceGuard >= 0 && scopedAcceptance > sourceGuard && scopedAcceptance < fingerprintGuard,
                "槽位作用域候选通过来源校验后必须直接放行，不能被注册配方第一输出误拒绝");
        assertTrue(source.substring(scopedAcceptance, fingerprintGuard).contains("return false;"),
                "scoped 候选必须跳过只适用于普通候选的样板主产物指纹复检");
    }

    @Test
    void selectableRecipeLogicReplacesUnscopedDuplicatesWithSlotScopedCandidates() throws IOException {
        String source = Files.readString(SELECTABLE_RECIPE_LOGIC);
        String guard = Files.readString(IDENTITY_GUARD);

        assertTrue(source.contains("RecipeTypePatternSearchHelper.collectActiveMarkedPatternRecipes(machine)"),
                "山海选择集逻辑只能收集 AE 已真实激活的槽，不得给所有未下单样板自动补料");
        assertTrue(source.contains("PatternSlotScopedRecipe.shadowKeyForScoped(scoped)"),
                "槽位副本存在时必须先建立 shadow key 集合，避免候选×槽位嵌套匹配");
        assertTrue(source.contains("shadowedRecipeKeys.contains(PatternSlotScopedRecipe.unscopedShadowKey(candidate))"),
                "未分槽候选应通过 O(1) shadow key 判断是否被槽位副本覆盖");
        assertFalse(source.contains("PatternSlotScopedRecipe.represents(scoped, candidate)"),
                "热点路径不得保留候选×槽位的嵌套 represents 扫描");
        assertFalse(guard.contains("if (!PatternSlotScopedRecipe.isScoped(recipe))"),
                "输入守卫不得全局拒绝未分槽候选，槽位解析失败时必须保留兼容回退");
    }

    @Test
    void activeOnlyCollectionNeverRunsFirstSparkTopUp() throws IOException {
        String source = Files.readString(SEARCH_HELPER);
        int methodStart = source.indexOf("public static Set<GTRecipe> collectActiveMarkedPatternRecipes");
        int methodEnd = source.indexOf("public static Set<GTRecipe> collectNativeVirtualRecipes", methodStart);

        assertTrue(methodStart >= 0 && methodEnd > methodStart,
                "必须提供只收集已激活槽位的独立入口");
        String method = source.substring(methodStart, methodEnd);
        assertFalse(method.contains("collectFirstSparkPatternRecipes"),
                "active-only 入口不得扫描未下单槽位或从 AE 主动拉取其原料");
    }

    @Test
    void activeScopedCandidatesAreMergedBeforeEmptyBaseCandidatesReturn() throws IOException {
        String source = Files.readString(SELECTABLE_RECIPE_LOGIC);
        int search = source.indexOf("Set<GTRecipe> recipes = searchSelectedRecipeTypes(machine);");
        int merge = source.indexOf("Set<GTRecipe> merged = mergeMarkedPatternRecipesCached(machine, recipes);", search);
        int emptyCheck = source.indexOf("if (merged.isEmpty())", merge);

        assertTrue(search >= 0 && merge > search && emptyCheck > merge,
                "普通候选为空时也必须先合并 active scoped 槽，次要输出样板才能首次进入缓存");
    }

    @Test
    void actualConsumptionAlsoRejectsFallbackToAnotherScopedSlot() throws IOException {
        String source = Files.readString(IDENTITY_GUARD_MIXIN);

        assertTrue(source.contains("PatternIdentityGuard.scopedSourceRejects(machine, recipe, trySlot)"),
                "实际扣料阶段也必须拒绝槽位作用域配方回退到其他样板槽");
        assertTrue(source.contains("if (!simulate)"),
                "实际扣料只执行轻量来源校验，不应重复完整样板指纹计算");
    }
}
