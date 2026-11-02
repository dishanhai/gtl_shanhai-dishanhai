package com.dishanhai.gt_shanhai.common.machine.preview;

import com.gtladd.gtladditions.api.machine.trait.IWirelessNetworkEnergyHandler;
import com.gtladd.gtladditions.api.recipe.IWirelessGTRecipe;
import java.math.BigInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;

/** 山海：重用多配方運算，只扣本機能源倉，不接無線電網。 */
public final class ShanhaiLocalEnergyHandler implements IWirelessNetworkEnergyHandler {
    private final LongSupplier stored;
    private final LongUnaryOperator drain;
    private final BooleanSupplier online;
    public ShanhaiLocalEnergyHandler(LongSupplier stored, LongUnaryOperator drain, BooleanSupplier online) {
        this.stored = stored;
        this.drain = drain;
        this.online = online;
    }
    @Override public boolean isOnline() { return online.getAsBoolean(); }
    @Override public BigInteger getMaxAvailableEnergy() {
        return BigInteger.valueOf(isOnline() ? Math.max(0L, stored.getAsLong()) : 0L);
    }
    @Override public boolean consumeEnergy(int energy) { return consumeEnergy((long) energy); }
    @Override public boolean consumeEnergy(BigInteger energy) {
        return energy != null && energy.signum() >= 0 && energy.bitLength() <= 63
                && consumeEnergy(energy.longValue());
    }
    @Override public boolean consumeEnergy(long energy) {
        if (!isOnline() || energy < 0 || stored.getAsLong() < energy) return false;
        return energy == 0 || drain.applyAsLong(energy) == energy;
    }

    /** GTLAdd 原生配方存的是帶符號變化量：負值代表耗電，本機容器只接受正扣量。 */
    public boolean consumeRecipeTick(IWirelessGTRecipe recipe) {
        BigInteger delta = recipe.getWirelessEuTickInputs();
        return delta == null ? consumeEnergy(0L) : delta.signum() <= 0 && consumeEnergy(delta.negate());
    }
}
