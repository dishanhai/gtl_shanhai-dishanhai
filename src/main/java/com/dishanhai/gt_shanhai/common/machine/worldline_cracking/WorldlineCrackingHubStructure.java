package com.dishanhai.gt_shanhai.common.machine.worldline_cracking;

import com.dishanhai.gt_shanhai.api.DShanhaiMBParser;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import net.minecraft.world.level.block.Block;
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
        return DShanhaiMBParser.parse(
            new ResourceLocation("gt_shanhai", "worldline_cracking_hub"),
            c -> {
                switch (c) {
                    case '~': return Predicates.controller(Predicates.blocks(definition.getBlock()));
                    case 'A': return Predicates.blocks(GRAY_CONCRETE);
                    case 'B': return Predicates.blocks(HIGH_STRENGTH_CONCRETE);
                    case 'C': return Predicates.blocks(PLASCRETE);
                    case 'D': return Predicates.blocks(STRESS_PROOF_CASING);
                    case 'E': return Predicates.blocks(POLISHED_DEEPSLATE_STAIRS);
                    case 'F': return Predicates.blocks(GLASS_PANE);
                    case 'G': return Predicates.blocks(POLISHED_DEEPSLATE_WALL);
                    case 'H': return Predicates.blocks(OAK_LEAVES);
                    case 'I': return Predicates.blocks(END_ROD);
                    case 'J': return Predicates.blocks(CYAN_STAINED_GLASS_PANE);
                    case 'K': return Predicates.blocks(NETHERITE_BLOCK);
                    case 'L': return Predicates.blocks(LIGHT_BLUE_CONCRETE);
                    case 'M': return Predicates.blocks(CYAN_STAINED_GLASS);
                    case 'N': return Predicates.blocks(POLISHED_DEEPSLATE_SLAB);
                    case 'O': return Predicates.blocks(REDSTONE_LAMP);
                    case 'P': return Predicates.blocks(WATER);
                    case 'Q': return Predicates.blocks(PRISMARINE_WALL);
                    case 'R': return Predicates.blocks(SEA_LANTERN);
                    case 'S': return Predicates.blocks(POLISHED_DEEPSLATE);
                    case 'T': return Predicates.blocks(PRISMARINE_BRICK_SLAB);
                    case 'U': return Predicates.blocks(PRISMARINE_BRICK_STAIRS);
                    case 'V': return Predicates.blocks(WARPED_TRAPDOOR);
                    case 'W': return Predicates.blocks(WARPED_FENCE);
                    case 'X': return Predicates.blocks(LIGHTNING_ROD);
                    case 'Y': return Predicates.blocks(BEACON);
                    case 'Z': return Predicates.blocks(REDSTONE_WIRE);
                    case 'a': return Predicates.blocks(PENTLANDITE_ORE);
                    default: return null;
                }
            },
            RelativeDirection.BACK, RelativeDirection.UP, RelativeDirection.LEFT);
    }
}
