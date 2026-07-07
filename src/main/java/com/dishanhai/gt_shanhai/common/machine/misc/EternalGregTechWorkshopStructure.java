package com.dishanhai.gt_shanhai.common.machine.misc;

import com.dishanhai.gt_shanhai.api.DShanhaiMBParser;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/** GTNL Eternal GregTech Workshop main structure, ported from the original .mb files. */
public final class EternalGregTechWorkshopStructure {

    private static final List<DShanhaiMBParser.PiecePlacement> MAIN_PIECES = List.of(
            DShanhaiMBParser.piece(id("top"), 37, 46, 11, true),
            DShanhaiMBParser.piece(id("up"), 26, 27, 0, true),
            DShanhaiMBParser.piece(id("center"), 26, 5, 1, true),
            DShanhaiMBParser.piece(id("down"), 26, -6, 0, true),
            DShanhaiMBParser.piece(id("bottom"), 37, -28, 11, true));

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        return DShanhaiMBParser.parsePlacedSequence(MAIN_PIECES, ch -> map(ch, definition),
                com.gregtechceu.gtceu.api.pattern.util.RelativeDirection.LEFT,
                com.gregtechceu.gtceu.api.pattern.util.RelativeDirection.UP,
                com.gregtechceu.gtceu.api.pattern.util.RelativeDirection.FRONT);
    }

    private static ResourceLocation id(String piece) {
        return new ResourceLocation("gt_shanhai", "eternal_gregtech_workshop/" + piece);
    }

    private static TraceabilityPredicate map(char ch, MultiblockMachineDefinition definition) {
        return switch (ch) {
            case '~' -> Predicates.controller(Predicates.blocks(definition.getBlock()));
            case 'A' -> block("gtladditions:god_forge_trim_casing");
            case 'B' -> block("gtlcore:component_assembly_line_casing_max");
            case 'C' -> block("gtlcore:dimension_injection_casing");
            case 'D' -> block("gtlcore:manipulator");
            case 'E' -> block("gtladditions:extreme_density_casing");
            case 'F' -> block("dishanhai:naquadria_reinforced_water_plant_casing");
            case 'G' -> block("gtladditions:god_forge_inner_casing");
            case 'H' -> block("gtlcore:dimension_connection_casing");
            case 'I' -> block("gtceu:computer_casing");
            case 'J' -> block("dishanhai:particle_beam_guidance_pipe_casing");
            case 'K' -> block("gtceu:naquadah_alloy_frame");
            case 'L' -> block("dishanhai:omni_purpose_infinity_fused_glass");
            case 'M' -> block("dishanhai:transcendentally_reinforced_borosilicate_glass");
            case 'N' -> inputOutputOr("gtladditions:god_forge_inner_casing");
            case 'O' -> block("dishanhai:quark_exclusion_casing");
            case 'P' -> block("dishanhai:reinforced_temporal_structure_casing");
            case 'Q' -> block("gtladditions:central_graviton_flow_regulator");
            case 'R' -> block("gtladditions:god_forge_energy_casing");
            case 'S' -> block("gtladditions:remote_graviton_flow_regulator");
            case 'T' -> block("gtladditions:gravity_stabilization_casing");
            case 'U' -> block("dishanhai:gallifreyan_spacetime_compression_field_generator");
            case 'V' -> block("dishanhai:gallifreyan_time_dilation_field_generator");
            case 'W' -> block("dishanhai:reinforced_spatial_structure_casing");
            case 'X' -> block("dishanhai:gallifreyan_stabilisation_field_generator");
            case 'Y' -> workshopModuleOr("gtladditions:god_forge_trim_casing");
            case 'Z' -> block("gtlcore:dimension_connection_casing");
            case 'a' -> Predicates.blocks(Blocks.AIR);
            case ' ' -> Predicates.any();
            default -> Predicates.any();
        };
    }

    private static TraceabilityPredicate inputOutputOr(String fallbackId) {
        return block(fallbackId)
                .or(Predicates.abilities(PartAbility.MAINTENANCE).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1));
    }

    private static TraceabilityPredicate workshopModuleOr(String fallbackId) {
        TraceabilityPredicate predicate = block(fallbackId);
        predicate = predicate.or(optionalBlock("gt_shanhai:eternal_gregtech_workshop_fusion_module"));
        predicate = predicate.or(optionalBlock("gt_shanhai:eternal_gregtech_workshop_eye_of_harmony_module"));
        predicate = predicate.or(optionalBlock("gt_shanhai:eternal_gregtech_workshop_extra_module"));
        return predicate;
    }

    private static TraceabilityPredicate optionalBlock(String id) {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
        if (block == null || block == Blocks.AIR) {
            GTDishanhaiMod.LOGGER.warn("Missing optional block for Eternal GregTech Workshop module slot: {}", id);
            return null;
        }
        return Predicates.blocks(block);
    }

    private static TraceabilityPredicate block(String id) {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
        if (block == null || block == Blocks.AIR) {
            GTDishanhaiMod.LOGGER.warn("Missing block for Eternal GregTech Workshop structure preview: {}", id);
            return Predicates.any();
        }
        return Predicates.blocks(block);
    }

    private EternalGregTechWorkshopStructure() {}
}
