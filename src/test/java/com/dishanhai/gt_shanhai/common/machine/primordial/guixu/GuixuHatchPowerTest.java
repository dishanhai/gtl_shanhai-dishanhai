package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GuixuHatchPowerTest {
    static IEnergyContainer hatch(long inV, long inA, long outV, long outA, long capacity) {
        return (IEnergyContainer) Proxy.newProxyInstance(IEnergyContainer.class.getClassLoader(),
                new Class<?>[]{IEnergyContainer.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getInputVoltage" -> inV;
                    case "getInputAmperage" -> inA;
                    case "getOutputVoltage" -> outV;
                    case "getOutputAmperage" -> outA;
                    case "getEnergyCapacity" -> capacity;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test void mixedTierAndAmperageSumIndividuallyAndDuplicateHandlersCountOnce() {
        var first = hatch(32, 2, 0, 0, 1024);
        var second = hatch(128, 4, 0, 0, 8192);
        var result = GuixuHatchPower.read(List.of(first, second, first), true);
        assertEquals(2, result.count());
        assertEquals(128, result.highestVoltage());
        assertEquals(BigInteger.valueOf(576), result.power());
    }

    @Test void generationReadsOnlyOutputRatingsAndOrdinaryCapacityIsNotPower() {
        var hatch = hatch(8192, 64, 512, 2, 1_000_000);
        assertEquals(BigInteger.valueOf(1024), GuixuHatchPower.read(List.of(hatch), false).power());
        assertEquals(BigInteger.valueOf(524288), GuixuHatchPower.read(List.of(hatch), true).power());
        assertEquals(BigInteger.ZERO, GuixuHatchPower.read(List.of(), true).power());
    }

    @Test void totalPowerDoesNotSaturateAtLongMaxValue() {
        var hatch = hatch(Long.MAX_VALUE, 16, 0, 0, 123);
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(16)),
                GuixuHatchPower.read(List.of(hatch), true).power());
    }

    @Test void networkRefillBoostAppliesToInputOnly() {
        var kind = com.dishanhai.gt_shanhai.common.machine.ParallelPowerBudget.HatchKind.NET;
        assertEquals(BigInteger.valueOf(16_384), GuixuHatchPower.powerFor(kind, 512, 2, true));
        assertEquals(BigInteger.valueOf(1024), GuixuHatchPower.powerFor(kind, 512, 2, false));
    }
}
