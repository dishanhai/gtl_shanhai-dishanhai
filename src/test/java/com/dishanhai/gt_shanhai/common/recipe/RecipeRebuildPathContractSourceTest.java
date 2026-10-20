package com.dishanhai.gt_shanhai.common.recipe;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 複查報告 P0-01 / P0-02 / P1-02 / P1-03 的源碼契約。
 */
class RecipeRebuildPathContractSourceTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void canonicalMarkerUsesIdentityPerTypeInsteadOfRecipeIdEquality() throws Exception {
        String api = read("src/main/java/com/dishanhai/gt_shanhai/api/DShanhaiRecipeModifierAPI.java");

        // GTRecipe.equals 只比較 id：以配方物件作 WeakHashMap 鍵會讓同 id 的新副本被誤判為 canonical
        assertFalse(api.contains("new WeakHashMap<>()"),
                "canonical 標記不能再以 GTRecipe 的 equals/hashCode 定位");
        assertTrue(api.contains("newSetFromMap(new IdentityHashMap<>())"),
                "canonical 標記必須是標識比較");
        assertTrue(api.contains("replaceCanonicalLookupRecipes"));
        assertTrue(api.contains("unmarkCanonicalLookupRecipe"));
    }

    @Test
    void rebuildRemarksTheCopiesItWritesBack() throws Exception {
        String rebuild = read(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/RecipeRebuildService.java");

        // 重建寫回後必須顯式標記本次寫回的副本，否則 strip/replace 下次重建會整批失效
        assertTrue(rebuild.contains("DShanhaiRecipeModifierAPI.replaceCanonicalLookupRecipes("),
                "rebuildType 必須以本次寫回的集合重建 canonical 標記");
        assertTrue(rebuild.contains("DShanhaiRecipeModifierAPI.markCanonicalLookupRecipes("),
                "增量刷新必須標記新寫回的副本");
    }

    @Test
    void batchRebuildNotifiesOnceInsteadOfPerType() throws Exception {
        String rebuild = read(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/RecipeRebuildService.java");
        String api = read("src/main/java/com/dishanhai/gt_shanhai/api/DShanhaiRecipeModifierAPI.java");

        assertTrue(rebuild.contains("rebuildType(String recipeTypeId, RebuildReason reason, boolean notify)"),
                "rebuildType 需要 notify 參數供批次內靜默重建");
        assertTrue(rebuild.contains("rebuildType(typeId, reason, false)"),
                "批次內逐 type 重建不可各自發包");
        assertTrue(rebuild.contains("notifyTypes"));
        // reapplyPersistedLookupRules 以前會重建兩輪（RELOAD + RULE_CHANGED）
        assertFalse(api.contains("            applyAllReplaceRules();"),
                "reapplyPersistedLookupRules 不得再疊加第二次全量重建");
    }

    @Test
    void legacyLookupWritesGoThroughTheSingleRebuildEntry() throws Exception {
        String api = read("src/main/java/com/dishanhai/gt_shanhai/api/DShanhaiRecipeModifierAPI.java");

        assertFalse(api.contains("rebuildLookupFromOriginals"),
                "第二套重建實作必須移除，否則快照可被活 lookup 覆蓋");
        assertFalse(api.contains("rebuildLookup" + "FromOriginals"),
                "不得殘留委派殘骸");
        assertTrue(api.contains("RecipeRebuildService.rebuildType"),
                "規則變更必須走 RecipeRebuildService");
        assertTrue(api.contains("applyContentTransform"),
                "內容級批量替換必須寫成覆寫後統一重建");
        assertTrue(api.contains("runRuleBatch"),
                "批次規則變更必須收攏成一輪重建");
    }

    @Test
    void recipeSyncPacketIsServerToClientOnlyAndDropsUnusedPayload() throws Exception {
        String sync = read("src/main/java/com/dishanhai/gt_shanhai/network/RecipeSyncPacket.java");

        assertTrue(sync.contains("Optional.of(NetworkDirection.PLAY_TO_CLIENT)"),
                "配方同步包只允許伺服器 → 客戶端");
        assertFalse(sync.contains("private final List<String> recipeIds"),
                "客戶端從未讀取的 recipeIds 不應繼續佔用頻寬");
        assertTrue(sync.contains("syncRecipesToAll"),
                "需要帶配方內容的定向同步，專用伺服器客戶端才看得到覆寫");
        assertTrue(sync.contains("syncOverridesTo"),
                "登入補發入口必須存在");
    }
}
