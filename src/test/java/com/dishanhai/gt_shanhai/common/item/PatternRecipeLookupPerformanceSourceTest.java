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
    void avoidsPerCandidateDebugFileLoggingInPatternLookupHotPath() throws IOException {
        String patternHelper = Files.readString(PATTERN_HELPER);
        String modifierApi = Files.readString(MODIFIER_API);

        assertFalse(patternHelper.contains("LOG.debug(\"[inferRecipe] inputs="),
                "样板反推不得为每次匹配拼接并写入调试详情");
        assertFalse(modifierApi.contains("LOG.debug(\"[StripByType]"),
                "Branch 遍历不得为每个剥离候选写调试日志");
        assertFalse(modifierApi.contains("LOG.debug(\"[ReplaceByType]"),
                "Branch 遍历不得为每个替换候选写调试日志");
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
