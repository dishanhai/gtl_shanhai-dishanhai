package com.dishanhai.gt_shanhai.common.item;

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
        assertTrue(source.contains("ShanhaiPatternEncoder.encode(recipe, player, true)"));
        String encoder = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "common", "item", "ShanhaiPatternEncoder.java"));
        assertTrue(encoder.contains("PatternQuickUploadMetadata.writeRecipeTypeId"),
                "GTLCore 搜尋星律目標依賴其公開的配方類型 metadata");
        assertTrue(source.contains("PatternDetailsHelper.decodePattern(pattern, player.level())"));
        assertTrue(source.contains("PatternRecipeTypeHelper.readRecipeTypeId(pattern)"));
        assertTrue(source.contains("PatternRecipeTypeHelper.areRecipeTypeIdsEquivalent"));
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
        assertTrue(source.contains("hostType.registryName"),
                "星律主机的全部配方类型必须逐一参与匹配");
        assertTrue(source.contains("for (int i = uploaded.size() - 1; i >= 0; i--)"));
        assertTrue(source.indexOf("uploaded.add(new UploadedPattern")
                < source.indexOf("if (!consumePatternSource(menu, committedSource))"));
        assertTrue(source.contains("insertIntoStellarTarget"),
                "星律写入必须走山海侧直接槽位实现，避免被外部 GTLCore mixin 禁用");
        assertTrue(source.contains("removeFromStellarTarget"),
                "直接写入的样板必须能按槽位原子回滚");
        assertTrue(source.contains("PatternQuickUploadService.findTargets"),
                "自有快取未命中时必须用 GTLCore 公开搜索补齐漏掉的已加载多方块目标");
        assertTrue(source.contains("findPublicStellarTargets"),
                "公开搜索结果只能作为星律目标发现，不能替代山海侧直接写入");
        assertTrue(source.contains("Actionable.SIMULATE"));
        assertTrue(source.contains("Actionable.MODULATE"));
        assertTrue(source.contains("boolean useSda = !useInventoryFallback"));
        assertTrue(source.contains("boolean useInventoryFallback = !sdaPatterns.isEmpty()"));
        assertTrue(source.contains("sdaPatterns.size() < MIN_SDA_FALLBACK_PATTERNS"));
        assertTrue(source.contains("givePatterns(player, sdaPatterns)"));
        assertTrue(source.contains("PatternSource committedSource = limitPatternSource(source, committedCount)"));
        assertTrue(source.contains("int skippedCount = useInventoryFallback || useSda ? 0 : sdaPatterns.size()"));
    }

    @Test
    void expandsCompositeAndRuntimeMultiRecipeTypesAndChargesSda() throws Exception {
        String source = Files.readString(SERVICE);
        String binding = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "common", "item", "WildcardPatternRecipeTypeBinding.java"));

        assertTrue(binding.contains("getMultiRecipeType"));
        assertTrue(binding.contains("getTypeList"));
        assertTrue(source.contains("MIN_SDA_FALLBACK_PATTERNS = 20"));
        assertTrue(source.contains("IAEItemPowerStorage"));
        assertTrue(source.contains("getAEMaxPower(sda)"));
        assertTrue(source.contains("injectAEPower(sda, maxPower, Actionable.MODULATE)"));
        assertTrue(source.contains("direct_partial_success"));
    }

    @Test
    void operationDetailsAreSentToBothActionBarAndChat() throws Exception {
        String source = Files.readString(SERVICE);

        assertTrue(source.contains("player.displayClientMessage(message, true)"));
        assertTrue(source.contains("player.sendSystemMessage(detail)"));
        assertTrue(source.contains("target.bufferPos()"));
        assertTrue(source.contains("recipeTypeId"));
        assertTrue(source.contains("uploaded.size()"));
    }

    @Test
    void automaticSelectionHasStableDimensionDistanceAndMachineOrdering() throws Exception {
        String source = Files.readString(SERVICE);

        assertTrue(source.contains("target.levelKey().equals(playerLevel) ? 0 : 1"));
        assertTrue(source.contains("thenComparingLong(target -> distanceSquared(playerLevel, playerPos, target))"));
        assertTrue(source.contains("thenComparing(target -> target.targetMachineId() == null"));
        assertTrue(source.contains("thenComparing(target -> target.levelKey().location().toString())"));
        assertTrue(source.contains("thenComparingLong(target -> target.bufferPos().asLong())"));
    }
}
