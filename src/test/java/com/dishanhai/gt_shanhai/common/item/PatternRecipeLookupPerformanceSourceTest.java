package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternRecipeLookupPerformanceSourceTest {

    private static final Path SEARCH_HELPER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "RecipeTypePatternSearchHelper.java");
    private static final Path PATTERN_HELPER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "PatternRecipeTypeHelper.java");
    private static final Path VIRTUAL_PATTERN_HELPER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "VirtualPatternEncodingHelper.java");
    private static final Path GTM_PATTERN_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "Ae2GtmProcessingPatternMixin.java");
    private static final Path SHANHAI_PATTERN_ENCODER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "ShanhaiPatternEncoder.java");
    private static final Path MODIFIER_API = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "api", "DShanhaiRecipeModifierAPI.java");
    private static final Path PATTERN_BUFFER_MACHINE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "part", "RecipeTypePatternBufferPartMachine.java");
    private static final Path SELECTABLE_RECIPE_LOGIC = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "api", "machine", "SelectableRecipeTypeSetRecipeLogic.java");
    private static final Path PRIMORDIAL_MODULE_LOGIC = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "primordial", "PrimordialModuleRecipeLogic.java");
    private static final Path MIXIN_CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");

    @Test
    void cachesStellarPatternRecipeInferenceUntilPatternRecipeOrInventoryChanges() throws IOException {
        String source = Files.readString(SEARCH_HELPER);

        assertTrue(source.contains("getMarkedRecipeCached(\n                        buffer, access, slot, inferenceInputs, inferenceInventoryFingerprint)"),
                "星律 active/首配路径应共用每槽样板配方缓存");
        assertTrue(source.contains("DShanhaiRecipeModifierAPI.getPatternCacheRevision()"),
                "运行期配方规则变化后必须失效样板推断缓存");
        assertTrue(source.contains("inferenceInventoryFingerprint"),
                "库存拉取或共享催化仓变化后必须失效样板推断缓存");
        assertEquals(1, occurrences(source, "access.gtShanhai$getPatternInferenceInputs()"),
                "一次星律候选收集只能快照一次库存上下文，不能逐槽重复分配");
        assertEquals(1, occurrences(source, "access.gtShanhai$getPatternRecipe(slot, inferenceInputs)"),
                "昂贵的样板反推只能保留在缓存未命中的加载路径");
    }

    @Test
    void activeMarkedRecipeMergeOnlyCachesWithinTheSameServerTick() throws IOException {
        String source = Files.readString(SELECTABLE_RECIPE_LOGIC);
        String searchHelper = Files.readString(SEARCH_HELPER);

        assertTrue(source.contains("cachedMarkedRecipeTick"));
        assertTrue(source.contains("if (tick == cachedMarkedRecipeTick)"));
        assertTrue(source.contains("RecipeTypePatternSearchHelper.collectActiveMarkedPatternRecipes(machine)"));
        assertTrue(searchHelper.contains("ACTIVE_MARKED_RECIPE_CACHE"),
                "同一机器同一 tick 的 active-only 样板扫描结果必须在 helper 层复用，避免多模块重复扫部件");
        assertTrue(searchHelper.contains("if (!includeFirstSpark && scannedRecipeHandlers)"),
                "active-only 路径通过 MERecipeHandler 扫过后不得再重复扫描整台多方块部件");
        assertTrue(searchHelper.indexOf("if (!includeFirstSpark && (activeSlots == null || activeSlots.length == 0))")
                        < searchHelper.indexOf("GenericStack[] inferenceInputs = access.gtShanhai$getPatternInferenceInputs()"),
                "active-only 候选收集没有 active 槽时必须提前返回，不能每 tick 快照推断输入");
        assertFalse(source.contains("tick - cachedMarkedRecipeTick"),
                "active scoped 槽状态只能同 tick 复用，不能跨 tick 缓存导致 AE 下单状态延迟");
    }

    @Test
    void primordialModulesUseALongerRecipeSetCacheWithoutCachingParallelData() throws IOException {
        String selectable = Files.readString(SELECTABLE_RECIPE_LOGIC);
        String primordial = Files.readString(PRIMORDIAL_MODULE_LOGIC);

        assertTrue(selectable.contains("cachedMergedLookupTick"));
        assertTrue(selectable.contains("if (base == cachedMergedLookupBase && tick == cachedMergedLookupTick"),
                "同一 tick 内重复 lookup 不应重复构造 active scoped 合并集合");
        assertTrue(selectable.contains("protected long getMaxLookupCacheTicks()"));
        assertTrue(selectable.contains("Math.min(getMaxLookupCacheTicks(), entry.cacheWindow * 2)"));
        assertTrue(primordial.contains("private static final long ACTIVE_LOOKUP_CACHE_TICKS = 200L;"));
        assertTrue(primordial.contains("protected long getMaxLookupCacheTicks()"));
        assertTrue(primordial.contains("return ACTIVE_LOOKUP_CACHE_TICKS;"));
        assertTrue(selectable.contains("protected boolean shouldInvalidateLookupCacheWhenNoRunnableRecipe()"));
        assertTrue(primordial.contains("protected boolean shouldInvalidateLookupCacheWhenNoRunnableRecipe()"));
        assertTrue(primordial.contains("return false;"));
        assertTrue(primordial.contains("Set<GTRecipe> recipes = lookupRecipeIterator();"));
        assertTrue(primordial.contains("findMaxMatchableScaledRecipe(recipe, totalParallel)"),
                "并行和输入输出容量仍必须每轮实时计算，不能随 recipe 集合缓存一起缓存");
        assertFalse(primordial.contains("base.equals(cachedModuleConditionSource)"),
                "模块等级过滤缓存不得每 tick 对大候选 Set 做 equals；应由基类合并阶段减少重复集合构造");
    }

    @Test
    void avoidsPerCandidateDebugFileLoggingInPatternLookupHotPath() throws IOException {
        String patternHelper = Files.readString(PATTERN_HELPER);
        String modifierApi = Files.readString(MODIFIER_API);
        String selectable = Files.readString(SELECTABLE_RECIPE_LOGIC);

        assertFalse(patternHelper.contains("LOG.debug(\"[inferRecipe] inputs="),
                "样板反推不得为每次匹配拼接并写入调试详情");
        assertFalse(modifierApi.contains("LOG.debug(\"[StripByType]"),
                "Branch 遍历不得为每个剥离候选写调试日志");
        assertFalse(modifierApi.contains("LOG.debug(\"[ReplaceByType]"),
                "Branch 遍历不得为每个替换候选写调试日志");
        assertTrue(selectable.contains("if (QuantumDiagnostics.ENABLED"),
                "配方类型搜索热路径诊断必须在调用点用 static final 开关包裹，避免 detail 字符串先拼接");
    }

    @Test
    void doesNotRegisterReflectivePatternBufferActiveSlotOptimization() throws IOException {
        String config = Files.readString(MIXIN_CONFIG);

        assertFalse(config.contains("MEPatternBufferActiveSlotCacheMixin"),
                "不得重新注册逐槽反射 isActive 的样板总成优化");
    }

    @Test
    void stellarPatternBufferCollectsActiveUncachedSlotsWithoutReflectionOrStreams() throws IOException {
        String source = Files.readString(PATTERN_BUFFER_MACHINE);

        assertTrue(source.contains("protected int[] getActiveAndUnCachedSlots()"),
                "星律样板总成必须直接覆写活跃未缓存槽位收集");
        assertTrue(source.contains("MEPatternBufferPartMachineBase.InternalSlot internalSlot = getInternalSlot(slotIndex)"),
                "热点路径必须静态访问 InternalSlot，不能反射取槽位");
        assertTrue(source.contains("internalSlot.isActive() && !hasRecipeCacheInSlot(slotIndex)"),
                "每次调用仍须实时保留 GTLCore 的活跃与配方缓存判定");
        assertTrue(source.contains("activeUncachedSlotsScratch"),
                "热点路径必须复用收集缓冲区");
        assertTrue(source.contains("NO_ACTIVE_UNCACHED_SLOTS"),
                "空结果不得每次分配新数组");
        assertFalse(source.contains("IntStream"),
                "星律样板总成热点路径不得重新使用 IntStream");
        assertFalse(source.contains("getDeclaredMethod(\"isActive\")"),
                "热点路径不得重新引入 isActive 反射");
    }

    @Test
    void recipeTypeMetadataReadAvoidsPerCallCollectionsAndDuplicateReads() throws IOException {
        String source = Files.readString(PATTERN_HELPER);

        assertFalse(source.contains("new java.util.LinkedHashSet<>()"),
                "读取 GTLCore 配方类型字段不得为每个样板分配去重集合");
        assertTrue(source.contains("peekRecipe(stack, level, existing)"),
                "peekRecipeTypeId 必须复用已读取的类型字段，不能再次解析 NBT");
        assertFalse(source.contains("System.nanoTime()"),
                "配方类型热点读取不得为每个样板执行纳秒计时");
    }

    @Test
    void virtualPatternLookupCachesMissesAndUsesIndexedTypeCandidates() throws IOException {
        String source = Files.readString(VIRTUAL_PATTERN_HELPER);

        assertTrue(source.contains("matchingRecipeResolved"),
                "无匹配配方的结果也必须缓存，避免每次打开样板总成重复反查");
        assertFalse(source.contains("List<GTRecipe> matches = new ArrayList<>()"),
                "无匹配和单匹配路径不得为每次反查创建结果列表");
        assertTrue(source.contains("index.candidates(outputBag)"),
                "指定配方类型查询必须先按完整输出索引缩小候选集");
        assertTrue(source.indexOf("index.candidates(outputBag)")
                        < source.indexOf("index.scaledCandidates(outputBag)"),
                "倍率形状索引只能作为完整输出索引未命中后的回退");
        assertTrue(source.contains("private static RecipeOutputIndexes recipeOutputIndexes"),
                "全部配方与不消耗配方索引必须在同一次注册表扫描中构建");
        assertFalse(source.contains("LOG.info(\"[VirtualPatternEncoding]"),
                "批量样板编码不得逐样板写 INFO 日志");
        assertFalse(source.contains("return Objects.hash(inputs, outputs, revision)"),
                "样板缓存键哈希不得分配 Objects.hash 变参数组");
    }

    @Test
    void knownGtRecipeEncodingBypassesReverseLookupAndInfoLogging() throws IOException {
        String virtualHelper = Files.readString(VIRTUAL_PATTERN_HELPER);
        String patternHelper = Files.readString(PATTERN_HELPER);
        String mixin = Files.readString(GTM_PATTERN_MIXIN);
        String encoder = Files.readString(SHANHAI_PATTERN_ENCODER);

        assertTrue(mixin.contains("ShanhaiPatternEncoder.encode(recipe, player, true)"),
                "GTLCore 样板编码 Mixin 必须委托给山海共享编码器");
        assertTrue(encoder.contains("pushAuthoritativeEncodingRecipe(recipe)")
                        && encoder.contains("finally")
                        && encoder.contains("popAuthoritativeEncodingRecipe()"),
                "已知 GT 配方的编码上下文必须用 finally 清理");
        assertTrue(virtualHelper.contains("currentAuthoritativeEncodingRecipe() != null"),
                "已预先构造虚拟输入的 GT 配方不得再次做通用包裹反查");
        assertTrue(patternHelper.contains("writeAuthoritativeRecipeType(stack, authoritativeRecipe)"),
                "已知 GT 配方必须直接写入权威类型字段");
        assertFalse(encoder.contains("LOG.info("),
                "批量 GT 配方编码不得逐原料写 INFO 日志");
    }

    @Test
    void productionSourcesDoNotContainTemporaryStellarDiagnostics() throws IOException {
        StringBuilder sources = new StringBuilder();
        try (Stream<Path> paths = Files.walk(Path.of("src", "main", "java"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                sources.append(Files.readString(path));
            }
        }
        assertFalse(sources.toString().contains("stellarPattern("),
                "实机排查完成后不得保留星律全字段诊断调用");
        assertFalse(sources.toString().contains("[StellarPatternDiag]"),
                "正式构建不得继续输出临时星律诊断前缀");
    }

    private static int occurrences(String source, String target) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(target, from)) >= 0) {
            count++;
            from += target.length();
        }
        return count;
    }
}
