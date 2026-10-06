package com.dishanhai.gt_shanhai;

import com.dishanhai.gt_shanhai.api.ModuleLevelCondition;
import com.dishanhai.gt_shanhai.api.RecipeNoteCondition;
import com.dishanhai.gt_shanhai.common.heat.ShanhaiHeatGate;
import com.gregtechceu.gtceu.api.addon.GTAddon;
import com.gregtechceu.gtceu.api.addon.IGTAddon;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate;

import com.dishanhai.gt_shanhai.common.DShanhaiCreativeModeTabs;
import com.dishanhai.gt_shanhai.common.item.DShanhaiItems;
import com.dishanhai.gt_shanhai.common.machine.DShanhaiMachines;
import com.dishanhai.gt_shanhai.common.recipe.DShanhaiJavaRecipeLibrary;

import net.minecraft.data.recipes.FinishedRecipe;

import java.util.function.Consumer;
import java.util.List;

@GTAddon
public class GTDishanhaiGTAddon implements IGTAddon {

    @Override
    public String addonModId() {
        return GTDishanhaiMod.MOD_ID;
    }

    @Override
    public GTRegistrate getRegistrate() {
        return GTDishanhaiRegistration.REGISTRATE;
    }

    @Override
    public void initializeAddon() {
        DShanhaiCreativeModeTabs.init();
        getRegistrate().creativeModeTab(DShanhaiCreativeModeTabs.TAB_DISHANHAI);
        DShanhaiItems.init();

        // 注册配方模块等级条件 .ml("moduleId", level)
        GTRegistries.RECIPE_CONDITIONS.unfreeze();
        GTRegistries.RECIPE_CONDITIONS.register("module_level", ModuleLevelCondition.TYPE);
        GTRegistries.RECIPE_CONDITIONS.register("recipe_note", RecipeNoteCondition.TYPE);
        // BHC 条件暂禁用排查崩溃: GTRegistries.RECIPE_CONDITIONS.register("bhc_recipe", BHCRecipeCondition.TYPE);
        GTRegistries.RECIPE_CONDITIONS.freeze();

        GTRegistries.MACHINES.unfreeze();
        DShanhaiMachines.init();
        GTRegistries.MACHINES.freeze();

        List<String> heatMachineIds = List.of(
                DShanhaiMachines.TAIXU_SMELTING_FURNACE.getId().toString(),
                DShanhaiMachines.PRIMORDIAL_ETERNAL_SMELTING_FURNACE.getId().toString(),
                DShanhaiMachines.PRIMORDIAL_MOLECULAR_RIFT_CORE.getId().toString());
        String heatMachineProblem = ShanhaiHeatGate.verifyMachineIds(heatMachineIds);
        if (heatMachineProblem != null) {
            throw new IllegalStateException("[gt_shanhai] " + heatMachineProblem
                    + "；额外挂载热力判定拒绝继续加载");
        }
        GTDishanhaiMod.LOGGER.info("额外挂载热力白名单自检通过：{}", heatMachineIds);
    }

    @Override
    public void addRecipes(Consumer<FinishedRecipe> provider) {
        DShanhaiJavaRecipeLibrary.registerRecipes(provider);
    }
}
