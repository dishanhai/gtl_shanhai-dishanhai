package com.dishanhai.gt_shanhai.common.machine.structure;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

/*** STR 多方块结构 (50x56x50) */
public class STRStructure {

    private static Block block(String id) {
        return block(id, Blocks.BARRIER);
    }

    private static Block block(String id, Block fallback) {
        Block resolved = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(id));
        if (resolved != null && resolved != Blocks.AIR) {
            return resolved;
        }
        Block safeFallback = fallback != null && fallback != Blocks.AIR ? fallback : Blocks.BARRIER;
        GTDishanhaiMod.LOGGER.warn("[STRStructure] Missing block {}, fallback to {}",
                id, ForgeRegistries.BLOCKS.getKey(safeFallback));
        return safeFallback;
    }

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        Block dimensionallyTranscendentCasing = block("gtlcore:dimensionally_transcendent_casing");
        Block dimensionalBridgeCasing = block("kubejs:dimensional_bridge_casing");
        Block highStrengthConcrete = block("kubejs:high_strength_concrete");
        Block plascrete = block("gtceu:plascrete");
        Block hollowCasing = block("kubejs:hollow_casing");
        Block magicCore = block("kubejs:magic_core");
        Block speedingPipe = block("kubejs:speeding_pipe");
        Block containmentFieldGenerator = block("kubejs:containment_field_generator");
        Block molecularCasing = block("gtlcore:molecular_casing");
        Block highPowerCasing = block("gtceu:high_power_casing");
        Block degenerateRheniumConstrainedCasing = block("gtlcore:degenerate_rhenium_constrained_casing");
        Block rheniumReinforcedEnergyGlass = block("gtlcore:rhenium_reinforced_energy_glass");
        Block lava = block("minecraft:lava");
        Block annihilateCore = block("kubejs:annihilate_core");
        Block dimensionalStabilityCasing = block("kubejs:dimensional_stability_casing");
        Block spacetimeBendingCore = block("gtlcore:spacetimebendingcore");
        Block gravityStabilizationCasing = block("gtladditions:gravity_stabilization_casing");
        Block spaceElevatorInternalSupport = block("kubejs:space_elevator_internal_support");
        Block spacetimeContinuumRipper = block("gtlcore:spacetimecontinuumripper");
        Block gravitationalWaveAntennaTransmitter = block("gt_shanhai:gravitational_wave_antenna_transmitter", definition.getBlock());

        return FactoryBlockPattern.start(RelativeDirection.BACK, RelativeDirection.UP, RelativeDirection.LEFT)
            .aisle(STRData1.D1)
            .aisle(STRData1.D2)
            .aisle(STRData1.D3)
            .aisle(STRData1.D4)
            .aisle(STRData1.D5)
            .aisle(STRData1.D6)
            .aisle(STRData1.D7)
            .aisle(STRData1.D8)
            .aisle(STRData1.D9)
            .aisle(STRData1.D10)
            .aisle(STRData1.D11)
            .aisle(STRData1.D12)
            .aisle(STRData1.D13)
            .aisle(STRData1.D14)
            .aisle(STRData1.D15)
            .aisle(STRData1.D16)
            .aisle(STRData1.D17)
            .aisle(STRData1.D18)
            .aisle(STRData1.D19)
            .aisle(STRData1.D20)
            .aisle(STRData1.D21)
            .aisle(STRData1.D22)
            .aisle(STRData1.D23)
            .aisle(STRData1.D24)
            .aisle(STRData1.D25)
            .aisle(STRData1.D26)
            .aisle(STRData1.D27)
            .aisle(STRData1.D28)
            .aisle(STRData1.D29)
            .aisle(STRData1.D30)
            .aisle(STRData2.D31)
            .aisle(STRData2.D32)
            .aisle(STRData2.D33)
            .aisle(STRData2.D34)
            .aisle(STRData2.D35)
            .aisle(STRData2.D36)
            .aisle(STRData2.D37)
            .aisle(STRData2.D38)
            .aisle(STRData2.D39)
            .aisle(STRData2.D40)
            .aisle(STRData2.D41)
            .aisle(STRData2.D42)
            .aisle(STRData2.D43)
            .aisle(STRData2.D44)
            .aisle(STRData2.D45)
            .aisle(STRData2.D46)
            .aisle(STRData2.D47)
            .aisle(STRData2.D48)
            .aisle(STRData2.D49)
            .aisle(STRData2.D50)

            .where('A', Predicates.blocks(dimensionallyTranscendentCasing))
            .where('B', Predicates.blocks(dimensionalBridgeCasing))
            .where('C', Predicates.blocks(highStrengthConcrete))
            .where('D', Predicates.blocks(plascrete))
            .where('E', Predicates.blocks(hollowCasing))
            .where('F', Predicates.blocks(magicCore))
            .where('G', Predicates.blocks(speedingPipe))
            .where('H', Predicates.blocks(containmentFieldGenerator))
            .where('I', Predicates.blocks(molecularCasing))
            .where('J', Predicates.blocks(highPowerCasing))
            .where('K', Predicates.blocks(degenerateRheniumConstrainedCasing))
            .where('L', Predicates.blocks(rheniumReinforcedEnergyGlass))
            .where('M', Predicates.blocks(lava))
            .where('N', Predicates.blocks(annihilateCore))
            .where('O', Predicates.blocks(dimensionalStabilityCasing))
            .where('P', Predicates.blocks(spacetimeBendingCore))
            .where('Q', Predicates.blocks(gravityStabilizationCasing))
            .where('R', Predicates.blocks(spaceElevatorInternalSupport))
            .where('S', Predicates.blocks(spacetimeContinuumRipper))
            .where('T', Predicates.blocks(gravitationalWaveAntennaTransmitter))
            .where('U', Predicates.blocks(dimensionallyTranscendentCasing, dimensionalBridgeCasing, molecularCasing)
                    .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                    .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                    .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                    .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1))
                    .or(Predicates.abilities(PartAbility.COMPUTATION_DATA_RECEPTION).setPreviewCount(1))
                    .or(Predicates.abilities(PartAbility.COMPUTATION_DATA_TRANSMISSION).setPreviewCount(1)))
            .where(' ', Predicates.any())
            .where('~', Predicates.controller(Predicates.blocks(definition.getBlock())))
            .build();
    }
}
