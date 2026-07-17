package com.dishanhai.gt_shanhai.common.machine.primordial.module.collector;
import com.dishanhai.gt_shanhai.common.machine.primordial.*;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;

import com.gtladd.gtladditions.common.machine.multiblock.structure.MultiBlockStructure;

public class PrimordialCosmicOriginCoreStructure {

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        Block bronzeCasing = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtceu", "bronze_machine_casing"));
        Block industrialSteamCasing = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtceu", "industrial_steam_casing"));

        var blocks = Predicates.blocks(
                bronzeCasing, industrialSteamCasing);

        return MultiBlockStructure.INSTANCE.getFORGE_OF_THE_ANTICHRIST_MODULE()
                .where('A', blocks)
                .where('B', blocks
                        .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1)))
                .where('C', blocks)
                .where('D', blocks)
                .where('E', blocks)
                .where('F', blocks)
                .where('G', blocks)
                .where('H', blocks)
                .where('I', blocks)
                .where('J', blocks)
                .where('K', blocks)
                .where('L', blocks)
                .where('M', blocks)
                .where('N', blocks)
                .where('O', blocks)
                .where('P', blocks)
                .where('Q', blocks)
                .where('S', blocks)
                .where('~', Predicates.controller(Predicates.blocks(definition.getBlock())))
                .build();
    }

    private PrimordialCosmicOriginCoreStructure() {}
}
