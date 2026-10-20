package com.dishanhai.gt_shanhai.common.machine.structure;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.DShanhaiMBParser;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
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

        return DShanhaiMBParser.parse(
            new ResourceLocation("gt_shanhai", "str_structure"),
            c -> {
                switch (c) {
                    case '~': return Predicates.controller(Predicates.blocks(definition.getBlock()));
                    case 'A': return Predicates.blocks(dimensionallyTranscendentCasing);
                    case 'B': return Predicates.blocks(dimensionalBridgeCasing);
                    case 'C': return Predicates.blocks(highStrengthConcrete);
                    case 'D': return Predicates.blocks(plascrete);
                    case 'E': return Predicates.blocks(hollowCasing);
                    case 'F': return Predicates.blocks(magicCore);
                    case 'G': return Predicates.blocks(speedingPipe);
                    case 'H': return Predicates.blocks(containmentFieldGenerator);
                    case 'I': return Predicates.blocks(molecularCasing);
                    case 'J': return Predicates.blocks(highPowerCasing);
                    case 'K': return Predicates.blocks(degenerateRheniumConstrainedCasing);
                    case 'L': return Predicates.blocks(rheniumReinforcedEnergyGlass);
                    case 'M': return Predicates.blocks(lava);
                    case 'N': return Predicates.blocks(annihilateCore);
                    case 'O': return Predicates.blocks(dimensionalStabilityCasing);
                    case 'P': return Predicates.blocks(spacetimeBendingCore);
                    case 'Q': return Predicates.blocks(gravityStabilizationCasing);
                    case 'R': return Predicates.blocks(spaceElevatorInternalSupport);
                    case 'S': return Predicates.blocks(spacetimeContinuumRipper);
                    case 'T': return Predicates.blocks(gravitationalWaveAntennaTransmitter);
                    case 'U': return Predicates.blocks(dimensionallyTranscendentCasing, dimensionalBridgeCasing, molecularCasing)
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.COMPUTATION_DATA_RECEPTION).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.COMPUTATION_DATA_TRANSMISSION).setPreviewCount(1));
                    default: return null;
                }
            },
            RelativeDirection.BACK, RelativeDirection.UP, RelativeDirection.LEFT);
    }
}
