package com.dishanhai.gt_shanhai.common.machine.worldline_cracking;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.resources.ResourceLocation;

/*** 世线裂解枢纽 多方块结构 (73x68x73) */
public class WorldlineCrackingHubStructure {

    public static final Block GRAY_CONCRETE;
    public static final Block HIGH_STRENGTH_CONCRETE;
    public static final Block PLASCRETE;
    public static final Block STRESS_PROOF_CASING;
    public static final Block POLISHED_DEEPSLATE_STAIRS;
    public static final Block GLASS_PANE;
    public static final Block POLISHED_DEEPSLATE_WALL;
    public static final Block OAK_LEAVES;
    public static final Block END_ROD;
    public static final Block CYAN_STAINED_GLASS_PANE;
    public static final Block NETHERITE_BLOCK;
    public static final Block LIGHT_BLUE_CONCRETE;
    public static final Block CYAN_STAINED_GLASS;
    public static final Block POLISHED_DEEPSLATE_SLAB;
    public static final Block REDSTONE_LAMP;
    public static final Block WATER;
    public static final Block PRISMARINE_WALL;
    public static final Block SEA_LANTERN;
    public static final Block POLISHED_DEEPSLATE;
    public static final Block PRISMARINE_BRICK_SLAB;
    public static final Block PRISMARINE_BRICK_STAIRS;
    public static final Block WARPED_TRAPDOOR;
    public static final Block WARPED_FENCE;
    public static final Block LIGHTNING_ROD;
    public static final Block BEACON;
    public static final Block REDSTONE_WIRE;
    public static final Block PENTLANDITE_ORE;

    static {
        GRAY_CONCRETE = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:gray_concrete"));
        HIGH_STRENGTH_CONCRETE = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("kubejs:high_strength_concrete"));
        PLASCRETE = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("gtceu:plascrete"));
        STRESS_PROOF_CASING = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("gtceu:stress_proof_casing"));
        POLISHED_DEEPSLATE_STAIRS = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:polished_deepslate_stairs"));
        GLASS_PANE = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:glass_pane"));
        POLISHED_DEEPSLATE_WALL = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:polished_deepslate_wall"));
        OAK_LEAVES = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:oak_leaves"));
        END_ROD = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:end_rod"));
        CYAN_STAINED_GLASS_PANE = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:cyan_stained_glass_pane"));
        NETHERITE_BLOCK = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:netherite_block"));
        LIGHT_BLUE_CONCRETE = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:light_blue_concrete"));
        CYAN_STAINED_GLASS = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:cyan_stained_glass"));
        POLISHED_DEEPSLATE_SLAB = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:polished_deepslate_slab"));
        REDSTONE_LAMP = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:redstone_lamp"));
        WATER = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:water"));
        PRISMARINE_WALL = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:prismarine_wall"));
        SEA_LANTERN = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:sea_lantern"));
        POLISHED_DEEPSLATE = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:polished_deepslate"));
        PRISMARINE_BRICK_SLAB = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:prismarine_brick_slab"));
        PRISMARINE_BRICK_STAIRS = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:prismarine_brick_stairs"));
        WARPED_TRAPDOOR = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:warped_trapdoor"));
        WARPED_FENCE = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:warped_fence"));
        LIGHTNING_ROD = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:lightning_rod"));
        BEACON = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:beacon"));
        REDSTONE_WIRE = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft:redstone_wire"));
        PENTLANDITE_ORE = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("gtceu:pentlandite_ore"));
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
            .aisle(STRData2.D51)
            .aisle(STRData2.D52)
            .aisle(STRData2.D53)
            .aisle(STRData2.D54)
            .aisle(STRData2.D55)
            .aisle(STRData2.D56)
            .aisle(STRData2.D57)
            .aisle(STRData2.D58)
            .aisle(STRData2.D59)
            .aisle(STRData2.D60)
            .aisle(STRData3.D61)
            .aisle(STRData3.D62)
            .aisle(STRData3.D63)
            .aisle(STRData3.D64)
            .aisle(STRData3.D65)
            .aisle(STRData3.D66)
            .aisle(STRData3.D67)
            .aisle(STRData3.D68)
            .aisle(STRData3.D69)
            .aisle(STRData3.D70)
            .aisle(STRData3.D71)
            .aisle(STRData3.D72)
            .aisle(STRData3.D73)

            .where('A', Predicates.blocks(GRAY_CONCRETE))
            .where('B', Predicates.blocks(HIGH_STRENGTH_CONCRETE))
            .where('C', Predicates.blocks(PLASCRETE))
            .where('D', Predicates.blocks(STRESS_PROOF_CASING))
            .where('E', Predicates.blocks(POLISHED_DEEPSLATE_STAIRS))
            .where('F', Predicates.blocks(GLASS_PANE))
            .where('G', Predicates.blocks(POLISHED_DEEPSLATE_WALL))
            .where('H', Predicates.blocks(OAK_LEAVES))
            .where('I', Predicates.blocks(END_ROD))
            .where('J', Predicates.blocks(CYAN_STAINED_GLASS_PANE))
            .where('K', Predicates.blocks(NETHERITE_BLOCK))
            .where('L', Predicates.blocks(LIGHT_BLUE_CONCRETE))
            .where('M', Predicates.blocks(CYAN_STAINED_GLASS))
            .where('N', Predicates.blocks(POLISHED_DEEPSLATE_SLAB))
            .where('O', Predicates.blocks(REDSTONE_LAMP))
            .where('P', Predicates.blocks(WATER))
            .where('Q', Predicates.blocks(PRISMARINE_WALL))
            .where('R', Predicates.blocks(SEA_LANTERN))
            .where('S', Predicates.blocks(POLISHED_DEEPSLATE))
            .where('T', Predicates.blocks(PRISMARINE_BRICK_SLAB))
            .where('U', Predicates.blocks(PRISMARINE_BRICK_STAIRS))
            .where('V', Predicates.blocks(WARPED_TRAPDOOR))
            .where('W', Predicates.blocks(WARPED_FENCE))
            .where('X', Predicates.blocks(LIGHTNING_ROD))
            .where('Y', Predicates.blocks(BEACON))
            .where('Z', Predicates.blocks(REDSTONE_WIRE))
            .where('a', Predicates.blocks(PENTLANDITE_ORE))
            .where(' ', Predicates.blocks(Blocks.AIR))
            .where('~', Predicates.controller(Predicates.blocks(definition.getBlock())))
            .build();
    }
}