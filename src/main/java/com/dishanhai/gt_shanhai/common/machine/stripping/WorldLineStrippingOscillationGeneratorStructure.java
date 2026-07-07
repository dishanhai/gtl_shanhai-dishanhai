package com.dishanhai.gt_shanhai.common.machine.stripping;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;

import com.gtladd.gtladditions.common.machine.multiblock.structure.MultiBlockStructure;

/**
 * 世线剥离震荡发生器结构
 * 全字符统一映射到终焉创始现实修改矩阵的完整方块池
 */
public class WorldLineStrippingOscillationGeneratorStructure {

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        Block extremeDensityCasing = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtladditions", "extreme_density_casing"));
        Block quantumGlass = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtladditions", "quantum_glass"));
        Block qftCoil = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtlcore", "qft_coil"));
        Block manipulator = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtlcore", "manipulator"));
        Block dimensionalBridgeCasing = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("kubejs", "dimensional_bridge_casing"));
        Block spacetimeBlock = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtceu", "spacetime_block"));
        Block tearBlock = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtceu", "tear_block"));
        Block spacetimeContinuumRipper = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtlcore", "spacetimecontinuumripper"));
        Block spacetimeBendingCore = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtlcore", "spacetimebendingcore"));

        // 完整方块池 — 含量子玻璃，覆盖模块图案中所有字符可能出现的位置
        var allBlocks = Predicates.blocks(
                extremeDensityCasing, quantumGlass, qftCoil, manipulator,
                dimensionalBridgeCasing, spacetimeBlock, tearBlock,
                spacetimeContinuumRipper, spacetimeBendingCore);

        return MultiBlockStructure.INSTANCE.getFORGE_OF_THE_ANTICHRIST_MODULE()
                .where('A', allBlocks)
                .where('B', allBlocks
                        .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1)))
                .where('C', allBlocks)
                .where('D', allBlocks)
                .where('E', allBlocks)
                .where('F', allBlocks)
                .where('G', allBlocks)
                .where('H', allBlocks)
                .where('I', allBlocks)
                // J: 顶点模块位 — 允许安装世线剥离震荡发生器控制器
                .where('J', allBlocks
                        .or(Predicates.blocks(definition.getBlock())))
                .where('K', allBlocks)
                .where('L', allBlocks)
                .where('M', allBlocks)
                .where('N', allBlocks)
                .where('O', allBlocks)
                .where('P', allBlocks)
                .where('Q', allBlocks)
                .where('S', allBlocks)
                .where('~', Predicates.controller(Predicates.blocks(definition.getBlock())))
                .build();
    }

    private WorldLineStrippingOscillationGeneratorStructure() {}
}
