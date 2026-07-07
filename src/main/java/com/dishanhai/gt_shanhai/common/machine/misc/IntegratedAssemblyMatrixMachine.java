package com.dishanhai.gt_shanhai.common.machine.misc;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.api.DShanhaiMBParser;
import com.dishanhai.gt_shanhai.common.block.DShanhaiBlocks;

import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;

import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleTypeRecipesMachine;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.List;

import static com.dishanhai.gt_shanhai.GTDishanhaiMod.MOD_ID;

public class IntegratedAssemblyMatrixMachine extends GTLAddWirelessWorkableElectricMultipleTypeRecipesMachine {

    private int cachedTier = 1;

    public IntegratedAssemblyMatrixMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, GTRecipeTypes.ASSEMBLY_LINE_RECIPES, args);
    }

    @Override
    public GTRecipeType[] getRecipeTypes() {
        return new GTRecipeType[] { GTRecipeTypes.ASSEMBLY_LINE_RECIPES };
    }

    @Override
    public GTRecipeType getRecipeType() {
        return GTRecipeTypes.ASSEMBLY_LINE_RECIPES;
    }

    // ==================== 结构等级检测 ====================

    private void detectStructureTier() {
        if (!isFormed() || getLevel() == null) {
            cachedTier = 1;
            return;
        }
        int found = 0;
        BlockPos center = getPos();
        Block tier2Block = DShanhaiBlocks.CASING_TRANSCENDENT.get();
        Block tier3Block = DShanhaiBlocks.CASING_RHENIUM.get();

        BlockPos[] checkPositions = {
            center.offset(4, 0, 0), center.offset(-4, 0, 0),
            center.offset(1, 0, 1), center.offset(-1, 0, 1)
        };

        for (BlockPos pos : checkPositions) {
            Block block = getLevel().getBlockState(pos).getBlock();
            if (block.equals(tier3Block)) {
                found += 2;
            } else if (block.equals(tier2Block)) {
                found += 1;
            }
        }

        if (found >= 6) cachedTier = 3;
        else if (found >= 2) cachedTier = 2;
        else cachedTier = 1;
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        detectStructureTier();
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        cachedTier = 1;
    }

    // ==================== 并行 ====================

    @Override
    public int getMaxParallel() {
        if (!isFormed()) return 1;
        return switch (cachedTier) {
            case 3 -> 256;
            case 2 -> 64;
            default -> 16;
        };
    }

    // ==================== 内部物流 — 自动填充空缺槽位 ====================

    @Override
    public long getMaxVoltage() {
        return super.getMaxVoltage();
    }

    // ==================== Jade 显示 ====================

    @Override
    public void addDisplayText(List<Component> textList) {
        if (isFormed()) {
            textList.add(Component.literal("§e结构等级: §fTier " + cachedTier
                + " §7· §b并行: §f" + getMaxParallel()));
            textList.add(Component.literal("§7集成物流 · 1.5×基础耗时 · 高批次产能"));
        } else {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure")
                .withStyle(ChatFormatting.RED));
        }
        textList.add(Component.literal("§c集成装配矩阵 §7- TST 移植").withStyle(ChatFormatting.DARK_RED));
    }

    // ==================== 结构定义 ====================

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        return DShanhaiMBParser.parseTransposed(
            new ResourceLocation(MOD_ID, "integrated_assembly_matrix"),
            c -> {
                if (c == '~') return Predicates.controller(Predicates.blocks(definition.getBlock()));
                if (c == 'A') return Predicates.blocks(DShanhaiBlocks.CASING_QUANTUM_GLASS.get());
                if (c == 'B') return Predicates.blocks(DShanhaiBlocks.CASING_ASSEMBLY.get());
                if (c == 'C') return Predicates.blocks(DShanhaiBlocks.CASING_MOLECULAR.get());
                if (c == 'D') return Predicates.blocks(DShanhaiBlocks.CASING_TRANSCENDENT.get())
                    .or(Predicates.blocks(DShanhaiBlocks.CASING_ASSEMBLY.get()));
                if (c == 'F') return Predicates.blocks(DShanhaiBlocks.CASING_RHENIUM.get())
                    .or(Predicates.blocks(DShanhaiBlocks.CASING_TRANSCENDENT.get()))
                    .or(Predicates.blocks(DShanhaiBlocks.CASING_ASSEMBLY.get()));
                if (c == 'G') return Predicates.blocks(DShanhaiBlocks.CASING_MOLECULAR.get());
                if (c == 'H') return Predicates.blocks(DShanhaiBlocks.CASING_QUANTUM_GLASS.get());
                if (c == 'E') return Predicates.blocks(DShanhaiBlocks.CASING_ASSEMBLY.get())
                    .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                    .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                    .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                    .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1))
                    .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setPreviewCount(1));
                return null;
            }
        );
    }

    // ==================== 注册 ====================

    public static MultiblockMachineDefinition register() {
        var def = GTDishanhaiRegistration.REGISTRATE
            .multiblock("integrated_assembly_matrix", IntegratedAssemblyMatrixMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .recipeTypes(GTRecipeTypes.ASSEMBLY_LINE_RECIPES)
            .appearanceBlock(DShanhaiBlocks.CASING_ASSEMBLY::get)
            .pattern(IntegratedAssemblyMatrixMachine::createPattern)
            .workableCasingRenderer(
                new ResourceLocation(MOD_ID, "block/casing_assembly"),
                new ResourceLocation(MOD_ID, "block/multiblock/integrated_assembly_matrix"))
            .register();

        def.setTooltipBuilder((stack, tips) -> {
            tips.add(Component.literal("§c§l集成装配矩阵 §r§7- TST 移植"));
            tips.add(Component.literal(""));
            tips.add(Component.translatable("gt_shanhai.machine.integrated_assembly_matrix.tooltip.0"));
            tips.add(Component.translatable("gt_shanhai.machine.integrated_assembly_matrix.tooltip.1"));
            tips.add(Component.translatable("gt_shanhai.machine.integrated_assembly_matrix.tooltip.2"));
            tips.add(Component.literal(""));
            tips.add(Component.literal("§a配方: 装配线"));
            tips.add(Component.literal("§e结构等级 Tier 1-3: 并行 16/64/256"));
        });
        return def;
    }
}
