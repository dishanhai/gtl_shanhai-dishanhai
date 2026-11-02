package com.dishanhai.gt_shanhai.common.machine.preview;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import com.lowdragmc.lowdraglib.utils.BlockInfo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.HashSet;

/** 山海：正式回響成型；KubeJS晚註冊方塊延遲查詢，僅前方5×3介面接受本機倉口。 */
public final class ShanhaiNyarlathotepEchoStructure {
    private ShanhaiNyarlathotepEchoStructure() {}

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        var pattern = FactoryBlockPattern.start(RelativeDirection.RIGHT, RelativeDirection.DOWN, RelativeDirection.BACK);
        var symbols = new HashSet<Character>();
        for (String[] aisle : ShanhaiNyarlathotepEchoLayout.aisles()) {
            pattern.aisle(aisle);
            for (String row : aisle) for (char symbol : row.toCharArray()) symbols.add(symbol);
        }
        for (char symbol : symbols) {
            if (symbol == ' ' || symbol == 'S') continue;
            var predicate = lazy(ShanhaiNyarlathotepEchoLayout.blockId(symbol));
            if (symbol == 'C') predicate = predicate.or(Predicates.autoAbilities(definition.getRecipeTypes()));
            pattern.where(symbol, predicate);
        }
        return pattern.where('S', Predicates.controller(Predicates.blocks(definition.getBlock())))
                .where(' ', Predicates.air()).build();
    }

    private static TraceabilityPredicate lazy(String blockId) {
        var id = new ResourceLocation(blockId);
        return new TraceabilityPredicate(state -> {
            Block block = registeredBlock(id);
            return block != null && state.getBlockState().is(block);
        }, () -> new BlockInfo[]{new BlockInfo(previewBlock(id).defaultBlockState())});
    }

    private static Block registeredBlock(ResourceLocation id) {
        if (!ForgeRegistries.BLOCKS.containsKey(id)) return null;
        Block block = ForgeRegistries.BLOCKS.getValue(id);
        return block == Blocks.AIR ? null : block;
    }

    /** 缺件洋紅色僅供預覽；匹配仍回傳false，不能用佔位方塊成型。 */
    private static Block previewBlock(ResourceLocation id) {
        Block block = registeredBlock(id);
        return block == null ? Blocks.MAGENTA_CONCRETE : block;
    }

    public static Block appearanceBlock() {
        return previewBlock(new ResourceLocation(ShanhaiNyarlathotepEchoLayout.blockId('C')));
    }
}
