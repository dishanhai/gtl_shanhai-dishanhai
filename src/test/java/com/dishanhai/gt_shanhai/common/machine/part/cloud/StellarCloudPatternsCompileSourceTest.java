package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 山海：派生身份必須可重載，無類型原件只能搜尋宿主已授權的共享類型。 */
class StellarCloudPatternsCompileSourceTest {
    private static final Path SOURCE = Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/part/cloud/StellarCloudPatterns.java");

    @Test
    void derivedRecipeIdentityIsPersistedIndependentlyOfPlayerEncodingPreference() throws IOException {
        String source = Files.readString(SOURCE);
        String append = source.substring(source.indexOf("private static void append("));
        int validation = append.indexOf("storedRecipeFitsPattern(original, level, recipe)");
        int copy = append.indexOf("original.copyWithCount(1)");
        int identity = append.indexOf("putString(PatternRecipeTypeHelper.TAG_RECIPE_ID, recipe.id.toString())");
        int publish = append.indexOf("result.add(new Template(stack, pattern, recipe))");
        assertTrue(validation >= 0 && validation < copy && copy < identity && identity < publish,
                "雲端派生副本須在 IO 驗證後寫入已驗證的 recipe.id，才能由 baseStack 重載");
        assertFalse(append.contains("PatternRecipeTypeHelper.writeRecipeId("),
                "內部派生身份不得被玩家的 encodeWriteRecipeId 偏好略過");
    }

    @Test
    void untypedOriginalSearchIncludesOnlyAuthorizedSharedRegistryTypes() throws IOException {
        String source = Files.readString(SOURCE);
        int candidates = source.indexOf("for (var candidate : GTRegistries.RECIPE_TYPES)");
        int authorization = source.indexOf("RecipeTypeSharedSearchSets.isShared(host, candidateType)", candidates);
        int include = source.indexOf("candidateTypes.add(candidateType)", authorization);
        int lookup = source.indexOf("findMatchingRecipeForPattern(");
        assertTrue(candidates >= 0 && candidates < authorization && authorization < include && include < lookup,
                "無類型原件必須補齊登記表中與宿主共享的類型，再做 IO 配方比對；不得跨未授權類型搜尋");
        assertFalse(source.contains("type.isEmpty() ? hostTypes : Set.of(type)"),
                "只搜尋 hostTypes 會漏掉被來源共享目錄收錄的原件");
    }

    @Test
    void sharedTypeExpansionPreservesTaggedDomainAndAmbiguityRejection() throws IOException {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("if (type.isEmpty())"), "僅無類型原件可展開搜尋範圍");
        assertTrue(source.contains("!matched.id.equals(candidate.id)) return null"),
                "多個已授權類型匹配到不同配方 ID 時仍須拒絕歧義");
        assertTrue(source.contains("types.stream().noneMatch(host -> RecipeTypeSharedSearchSets.isShared(host, type))"),
                "發布前仍須再次驗證宿主共享授權");
        assertTrue(source.contains("PatternRecipeTypeHelper.areRecipeTypeIdsEquivalent(")
                        && source.contains("candidateType, candidate.recipeType.registryName.toString()"),
                "底層 lookup 失效的全域 fallback 不得越過本次請求的類型域");
    }
}
