package com.dishanhai.gt_shanhai.common.machine.primordial.module.core;

import com.dishanhai.gt_shanhai.api.machine.primordial.IPrimordialOutputMultiplierModule;
import com.dishanhai.gt_shanhai.api.recipe.PrimordialMyriadRecipeTypes;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class PrimordialMyriadProliferationCore extends PrimordialOmegaEngineModuleBase
        implements IPrimordialOutputMultiplierModule {

    public PrimordialMyriadProliferationCore(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    @Override
    public PrimordialMyriadProliferationCoreLogic createRecipeLogic(Object... args) {
        return new PrimordialMyriadProliferationCoreLogic(this);
    }

    @Override
    public PrimordialMyriadProliferationCoreLogic getRecipeLogic() {
        return (PrimordialMyriadProliferationCoreLogic) recipeLogic;
    }

    @Override
    public int getCurrentOutputMultiplier() {
        PrimordialMyriadProliferationCoreLogic logic = getRecipeLogic();
        return resolveOutputMultiplier(
                isFormed(),
                this::isHostConnected,
                logic::isWorking,
                () -> getRecipeTypeId(logic.getLastRecipe()));
    }

    static int resolveOutputMultiplier(boolean formed,
                                       BooleanSupplier hostConnected,
                                       BooleanSupplier working,
                                       Supplier<ResourceLocation> recipeTypeId) {
        if (!formed || !hostConnected.getAsBoolean()) {
            return 1;
        }
        if (!working.getAsBoolean()) {
            return 10;
        }
        return resolveOutputMultiplier(true, true, recipeTypeId.get());
    }

    static int resolveOutputMultiplier(boolean attached, boolean working,
                                       ResourceLocation recipeTypeId) {
        if (!attached) {
            return 1;
        }
        if (!working || recipeTypeId == null) {
            return 10;
        }
        if (PrimordialMyriadRecipeTypes.TIER_1_ID.equals(recipeTypeId)) {
            return 1000;
        }
        if (PrimordialMyriadRecipeTypes.TIER_2_ID.equals(recipeTypeId)) {
            return 100;
        }
        return 10;
    }

    private ResourceLocation getActiveRecipeTypeId() {
        PrimordialMyriadProliferationCoreLogic logic = getRecipeLogic();
        return logic.isWorking() ? getRecipeTypeId(logic.getLastRecipe()) : null;
    }

    private static ResourceLocation getRecipeTypeId(GTRecipe recipe) {
        return recipe == null || recipe.recipeType == null ? null : recipe.recipeType.registryName;
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        if (isFormed()) {
            int multiplier = getCurrentOutputMultiplier();
            addHostStatusDisplay(textList);
            addEnergyDisplay(textList);
            addWorkingStatus(textList);

            Component stage;
            if (multiplier >= 1000) {
                stage = Component.translatable(
                        "gt_shanhai.machine.primordial_myriad_proliferation_core.stage.tier_1");
            } else if (multiplier >= 100) {
                stage = Component.translatable(
                        "gt_shanhai.machine.primordial_myriad_proliferation_core.stage.tier_2");
            } else {
                stage = Component.translatable(
                        "gt_shanhai.machine.primordial_myriad_proliferation_core.stage.base");
            }
            textList.add(Component.translatable(
                    "gt_shanhai.machine.primordial_myriad_proliferation_core.stage", stage)
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
            textList.add(Component.translatable(
                    "gt_shanhai.machine.primordial_myriad_proliferation_core.multiplier", multiplier)
                    .withStyle(ChatFormatting.AQUA));

            ResourceLocation activeRecipeTypeId = getActiveRecipeTypeId();
            if (activeRecipeTypeId != null) {
                textList.add(Component.translatable(
                        "gt_shanhai.machine.primordial_myriad_proliferation_core.recipe_type",
                        activeRecipeTypeId.toString()).withStyle(ChatFormatting.GRAY));
            }
        } else {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure")
                    .withStyle(ChatFormatting.RED));
        }
        textList.add(Component.translatable(
                "gt_shanhai.machine.primordial_myriad_proliferation_core.name")
                .withStyle(ChatFormatting.GOLD));
    }
}
