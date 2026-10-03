package com.dishanhai.gt_shanhai.common.item;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JeiPatternQuickEncodeServiceTest {

    private static final Path SERVICE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "JeiPatternQuickEncodeService.java");

    @Test
    void batchEncodingKeepsExactRecipeIdentityAndVirtualProviderSemantics() throws Exception {
        String source = Files.readString(SERVICE);

        assertTrue(source.contains("recipeType.getLookup().getLookup().getRecipes(true)"));
        assertTrue(source.contains("recipe.recipeType == recipeType"));
        assertTrue(source.contains("unique.putIfAbsent(recipe.id, recipe)"));
        assertTrue(source.contains("Comparator.comparing(recipe -> recipe.id.toString())"));
        assertTrue(source.contains("ShanhaiPatternEncoder.encode(recipe, player, menu, true)"));
        String encoder = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "common", "item", "ShanhaiPatternEncoder.java"));
        assertTrue(encoder.contains("PatternQuickUploadMetadata.writeRecipeTypeId"),
                "GTLCore 搜尋星律目標依賴其公開的配方類型 metadata");
        assertTrue(source.contains("PatternDetailsHelper.decodePattern(pattern, player.level())"));
        assertTrue(source.contains("PatternRecipeTypeHelper.readRecipeTypeId(pattern)"));
        assertTrue(source.contains("PatternRecipeTypeHelper.areRecipeTypeIdsEquivalent"));
        assertTrue(source.contains("ResourceLocation representativeTypeId = recipeTypeIds.iterator().next()"),
                "缓存目标必须使用有效主机配方类型，GTLCore Target 不接受 null recipeTypeId");
    }

    @Test
    void uploadTargetsOnlyCompatibleStellarAssembliesAndRollsBackAtomically() throws Exception {
        String source = Files.readString(SERVICE);

        assertTrue(source.contains("grid.getMachineClasses()"),
                "目标检索必须遍历当前 ME Grid 的机器类别，而不能只读当前活动配方");
        assertTrue(source.contains("grid.getActiveMachines"),
                "目标检索必须覆盖网络中所有 active 星律样板总成");
        assertTrue(source.contains("machine instanceof RecipeTypePatternBufferPartMachine stellar"));
        assertTrue(source.contains("WildcardPatternRecipeTypeBinding.collectHostRecipeTypes"));
        assertTrue(source.contains("type.registryName"),
                "星律主机的全部配方类型必须逐一参与匹配");
        assertTrue(source.contains("for (int i = uploaded.size() - 1; i >= 0; i--)"));
        assertTrue(source.indexOf("uploaded.add(new UploadedPattern")
                < source.indexOf("if (!consumePatternSource(menu, committedSource))"));
        assertTrue(source.contains("insertIntoStellarTarget"),
                "星律写入必须走山海侧直接槽位实现，避免被外部 GTLCore mixin 禁用");
        assertTrue(source.contains("removeFromTarget"),
                "直接写入的样板必须能按槽位原子回滚");
        assertTrue(source.contains("PatternQuickUploadService.findTargets"),
                "自有快取未命中时必须用 GTLCore 公开搜索补齐漏掉的已加载多方块目标");
        assertTrue(source.contains("findPublicStellarTargets"),
                "公开搜索结果只能作为星律目标发现，不能替代山海侧直接写入");
        String publicSearch = source.substring(
                source.indexOf("private static List<PatternQuickUploadService.Target> findPublicStellarTargets"),
                source.indexOf("private static PatternQuickUploadService.UploadResult safeInsertIntoTarget"));
        assertTrue(publicSearch.contains("filterToStellarTarget"));
        assertFalse(publicSearch.contains("candidate.recipeTypeId() == null"),
                "GTLCore 提供的单一类型不能决定多配方主机是否兼容");
        assertTrue(source.contains("Actionable.SIMULATE"));
        assertTrue(source.contains("Actionable.MODULATE"));
        assertTrue(source.contains("boolean useSda = !useInventoryFallback"));
        assertTrue(source.contains("boolean useInventoryFallback = !sdaPatterns.isEmpty()"));
        assertTrue(source.contains("sdaPatterns.size() < MIN_SDA_FALLBACK_PATTERNS"));
        assertTrue(source.contains("givePatterns(player, sdaPatterns)"));
        assertTrue(source.contains("PatternSource committedSource = limitPatternSource(source, committedCount)"));
        assertTrue(source.contains("int skippedCount = useInventoryFallback || useSda ? 0 : sdaPatterns.size()"));
        String routing = source.substring(source.indexOf("for (ItemStack pattern : patterns) {"),
                source.indexOf("boolean useInventoryFallback"));
        assertTrue(routing.contains("while (result == null)"),
                "每張樣板要嘗試所有候選星律，不能只嘗試前兩個就回退");
        assertTrue(routing.contains("removeTarget(availableTargets, currentTarget)"));
    }

    @Test
    void expandsCompositeAndRuntimeMultiRecipeTypesAndChargesSda() throws Exception {
        String source = Files.readString(SERVICE);
        String binding = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "common", "item", "WildcardPatternRecipeTypeBinding.java"));

        assertTrue(binding.contains("getMultiRecipeType"));
        assertTrue(binding.contains("getTypeList"));
        assertTrue(binding.contains("collectMachineRecipeTypes(types, controller.self())"),
                "星律必須讀取直接綁定控制器自身的完整配方類型");
        assertTrue(binding.contains("if (controller == null || !controller.isFormed()) continue;"),
                "已失效的控制器不能提供可寫入配方類型");
        assertTrue(binding.contains("getRecipeTypeNameSet"),
                "原初模块公开的完整类型名称集合必须直接纳入匹配");
        assertTrue(source.contains("MIN_SDA_FALLBACK_PATTERNS = 20"));
        assertTrue(source.contains("IAEItemPowerStorage"));
        assertTrue(source.contains("getAEMaxPower(sda)"));
        assertTrue(source.contains("injectAEPower(sda, maxPower, Actionable.MODULATE)"));
        assertTrue(source.contains("direct_partial_success"));
    }

    @Test
    void mountedModuleTypesDoNotBecomeEngineStellarTypes() throws Exception {
        String binding = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "common", "item", "WildcardPatternRecipeTypeBinding.java"));
        String registration = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "common", "machine", "DShanhaiMachines.java"));
        String engine = registration.substring(registration.indexOf("PRIMORDIAL_OMEGA_ENGINE ="),
                registration.indexOf("PRIMORDIAL_OMEGA_ENGINE.setTooltipBuilder"));
        String furnace = registration.substring(registration.indexOf("PRIMORDIAL_ETERNAL_SMELTING_FURNACE ="),
                registration.indexOf("PRIMORDIAL_ETERNAL_SMELTING_FURNACE.setTooltipBuilder"));

        assertFalse(engine.contains("getSTELLAR_LGNITION()"));
        assertTrue(furnace.contains("getSTELLAR_LGNITION()"));
        assertFalse(binding.contains("getModuleSet()"),
                "引擎星律不能繼承掛載在引擎上的永恆熔煉爐配方類型");
        assertFalse(binding.contains("controller.getParts()"),
                "星律配方類型不得從其他零件借用，只能來自直接綁定的控制器");
    }

    @Test
    void operationDetailsAreSentToBothActionBarAndChat() throws Exception {
        String source = Files.readString(SERVICE);

        assertTrue(source.contains("player.displayClientMessage(message, true)"));
        assertTrue(source.contains("player.sendSystemMessage(detail)"));
        assertTrue(source.contains("target.bufferPos()"));
        assertTrue(source.contains("recipeTypeId"));
        assertTrue(source.contains("uploaded.size()"));
        assertTrue(source.contains("describeTargets(player, uploaded, sdaCount, inventoryCount)"));
        assertTrue(source.contains("/shanhai stellar_tp "),
                "成功寫入目標的座標應使用既有的安全傳送指令");
        assertTrue(source.contains("ClickEvent.Action.RUN_COMMAND"));
        assertTrue(source.contains("inventory.getStackInSlot(slot).isEmpty()"),
                "操作後的已用和未使用槽位應由目標庫存實際統計");
        assertTrue(source.contains("inventory.size() - occupied"));
        assertTrue(source.contains("target.levelKey().location().toString()"));
        assertTrue(source.contains("message.gt_shanhai.jei.quick_encode.target_inventory"),
                "未上傳而直接進入物品欄時不能誤報目標為 SDA");
    }

    @Test
    void vanillaSmeltingRecipesUseTheGtElectricFurnaceEncodingRoute() throws Exception {
        String source = Files.readString(SERVICE);
        assertTrue(source.contains("resolveRecipe(player.level(), anchorRecipeId)"),
                "原版 JEI 配方必须使用服务端 RecipeManager 解析");
        assertTrue(source.contains("collectVanillaSmeltingRecipes(player.level())"),
                "原版熔炉的整类编写必须只收集 minecraft:smelting 配方");

        String helper = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "common", "item", "PatternRecipeTypeHelper.java"));
        assertTrue(helper.contains("RecipeType.SMELTING"));
        assertTrue(helper.contains("GTRecipeTypes.FURNACE_RECIPES.toGTrecipe"),
                "原版熔炉配方必须复用 GTCEu 的电炉代理转换，保持样板执行语义一致");
    }

    @Test
    void resolvesNestedGtRecipeIdsWithoutGlobalLookupAbort() throws Exception {
        String helper = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "common", "item", "PatternRecipeTypeHelper.java"));

        assertTrue(helper.contains("findRecipeTypeFromId(expected)"),
                "嵌套配方 ID 必须先按第一段路径定位配方类型");
        assertTrue(helper.contains("type.getRecipe(recipeManager, expected)"),
                "服务端配方表应优先按完整 ID 直查");
        assertTrue(helper.contains("for (List<GTRecipe> proxiedRecipes : type.getProxyRecipes().values())"),
                "解析必须覆盖 GTCEu 的原版代理配方");
        String lookup = helper.substring(helper.indexOf("private static GTRecipe findRecipeInType"),
                helper.indexOf("public static SmeltingRecipe resolveVanillaSmeltingRecipe"));
        assertTrue(lookup.contains("catch (RuntimeException exception)"),
                "单个配方类型 lookup 失败不能中止其他类型的解析");
    }

    @Test
    void jeiButtonsAcceptVanillaSmeltingRecipeWrappers() throws Exception {
        String source = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "integration", "jei", "JeiPatternQuickEncodeButtons.java"));
        assertTrue(source.contains("SmeltingRecipe"),
                "原版熔炉 JEI 页面必须被快速编写按钮识别");
        assertTrue(source.contains("recipe.getId()"),
                "原版配方按钮必须传递其原始 recipe id");
    }

    @Test
    void molecularAssemblerTargetsUseThePublicUploadContract() throws Exception {
        String source = Files.readString(SERVICE);
        assertTrue(source.contains("new ResourceLocation(\"gtceu\", \"molecular_assembler_matrix\")"),
                "分子操纵者矩阵必须作为明确目标 ID 支持");
        assertTrue(source.contains("new ResourceLocation(\"gt_shanhai\", \"primordial_molecular_assembler_module\")"),
                "原初分子操纵模块必须作为明确目标 ID 支持");
        assertTrue(source.contains("new ResourceLocation(\"gtceu\", \"me_molecular_assembler_io\")"),
                "分子目标实际写入端必须限定为分子操纵者 IO 端口");
        assertTrue(source.contains("PatternQuickUploadService.insertIntoTargetSlotResult"),
                "非星律目标必须复用 GTLCore 的通用插入 API");
        assertTrue(source.contains("PatternQuickUploadService.removeFromTarget"),
                "非星律目标回滚必须复用 GTLCore 的通用回滚 API");
        assertTrue(source.contains("UploadStatus.INSERTED"),
                "重复或失败结果不能被误记为已上传");
        assertTrue(source.contains("MOLECULAR_ASSEMBLER_IO_ID.equals(machine.getDefinition().getId())"),
                "原初分子操纵模块必须通过实际 me_molecular_assembler_io 端口识别");
        assertTrue(source.contains("level.getBlockEntity(target.bufferPos()) instanceof PatternContainer"),
                "独立分子操纵者矩阵只能按自身 PatternContainer 识别，不能借用主机 ID 冒充端口");
    }

    @Test
    void automaticSelectionHasStableDimensionDistanceAndMachineOrdering() throws Exception {
        String source = Files.readString(SERVICE);

        assertTrue(source.contains("target.levelKey().equals(playerLevel) ? 0 : 1"));
        assertTrue(source.contains("isMolecularAssemblerControllerHint(target) ? 1 : 0"),
                "星律必须排在分子操纵者目标之前，后者只能作为替补");
        assertTrue(source.contains("thenComparingLong(target -> distanceSquared(playerLevel, playerPos, target))"));
        assertTrue(source.contains("thenComparing(target -> target.targetMachineId() == null"));
        assertTrue(source.contains("thenComparing(target -> target.levelKey().location().toString())"));
        assertTrue(source.contains("thenComparingLong(target -> target.bufferPos().asLong())"));
    }
}
