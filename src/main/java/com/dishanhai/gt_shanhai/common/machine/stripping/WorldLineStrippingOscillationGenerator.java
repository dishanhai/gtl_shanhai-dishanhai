package com.dishanhai.gt_shanhai.common.machine.stripping;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;

import com.google.common.primitives.Ints;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;

import java.util.List;
import java.util.UUID;

/**
 * 世线剥离震荡发生器
 * 终焉创始现实修改矩阵的附属模块机器
 * 从宇宙模拟和星核剥离中提取世线能量，剥离现实分支线
 */
public class WorldLineStrippingOscillationGenerator extends com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine {

    private static final long MAX_PARALLEL = 9223372036854775807L;

    public WorldLineStrippingOscillationGenerator(IMachineBlockEntity holder) {
        super(holder);
        if (getUuid() == null) setUuid(UUID.randomUUID());
    }

    @Override
    public WorldLineStrippingOscillationGeneratorLogic createRecipeLogic(Object... args) {
        return new WorldLineStrippingOscillationGeneratorLogic(this);
    }

    @Override
    public WorldLineStrippingOscillationGeneratorLogic getRecipeLogic() {
        return (WorldLineStrippingOscillationGeneratorLogic) recipeLogic;
    }

    @Override
    public int getMaxParallel() {
        return Ints.saturatedCast(MAX_PARALLEL);
    }

    @Override
    public long getMaxVoltage() { return Long.MAX_VALUE; }

    @Override
    public int getTier() { return 9; }

    @Override
    public java.util.List<com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider> getSubTabs() {
        return java.util.Collections.emptyList();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (getUuid() == null) {
            setUuid(UUID.randomUUID());
        }
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        // 环隐藏由 FOTC 渲染器每帧处理
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
    }

    @Override
    protected void addParallelDisplay(List<Component> textList) {
        var inf = DShanhaiTextUtil.createUltimateRainbow("无限");
        textList.add(Component.literal("")
                .append(Component.literal("同时处理至多"))
                .append(inf)
                .append(Component.literal("个配方")));
        textList.add(Component.translatable("gtladditions.multiblock.threads", inf));
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        if (isFormed()) {
            addEnergyDisplay(textList);
            addParallelDisplay(textList);
            addWorkingStatus(textList);
            textList.add(Component.translatable("gt_shanhai.machine.world_line_stripping_oscillation_generator.mode")
                    .withStyle(ChatFormatting.GREEN));
        } else {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure")
                    .withStyle(ChatFormatting.RED));
        }
        textList.add(Component.translatable("module.world_line_stripping_oscillation_generator.name")
                .withStyle(ChatFormatting.GOLD));
    }
}
