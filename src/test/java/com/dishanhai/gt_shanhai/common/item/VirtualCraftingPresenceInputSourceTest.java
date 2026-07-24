package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualCraftingPresenceInputSourceTest {

    private static final Path MIXIN_CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");
    private static final Path PRESENCE_EXTRACTOR = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "VirtualCraftingPatternInputExtractor.java");
    private static final Path CRAFTING_HELPER_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "CraftingCpuHelperVirtualPatternInputsMixin.java");
    private static final Path CRAFTING_INITIAL_EXTRACTOR = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "VirtualCraftingInitialItemExtractor.java");
    private static final Path PRESENCE_STATE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "VirtualCraftingPresenceState.java");
    private static final Path CRAFTING_CPU_LOGIC_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "CraftingCpuLogicVirtualPresenceMixin.java");
    private static final Path QUANTUM_CPU_LOGIC = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "ae2", "quantum", "QuantumCraftingCPULogic.java");
    private static final Path PATTERN_SLOT_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "GTLCorePatternInternalSlotVirtualProviderMixin.java");
    private static final Path PATTERN_MACHINE_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "GTLCoreMEPatternBufferVirtualProviderMixin.java");
    private static final Path RECIPE_TYPE_PATTERN_MACHINE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "part", "RecipeTypePatternBufferPartMachine.java");
    private static final Path RECIPE_TYPE_PATTERN_SEARCH = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "RecipeTypePatternSearchHelper.java");
    private static final Path PATTERN_MACHINE_ACCESS = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "VirtualPatternBufferMachineAccess.java");
    private static final Path AE_UTILS_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "AeUtilsVirtualPatternInputsMixin.java");
    private static final Path OLD_INITIAL_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "CraftingCpuHelperVirtualInitialItemsMixin.java");
    private static final Path QUANTUM_SERVICE_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "QuantumCraftingServiceMixin.java");
    private static final Path CRAFTING_TREE_PROCESS_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "CraftingTreeProcessVirtualPresenceMixin.java");
    private static final Path CRAFTING_TREE_NODE_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "CraftingTreeNodeVirtualPresenceMixin.java");
    private static final Path ENCODING_HELPER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "VirtualPatternEncodingHelper.java");
    private static final Path PROCESSING_PATTERN_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "AEProcessingPatternVirtualProviderMixin.java");

    @Test
    void executionChecksPresenceWithoutConsumingIt() throws IOException {
        assertTrue(Files.exists(PRESENCE_EXTRACTOR), "需要独立的虚拟在场输入抽取器");
        String extractor = Files.readString(PRESENCE_EXTRACTOR);

        assertTrue(extractor.contains("VirtualPatternEncodingHelper.isPresenceInput(input)"));
        assertTrue(extractor.contains("Actionable.SIMULATE"));
        assertTrue(extractor.contains("inputHolder[index] = new KeyCounter()"));
        assertFalse(extractor.contains("expectedContainerItems.add(presenceKey"),
                "目标未离开 CPU，不得等待机器返还同一目标");
    }

    @Test
    void partialBulkExtractionIsRecordedBeforeRollback() throws IOException {
        String extractor = Files.readString(PRESENCE_EXTRACTOR);
        int recordExtracted = extractor.indexOf("inputHolder[index].add(template.what(), extracted);");
        int rejectMismatch = extractor.indexOf("if (extracted != amount)");

        assertTrue(recordExtracted >= 0 && recordExtracted < rejectMismatch,
                "批量真实抽取异常时必须先登记已抽数量，回滚才能完整返还材料");
    }

    @Test
    void nativeAndBulkExtractionSharePresenceSemantics() throws IOException {
        assertTrue(Files.exists(CRAFTING_HELPER_MIXIN), "AE 原生 CPU 抽取需要接入在场输入抽取器");
        assertTrue(Files.exists(AE_UTILS_MIXIN), "GTLCore 批量抽取需要接入同一在场输入抽取器");
        String config = Files.readString(MIXIN_CONFIG);

        assertTrue(config.contains("CraftingCpuHelperVirtualPatternInputsMixin"));
        assertTrue(config.contains("AeUtilsVirtualPatternInputsMixin"));
    }

    @Test
    void submissionNoLongerBlocksMissingVirtualTargets() throws IOException {
        String config = Files.readString(MIXIN_CONFIG);
        String quantumService = Files.readString(QUANTUM_SERVICE_MIXIN);

        assertFalse(Files.exists(OLD_INITIAL_MIXIN));
        assertFalse(config.contains("CraftingCpuHelperVirtualInitialItemsMixin"));
        assertTrue(Files.exists(CRAFTING_INITIAL_EXTRACTOR),
                "允许递归补缺口仍需要初始抽取分流，但只能跳过虚拟份额而不能阻断订单");
        assertFalse(quantumService.contains("VirtualProviderSoftLocks.tryReserve"),
                "目标缺失必须交给合成树补齐，提交阶段不得提前拦截");
    }

    @Test
    void initialExtractionSeparatesVirtualPresenceFromRealMaterials() throws IOException {
        assertTrue(Files.exists(CRAFTING_INITIAL_EXTRACTOR), "需要在 AE 初始抽取层拆分虚拟份额");
        String extractor = Files.readString(CRAFTING_INITIAL_EXTRACTOR);
        String mixin = Files.readString(CRAFTING_HELPER_MIXIN);

        assertTrue(mixin.contains("tryExtractInitialItems"), "普通、批量和量子 CPU 必须共用 AE 初始抽取入口");
        assertTrue(extractor.contains("VirtualPatternEncodingHelper.collectPresenceRequirements(plan)"));
        assertTrue(extractor.contains("VirtualPatternEncodingHelper.collectConsumableRequirements(plan)"),
                "必须从 patternTimes 单独计算普通耗材上限，不能从 usedItems 再减虚拟份额");
        assertTrue(extractor.contains("storage.extract(key, needed, Actionable.SIMULATE, src)"),
                "虚拟份额只允许检查网络存在性");
        assertTrue(extractor.contains("realInitialAmount(usedAmount, consumableRequirements.getLong(what))"),
                "同 key 合并后，usedItems 中属于普通耗材上限内的数量必须全部真实抽取");
        assertFalse(extractor.contains("storage.extract(what, virtualAmount, Actionable.MODULATE, src)"),
                "虚拟份额不得从网络转移所有权到 CPU");
    }

    @Test
    void sameKeyPresenceMustNotReduceOrdinaryInitialMaterialsTwice() {
        assertEquals(3L, VirtualCraftingInitialItemExtractor.realInitialAmount(3L, 300L),
                "3 个已合并的 usedItems 仍全部属于普通耗材，不能因另有 1 个同 key presence 再减成 2");
        assertEquals(3L, VirtualCraftingInitialItemExtractor.realInitialAmount(4L, 3L),
                "usedItems 明确包含额外 presence 时，只抽取普通耗材上限");
        assertEquals(0L, VirtualCraftingInitialItemExtractor.realInitialAmount(1L, 0L),
                "纯 presence 输入不得转移到 CPU");
    }

    @Test
    void completionAndCancellationReturnOnlyRecursivelyCraftedPresence() throws IOException {
        assertTrue(Files.exists(PRESENCE_STATE), "需要按 CPU 任务记录网络中的非所有权虚拟目标");
        assertTrue(Files.exists(CRAFTING_CPU_LOGIC_MIXIN), "原生 AE CPU 需要持久化非所有权在场记录");
        String state = Files.readString(PRESENCE_STATE);
        String nativeLogic = Files.readString(CRAFTING_CPU_LOGIC_MIXIN);
        String quantumLogic = Files.readString(QUANTUM_CPU_LOGIC);

        assertFalse(state.contains("discardOwnedPresence"),
                "递归补出的目标是真实合成产物，完成或取消后必须由 CPU 原生 storeItems 返还");
        assertFalse(nativeLogic.contains("discardOwnedPresence"));
        assertFalse(quantumLogic.contains("discardOwnedPresence"));
        assertTrue(state.contains("inventory.extract(key, remaining, Actionable.SIMULATE)"),
                "递归补出的真实目标进入 CPU 后只检查在场，不在父样板执行时消费");
    }

    @Test
    void patternBufferPersistsAndRebuildsVirtualIdentityForItemsAndFluids() throws IOException {
        String slotMixin = Files.readString(PATTERN_SLOT_MIXIN);
        String machineMixin = Files.readString(PATTERN_MACHINE_MIXIN);
        String patternMachine = Files.readString(RECIPE_TYPE_PATTERN_MACHINE);

        assertTrue(slotMixin.contains("writeVirtualTargets(itemInventory, fluidInventory"),
                "存档必须保留物品和流体虚拟身份，不能只保存普通库存数量");
        assertTrue(slotMixin.contains("readVirtualTargets(itemInventory, fluidInventory"),
                "读档后必须恢复虚拟身份，退换时才能剥离而不返还");
        assertTrue(machineMixin.contains("restoreVirtualTargetsFromPatterns"),
                "旧存档缺少标记时，应从本槽样板的 PresenceInput 恢复身份");
        assertTrue(machineMixin.contains("access.gtShanhai$hasVirtualTarget"),
                "已有精确 NBT 标记时不得被旧存档迁移逻辑放大");
        assertTrue(machineMixin.contains("Long.MAX_VALUE"),
                "旧实现按批量次数放大的纯虚拟残留必须整项认回虚拟身份");
        assertTrue(patternMachine.contains("gtShanhai$restoreVirtualTargetsFromPatterns(getAvailablePatterns())"),
                "旧存档恢复必须从星律样板总成实际存在的 onLoad 生命周期触发");
        assertFalse(machineMixin.contains("@Shadow\n    public abstract List<IPatternDetails> getAvailablePatterns();"),
                "GTLCore 基类没有 getAvailablePatterns，不能声明错误的 shadow");
    }

    @Test
    void automaticStellarTopUpNeverExtractsReusableInputsAsRealStock() throws IOException {
        String helper = Files.readString(ENCODING_HELPER);
        String search = Files.readString(RECIPE_TYPE_PATTERN_SEARCH);
        String machineAccess = Files.readString(PATTERN_MACHINE_ACCESS);
        String machineMixin = Files.readString(PATTERN_MACHINE_MIXIN);
        String slotMixin = Files.readString(PATTERN_SLOT_MIXIN);
        int topUpStart = search.indexOf("private static void topUpVirtualSupply");
        int topUpEnd = search.indexOf("private static boolean hasConsumableStock", topUpStart);
        String topUp = search.substring(topUpStart, topUpEnd);

        assertTrue(helper.contains("public static GenericStack resolveVirtualTargetForPatternInput(GenericStack input, GTRecipe recipe)"),
                "自动补料必须用权威配方解析虚拟目标，不能直接处理供应器外壳");
        assertTrue(search.contains("resolveVirtualTargetForPatternInput(in, recipe)"),
                "星律已有权威配方，虚拟目标解析不得重新全局反推并串配方类型");
        assertTrue(search.contains("gtShanhai$addVirtualTargetToSlot(slot, presenceTarget.what()"),
                "不消耗输入应写成一份带身份的虚拟在场目标");
        assertFalse(search.contains("if (notConsumable[i] && getInternalAmount(internalSlot, in.what()) > 0) continue;"),
                "旧逻辑会把不消耗输入按 achievable 从 AE 真实抽入并永久残留");
        int presenceBranch = topUp.indexOf("GenericStack presenceTarget = presenceTargets[i];");
        int leavePresenceBranch = topUp.indexOf("continue;", presenceBranch);
        int realExtraction = topUp.indexOf("Actionable.MODULATE", presenceBranch);
        assertTrue(presenceBranch >= 0 && leavePresenceBranch > presenceBranch && realExtraction > leavePresenceBranch,
                "虚拟在场分支必须在任何真实 AE 抽取前结束");
        assertTrue(machineAccess.contains("gtShanhai$addVirtualTargetToSlot"));
        assertTrue(machineMixin.contains("public boolean gtShanhai$addVirtualTargetToSlot"));
        assertTrue(slotMixin.contains("gtShanhai$clearVirtualTargetsIfDepleted"),
                "旧存档只剩虚拟目标时必须允许加载阶段立即清理");
        assertTrue(slotMixin.contains("VirtualPatternBufferSlotState.clearVirtualCircuit(itemInventory)"),
                "清理虚拟电路时必须同步清掉电路身份和缓存");
        assertTrue(slotMixin.contains("IntCircuitBehaviour.isIntegratedCircuit(key.toStack())"),
                "旧存档没有虚拟电路标记时也必须从虚拟目标识别并清理缓存");
        assertTrue(machineMixin.contains("access.gtShanhai$clearVirtualTargetsIfDepleted()"));
    }

    @Test
    void externalPresenceRecordSurvivesRestartWithoutOwningAnItem() throws IOException {
        String state = Files.readString(PRESENCE_STATE);
        String nativeLogic = Files.readString(CRAFTING_CPU_LOGIC_MIXIN);
        String quantumLogic = Files.readString(QUANTUM_CPU_LOGIC);

        assertTrue(state.contains("writeToNBT"));
        assertTrue(state.contains("readFromNBT"));
        assertTrue(nativeLogic.contains("method = \"writeToNBT\""));
        assertTrue(nativeLogic.contains("method = \"readFromNBT\""));
        assertTrue(quantumLogic.contains("VirtualCraftingPresenceState.writeToNBT(inventory, data)"));
        assertTrue(quantumLogic.contains("VirtualCraftingPresenceState.readFromNBT(inventory, data)"));
    }

    @Test
    void planningDoesNotExpandReusablePresenceInputsOnePatternAtATime() throws IOException {
        String config = Files.readString(MIXIN_CONFIG);

        assertTrue(Files.exists(CRAFTING_TREE_PROCESS_MIXIN),
                "需要在 AE2 合成树层解除虚拟返还项触发的逐次计算限制");
        assertTrue(Files.exists(CRAFTING_TREE_NODE_MIXIN),
                "需要统一修正 AE2 与 GTLCore 四种合成树请求入口");
        assertTrue(config.contains("CraftingTreeProcessVirtualPresenceMixin"));
        assertTrue(config.contains("CraftingTreeNodeVirtualPresenceMixin"));

        String processMixin = Files.readString(CRAFTING_TREE_PROCESS_MIXIN);
        String nodeMixin = Files.readString(CRAFTING_TREE_NODE_MIXIN);
        assertTrue(processMixin.contains("this.limitQty = requiresPerPatternLimit"),
                "仅 PresenceInput 的返还项不得把整张样板强制为 times=1");
        assertTrue(nodeMixin.contains("\"request\", \"adaptiveRequest\", \"fastRequest\", \"ultraFastRequest\""),
                "AE2 LEGACY 与 GTLCore 三种快速计算模式必须使用同一语义");
        assertTrue(nodeMixin.contains("this.parentInput.getMultiplier()"),
                "批量规划时虚拟在场输入只请求自身需求量，不得乘下单次数");
        assertTrue(nodeMixin.contains("priority = 1500"),
                "节点修正必须晚于 GTLCore 默认优先级 Mixin 才能覆盖其新增方法");
        assertFalse(processMixin.contains("java.lang.reflect") || nodeMixin.contains("java.lang.reflect"),
                "下单热点路径不得使用反射");
    }

    @Test
    void runtimePresenceDetectionDoesNotReanalyzePatternRecipes() throws IOException {
        String helper = Files.readString(ENCODING_HELPER);
        int methodStart = helper.indexOf("public static boolean containsVirtualProviderPattern");
        int methodEnd = helper.indexOf("public static boolean isPresenceInput", methodStart);
        String method = helper.substring(methodStart, methodEnd);

        assertTrue(method.contains("details.getInputs()"),
                "样板已经生成 PresenceInput 后应直接扫描输入类型");
        assertFalse(method.contains("getSparseInputs"),
                "运行期检查不得重复构造样板分析键并查询 GT 配方");
    }

    @Test
    void processingPatternsCacheQuantityIndependentPlanningInputs() throws IOException {
        String mixin = Files.readString(PROCESSING_PATTERN_MIXIN);

        assertTrue(mixin.contains("gtShanhai$cachedPlanningInputs"),
                "同一张处理样板应复用已解析的 PresenceInput 数组");
        assertTrue(mixin.contains("gtShanhai$cachedPlanningRevision"),
                "结构缓存必须绑定配方 revision");
        assertTrue(mixin.contains("DShanhaiRecipeModifierAPI.getPatternCacheRevision()"),
                "配方修改后必须重建样板结构缓存");
        assertTrue(mixin.contains("gtShanhai$getPlanningInputs()"),
                "getInputs 与推送路径必须复用同一个结构缓存入口");
        assertFalse(mixin.contains("requestedAmount"),
                "样板结构缓存不得绑定具体下单数量");
    }
}
