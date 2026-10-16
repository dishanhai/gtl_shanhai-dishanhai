package com.dishanhai.gt_shanhai.common.item;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class PatternRecipeIdPrioritySourceTest {

    private static final Path PATTERN_HELPER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "PatternRecipeTypeHelper.java");
    private static final Path STELLAR_BUFFER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "part", "RecipeTypePatternBufferPartMachine.java");

    @Test
    void authoritativePatternEncodingStoresExactRecipeIdAndReadsLegacyWildcardId() throws Exception {
        String helper = Files.readString(PATTERN_HELPER);
        String selectedPattern = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "common", "item", "SelectedRecipePattern.java"));

        assertTrue(helper.contains("TAG_RECIPE_ID = \"gt_shanhai_recipe_id\""),
                "一般處理樣板需要明確的配方 ID NBT 欄位");
        assertTrue(helper.contains("tag.putString(TAG_RECIPE_ID, recipeId)"),
                "權威編碼時必須把完整配方 ID 寫入樣板");
        assertTrue(helper.contains("SelectedRecipePattern.RECIPE_ID"),
                "讀取配方 ID 時必須保留舊通配展開樣板相容");
        assertTrue(selectedPattern.contains("RECIPE_ID = \"gt_shanhai_bundle_recipe\""));
        assertTrue(helper.contains("PatternIdentityMatcher.matches(recipe, details)"),
                "精確 ID 必須用樣板身份校驗，玩家改樣板後不能鎖死舊配方");
    }

    @Test
    void stellarOrdinarySlotsResolveRecipeIdBeforeFallingBackToPatternMatching() throws Exception {
        String source = Files.readString(STELLAR_BUFFER);
        int methodStart = source.indexOf(
                "public GTRecipe gtShanhai$getPatternRecipe(int slot, GenericStack[] availableCatalystInputs)");
        int methodEnd = source.indexOf("public GenericStack[] gtShanhai$getPatternInferenceInputs()", methodStart);
        assertTrue(methodStart >= 0 && methodEnd > methodStart, "應找到星律普通樣板槽的配方解析入口");
        String lookup = source.substring(methodStart, methodEnd);

        int idRead = lookup.indexOf("PatternRecipeTypeHelper.readRecipeId(stack)");
        int exactLookup = lookup.indexOf("PatternRecipeTypeHelper.resolveRecipe(recipeId)");
        int fitsPattern = lookup.indexOf(
                "PatternRecipeTypeHelper.storedRecipeFitsPattern(stack, getLevel(), recipe)");
        int fallback = lookup.indexOf("PatternRecipeTypeHelper.peekRecipe(");
        assertTrue(idRead >= 0 && idRead < exactLookup,
                "普通樣板槽需先讀取其配方 ID");
        assertTrue(exactLookup < fitsPattern && fitsPattern < fallback,
                "樣板內容不再覆蓋該配方時，必須回到原有解析");
    }

    @Test
    void processingPatternTooltipShowsItsStoredRecipeId() throws Exception {
        String tooltipApi = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "api", "DShanhaiItemTooltipAPI.java"));
        int methodStart = tooltipApi.indexOf("private static void addPatternRecipeTypeTooltip");
        int methodEnd = tooltipApi.indexOf("private static String recipeTypeDisplayName", methodStart);
        assertTrue(methodStart >= 0 && methodEnd > methodStart, "應找到樣板配方資訊 tooltip 生成入口");
        String tooltip = tooltipApi.substring(methodStart, methodEnd);

        assertTrue(tooltip.contains("PatternRecipeTypeHelper.readRecipeId(stack)"),
                "樣板 tooltip 應讀取已保存的精確配方 ID");
        assertTrue(tooltip.contains("tooltip.add(Component.literal(\"§b配方 ID：§f\" + recipeId))"),
                "樣板 tooltip 應顯示完整配方 ID");
    }
}
