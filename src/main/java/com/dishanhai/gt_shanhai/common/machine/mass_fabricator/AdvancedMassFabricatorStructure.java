package com.dishanhai.gt_shanhai.common.machine.mass_fabricator;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.common.data.GTBlocks;

/**
 * 进阶质量发生器结构定义
 * 
 * 简化版本 5x5x5 结构（后续可扩展为完整 13x13x21）
 */
public class AdvancedMassFabricatorStructure {
    
    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        return FactoryBlockPattern.start()
                // 第 0 层（底层）
                .aisle("CCCCC", "CCCCC", "CCCCC", "CCCCC", "CCCCC")
                // 第 1-3 层（主体）
                .aisle("CCCCC", "CAAAC", "CAAAC", "CAAAC", "CCCCC")
                .aisle("CCCCC", "CA~AC", "CAAAC", "CAAAC", "CCCCC")
                .aisle("CCCCC", "CAAAC", "CAAAC", "CAAAC", "CCCCC")
                // 第 4 层（顶层）
                .aisle("CCCCC", "CCCCC", "CCCCC", "CCCCC", "CCCCC")
                
                // C: 质量发生器外壳 + 仓体（能源仓、输入仓、输出仓、维护仓）
                .where('C', Predicates.blocks(GTBlocks.CASING_PTFE_INERT.get())
                        .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setMinGlobalLimited(1).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setMaxGlobalLimited(4).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setMaxGlobalLimited(4).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setMaxGlobalLimited(4).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setMaxGlobalLimited(4).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.MAINTENANCE).setExactLimit(1).setPreviewCount(1))
                        // TODO: 添加并行控制核心仓（自定义 PartAbility）
                        // .or(Predicates.abilities(ParallelControllerAbility).setExactLimit(1).setPreviewCount(1))
                )
                
                // A: 内部填充（高级机械方块）
                .where('A', Predicates.blocks(GTBlocks.CASING_STEEL_SOLID.get())
                        .or(Predicates.air()))
                
                // ~: 主机（前中央位置）
                .where('~', Predicates.controller(Predicates.blocks(definition.getBlock())))
                
                .build();
    }
}
