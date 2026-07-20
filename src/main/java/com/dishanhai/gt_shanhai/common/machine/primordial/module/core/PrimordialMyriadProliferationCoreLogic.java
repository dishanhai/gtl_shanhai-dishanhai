package com.dishanhai.gt_shanhai.common.machine.primordial.module.core;

import com.dishanhai.gt_shanhai.api.recipe.PrimordialMyriadRecipeTypes;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialModuleRecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gtladd.gtladditions.api.machine.trait.IWirelessNetworkEnergyHandler;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;
import com.gtladd.gtladditions.api.recipe.WirelessGTRecipe;
import com.gtladd.gtladditions.common.data.ParallelData;

import net.minecraft.resources.ResourceLocation;
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
        return buildingFinalWirelessRecipe || isWorking();
    }

    @Nullable
    ResourceLocation getActiveRecipeTypeId() {
        return activeRecipeTypeId;
    }
}
