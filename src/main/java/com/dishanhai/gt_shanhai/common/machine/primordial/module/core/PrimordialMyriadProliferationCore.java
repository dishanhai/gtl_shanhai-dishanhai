package com.dishanhai.gt_shanhai.common.machine.primordial.module.core;

import com.dishanhai.gt_shanhai.api.machine.primordial.IPrimordialOutputMultiplierModule;
import com.dishanhai.gt_shanhai.api.recipe.PrimordialMyriadRecipeTypes;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class PrimordialMyriadProliferationCore extends PrimordialOmegaEngineModuleBase
        implements IPrimordialOutputMultiplierModule {

    private static final String KEY_ACTIVE_RECIPE_TYPE_ID = "sh_myriad_active_recipe_type";

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
    public long getCurrentParallel() {
        return 1L;
    }

    @Override
    public int getCurrentOutputMultiplier() {
        PrimordialMyriadProliferationCoreLogic logic = getRecipeLogic();
        return resolveOutputMultiplier(
                isFormed(),
                this::isHostConnected,
                logic::isOutputMultiplierActive,
                logic::getActiveRecipeTypeId);
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
        return logic.isOutputMultiplierActive() ? logic.getActiveRecipeTypeId() : null;
    }

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forSyncing) {
        super.saveCustomPersistedData(tag, forSyncing);
        if (forSyncing) return;
        ResourceLocation activeRecipeTypeId = getRecipeLogic().getActiveRecipeTypeId();
        if (activeRecipeTypeId != null) {
            tag.putString(KEY_ACTIVE_RECIPE_TYPE_ID, activeRecipeTypeId.toString());
        }
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (tag.contains(KEY_ACTIVE_RECIPE_TYPE_ID)) {
            try {
                getRecipeLogic().restoreActiveRecipeTypeId(new ResourceLocation(
                        tag.getString(KEY_ACTIVE_RECIPE_TYPE_ID)));
            } catch (Exception ignored) {
                getRecipeLogic().restoreActiveRecipeTypeId(null);
            }
        }
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
