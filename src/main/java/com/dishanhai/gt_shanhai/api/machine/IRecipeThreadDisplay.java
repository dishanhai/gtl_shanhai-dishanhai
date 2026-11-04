package com.dishanhai.gt_shanhai.api.machine;

// Author: dishanhai

import net.minecraft.nbt.CompoundTag;

/** Read-only snapshot contract shared by recipe-thread logic and Jade. */
public interface IRecipeThreadDisplay {
    CompoundTag getRecipeThreadDisplay(int first, int maximum);
    void onThreadInputsChanged();
    int getActiveRecipeThreadCount();
    boolean isRecipeThreadDisplayAvailable();
}
