package com.dishanhai.gt_shanhai.common.machine.primordial.module.core;

import com.dishanhai.gt_shanhai.api.recipe.PrimordialMyriadRecipeTypes;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialModuleRecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gtladd.gtladditions.api.machine.trait.IWirelessNetworkEnergyHandler;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;
import com.gtladd.gtladditions.api.recipe.WirelessGTRecipe;
import com.gtladd.gtladditions.common.data.ParallelData;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public class PrimordialMyriadProliferationCoreLogic extends PrimordialModuleRecipeLogic {

    @Nullable
    private ResourceLocation activeRecipeTypeId;
    private boolean buildingFinalWirelessRecipe;

    public PrimordialMyriadProliferationCoreLogic(
            GTLAddWirelessWorkableElectricMultipleRecipesMachine machine) {
        super(machine);
    }

    private boolean pauseForMyriadConflict() {
        if (!(getMachine() instanceof PrimordialMyriadProliferationCore core)) return false;
        Component warning = core.getMyriadConflictWarning();
        if (warning == null) return false;
        setWaiting(warning);
        return true;
    }

    @Override
    public GTRecipe getGTRecipe() {
        if (pauseForMyriadConflict()) return null;
        return super.getGTRecipe();
    }

    /** 在 GTCEu 呼叫 machine.onWorking／interruptRecipe 之前暫停，不丟已扣料批次。 */
    @Override
    public void handleRecipeWorking() {
        if (pauseForMyriadConflict()) return;
        super.handleRecipeWorking();
    }

    @Override
    protected boolean allowsEmptyRecipeOutputs() {
        return true;
    }

    @Override
    protected WirelessGTRecipe buildFinalWirelessRecipe(
            ParallelData parallelData, IWirelessNetworkEnergyHandler wirelessTrait) {
        ResourceLocation selected = selectActiveRecipeTypeId(parallelData);
        setOutputMultiplierState(selected, true);
        WirelessGTRecipe result = null;
        try {
            result = super.buildFinalWirelessRecipe(parallelData, wirelessTrait);
            return result;
        } finally {
            setOutputMultiplierState(result == null ? null : selected, false);
        }
    }

    @Override
    public void onRecipeFinish() {
        super.onRecipeFinish();
        if (!isWorking()) {
            setOutputMultiplierState(null, false);
        }
    }

    private ResourceLocation selectActiveRecipeTypeId(@Nullable ParallelData parallelData) {
        if (parallelData == null) {
            return null;
        }
        ResourceLocation selected = null;
        for (GTRecipe recipe : parallelData.getOriginRecipeList()) {
            if (recipe == null || recipe.recipeType == null) {
                continue;
            }
            ResourceLocation candidate = recipe.recipeType.registryName;
            if (PrimordialMyriadRecipeTypes.TIER_1_ID.equals(candidate)) {
                selected = candidate;
                break;
            }
            if (PrimordialMyriadRecipeTypes.TIER_2_ID.equals(candidate)) {
                selected = candidate;
            }
        }
        return selected;
    }

    private void setOutputMultiplierState(@Nullable ResourceLocation recipeTypeId, boolean building) {
        boolean changed = !Objects.equals(activeRecipeTypeId, recipeTypeId)
                || buildingFinalWirelessRecipe != building;
        activeRecipeTypeId = recipeTypeId;
        buildingFinalWirelessRecipe = building;
        if (changed && getMachine() instanceof PrimordialMyriadProliferationCore core) {
            core.invalidateHostOutputMultiplierCache();
        }
    }

    void restoreActiveRecipeTypeId(@Nullable ResourceLocation recipeTypeId) {
        setOutputMultiplierState(recipeTypeId, false);
    }

    boolean isOutputMultiplierActive() {
        return buildingFinalWirelessRecipe || isWorking() || activeRecipeTypeId != null && isWaiting();
    }

    @Nullable
    ResourceLocation getActiveRecipeTypeId() {
        return activeRecipeTypeId;
    }
}
