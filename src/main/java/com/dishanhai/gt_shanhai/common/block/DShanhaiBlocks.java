package com.dishanhai.gt_shanhai.common.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.client.renderer.RenderType;

import com.tterrag.registrate.util.entry.BlockEntry;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;

public class DShanhaiBlocks {

    public static final BlockEntry<Block> CASING_ASSEMBLY = GTDishanhaiRegistration.REGISTRATE
        .block("casing_assembly", Block::new)
        .initialProperties(() -> Blocks.IRON_BLOCK)
        .addLayer(() -> RenderType::cutoutMipped)
        .item(BlockItem::new)
        .build()
        .register();

    public static final BlockEntry<Block> CASING_MOLECULAR = GTDishanhaiRegistration.REGISTRATE
        .block("casing_molecular", Block::new)
        .initialProperties(() -> Blocks.IRON_BLOCK)
        .addLayer(() -> RenderType::cutoutMipped)
        .item(BlockItem::new)
        .build()
        .register();

    public static final BlockEntry<Block> CASING_TRANSCENDENT = GTDishanhaiRegistration.REGISTRATE
        .block("casing_transcendent", Block::new)
        .initialProperties(() -> Blocks.IRON_BLOCK)
        .addLayer(() -> RenderType::cutoutMipped)
        .item(BlockItem::new)
        .build()
        .register();

    public static final BlockEntry<Block> CASING_RHENIUM = GTDishanhaiRegistration.REGISTRATE
        .block("casing_rhenium", Block::new)
        .initialProperties(() -> Blocks.IRON_BLOCK)
        .addLayer(() -> RenderType::cutoutMipped)
        .item(BlockItem::new)
        .build()
        .register();

    public static final BlockEntry<Block> CASING_QUANTUM_GLASS = GTDishanhaiRegistration.REGISTRATE
        .block("casing_quantum_glass", Block::new)
        .initialProperties(() -> Blocks.GLASS)
        .addLayer(() -> RenderType::translucent)
        .item(BlockItem::new)
        .build()
        .register();

    /** 由 {@code GTDishanhaiGTAddon.initializeAddon()} 呼叫，藉此在 RegisterEvent 之前完成上面的靜態註冊。 */
    public static void init() {}

    private DShanhaiBlocks() {}
}
