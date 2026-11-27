package com.dishanhai.gt_shanhai.common.machine.aetherophasic;

import com.dishanhai.gt_shanhai.api.DShanhaiMBParser;
import com.dishanhai.gt_shanhai.common.block.DShanhaiBlocks;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

/** 山海：JSON 接入既有多方块图案与终端；原主机标记替换为本机控制器。 */
public final class AetherophasicEngineStructure {
    private AetherophasicEngineStructure() {}

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        var layout = AetherophasicEngineLayout.load();
        // 六个位置可保留 OPV 密封机械方块，也可安装标准物品／流体／能源仓。
        var hatches = Predicates.abilities(PartAbility.IMPORT_ITEMS, PartAbility.EXPORT_ITEMS,
                PartAbility.IMPORT_FLUIDS, PartAbility.EXPORT_FLUIDS,
                PartAbility.INPUT_ENERGY, PartAbility.OUTPUT_ENERGY);
        return DShanhaiMBParser.parseArray(layout.aisles, symbol -> {
            if (symbol == layout.controllerSymbol) {
                return Predicates.controller(Predicates.blocks(definition.getBlock()));
            }
            var entry = layout.palette.get(symbol - 'A');
            var id = new ResourceLocation(entry.get("Name").getAsString());
            if (!ForgeRegistries.BLOCKS.containsKey(id)) {
                throw new IllegalStateException("Missing aetherophasic structure block: " + id);
            }
            Block block = ForgeRegistries.BLOCKS.getValue(id);
            // 原 NBT 的 active=true 只属于已被替换的主机标记；其余八种材料与仓位无状态属性。
            if (entry.has("Properties")) {
                throw new IllegalStateException("Unmapped aetherophasic block properties: " + id);
            }
            TraceabilityPredicate predicate = Predicates.blocks(block);
            return symbol == layout.hatchSymbol ? predicate.or(hatches) : predicate;
        }, RelativeDirection.LEFT, RelativeDirection.UP, RelativeDirection.FRONT);
    }

    public static Block appearanceBlock() {
        return DShanhaiBlocks.AETHEROPHASIC_CASING.get();
    }
}
