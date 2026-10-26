package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;
import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gtladd.gtladditions.common.data.ParallelData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GuixuDirectionRotationTest {
    private static final Unsafe UNSAFE = unsafe();

    @Test
    void mixedCandidatesBecomeSeparateConsumerAndGeneratorBatchesInAlternatingOrder() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.assertBatch(true);
            fixture.assertBatch(false);
            fixture.assertBatch(true);
            fixture.assertBatch(false);
            assertEquals(0, fixture.logic.invalidations);
        }
    }

    @Test
    void missingInputHatchPowerFallsBackToGenerationWithoutMixingDirections() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.host.inputPower = BigInteger.ZERO;
            fixture.assertBatch(false);
            fixture.host.inputPower = BigInteger.valueOf(1000);
            fixture.assertBatch(true);
        }
    }

    @Test
    void emptyInputEnergyFallsBackToGenerationAndRecoversAfterTheInputIsRefilled() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.host.stored = 0;
            fixture.assertBatch(false);
            fixture.host.stored = 100_000;
            fixture.assertBatch(true);
        }
    }

    @Test
    void neitherDirectionHavingPowerReturnsNoBatchAndInvalidatesTheLookup() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.host.inputPower = fixture.host.outputPower = BigInteger.ZERO;
            assertNull(fixture.logic.calculateParallels());
            assertEquals(1, fixture.logic.invalidations);
            fixture.host.outputPower = BigInteger.valueOf(1000);
            fixture.assertBatch(false);
        }
    }

    /** Only external machine state is replaced; calculateParallels, private planDirection and allocation remain real. */
    private static final class Logic extends GuixuVisionCoreRecipeLogic {
        private Set<GTRecipe> candidates;
        private Host hostState;
        private int invalidations;

        private Logic() { super(null); }

        @Override protected Set<GTRecipe> lookupRecipeIterator() { return candidates; }
        @Override protected BigInteger getDirectionalHatchPower(boolean consumer) {
            return consumer ? hostState.inputPower : hostState.outputPower;
        }
        @Override public long getMaxParallel(GTRecipe recipe, long limit) { return Math.min(2L, limit); }
        @Override protected long getTotalParallelLimit() { return 4L; }
        @Override protected double getEuMultiplier() { return 1D; }
        @Override protected long getWirelessRecipeEut(GTRecipe recipe) { return 10L; }
        @Override protected void invalidateLookupSetCache() { invalidations++; }
    }

    private static final class Host extends GuixuVisionCoreMachine {
        private BigInteger inputPower, outputPower;
        private long stored;
        private GuixuEnergyHandler energy;

        private Host() { super(null); }

        @Override public GuixuEnergyHandler getWirelessNetworkEnergyHandler() { return energy; }
        @Override public int getLimitedDuration() { return 20; }
    }

    private static final class Fixture implements AutoCloseable {
        private final GTRecipeType previousGenerationType = DShanhaiRecipeTypes.GUIXU_ENERGY_EXTRACTION;
        private final GTRecipeType consumerType;
        private final GTRecipeType generatorType;
        private final Host host;
        private final Logic logic;

        private Fixture() throws Exception {
            consumerType = (GTRecipeType) UNSAFE.allocateInstance(GTRecipeType.class);
            generatorType = (GTRecipeType) UNSAFE.allocateInstance(GTRecipeType.class);
            host = (Host) UNSAFE.allocateInstance(Host.class);
            host.inputPower = host.outputPower = BigInteger.valueOf(1000);
            host.stored = 100_000;
            IEnergyContainer input = (IEnergyContainer) Proxy.newProxyInstance(
                    IEnergyContainer.class.getClassLoader(), new Class<?>[]{IEnergyContainer.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getEnergyStored")) return host.stored;
                        throw new UnsupportedOperationException(method.getName());
                    });
            host.energy = new GuixuEnergyHandler(() -> List.of(input), List::of, () -> true, () -> false);
            logic = (Logic) UNSAFE.allocateInstance(Logic.class);
            logic.hostState = host;
            Field field = GuixuVisionCoreRecipeLogic.class.getDeclaredField("host");
            field.setAccessible(true);
            field.set(logic, host);
            // Interleave directions by ID so a missing filter cannot pass through incidental list ordering.
            logic.candidates = new LinkedHashSet<>(List.of(
                    recipe(consumerType, "a_consumer"), recipe(generatorType, "b_generator"),
                    recipe(consumerType, "c_consumer"), recipe(generatorType, "d_generator")));
            DShanhaiRecipeTypes.GUIXU_ENERGY_EXTRACTION = generatorType;
        }

        private void assertBatch(boolean consumer) {
            ParallelData batch = logic.calculateParallels();
            assertNotNull(batch);
            assertTrue(batch.getShouldProcess(), "the native builder must still consume real recipe inputs");
            assertEquals(consumer, logic.isEnergyConsumer(), "native packaging must read this batch's direction");
            assertEquals(2, batch.getOriginRecipeList().size());
            for (GTRecipe recipe : batch.getOriginRecipeList()) {
                assertSame(consumer ? consumerType : generatorType, recipe.recipeType);
            }
            assertArrayEquals(new long[]{2L, 2L}, batch.getParallels());
        }

        @Override public void close() {
            DShanhaiRecipeTypes.GUIXU_ENERGY_EXTRACTION = previousGenerationType;
        }
    }

    private static GTRecipe recipe(GTRecipeType type, String id) {
        return new GTRecipe(type, new ResourceLocation("gt_shanhai", "direction_test/" + id),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                List.of(), List.of(), new CompoundTag(), 20, false);
    }

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
