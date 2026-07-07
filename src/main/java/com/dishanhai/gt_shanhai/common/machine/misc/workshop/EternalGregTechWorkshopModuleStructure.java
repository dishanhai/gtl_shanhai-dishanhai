package com.dishanhai.gt_shanhai.common.machine.misc.workshop;

import com.dishanhai.gt_shanhai.api.DShanhaiMBParser;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/** GTNL Eternal GregTech Workshop module structure, ported from the original module.mb. */
public final class EternalGregTechWorkshopModuleStructure {

    private static final ResourceLocation MODULE = id("module");

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        return DShanhaiMBParser.parseSequenceTransposed(List.of(MODULE), ch -> map(ch, definition),
                RelativeDirection.RIGHT, RelativeDirection.UP, RelativeDirection.BACK);
    }

    private static ResourceLocation id(String piece) {
        return new ResourceLocation("gt_shanhai", "eternal_gregtech_workshop/" + piece);
    }

    private static TraceabilityPredicate map(char ch, MultiblockMachineDefinition definition) {
        return switch (ch) {
            case '~' -> Predicates.controller(Predicates.blocks(definition.getBlock()));
            case 'A' -> block("dishanhai:transcendentally_reinforced_borosilicate_glass");
            case 'B' -> block("gtlcore:component_assembly_line_casing_max");
            case 'C' -> block("gtlcore:dimension_injection_casing");
            case 'D' -> block("gtlcore:dimension_connection_casing");
            case 'E' -> block("gtladditions:extreme_density_casing");
            case 'F' -> block("dishanhai:particle_beam_guidance_pipe_casing");
            case 'G' -> block("gtlcore:manipulator");
            case 'H' -> block("gtceu:naquadah_alloy_frame");
            case 'I' -> block("gtladditions:god_forge_trim_casing");
            case 'J' -> block("gtladditions:god_forge_inner_casing");
            case 'K' -> inputOutputOrAir("gtladditions:god_forge_trim_casing");
            case ' ' -> Predicates.any();
            default -> Predicates.any();
        };
    }

    private static TraceabilityPredicate inputOutputOrAir(String fallbackId) {
        return Predicates.blocks(Blocks.AIR)
                .or(block(fallbackId))
                .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1));
    }

    private static TraceabilityPredicate block(String id) {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
        if (block == null || block == Blocks.AIR) {
            GTDishanhaiMod.LOGGER.warn("Missing block for Eternal GregTech Workshop module structure preview: {}", id);
            return Predicates.any();
        }
        return Predicates.blocks(block);
    }

    private EternalGregTechWorkshopModuleStructure() {}
}
