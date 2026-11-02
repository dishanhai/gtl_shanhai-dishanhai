package com.dishanhai.gt_shanhai.common.machine.preview;

import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;

/** 山海：保留無產出配方的真實時長；每tick實扣成功後才發出廣播，等待立即撤銷。 */
public final class ShanhaiNyarlathotepEchoRecipeLogic extends RecipeLogic {
    public ShanhaiNyarlathotepEchoRecipeLogic(ShanhaiNyarlathotepEchoMachine machine) { super(machine); }

    @Override public void handleRecipeWorking() {
        int previousProgress = getProgress();
        super.handleRecipeWorking();
        ((ShanhaiNyarlathotepEchoMachine) getMachine()).refreshBroadcast(
                isWorking() && getProgress() > previousProgress && getProgress() <= getDuration());
    }

    @Override public void interruptRecipe() {
        ((ShanhaiNyarlathotepEchoMachine) getMachine()).stopBroadcast();
        super.interruptRecipe();
    }
}
