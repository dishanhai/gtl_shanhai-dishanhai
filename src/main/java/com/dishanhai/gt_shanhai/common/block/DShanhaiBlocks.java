package com.dishanhai.gt_shanhai.common.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.client.renderer.RenderType;

import com.tterrag.registrate.util.entry.BlockEntry;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterGeometry;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterShellBlock;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterInnerLatchBlock;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

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

    public static final BlockEntry<Block> AETHEROPHASIC_CASING = registerAetherophasicBlock("aetherophasic_casing", 0);
    public static final BlockEntry<Block> AETHEROPHASIC_CONDUIT = registerAetherophasicBlock("aetherophasic_conduit", 0);
    public static final BlockEntry<Block> AETHEROPHASIC_FASTENER = registerAetherophasicBlock("aetherophasic_fastener", 0);
    public static final BlockEntry<Block> AETHEROPHASIC_APERTURE = registerAetherophasicBlock("aetherophasic_aperture", 10);
    public static final BlockEntry<Block> AETHEROPHASIC_STELLAR_SURFACE = registerAetherophasicBlock("aetherophasic_stellar_surface", 15);
    public static final BlockEntry<Block> AETHEROPHASIC_STRUCTURAL_RIB = registerAetherophasicBlock("aetherophasic_structural_rib", 0);
    public static final BlockEntry<Block> AETHEROPHASIC_HEAT_VENT = registerAetherophasicBlock("aetherophasic_heat_vent", 0);
    public static final BlockEntry<Block> AETHEROPHASIC_OPTICAL_COUPLER = registerAetherophasicBlock("aetherophasic_optical_coupler", 0);
    public static final Map<StarLifterGeometry.Surface, BlockEntry<StarLifterShellBlock>> STAR_LIFTER_SHELL = registerStarLifterShell();
    public static final BlockEntry<Block> STAR_LIFTER_TERMINAL = registerAetherophasicBlock("star_lifter_terminal", 0);
    public static final BlockEntry<Block> STAR_LIFTER_INTERFACE = registerAetherophasicBlock("star_lifter_interface", 0);
    public static final BlockEntry<Block> STAR_LIFTER_MANIFOLD = registerAetherophasicBlock("star_lifter_manifold", 0);
    public static final BlockEntry<StarLifterInnerLatchBlock> STAR_LIFTER_INNER_LATCH = GTDishanhaiRegistration.REGISTRATE
            .block("star_lifter_inner_latch", StarLifterInnerLatchBlock::new)
            .initialProperties(() -> Blocks.IRON_BLOCK)
            .item(BlockItem::new)
            .build()
            .register();

    private static Map<StarLifterGeometry.Surface, BlockEntry<StarLifterShellBlock>> registerStarLifterShell() {
        var blocks = new EnumMap<StarLifterGeometry.Surface, BlockEntry<StarLifterShellBlock>>(StarLifterGeometry.Surface.class);
        for (var surface : StarLifterGeometry.Surface.values()) {
            var block = GTDishanhaiRegistration.REGISTRATE
                    .block("star_lifter_" + surface.name().toLowerCase(Locale.ROOT), StarLifterShellBlock::new)
                    .initialProperties(() -> Blocks.IRON_BLOCK)
                    .item(BlockItem::new)
                    .build()
                    .register();
            blocks.put(surface, block);
        }
        return Map.copyOf(blocks);
    }

    private static BlockEntry<Block> registerAetherophasicBlock(String name, int lightLevel) {
        return GTDishanhaiRegistration.REGISTRATE.block(name, Block::new)
            .initialProperties(() -> Blocks.IRON_BLOCK)
            .properties(properties -> properties.lightLevel(state -> lightLevel))
            .item(BlockItem::new)
            .build()
            .register();
    }

    /** 由 {@code GTDishanhaiGTAddon.initializeAddon()} 呼叫，藉此在 RegisterEvent 之前完成上面的靜態註冊。 */
    public static void init() {}

    private DShanhaiBlocks() {}
}
