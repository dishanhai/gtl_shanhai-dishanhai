package com.dishanhai.gt_shanhai.common.machine.preview;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
import com.gtladd.gtladditions.api.recipe.IWirelessGTRecipe;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：以真實可扣減容器測試本機供電，不依賴 Forge 世界啟動。 */
class ShanhaiLocalEnergyHandlerTest {
    private Object handler(AtomicLong energy, AtomicBoolean online) throws Exception {
        assertTrue(Files.exists(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/preview/ShanhaiLocalEnergyHandler.java")),
                "Missing local hatch energy adapter");
        return Class.forName("com.dishanhai.gt_shanhai.common.machine.preview.ShanhaiLocalEnergyHandler")
                .getConstructor(LongSupplier.class, LongUnaryOperator.class, BooleanSupplier.class)
                .newInstance((LongSupplier) energy::get,
                        (LongUnaryOperator) amount -> { energy.addAndGet(-amount); return amount; },
                        (BooleanSupplier) online::get);
    }

    private boolean consume(Object handler, BigInteger amount) throws Exception {
        return (boolean) handler.getClass().getMethod("consumeEnergy", BigInteger.class).invoke(handler, amount);
    }

    @Test void consumesExactlyFromTheLocalContainer() throws Exception {
        var energy = new AtomicLong(1000);
        var handler = handler(energy, new AtomicBoolean(true));
        assertTrue(consume(handler, BigInteger.valueOf(600)));
        assertEquals(400, energy.get());
        assertFalse(consume(handler, BigInteger.valueOf(401)));
        assertEquals(400, energy.get(), "Insufficient power must not partially drain");
    }

    @Test void oversizedOrNegativeRequestsCannotOverflowOrGenerateEnergy() throws Exception {
        var energy = new AtomicLong(Long.MAX_VALUE);
        var handler = handler(energy, new AtomicBoolean(true));
        assertFalse(consume(handler, BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)));
        assertFalse(consume(handler, BigInteger.valueOf(-1)));
        assertEquals(Long.MAX_VALUE, energy.get());
    }

    @Test void unloadDisablesPowerAndAvailableEnergy() throws Exception {
        var energy = new AtomicLong(1000);
        var online = new AtomicBoolean(true);
        var handler = handler(energy, online);
        online.set(false);
        assertFalse(consume(handler, BigInteger.ONE));
        assertEquals(BigInteger.ZERO, handler.getClass().getMethod("getMaxAvailableEnergy").invoke(handler));
        assertEquals(1000, energy.get());
    }

    @Test void nativeNegativeRecipeInputsConsumePositiveLocalEnergy() throws Exception {
        var energy = new AtomicLong(1000);
        var handler = handler(energy, new AtomicBoolean(true));
        assertTrue(java.util.Arrays.stream(handler.getClass().getMethods())
                .anyMatch(method -> method.getName().equals("consumeRecipeTick")),
                "Native signed input must be adapted before entering the positive-only local container");
        var consumeTick = handler.getClass().getMethod("consumeRecipeTick", IWirelessGTRecipe.class);
        // 真實依賴介面：GuixuNativeEnergyContractTest 另外核對 factory 產生的是負號。
        assertEquals(true, consumeTick.invoke(handler, (IWirelessGTRecipe) () -> BigInteger.valueOf(-600)));
        assertEquals(400, energy.get());
        assertEquals(false, consumeTick.invoke(handler, (IWirelessGTRecipe) () -> BigInteger.valueOf(-401)));
        assertEquals(400, energy.get());
        assertEquals(false, consumeTick.invoke(handler, (IWirelessGTRecipe) () -> BigInteger.valueOf(100)));
        assertEquals(400, energy.get(), "Generator-style signed input must not create energy");
    }
}
