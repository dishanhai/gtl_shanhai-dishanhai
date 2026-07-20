package com.dishanhai.gt_shanhai;

import com.dishanhai.gt_shanhai.api.ModuleLevelCondition;
import com.dishanhai.gt_shanhai.api.RecipeNoteCondition;
import com.gregtechceu.gtceu.api.addon.GTAddon;
import com.gregtechceu.gtceu.api.addon.IGTAddon;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate;

import com.dishanhai.gt_shanhai.common.DShanhaiCreativeModeTabs;
import com.dishanhai.gt_shanhai.common.item.DShanhaiItems;
import com.dishanhai.gt_shanhai.common.machine.DShanhaiMachines;

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

        // 注册方块（需在机器之前，机器结构引用方块）
        com.dishanhai.gt_shanhai.common.block.DShanhaiBlocks.init();

        GTRegistries.MACHINES.unfreeze();
        DShanhaiMachines.init();
        GTRegistries.MACHINES.freeze();
    }
}
