package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 山海星律 P3：批次與未就緒配方表的 Minecraft 執行邊界。 */
class StellarP3BatchLookupSourceTest {
    private static String read(String path) throws IOException {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai").resolve(path))
                .replace("\r\n", "\n");
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature);
        return source.substring(start, source.indexOf("\n    }", start));
    }

    @Test
    void multiplierBatchDefersWholeTableWorkAndRestoresTheFlagOnFailure() throws IOException {
        String machine = read("common/machine/part/RecipeTypePatternBufferPartMachine.java");
        String refresh = method(machine, "public void refreshOutputMultiplierPatterns()");
        assertTrue(refresh.contains("refreshingOutputMultiplierPatterns = true;"));
        assertTrue(refresh.contains("finally {"));
        assertTrue(refresh.indexOf("refreshingOutputMultiplierPatterns = false;") > refresh.indexOf("finally {"));
        assertTrue(refresh.contains("publishedProductOutputsMissMultiplier()"), "批次結束後核對一次");
        String clear = method(machine, "private void clearOutputMultiplierPatternCache()");
        assertTrue(clear.indexOf("if (refreshingOutputMultiplierPatterns) return;") >= 0);
        String change = method(machine, "protected void onPatternChange(int index)");
        assertTrue(change.indexOf("if (!refreshingOutputMultiplierPatterns)")
                < change.indexOf("publishedProductOutputsMissMultiplier()"));
        assertTrue(change.contains("RecipeTypePatternSearchHelper.clearPatternSlotState(this, index)"),
                "逐槽配方／輸入狀態仍要失效");
        String reindex = method(machine, "protected void reCalculatePatternSlotMap()");
        assertTrue(reindex.indexOf("if (refreshingOutputMultiplierPatterns) return;")
                < reindex.indexOf("super.reCalculatePatternSlotMap()"),
                "父類逐槽回呼也會全表重建反向索引，必須一起合併");
        assertTrue(refresh.indexOf("reCalculatePatternSlotMap()") > refresh.indexOf("finally {"));
    }

    @Test
    void taggedPeekNeverFallsBackToAnotherTypeAndWaitsForLookup() throws IOException {
        String helper = read("common/item/PatternRecipeTypeHelper.java");
        String peek = method(helper, "private static GTRecipe peekRecipe(ItemStack stack, Level level, String existing,\n");
        int pending = peek.indexOf("isRecipeLookupPending(stack)");
        assertTrue(pending >= 0 && pending < peek.indexOf("inferRecipe("));
        assertFalse(peek.contains("inferRecipe(stack, level, \"\","),
                "只讀解析不可在既有類型失配時跨域推斷；無標記時本來就以 existing 空字串推斷");
        String ready = method(helper, "private static boolean isRecipeTypeLookupPopulated(");
        assertTrue(ready.contains("getRecipes(true).findAny().isPresent()"),
                "就緒檢查位於每槽快取命中前，遞迴 flatMap 必須用終端操作短路，不能經 iterator 收集整個子流");
    }

    @Test
    void pendingLookupDoesNotPoisonTheSlotOrMarkedRecipeCache() throws IOException {
        String machine = read("common/machine/part/RecipeTypePatternBufferPartMachine.java");
        String slot = method(machine, "public GTRecipe gtShanhai$getPatternRecipe(int slot, GenericStack[]");
        int pending = slot.indexOf("isRecipeLookupPending(stack)");
        assertTrue(pending >= 0 && pending < slot.indexOf("patternRecipeTypeIds[slot] ="));
        String helper = read("common/item/RecipeTypePatternSearchHelper.java");
        String cached = method(helper, "private static GTRecipe getMarkedRecipeCached(");
        assertTrue(cached.indexOf("isRecipeLookupPending(") >= 0);
        assertTrue(cached.indexOf("isRecipeLookupPending(") < cached.indexOf("synchronized (MARKED_RECIPE_CACHE)"),
                "配方表未就緒時不接受舊命中，也不記錄暫時的 null");
        String generic = method(helper, "private static GTRecipe peekRecipeCached(");
        assertTrue(generic.indexOf("isRecipeLookupPending(stack)") >= 0);
        assertTrue(generic.indexOf("isRecipeLookupPending(stack)") < generic.indexOf("synchronized (PATTERN_PEEK_CACHE)"),
                "共用只讀解析的虛擬供料路徑也不可把未就緒 null 固定到樣板身份");
    }
}
