package com.dishanhai.gt_shanhai.api.machine;

import com.dishanhai.gt_shanhai.common.machine.wave.GravitationalWaveBroadcastManager;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;
import net.minecraft.server.level.ServerLevel;

public abstract class DShanhaiBroadcastSourceMachine extends GTLAddWirelessWorkableElectricMultipleRecipesMachine {
    private boolean shanhaiBroadcastRegistered;

    protected DShanhaiBroadcastSourceMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    protected boolean shouldRegisterShanhaiBroadcast() {
        return isFormed();
    }

    protected int getShanhaiBroadcastRadius() {
        return 0;
    }

    protected int getShanhaiBroadcastPowerLevel() {
        return 0;
    }

    protected int getShanhaiBroadcastLensCount() {
        return 0;
    }

    protected int getShanhaiFixedOutputMultiplier() {
        return 0;
    }

    protected final void updateShanhaiBroadcastSource() {
        if (!(getLevel() instanceof ServerLevel level)) return;
        if (!shouldRegisterShanhaiBroadcast() || getShanhaiBroadcastRadius() <= 0) {
            removeShanhaiBroadcastSource();
            return;
        }

        GravitationalWaveBroadcastManager.INSTANCE.addSource(
                level,
                getPos(),
                getShanhaiBroadcastRadius(),
                getShanhaiBroadcastPowerLevel(),
                getShanhaiBroadcastLensCount(),
                getShanhaiFixedOutputMultiplier());
        shanhaiBroadcastRegistered = true;
    }

    protected final void removeShanhaiBroadcastSource() {
        if (shanhaiBroadcastRegistered && getLevel() instanceof ServerLevel level) {
            GravitationalWaveBroadcastManager.INSTANCE.removeSource(level, getPos());
            shanhaiBroadcastRegistered = false;
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        updateShanhaiBroadcastSource();
    }

    @Override
    public void onUnload() {
        removeShanhaiBroadcastSource();
        super.onUnload();
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        updateShanhaiBroadcastSource();
    }

    @Override
    public void onStructureInvalid() {
        removeShanhaiBroadcastSource();
        super.onStructureInvalid();
    }
}
