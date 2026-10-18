package com.dishanhai.gt_shanhai.common.item;

public final class PatternRecipeIdExecutionPolicy {

    private PatternRecipeIdExecutionPolicy() {
    }

    public static boolean allowsCandidate(String storedRecipeId, String candidateRecipeId,
            boolean enforceStoredRecipeId) {
        if (storedRecipeId == null || storedRecipeId.isEmpty() || !enforceStoredRecipeId) {
            return true;
        }
        return storedRecipeId.equals(candidateRecipeId);
    }
}
