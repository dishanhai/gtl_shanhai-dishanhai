package com.dishanhai.gt_shanhai.common.machine.primordial.module.crafting;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gtladd.gtladditions.common.machine.multiblock.structure.MultiBlockStructure;
import org.gtlcore.gtlcore.api.machine.multiblock.GTLPartAbility;
import org.gtlcore.gtlcore.common.data.GTLMachines;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

public final class PrimordialMolecularAssemblerModuleStructure {

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        Block bronzeCasing = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtceu", "bronze_machine_casing"));
        Block industrialSteamCasing = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtceu", "industrial_steam_casing"));
        var casings = Predicates.blocks(bronzeCasing, industrialSteamCasing);
        var io = Predicates.blocks(GTLMachines.GTAEMachines.ME_MOLECULAR_ASSEMBLER_IO.get())
                .setExactLimit(1)
                .setPreviewCount(1);
        var patternContainers = Predicates.abilities(GTLPartAbility.MOLECULAR_ASSEMBLER_MATRIX)
                .setMinGlobalLimited(1)
                .setPreviewCount(1);

        return MultiBlockStructure.INSTANCE.getFORGE_OF_THE_ANTICHRIST_MODULE()
                .where('A', casings)
                .where('B', casings
                        .or(io)
                        .or(patternContainers)
                        .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1)))
                .where('C', casings)
                .where('D', casings)
                .where('E', casings)
                .where('F', casings)
                .where('G', casings)
                .where('H', casings)
                .where('I', casings)
                .where('J', casings)
                .where('K', casings)
                .where('L', casings)
                .where('M', casings)
                .where('N', casings)
                .where('O', casings)
                .where('P', casings)
                .where('Q', casings)
                .where('S', casings)
                .where('~', Predicates.controller(Predicates.blocks(definition.getBlock())))
                .build();
    }

    private PrimordialMolecularAssemblerModuleStructure() {}
}
