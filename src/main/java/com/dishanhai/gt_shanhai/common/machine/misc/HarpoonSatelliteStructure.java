package com.dishanhai.gt_shanhai.common.machine.misc;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;

import com.dishanhai.gt_shanhai.common.machine.structure.HSData1;
import com.dishanhai.gt_shanhai.common.machine.structure.HSData2;
import com.dishanhai.gt_shanhai.common.machine.structure.HSData3;

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

        return FactoryBlockPattern.start(RelativeDirection.RIGHT, RelativeDirection.UP, RelativeDirection.BACK)
                .aisle(HSData1.D1)
                .aisle(HSData1.D2)
                .aisle(HSData1.D3)
                .aisle(HSData1.D4)
                .aisle(HSData1.D5)
                .aisle(HSData1.D6)
                .aisle(HSData1.D7)
                .aisle(HSData1.D8)
                .aisle(HSData1.D9)
                .aisle(HSData1.D10)
                .aisle(HSData1.D11)
                .aisle(HSData1.D12)
                .aisle(HSData1.D13)
                .aisle(HSData1.D14)
                .aisle(HSData1.D15)
                .aisle(HSData1.D16)
                .aisle(HSData1.D17)
                .aisle(HSData1.D18)
                .aisle(HSData1.D19)
                .aisle(HSData1.D20)
                .aisle(HSData1.D21)
                .aisle(HSData1.D22)
                .aisle(HSData1.D23)
                .aisle(HSData1.D24)
                .aisle(HSData1.D25)
                .aisle(HSData1.D26)
                .aisle(HSData1.D27)
                .aisle(HSData1.D28)
                .aisle(HSData1.D29)
                .aisle(HSData1.D30)
                .aisle(HSData1.D31)
                .aisle(HSData1.D32)
                .aisle(HSData1.D33)
                .aisle(HSData1.D34)
                .aisle(HSData1.D35)
                .aisle(HSData1.D36)
                .aisle(HSData1.D37)
                .aisle(HSData1.D38)
                .aisle(HSData1.D39)
                .aisle(HSData1.D40)
                .aisle(HSData2.D41)
                .aisle(HSData2.D42)
                .aisle(HSData2.D43)
                .aisle(HSData2.D44)
                .aisle(HSData2.D45)
                .aisle(HSData2.D46)
                .aisle(HSData2.D47)
                .aisle(HSData2.D48)
                .aisle(HSData2.D49)
                .aisle(HSData2.D50)
                .aisle(HSData2.D51)
                .aisle(HSData2.D52)
                .aisle(HSData2.D53)
                .aisle(HSData2.D54)
                .aisle(HSData2.D55)
                .aisle(HSData2.D56)
                .aisle(HSData2.D57)
                .aisle(HSData2.D58)
                .aisle(HSData2.D59)
                .aisle(HSData2.D60)
                .aisle(HSData2.D61)
                .aisle(HSData2.D62)
                .aisle(HSData2.D63)
                .aisle(HSData2.D64)
                .aisle(HSData2.D65)
                .aisle(HSData2.D66)
                .aisle(HSData2.D67)
                .aisle(HSData2.D68)
                .aisle(HSData2.D69)
                .aisle(HSData2.D70)
                .aisle(HSData2.D71)
                .aisle(HSData2.D72)
                .aisle(HSData2.D73)
                .aisle(HSData2.D74)
                .aisle(HSData2.D75)
                .aisle(HSData2.D76)
                .aisle(HSData2.D77)
                .aisle(HSData2.D78)
                .aisle(HSData2.D79)
                .aisle(HSData2.D80)
                .aisle(HSData3.D81)
                .aisle(HSData3.D82)

                .where('~', Predicates.controller(Predicates.blocks(definition.getBlock())))
                .where('D', Predicates.blocks(gtceu_iv_machine_casing)
                        .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1)))
                .where('A', Predicates.blocks(gtceu_assembly_line_casing))
                .where('B', Predicates.blocks(gtceu_high_temperature_smelting_casing))
                .where('C', Predicates.blocks(gtceu_iv_machine_casing))
                .where('E', Predicates.blocks(gtceu_iv_machine_casing))
                .where('F', Predicates.blocks(gtceu_titanium_pipe_casing))
                .where('G', Predicates.blocks(gtceu_cleanroom_glass))
                .where('H', Predicates.blocks(gtceu_iv_machine_casing))
                .where('I', Predicates.blocks(minecraft_lava))
                .where('J', Predicates.blocks(gtceu_iv_machine_casing))
                .where('K', Predicates.blocks(gtceu_cleanroom_glass))
                .where('L', Predicates.blocks(gtceu_iv_machine_casing))
                .where('M', Predicates.blocks(gtceu_iv_machine_casing))
                .where('N', Predicates.blocks(gtceu_laser_safe_engraving_casing))
                .where('O', Predicates.blocks(gtceu_high_temperature_smelting_casing))
                .where('P', Predicates.blocks(gtceu_cleanroom_glass))
                .where('Q', Predicates.blocks(gtceu_iv_machine_casing))
                .where('R', Predicates.blocks(gtceu_secure_maceration_casing))
                .where('T', Predicates.blocks(gtceu_iv_machine_casing))
                .where('U', Predicates.blocks(gtceu_ev_machine_casing))
                .where('V', Predicates.blocks(gtceu_high_power_casing))
                .where('W', Predicates.blocks(gtceu_laser_safe_engraving_casing))
                .where('X', Predicates.blocks(gtceu_engine_intake_casing))
                .where('Y', Predicates.blocks(gtceu_reaction_safe_mixing_casing))
                .where('Z', Predicates.blocks(gtceu_ev_machine_casing))
                .where('a', Predicates.blocks(gtceu_stainless_evaporation_casing))
                .where('b', Predicates.blocks(gtceu_ev_machine_casing))
                .where('c', Predicates.blocks(gtceu_titanium_pipe_casing))
                .where(' ', Predicates.any())
                .build();
    }

    private HarpoonSatelliteStructure() {}
}
