package com.dishanhai.gt_shanhai.common.machine.misc;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.SimpleTieredMachine;

/**
 * 山海基础单方块机器 — 包装 SimpleTieredMachine，支持电压等级。
 */
public class DShanhaiBasicMachine extends SimpleTieredMachine {

    public DShanhaiBasicMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, 0, t -> 8000L * (long) Math.pow(4, Math.max(0, t)), args);
    }

    public DShanhaiBasicMachine(IMachineBlockEntity holder, int tier) {
        super(holder, tier, t -> 8000L * (long) Math.pow(4, Math.max(0, t)));
    }
}
