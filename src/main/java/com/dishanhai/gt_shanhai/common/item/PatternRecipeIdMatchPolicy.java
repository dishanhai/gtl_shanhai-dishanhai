package com.dishanhai.gt_shanhai.common.item;

import net.minecraft.resources.ResourceLocation;

/** Exact recipe-ID matching after the owning recipe type narrows the lookup. */
public final class PatternRecipeIdMatchPolicy {

    private PatternRecipeIdMatchPolicy() {
    }

    public static boolean matches(String patternRecipeTypeId, String patternRecipeId,
            String candidateRecipeTypeId, String candidateRecipeId) {
        ResourceLocation patternType = parse(PatternRecipeTypeHelper.canonicalRecipeTypeId(patternRecipeTypeId));
        ResourceLocation patternId = parse(patternRecipeId);
        ResourceLocation candidateType = parse(candidateRecipeTypeId);
        ResourceLocation candidateId = parse(candidateRecipeId);
        if (patternType == null || patternId == null || candidateType == null || candidateId == null) {
            return false;
        }

        if (!PatternRecipeTypeHelper.canonicalRecipeTypeId(patternRecipeTypeId)
                .equals(PatternRecipeTypeHelper.canonicalRecipeTypeId(candidateRecipeTypeId))) {
            return false;
        }
        // 電爐代理沿用原版 smelting ID；查詢端仍須在該類型或 smelting 表中找到它。
        if (isElectricFurnace(patternType)) {
            return patternId.equals(candidateId);
        }
        if (!hasTypePathPrefix(patternType, patternId) || !hasTypePathPrefix(patternType, candidateId)) {
            return false;
        }
        String typePathPrefix = patternType.getPath() + "/";
        String expectedPath = patternId.getPath();
        String candidatePath = candidateId.getPath();
        if (!patternId.getNamespace().equals(candidateId.getNamespace())) {
            return false;
        }

        String expectedSuffix = expectedPath.substring(typePathPrefix.length());
        String candidateSuffix = candidatePath.substring(typePathPrefix.length());
        return !expectedSuffix.isEmpty() && expectedSuffix.equals(candidateSuffix);
    }

    public static boolean hasTypePrefix(String recipeTypeId, String recipeId) {
        ResourceLocation recipeType = parse(PatternRecipeTypeHelper.canonicalRecipeTypeId(recipeTypeId));
        ResourceLocation recipe = parse(recipeId);
        return recipeType != null && recipe != null
                && (isElectricFurnace(recipeType) || hasTypePathPrefix(recipeType, recipe));
    }

    private static boolean isElectricFurnace(ResourceLocation recipeType) {
        return "gtceu".equals(recipeType.getNamespace()) && "electric_furnace".equals(recipeType.getPath());
    }

    private static boolean hasTypePathPrefix(ResourceLocation recipeType, ResourceLocation recipeId) {
        return recipeId.getPath().startsWith(recipeType.getPath() + "/");
    }

    private static ResourceLocation parse(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            return new ResourceLocation(id);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
