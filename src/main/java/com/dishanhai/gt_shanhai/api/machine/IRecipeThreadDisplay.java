package com.dishanhai.gt_shanhai.api.machine;

// Author: dishanhai

import net.minecraft.nbt.CompoundTag;

/** Read-only snapshot contract shared by recipe-thread logic and Jade. */
public interface IRecipeThreadDisplay {
    CompoundTag getRecipeThreadDisplay(int first, int maximum);
    void onThreadInputsChanged();
    int getActiveRecipeThreadCount();
    default int getRecipeExecutionBatchCount() { return getActiveRecipeThreadCount(); }
    default boolean hasMergedRecipeBatches() { return false; }
    boolean isRecipeThreadDisplayAvailable();
    /** No recipe lookup, frame switching or inventory transaction is allowed in this observation. */
    default RecipeThreadSnapshot getRecipeThreadSnapshot() { return null; }
}
