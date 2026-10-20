package com.dishanhai.gt_shanhai.common.machine.misc;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;

import com.dishanhai.gt_shanhai.api.DShanhaiMBParser;

/** 天界星云零点虹吸枢纽 — 卫星结构映射 (81x112x82) */
public class HarpoonSatelliteStructure {

    private static Block get(String id) {
        return ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
    }

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        Block gtceu_assembly_line_casing = get("gtceu:assembly_line_casing");
        Block gtceu_cleanroom_glass = get("gtceu:cleanroom_glass");
        Block gtceu_engine_intake_casing = get("gtceu:engine_intake_casing");
        Block gtceu_ev_machine_casing = get("gtceu:ev_machine_casing");
        Block gtceu_high_power_casing = get("gtceu:high_power_casing");
        Block gtceu_high_temperature_smelting_casing = get("gtceu:high_temperature_smelting_casing");
        Block gtceu_iv_machine_casing = get("gtceu:iv_machine_casing");
        Block gtceu_laser_safe_engraving_casing = get("gtceu:laser_safe_engraving_casing");
        Block gtceu_reaction_safe_mixing_casing = get("gtceu:reaction_safe_mixing_casing");
        Block gtceu_secure_maceration_casing = get("gtceu:secure_maceration_casing");
        Block gtceu_stainless_evaporation_casing = get("gtceu:stainless_evaporation_casing");
        Block gtceu_titanium_pipe_casing = get("gtceu:titanium_pipe_casing");
        Block minecraft_lava = get("minecraft:lava");

        return DShanhaiMBParser.parse(
                new ResourceLocation("gt_shanhai", "harpoon_satellite"),
                c -> {
                    switch (c) {
                        case '~': return Predicates.controller(Predicates.blocks(definition.getBlock()));
                        case 'D': return Predicates.blocks(gtceu_iv_machine_casing)
                                .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                                .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                                .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                                .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1));
                        case 'A': return Predicates.blocks(gtceu_assembly_line_casing);
                        case 'B': return Predicates.blocks(gtceu_high_temperature_smelting_casing);
                        case 'C': return Predicates.blocks(gtceu_iv_machine_casing);
                        case 'E': return Predicates.blocks(gtceu_iv_machine_casing);
                        case 'F': return Predicates.blocks(gtceu_titanium_pipe_casing);
                        case 'G': return Predicates.blocks(gtceu_cleanroom_glass);
                        case 'H': return Predicates.blocks(gtceu_iv_machine_casing);
                        case 'I': return Predicates.blocks(minecraft_lava);
                        case 'J': return Predicates.blocks(gtceu_iv_machine_casing);
                        case 'K': return Predicates.blocks(gtceu_cleanroom_glass);
                        case 'L': return Predicates.blocks(gtceu_iv_machine_casing);
                        case 'M': return Predicates.blocks(gtceu_iv_machine_casing);
                        case 'N': return Predicates.blocks(gtceu_laser_safe_engraving_casing);
                        case 'O': return Predicates.blocks(gtceu_high_temperature_smelting_casing);
                        case 'P': return Predicates.blocks(gtceu_cleanroom_glass);
                        case 'Q': return Predicates.blocks(gtceu_iv_machine_casing);
                        case 'R': return Predicates.blocks(gtceu_secure_maceration_casing);
                        case 'T': return Predicates.blocks(gtceu_iv_machine_casing);
                        case 'U': return Predicates.blocks(gtceu_ev_machine_casing);
                        case 'V': return Predicates.blocks(gtceu_high_power_casing);
                        case 'W': return Predicates.blocks(gtceu_laser_safe_engraving_casing);
                        case 'X': return Predicates.blocks(gtceu_engine_intake_casing);
                        case 'Y': return Predicates.blocks(gtceu_reaction_safe_mixing_casing);
                        case 'Z': return Predicates.blocks(gtceu_ev_machine_casing);
                        case 'a': return Predicates.blocks(gtceu_stainless_evaporation_casing);
                        case 'b': return Predicates.blocks(gtceu_ev_machine_casing);
                        case 'c': return Predicates.blocks(gtceu_titanium_pipe_casing);
                        default: return null;
                    }
                },
                RelativeDirection.RIGHT, RelativeDirection.UP, RelativeDirection.BACK);
    }

    private HarpoonSatelliteStructure() {}
}
