package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import com.dishanhai.gt_shanhai.api.DShanhaiMBParser;
import com.dishanhai.gt_shanhai.common.machine.DShanhaiMachines;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;

/** 山海：15×11×17 星律主庫；文件每行為一層，由上往下，各段由前往後。 */
public final class StellarCloudLibraryStructure {
    public static final int WIDTH = 15, HEIGHT = 11, DEPTH = 17;
    public static final int CONTROLLER_Y = 3, CORE_Y = 6, CORE_Z = 8;
    /** 山海：僅保存 id，待結構工廠呼叫時才解析，支援 KubeJS 的註冊時序。 */
    public static final Map<Character, String> MATERIALS = Map.of(
            'M', "gtlcore:molecular_casing",
            'I', "gtlcore:iridium_casing",
            'N', "gtlcore:naquadah_alloy_casing",
            'P', "gtceu:high_power_casing",
            'G', "gtceu:fusion_glass",
            'F', "kubejs:containment_field_generator",
            'H', "kubejs:hollow_casing",
            'C', "kubejs:high_strength_concrete");

    private StellarCloudLibraryStructure() {}

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        Map<Character, TraceabilityPredicate> palette = new HashMap<>();
        MATERIALS.forEach((symbol, id) -> palette.put(symbol, Predicates.blocks(requireBlock(id))));
        palette.put('I', palette.get('I').or(
                Predicates.blocks(DShanhaiMachines.CLOUD_PATTERN_LIBRARY_AE_CORE.getBlock()).setExactLimit(1)));
        palette.put('~', Predicates.controller(Predicates.blocks(definition.getBlock())));
        palette.put('#', Predicates.air());
        palette.put('.', Predicates.any());
        return DShanhaiMBParser.parse(new ResourceLocation("gt_shanhai", "cloud_pattern_library"),
                symbol -> {
                    var predicate = palette.get(symbol);
                    if (predicate == null) throw new IllegalArgumentException("Unknown cloud library symbol: " + symbol);
                    return predicate;
                }, RelativeDirection.RIGHT, RelativeDirection.BACK, RelativeDirection.DOWN);
    }

    public static Block appearanceBlock() {
        return requireBlock(MATERIALS.get('I'));
    }

    private static Block requireBlock(String id) {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
        if (block == null || block == Blocks.AIR) {
            throw new IllegalStateException("Missing cloud library block: " + id);
        }
        return block;
    }
}
