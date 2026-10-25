package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gtladd.gtladditions.api.machine.IWirelessElectricMultiblockMachine;
import com.gtladd.gtladditions.api.machine.logic.IWirelessRecipeLogic;
import com.gtladd.gtladditions.api.machine.trait.IWirelessNetworkEnergyHandler;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GuixuEnergyHandlerTest {
    private static final class Store {
        long stored, capacity;
        boolean shortChange;
        Store(long stored, long capacity) { this.stored = stored; this.capacity = capacity; }
        IEnergyContainer container() {
            return (IEnergyContainer) Proxy.newProxyInstance(IEnergyContainer.class.getClassLoader(),
                    new Class<?>[]{IEnergyContainer.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getEnergyStored" -> stored;
                        case "getEnergyCapacity" -> capacity;
                        case "changeEnergy" -> {
                            long delta = (long) args[0];
                            long actual = Math.max(-stored, Math.min(delta, capacity - stored));
                            if (shortChange && delta < 0) actual /= 2;
                            stored += actual;
                            yield actual;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    @Test
    void consumersAndGeneratorsUseSeparateSidesAndRejectInsufficientCapacity() {
        Store in1 = new Store(100, 200), in2 = new Store(90, 200);
        Store out = new Store(10, 100);
        var handler = new GuixuEnergyHandler(() -> List.of(in1.container(), in2.container()),
                () -> List.of(out.container()), () -> true, () -> false);
        assertEquals(BigInteger.valueOf(190), handler.getMaxAvailableEnergy());
        assertTrue(handler.consumeEnergy(-150L));
        assertEquals(0, in1.stored);
        assertEquals(40, in2.stored);
        assertEquals(10, out.stored);
        assertFalse(handler.consumeEnergy(100L));
        assertEquals(10, out.stored);
        assertTrue(handler.consumeEnergy(60L));
        assertEquals(70, out.stored);
        assertFalse(handler.consumeEnergy(-50L));
        assertEquals(40, in2.stored);
    }

    @Test
    void shortTransactionsRollBackEveryChangedContainer() {
        Store first = new Store(100, 200), second = new Store(100, 200);
        second.shortChange = true;
        var handler = new GuixuEnergyHandler(() -> List.of(first.container(), second.container()),
                List::of, () -> true, () -> false);
        assertFalse(handler.consumeEnergy(-150L));
        assertEquals(100, first.stored);
        assertEquals(100, second.stored);
    }

    @Test
    void exactBigIntegerTransfersSpanSeveralRealContainers() {
        Store first = new Store(Long.MAX_VALUE, Long.MAX_VALUE);
        Store second = new Store(Long.MAX_VALUE, Long.MAX_VALUE);
        var handler = new GuixuEnergyHandler(() -> List.of(first.container(), second.container()),
                List::of, () -> true, () -> false);
        assertTrue(handler.consumeEnergy(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO).negate()));
        assertEquals(0L, first.stored);
        assertEquals(0L, second.stored);
    }

    @Test
    void nativeWirelessTickCallsThePhysicalHandlerWithTheOriginalSign() throws Exception {
        Store input = new Store(100, 200), output = new Store(0, 200);
        var handler = new GuixuEnergyHandler(() -> List.of(input.container()), () -> List.of(output.container()),
                () -> true, () -> false);
        IWirelessElectricMultiblockMachine machine = new IWirelessElectricMultiblockMachine() {
            @Override public IWirelessNetworkEnergyHandler getWirelessNetworkEnergyHandler() { return handler; }
        };
        IWirelessRecipeLogic nativeLogic = new IWirelessRecipeLogic() {
            @Override public IWirelessElectricMultiblockMachine getWirelessMachine() { return machine; }
        };
        assertTrue(nativeLogic.handleWirelessTickInput(() -> BigInteger.valueOf(-30)));
        assertTrue(nativeLogic.handleWirelessTickInput(() -> BigInteger.valueOf(40)));
        assertEquals(70, input.stored);
        assertEquals(40, output.stored);
        assertNotNull(GuixuVisionCoreRecipeLogic.class.getDeclaredMethod("getWirelessMachine"));
    }

    @Test
    void twoTerminalsSharingOnePoolNeverDoubleCountOrOverdrawItsBalance() {
        Store pool = new Store(100, 1000);
        var first = pool.container();
        var second = pool.container();
        var uuid = java.util.UUID.fromString("c5e5ddce-4aa0-4b4b-9c08-7672dd53cc8a");
        var handler = new GuixuEnergyHandler(() -> List.of(first, second), List::of,
                () -> true, () -> false, container -> uuid);
        assertEquals(BigInteger.valueOf(100), handler.getMaxAvailableEnergy());
        assertFalse(handler.consumeEnergy(-150L));
        assertEquals(100L, pool.stored);
        assertTrue(handler.consumeEnergy(-80L));
        assertEquals(20L, pool.stored);
        assertEquals(BigInteger.valueOf(20), handler.getMaxAvailableEnergy());
    }
}
