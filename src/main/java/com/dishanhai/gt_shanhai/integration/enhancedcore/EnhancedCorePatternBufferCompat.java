package com.dishanhai.gt_shanhai.integration.enhancedcore;

import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;

import java.util.Arrays;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import net.minecraftforge.registries.ForgeRegistries;

/** 提供 EnhancedCore 聯合工廠使用的樣板總成結構謂詞。 */
public final class EnhancedCorePatternBufferCompat {

    // 候選順序同時用於預覽與自動搭建：保留原版多功能機械方塊為第一候選，
    // 再追加樣板總成；這樣既不改變原版外觀映射，也允許倉室方塊接入。
    private static final String[] PATTERN_BUFFER_IDS = {
            "gtlcore:multi_functional_casing",
            "gt_shanhai:recipe_type_pattern_buffer",
            "gtladditions:me_super_pattern_buffer",
            "gt_shanhai:recipe_type_pattern_buffer_proxy"
    };

    private EnhancedCorePatternBufferCompat() {}

    public static TraceabilityPredicate patternBufferPredicate() {
        Block[] blocks = new Block[PATTERN_BUFFER_IDS.length];
        int blockCount = 0;
        for (String id : PATTERN_BUFFER_IDS) {
            Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
            if (block == null || block == Blocks.AIR) {
                continue;
            }
            blocks[blockCount++] = block;
        }
        if (blockCount == 0) {
            throw new IllegalStateException("No pattern buffer block is registered for EnhancedCore integration");
        }
        // 單一 PredicateBlocks 讓外層 setMinGlobalLimited(1) 表示候選方塊擇一。
        return Predicates.blocks(Arrays.copyOf(blocks, blockCount));
    }
}
