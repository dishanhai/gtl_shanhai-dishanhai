package com.dishanhai.gt_shanhai.common.item;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class StellarPatternMultiplierSourceTest {

    private static final Path MACHINE = Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/part",
            "RecipeTypePatternBufferPartMachine.java");
    private static final Path MIXIN = Path.of("src/main/java/com/dishanhai/gt_shanhai/mixin",
            "SuperPatternAutoMatchMixin.java");
    private static final Path HELPER = Path.of("src/main/java/com/dishanhai/gt_shanhai/common/item",
            "VirtualPatternEncodingHelper.java");
    private static final Path VIRTUAL_BUFFER_MIXIN = Path.of("src/main/java/com/dishanhai/gt_shanhai/mixin",
            "GTLCoreMEPatternBufferVirtualProviderMixin.java");

    @Test
    void stellarBufferPersistsMultiplierModeAndReadsPrimordialHost() throws IOException {
        String source = Files.readString(MACHINE);

        assertTrue(source.contains("@Persisted\n    private boolean outputMultiplierModeEnabled"));
        assertTrue(source.contains("@Persisted\n    private int patternOutputMultiplier"));
        assertTrue(source.contains("@DescSynced\n    @Persisted\n    private int cachedHostOutputMultiplier = 1"),
                "读宿主结果必须缓存并同步到客户端 UI，不能让显示端重新按客户端控制器状态算 1x");
        assertTrue(source.contains("host.getMountedOutputMultiplier()"));
        assertTrue(source.contains("controller instanceof PrimordialOmegaEngineModuleBase module"),
                "星律在原初模块结构里时，控制器是模块而不是万象主机，必须经模块读取宿主倍率");
        assertTrue(source.contains("module.getHostOutputMultiplier()"));
        assertTrue(source.contains("new OutputMultiplierConfigurator()"));
        assertTrue(source.contains("syncOutputMultiplierFromHost"));
        assertTrue(source.contains("syncOutputMultiplierFromPattern"));
    }

    @Test
    void stellarBufferAutoDetectsHostMultiplierEveryTwoSecondsOnlyOnChange() throws IOException {
        String source = Files.readString(MACHINE);
        String poll = extractBlock(source, "private void pollOutputMultiplierHostState()");

        assertTrue(source.contains("OUTPUT_MULTIPLIER_HOST_CHECK_TICKS = 40L"));
        assertTrue(source.contains("outputMultiplierHostSyncSubscription"));
        assertTrue(source.contains("outputMultiplierHostSyncSubscription = subscribeServerTick("));
        assertTrue(source.contains("this::pollOutputMultiplierHostState"));
        assertTrue(poll.contains("if (!outputMultiplierModeEnabled) return;"));
        assertTrue(poll.contains("getOffsetTimer() % OUTPUT_MULTIPLIER_HOST_CHECK_TICKS != 0L"));
        assertTrue(poll.contains("if (detected == lastDetectedHostOutputMultiplier) return;"),
                "宿主倍率未变化时必须零刷新");
        assertTrue(poll.contains("applyOutputMultiplierSettings(true, detected)"));
    }

    @Test
    void realPatternReturnIsRewrittenOnlyForStellarBuffer() throws IOException {
        String source = Files.readString(MIXIN);

        assertTrue(source.contains("priority = 900"),
                "山海注入必须晚于 GTLAdd 对 getRealPattern 的默认优先级 Overwrite");
        assertTrue(source.contains("method = \"getRealPattern\""));
        assertTrue(source.contains("at = @At(\"RETURN\")"));
        assertTrue(source.contains("self instanceof RecipeTypePatternBufferPartMachine stellar"));
        assertTrue(source.contains("stellar.gtShanhai$applyOutputMultiplier"));
    }

    @Test
    void rewriteStartsFromBaseRecipeInsteadOfMultiplyingCurrentOutputsAgain() throws IOException {
        String source = Files.readString(HELPER);

        assertTrue(source.contains("rewritePatternOutputMultiplier"));
        assertTrue(source.contains("createScaledRecipeOutputs(recipe, targetMultiplier)"));
        assertTrue(source.contains("rewriteCycleContainerInputs"));
        assertTrue(source.contains("rewritePatternOutputMultiplierDirect(processingPattern, level, requestedMultiplier)"),
                "反查 GTRecipe 失败时也要按神锻模式直接改写当前内部样板输出");
        assertTrue(source.contains("multiplyCycleContainerStacks(pattern.getSparseOutputs(), targetMultiplier, false)"));
        assertTrue(source.contains("PatternDetailsHelper.encodeProcessingPattern"));
    }

    @Test
    void stellarBufferCachesRewrittenMultiplierPatterns() throws IOException {
        String source = Files.readString(MACHINE);
        String method = extractBlock(source, "public IPatternDetails gtShanhai$applyOutputMultiplier(");

        assertTrue(source.contains("outputMultiplierPatternCache"),
                "神锻样板模式不得每次查询都重新查配方和 encode/decode 样板");
        assertTrue(method.contains("DShanhaiRecipeModifierAPI.getPatternCacheRevision()"),
                "倍率样板缓存必须随运行期配方修改失效");
        assertTrue(source.contains("patternDefinitionHash"),
                "缓存键必须按样板定义 NBT 复用，不能绑定每次 decode 得到的新对象身份");
        assertTrue(method.contains("makeOutputMultiplierPatternCacheKey(pattern, stack, recipeTypeId, multiplier, recipeRevision)"));
        assertTrue(method.contains("outputMultiplierPatternCache.get(cacheKey)"));
        assertTrue(method.contains("cached.matches(pattern, stack, recipeTypeId, multiplier, recipeRevision)"));
        assertTrue(method.contains("outputMultiplierPatternCache.put(cacheKey"));
        assertTrue(!source.contains("System.identityHashCode(pattern)"),
                "getRealPattern 可能重新 decode，同一栈不应因对象 identity 变化穿透缓存");
        assertTrue(source.contains("clearOutputMultiplierPatternCache();"),
                "倍率、样板或配方缓存变化时必须清掉重写样板缓存");
    }

    @Test
    void stellarBufferPublishesRewrittenPatternsToAeCraftingProvider() throws IOException {
        String source = Files.readString(MACHINE);
        String refreshOne = extractBlock(source, "private boolean refreshVisibleOutputMultiplierPattern(");
        String available = extractBlock(source, "public List<IPatternDetails> getAvailablePatterns()");

        assertTrue(source.contains("MEPatternBufferSlot2PatternAccessor"),
                "AE 下单读取 GTLCore slot2PatternMap，不能只改 getRealPattern 的临时返回值");
        assertTrue(refreshOne.contains("gtShanhai$getSlot2PatternMap()"));
        assertTrue(refreshOne.contains("createOutputMultiplierBasePattern(slot, patternStack)"),
                "刷新 AE 可见样板时必须从槽内原始样板重新构造 base pattern，避免反复乘已改写 pattern");
        assertTrue(refreshOne.contains("gtShanhai$applyOutputMultiplier(basePattern, patternStack)"));
        assertTrue(refreshOne.contains("slot2PatternMap.put(slot, effectivePattern)"),
                "星律倍率必须写回 AE 可见的内部样板 map，计划数量才会变成倍率后数量");
        assertTrue(refreshOne.contains("removeSlotFromGTRecipeCache(slot)"),
                "倍率变化后旧配方缓存必须失效，避免继续按 1x 样板执行");
        assertTrue(source.contains("reCalculatePatternSlotMap();"));
        assertTrue(source.contains("needPatternSync = true;"));
        assertTrue(available.contains("refreshVisibleOutputMultiplierPatterns(false)"),
                "AE 查询可用样板前要自校正一次，防止加载后先暴露旧 1x pattern");
    }

    @Test
    void availablePatternRefreshAvoidsRepeatedFullScansAndMapRebuilds() throws IOException {
        String source = Files.readString(MACHINE);
        String refreshOne = extractBlock(source, "private boolean refreshVisibleOutputMultiplierPattern(");
        String onChange = extractBlock(source, "protected void onPatternChange(int index)");
        String available = extractBlock(source, "public List<IPatternDetails> getAvailablePatterns()");

        assertTrue(source.contains("outputMultiplierPatternsRefreshNeeded"),
                "倍率样板全量校正必须有脏标记，不能每次 AE 查询都重解码全部槽位");
        assertTrue(available.contains("wildcardPatterns.isEmpty()) return patterns"),
                "无通配样板时必须直接复用父类不可变列表，避免重复复制");
        assertFalse(refreshOne.contains("reCalculatePatternSlotMap();"),
                "单槽倍率刷新不能重复执行父类已经完成的全局槽位映射重算");
        assertFalse(onChange.contains("refreshVisibleOutputMultiplierPattern(index, true)"),
                "父类 getRealPattern 已完成单槽倍率改写，子类不得再次解码同一张样板");
    }

    @Test
    void multiplierRefreshAvoidsQuadraticReflectiveRefundSlotLookup() throws IOException {
        String machine = Files.readString(MACHINE);
        String mixin = Files.readString(VIRTUAL_BUFFER_MIXIN);
        String wildcardRefund = extractBlock(machine, "private void refundWildcardSlots()");
        String refund = extractBlock(mixin, "private void gtShanhai$stripVirtualTargetsBeforeRefund(");
        String findSlot = extractBlock(mixin, "private Object gtShanhai$findSlotByInventories(");

        assertTrue(mixin.contains("IdentityHashMap"),
                "退款时应按库存对象身份缓存 InternalSlot");
        assertTrue(wildcardRefund.contains(
                        "access.gtShanhai$indexRefundSlot(slot, slot.getItemInventory(), slot.getFluidInventory())"),
                "星律已知当前通配符槽时必须零反射登记，避免首次退款扫描全部槽位");
        assertTrue(refund.contains("if (!gtShanhai$hasVirtualRefundState(itemInventory, fluidInventory)) return;"),
                "普通槽无虚拟状态时必须在定位 InternalSlot 前立即返回");
        assertTrue(findSlot.contains("gtShanhai$slotByItemInventory.get(itemInventory)"),
                "正常退款必须 O(1) 查询槽位");
        assertTrue(findSlot.contains("gtShanhai$rebuildSlotInventoryIndex()"),
                "其它 GTLCore 总成首次未命中必须自动构建身份索引");
        assertTrue(!findSlot.contains("for (int i = 0; i < getInternalSlotCount(); i++)"),
                "每次退款不得重新扫描全部 InternalSlot");
        assertTrue(mixin.contains("gtShanhai$getInternalSlotMethod"),
                "兜底索引构建使用的反射 Method 必须只解析一次并复用");
    }

    private static String extractBlock(String source, String declaration) {
        int start = source.indexOf(declaration);
        assertTrue(start >= 0, "缺少方法声明: " + declaration);
        int openBrace = source.indexOf('{', start);
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) {
                return source.substring(openBrace, i + 1);
            }
        }
        throw new AssertionError("方法体未闭合: " + declaration);
    }
}
