package com.dishanhai.gt_shanhai.common.machine.trait;

import com.gregtechceu.gtceu.api.misc.EnergyContainerList;

import net.minecraft.core.Direction;

import java.math.BigInteger;
import java.util.function.LongSupplier;

/**
 * 无限能源容器列表——继承 EnergyContainerList，覆写所有能量/电压方法返回无限值。
 * 让 GTRecipeModifier.parallelizing 看到无限可用能量（电压×电流=∞），避免并行时长膨胀。
 * 电压通过 LongSupplier 动态读取，UI 改 tier 无需重新成型。
 */
public class InfinityEnergyContainerList extends EnergyContainerList {

    private final LongSupplier voltageSupplier;

    public InfinityEnergyContainerList(EnergyContainerList original, LongSupplier voltageSupplier) {
        super(java.util.List.of());
        this.voltageSupplier = voltageSupplier;
    }

    private long voltage() {
        long v = voltageSupplier.getAsLong();
        return v > 0 ? v : Long.MAX_VALUE;
    }

    @Override
    public long getEnergyStored() {
        return Long.MAX_VALUE;
    }

    @Override
    public long getEnergyCapacity() {
        return Long.MAX_VALUE;
    }

    @Override
    public long changeEnergy(long amount) {
        return 0;
    }

    @Override
    public long getInputVoltage() {
        return voltage();
    }

    @Override
    public long getOutputVoltage() {
        return voltage();
    }

    @Override
    public long getHighestInputVoltage() {
        return voltage();
    }

    @Override
    public long getInputAmperage() {
        return Long.MAX_VALUE;
    }

    @Override
    public long getOutputAmperage() {
        return Long.MAX_VALUE;
    }

    @Override
    public int getNumHighestInputContainers() {
        return 1;
    }

    @Override
    public long getInputPerSec() {
        return Long.MAX_VALUE;
    }

    @Override
    public long getOutputPerSec() {
        return Long.MAX_VALUE;
    }

    @Override
    public long acceptEnergyFromNetwork(Direction side, long voltage, long amperage) {
        return 0;
    }

    @Override
    public boolean inputsEnergy(Direction side) {
        return false;
    }

    @Override
    public boolean outputsEnergy(Direction side) {
        return false;
    }

    @Override
    public EnergyInfo getEnergyInfo() {
        var big = BigInteger.valueOf(Long.MAX_VALUE);
        return new EnergyInfo(big, big);
    }
}
