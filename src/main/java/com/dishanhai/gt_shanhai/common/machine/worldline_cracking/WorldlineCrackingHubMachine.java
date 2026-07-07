package com.dishanhai.gt_shanhai.common.machine.worldline_cracking;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.pattern.MultiblockWorldSavedData;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;

import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;

import java.util.List;

/**
 * 世线裂解枢纽 — 概率裂解 / 物质复现 / 世线采样
 * <p>
 * 确定性的暴政在此终结。投入原料，枢纽将其分解为未被观测的概率云。
 * 通过原初引擎并行加持，干预概率坍缩方向，锁定高价值产物。
 * 并行线程数超过阈值时，同时交付所有可能的结果。
 */
public class WorldlineCrackingHubMachine extends GTLAddWirelessWorkableElectricMultipleRecipesMachine {

    public WorldlineCrackingHubMachine(IMachineBlockEntity holder) {
        super(holder);
    }

    @Override
    public WorldlineCrackingHubRecipeLogic createRecipeLogic(Object... args) {
        return new WorldlineCrackingHubRecipeLogic(this);
    }

    @Override
    public WorldlineCrackingHubRecipeLogic getRecipeLogic() {
        return (WorldlineCrackingHubRecipeLogic) recipeLogic;
    }

    @Override
    public int getMaxParallel() {
        return 64;
    }

    @Override
    public long getMaxVoltage() {
        return Long.MAX_VALUE;
    }

    @Override
    protected void addParallelDisplay(List<Component> textList) {
        textList.add(Component.literal("")
                .append(Component.literal("可裂解 "))
                .append(DShanhaiTextUtil.createUltimateRainbow("所有世线"))
                .append(Component.literal(" 的可能形态")));
        textList.add(Component.translatable("gtladditions.multiblock.threads", 64));
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        if (isFormed()) {
            addEnergyDisplay(textList);
            addParallelDisplay(textList);
            addWorkingStatus(textList);
        } else {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure")
                    .withStyle(ChatFormatting.RED));
        }
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
    }
}
