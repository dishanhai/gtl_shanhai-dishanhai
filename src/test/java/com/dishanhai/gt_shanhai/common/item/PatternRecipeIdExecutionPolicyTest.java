package com.dishanhai.gt_shanhai.common.item;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class PatternRecipeIdExecutionPolicyTest {

    private static final String POLICY_CLASS =
            "com.dishanhai.gt_shanhai.common.item.PatternRecipeIdExecutionPolicy";

    @Test
    void exactPatternRecipeIdRejectsAnotherNamespaceWithTheSameRecipeType() throws Exception {
        assertFalse(allowsCandidate("gt_shanhai:macerator/essence", "kubejs:macerator/essence", true));
        assertTrue(allowsCandidate("gt_shanhai:macerator/essence", "gt_shanhai:macerator/essence", true));
    }

    @Test
    void missingOrEditedPatternIdentityRetainsTheExistingFallback() throws Exception {
        assertTrue(allowsCandidate("", "kubejs:macerator/essence", true));
        assertTrue(allowsCandidate("gt_shanhai:macerator/essence", "kubejs:macerator/essence", false));
    }

    @Test
    void stellarSlotChecksExactRecipeIdentityBeforeRecipeTypeCompatibility() throws Exception {
        String source = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "common", "machine", "part", "RecipeTypePatternBufferPartMachine.java"));
        int methodStart = source.indexOf("public boolean gtShanhai$slotAllowsRecipe(int slot, GTRecipe recipe)");
        int methodEnd = source.indexOf("\n    @Override", methodStart);
        assertTrue(methodStart >= 0 && methodEnd > methodStart, "應找到星律槽位配方放行入口");
        String method = source.substring(methodStart, methodEnd);

        int exactIdCheck = method.indexOf("PatternRecipeTypeHelper.storedRecipeIdAllowsCandidate");
        int recipeTypeCheck = method.indexOf("PatternRecipeTypeHelper.recipeMatchesTypeId");
        assertTrue(exactIdCheck >= 0 && exactIdCheck < recipeTypeCheck,
                "槽位必須在配方類型或共享搜尋集放行前執行完整配方 ID 檢查");
    }

    private static boolean allowsCandidate(String storedId, String candidateId, boolean enforceStoredId)
            throws Exception {
        try {
            Class<?> policy = Class.forName(POLICY_CLASS);
            Method method = policy.getMethod(
                    "allowsCandidate", String.class, String.class, boolean.class);
            return (boolean) method.invoke(null, storedId, candidateId, enforceStoredId);
        } catch (ClassNotFoundException | NoSuchMethodException exception) {
            fail("尚未提供可測試的星律精確配方 ID 執行政策", exception);
            return false;
        }
    }
}
