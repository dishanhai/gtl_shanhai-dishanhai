package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import com.dishanhai.gt_shanhai.common.machine.EnergyHatchPower;
import com.dishanhai.gt_shanhai.common.machine.ParallelPowerBudget;
import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import java.math.BigInteger;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

/** 山海：歸墟單側倉室的數量、最高電壓與總穩態功率。 */
public record GuixuHatchPower(int count, long highestVoltage, BigInteger power, boolean unlimited) {
    // 高於原生 long 並行、int 耗時與 long EU/t 所能形成的任何有限批次。
    private static final BigInteger UNLIMITED_BUDGET = BigInteger.ONE.shiftLeft(256);

    public static GuixuHatchPower read(List<IEnergyContainer> containers, boolean input) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<IEnergyContainer, Boolean>());
        BigInteger total = BigInteger.ZERO;
        long highest = 0L;
        boolean unlimited = false;
        for (IEnergyContainer container : containers) {
            if (container == null || !seen.add(container)) continue;
            long voltage = Math.max(0L, input ? container.getInputVoltage() : container.getOutputVoltage());
            long amps = Math.max(0L, input ? container.getInputAmperage() : container.getOutputAmperage());
            highest = Math.max(highest, voltage);
            var kind = EnergyHatchPower.classify(container);
            unlimited |= kind.isUnlimited();
            total = total.add(powerFor(kind, voltage, amps, input));
        }
        return new GuixuHatchPower(seen.size(), highest, unlimited ? UNLIMITED_BUDGET : total, unlimited);
    }

    static BigInteger powerFor(ParallelPowerBudget.HatchKind kind, long voltage, long amps, boolean input) {
        BigInteger power = BigInteger.valueOf(Math.max(0L, voltage)).multiply(BigInteger.valueOf(Math.max(0L, amps)));
        return input && kind == ParallelPowerBudget.HatchKind.NET
                ? power.multiply(BigInteger.valueOf(ParallelPowerBudget.NET_CAPACITY_FACTOR)) : power;
    }

    public String powerLabel() { return unlimited ? "无上限" : power.toString(); }
}
