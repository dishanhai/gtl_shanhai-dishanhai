package com.dishanhai.gt_shanhai.common.machine.structure;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.resources.ResourceLocation;

/*** STR 多方块结构 (50x56x50) */
public class STRStructure {

    public static final Block DIMENSIONALLY_TRANSCENDENT_CASING;
    public static final Block DIMENSIONAL_BRIDGE_CASING;
    public static final Block HIGH_STRENGTH_CONCRETE;
    public static final Block PLASCRETE;
    public static final Block HOLLOW_CASING;
    public static final Block MAGIC_CORE;
    public static final Block SPEEDING_PIPE;
    public static final Block CONTAINMENT_FIELD_GENERATOR;
    public static final Block MOLECULAR_CASING;
    public static final Block HIGH_POWER_CASING;
    public static final Block DEGENERATE_RHENIUM_CONSTRAINED_CASING;
    public static final Block RHENIUM_REINFORCED_ENERGY_GLASS;
    public static final Block LAVA;
    public static final Block ANNIHILATE_CORE;
    public static final Block DIMENSIONAL_STABILITY_CASING;
    public static final Block SPACETIMEBENDINGCORE;
    public static final Block GRAVITY_STABILIZATION_CASING;
    public static final Block SPACE_ELEVATOR_INTERNAL_SUPPORT;
    public static final Block SPACETIMECONTINUUMRIPPER;
    public static final Block GRAVITATIONAL_WAVE_ANTENNA_TRANSMITTER;

    static {
        DIMENSIONALLY_TRANSCENDENT_CASING = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("gtlcore:dimensionally_transcendent_casing"));
        DIMENSIONAL_BRIDGE_CASING = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("kubejs:dimensional_bridge_casing"));
        HIGH_STRENGTH_CONCRETE = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("kubejs:high_strength_concrete"));
        PLASCRETE = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("gtceu:plascrete"));
        HOLLOW_CASING = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("kubejs:hollow_casing"));
        MAGIC_CORE = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("kubejs:magic_core"));
        SPEEDING_PIPE = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("kubejs:speeding_pipe"));
        CONTAINMENT_FIELD_GENERATOR = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("kubejs:containment_field_generator"));
        MOLECULAR_CASING = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("gtlcore:molecular_casing"));
        HIGH_POWER_CASING = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("gtceu:high_power_casing"));
        DEGENERATE_RHENIUM_CONSTRAINED_CASING = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("gtlcore:degenerate_rhenium_constrained_casing"));
        RHENIUM_REINFORCED_ENERGY_GLASS = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("gtlcore:rhenium_reinforced_energy_glass"));
        LAVA = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:lava"));
        ANNIHILATE_CORE = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("kubejs:annihilate_core"));
        DIMENSIONAL_STABILITY_CASING = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("kubejs:dimensional_stability_casing"));
        SPACETIMEBENDINGCORE = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("gtlcore:spacetimebendingcore"));
        GRAVITY_STABILIZATION_CASING = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("gtladditions:gravity_stabilization_casing"));
        SPACE_ELEVATOR_INTERNAL_SUPPORT = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("kubejs:space_elevator_internal_support"));
        SPACETIMECONTINUUMRIPPER = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("gtlcore:spacetimecontinuumripper"));
        GRAVITATIONAL_WAVE_ANTENNA_TRANSMITTER = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("gt_shanhai:gravitational_wave_antenna_transmitter"));
    }

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
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

            .where('A', Predicates.blocks(DIMENSIONALLY_TRANSCENDENT_CASING))
            .where('B', Predicates.blocks(DIMENSIONAL_BRIDGE_CASING))
            .where('C', Predicates.blocks(HIGH_STRENGTH_CONCRETE))
            .where('D', Predicates.blocks(PLASCRETE))
            .where('E', Predicates.blocks(HOLLOW_CASING))
            .where('F', Predicates.blocks(MAGIC_CORE))
            .where('G', Predicates.blocks(SPEEDING_PIPE))
            .where('H', Predicates.blocks(CONTAINMENT_FIELD_GENERATOR))
            .where('I', Predicates.blocks(MOLECULAR_CASING))
            .where('J', Predicates.blocks(HIGH_POWER_CASING))
            .where('K', Predicates.blocks(DEGENERATE_RHENIUM_CONSTRAINED_CASING))
            .where('L', Predicates.blocks(RHENIUM_REINFORCED_ENERGY_GLASS))
            .where('M', Predicates.blocks(LAVA))
            .where('N', Predicates.blocks(ANNIHILATE_CORE))
            .where('O', Predicates.blocks(DIMENSIONAL_STABILITY_CASING))
            .where('P', Predicates.blocks(SPACETIMEBENDINGCORE))
            .where('Q', Predicates.blocks(GRAVITY_STABILIZATION_CASING))
            .where('R', Predicates.blocks(SPACE_ELEVATOR_INTERNAL_SUPPORT))
            .where('S', Predicates.blocks(SPACETIMECONTINUUMRIPPER))
            .where('T', Predicates.blocks(GRAVITATIONAL_WAVE_ANTENNA_TRANSMITTER))
            .where('U', Predicates.blocks(DIMENSIONALLY_TRANSCENDENT_CASING, DIMENSIONAL_BRIDGE_CASING, MOLECULAR_CASING)
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