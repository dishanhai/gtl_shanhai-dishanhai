package com.dishanhai.gt_shanhai.common.machine.part;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine;
import org.gtlcore.gtlcore.api.machine.trait.AECraft.IMECraftParallelCore;

/**
 * 山海超级并行核心 — 分子操纵者并行 MAX_VALUE
 */
public class DShanhaiSuperParallelCoreMachine extends MultiblockPartMachine implements IMECraftParallelCore {

    public DShanhaiSuperParallelCoreMachine(IMachineBlockEntity holder) {
        super(holder);
    }

    @Override
    public int getParallel() {
        return Integer.MAX_VALUE;
    }
}
