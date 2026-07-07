package com.dishanhai.gt_shanhai.common.machine.misc;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.SimpleTieredMachine;

/**
 * 山海基础单方块机器。
 * 单方块注册必须使用单方块机器基类，不能继承多方块控制器基类。
 */
public class DShanhaiWirelessBasicMachine extends SimpleTieredMachine {

    private final int tier;

    public DShanhaiWirelessBasicMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, readTier(args), t -> 8000L * (long) Math.pow(4, Math.max(0, t)), args);
        this.tier = args != null && args.length > 0 && args[0] instanceof Integer value ? value : 0;
    }

    public DShanhaiWirelessBasicMachine(IMachineBlockEntity holder, int tier) {
        super(holder, tier, t -> 8000L * (long) Math.pow(4, Math.max(0, t)));
        this.tier = tier;
    }

    private static int readTier(Object... args) {
        return args != null && args.length > 0 && args[0] instanceof Integer value ? value : 0;
    }

    @Override
    public long getMaxVoltage() {
        int safeTier = Math.max(0, Math.min(tier, GTValues.VH.length - 1));
        return GTValues.VH[safeTier];
    }

    public int getMaxParallel() {
        return 1;
    }
}
