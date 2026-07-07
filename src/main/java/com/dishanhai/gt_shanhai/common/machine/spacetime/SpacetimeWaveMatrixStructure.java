package com.dishanhai.gt_shanhai.common.machine.spacetime;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;

import com.gtladd.gtladditions.common.machine.multiblock.structure.MultiBlockStructure;

public class SpacetimeWaveMatrixStructure {

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        // 量子主题方块 — 主机周围
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

        // 时空撕裂主题方块
        Block spacetimeBlock = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtceu", "spacetime_block"));
        Block tearBlock = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtceu", "tear_block"));
        Block spacetimeContinuumRipper = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtlcore", "spacetimecontinuumripper"));
        Block spacetimeBendingCore = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtlcore", "spacetimebendingcore"));
        Block worldLineStrippingModule = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "world_line_stripping_oscillation_generator"));

        return MultiBlockStructure.INSTANCE.getFORGE_OF_THE_ANTICHRIST()
                // B: 主机外圈 — 量子致密外壳 + 仓体
                .where('B', Predicates.blocks(extremeDensityCasing)
                        .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1)))
                // A: 顶底内层 — 量子桥接外壳 | 时空弯曲核心
                .where('A', Predicates.blocks(dimensionalBridgeCasing)
                        .or(Predicates.blocks(spacetimeBendingCore)))
                // C: 核心圈内层 — 量子操作器 | 时空撕裂器
                .where('C', Predicates.blocks(manipulator)
                        .or(Predicates.blocks(spacetimeContinuumRipper)))
                // D: 背面结构 — 时空方块 | 泪滴方块
                .where('D', Predicates.blocks(spacetimeBlock)
                        .or(Predicates.blocks(tearBlock)))
                // E: 玻璃 — 量子玻璃
                .where('E', Predicates.blocks(quantumGlass))
                // F: 核心圈外圈 — 量子锻造线圈 + 仓体 | 时空方块
                .where('F', Predicates.blocks(qftCoil)
                        .or(Predicates.blocks(spacetimeBlock))
                        .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1)))
                // G/H/I/J/K: 内部填充及16顶点映射 — 时空撕裂主题
                .where('G', Predicates.blocks(extremeDensityCasing)
                        .or(Predicates.blocks(spacetimeBlock)))
                .where('H', Predicates.blocks(spacetimeBlock)
                        .or(Predicates.blocks(tearBlock)))
                .where('I', Predicates.blocks(dimensionalBridgeCasing)
                        .or(Predicates.blocks(spacetimeContinuumRipper)))
                .where('J', Predicates.blocks(extremeDensityCasing)
                        .or(Predicates.blocks(spacetimeBendingCore))
                        .or(Predicates.blocks(worldLineStrippingModule)))
                .where('K', Predicates.blocks(extremeDensityCasing)
                        .or(Predicates.blocks(tearBlock)))
                .where('~', Predicates.controller(Predicates.blocks(definition.getBlock())))
                .where(' ', Predicates.any())
                .build();
    }

    private SpacetimeWaveMatrixStructure() {}
}
