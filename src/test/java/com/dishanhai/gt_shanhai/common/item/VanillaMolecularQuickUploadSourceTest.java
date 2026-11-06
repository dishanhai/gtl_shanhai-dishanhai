package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 山海：JEI 至分子 IO 的原版專用樣板接線契約；不取代遊戲驗收。 */
class VanillaMolecularQuickUploadSourceTest {

    private static String itemSource(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/item", name + ".java"));
    }

    @Test
    void vanillaRecipeIdentityTakesPrecedenceOverCategoryFallback() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/integration/jei/JeiPatternQuickEncodeButtons.java"));
        String extractor = source.substring(source.indexOf("extractRecipeId(IRecipeLayoutDrawable"),
                source.indexOf("private record MultiblockFactory"));
        assertTrue(extractor.indexOf("instanceof Recipe<?>") >= 0
                        && extractor.indexOf("recipe.getId()") < extractor.indexOf("getRegistryName(recipe)"),
                "原版配方必須先使用自身精確 ID，分類 ID 只能作為第三方回退");
    }

    @Test
    void serverResolvesSmithingAndStonecuttingBeforeRejectingUnknownGtRecipes() throws Exception {
        String helper = itemSource("PatternRecipeTypeHelper");
        assertTrue(helper.contains("byKey(expected)"), "原版配方 ID 必須由服務端 RecipeManager 精確解析");
        assertTrue(helper.contains("instanceof SmithingRecipe") && helper.contains("instanceof StonecutterRecipe"));
        String service = itemSource("JeiPatternQuickEncodeService");
        assertTrue(service.indexOf("resolveVanillaMolecularRecipe(") >= 0
                        && service.indexOf("resolveVanillaMolecularRecipe(") < service.indexOf("recipe resolve failed"),
                "專用原版類型必須在 GT 配方失敗判定之前分流");
        assertTrue(service.contains("collectVanillaMolecularRecipes(player.level(), molecularAnchor, failure)"));
        assertTrue(service.contains("getAllRecipesFor(RecipeType.SMITHING)")
                        && service.contains("getAllRecipesFor(RecipeType.STONECUTTING)")
                        && service.contains("recipe.getType() == anchor.getType()"),
                "整類編寫只可收集選中配方的原版類型，不能混合鍛造、切石或 GT 配方");
    }

    @Test
    void nativeEncodingAssemblesSelectedInputsInsteadOfUsingStaticSmithingResults() throws Exception {
        String encoder = itemSource("ShanhaiPatternEncoder");
        assertTrue(encoder.contains("CraftingRecipeUtil.getIngredients(recipe)"),
                "鍛造原料需包含模板、基底與添加物，復用 AE2 原版配方讀取契約");
        assertTrue(encoder.contains("recipe.assemble(inputs, player.level().registryAccess())"),
                "鍛造裝飾與基底 NBT 必須按選定輸入計算");
        assertTrue(encoder.contains("recipe.matches(inputs, player.level())"),
                "選定候選不符配方時必須拒絕編碼");
        assertTrue(encoder.contains("PatternDetailsHelper.encodeSmithingTablePattern("));
        assertTrue(encoder.contains("PatternDetailsHelper.encodeStonecuttingPattern("));
    }

    @Test
    void specialPatternsKeepTheirExactRecipeIdentityAndUsePublicMolecularUpload() throws Exception {
        String service = itemSource("JeiPatternQuickEncodeService");
        assertTrue(service.contains("instanceof AESmithingTablePattern smithing")
                        && service.contains("instanceof AEStonecuttingPattern stonecutting"),
                "專用樣板不得因不是 AEProcessingPattern 而被拒絕，搜尋也需辨識其類型");
        assertTrue(service.contains("smithing.getRecipeId().toString().equals(recipeId)")
                        && service.contains("stonecutting.getRecipeId().toString().equals(recipeId)"),
                "解碼後必須校驗精確配方 ID，避免把其他同類配方當成成功");
        assertTrue(service.contains("PatternQuickUploadService.findTargets("));
        assertTrue(service.contains("PatternQuickUploadService.insertIntoTargetSlotResult("));
        assertTrue(service.contains("MOLECULAR_ASSEMBLER_MATRIX_ID.equals(machineId)")
                        && service.contains("PRIMORDIAL_MOLECULAR_ASSEMBLER_MODULE_ID.equals(machineId)"));
        assertTrue(service.indexOf("patterns.size() != recipeCount") < service.indexOf("for (ItemStack pattern : patterns)"),
                "整批所有配方通過驗證後，才可寫入任何目標或扣除空白樣板");
    }
}
