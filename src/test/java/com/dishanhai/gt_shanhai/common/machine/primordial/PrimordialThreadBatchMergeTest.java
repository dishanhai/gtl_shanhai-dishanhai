package com.dishanhai.gt_shanhai.common.machine.primordial;

// Author: dishanhai
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;
import com.gtladd.gtladditions.api.recipe.IWirelessGTRecipe;
import com.gtladd.gtladditions.api.recipe.WirelessGTRecipe;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PrimordialThreadBatchMergeTest {
    private static final Unsafe UNSAFE = unsafe();
    private static final GTRecipeType TYPE = recipeType();

    @Test void combinesPaidOutputsAndEnergyWithoutAliasingOrLongOutputAddition() {
        GTRecipe first = recipe("sulfur", 4L, BigInteger.ONE.shiftLeft(80).negate());
        GTRecipe second = recipe("sulfur", 6L, BigInteger.valueOf(-7L));
        first.outputs.put(null, new ArrayList<>(List.of(content(Long.MAX_VALUE))));
        second.outputs.put(null, new ArrayList<>(List.of(content(Long.MAX_VALUE))));

        GTRecipe merged = PrimordialThreadBatchMerge.merge(first, second);

        assertNotNull(merged);
        assertEquals(10L, merged.data.getLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY));
        assertEquals(BigInteger.ONE.shiftLeft(80).negate().subtract(BigInteger.valueOf(7L)),
                ((IWirelessGTRecipe) merged).getWirelessEuTickInputs());
        assertEquals(20, merged.duration);
        assertEquals(2, merged.outputs.get(null).size());
        assertEquals(Long.MAX_VALUE, ((CompoundTag) merged.outputs.get(null).get(1).content).getLong("amount"));
        ((CompoundTag) merged.outputs.get(null).get(0).content).putLong("amount", 1L);
        merged.outputs.get(null).get(1).chance = 1;
        merged.data.putString("extra", "changed");
        assertEquals(Long.MAX_VALUE, ((CompoundTag) first.outputs.get(null).getFirst().content).getLong("amount"));
        assertEquals(10000, second.outputs.get(null).getFirst().chance);
        assertFalse(first.data.contains("extra"));
        assertEquals(4L, first.data.getLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY));
    }

    @Test void combinesDifferentFullPoolsAndPreservesEveryRealSource() {
        GTRecipe first = recipe("sulfur", Long.MAX_VALUE, BigInteger.ZERO);
        GTRecipe second = recipe("copper", Long.MAX_VALUE, BigInteger.ZERO);
        first.data.putBoolean("mode", true);
        second.data.putBoolean("mode", true);

        GTRecipe merged = PrimordialThreadBatchMerge.merge(first, second);

        assertNotNull(merged);
        assertEquals(Long.MAX_VALUE, merged.data.getLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY));
        assertEquals(List.of(
                new PrimordialThreadRecipeIdentity.Identity("gt_shanhai:test", "gt_shanhai:sulfur"),
                new PrimordialThreadRecipeIdentity.Identity("gt_shanhai:test", "gt_shanhai:copper")),
                PrimordialThreadRecipeIdentity.sourcesOf(merged));
        assertTrue(merged.data.getBoolean("mode"));
        assertEquals(1, PrimordialThreadRecipeIdentity.sourcesOf(first).size());
        GTRecipe third = recipe("iron", Long.MAX_VALUE, BigInteger.ZERO);
        third.data.putBoolean("mode", true);
        GTRecipe threeSources = PrimordialThreadBatchMerge.merge(merged, third);
        assertNotNull(threeSources);
        assertEquals(3, PrimordialThreadRecipeIdentity.sourcesOf(threeSources).size());
        assertEquals(Long.MAX_VALUE, threeSources.data.getLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY));
        assertEquals(2, PrimordialThreadRecipeIdentity.sourcesOf(merged).size());
    }

    @Test void rejectsUnknownSourcesOrDifferentMetadataAcrossRecipes() {
        GTRecipe first = recipe("sulfur", 1L, BigInteger.ZERO);
        GTRecipe second = recipe("copper", 1L, BigInteger.ZERO);
        second.data.putBoolean("mode", true);
        assertNull(PrimordialThreadBatchMerge.merge(first, second));
        second.data.remove("mode");
        PrimordialThreadRecipeIdentity.store(second, List.of(
                new PrimordialThreadRecipeIdentity.Identity("", "")));
        assertNull(PrimordialThreadBatchMerge.merge(first, second));
    }

    @Test void rejectsUnsettledChanceAndTierBoost() {
        GTRecipe first = recipe("sulfur", 1L, BigInteger.ZERO);
        GTRecipe second = recipe("sulfur", 1L, BigInteger.ZERO);
        Content output = content(8L);
        second.outputs.put(null, new ArrayList<>(List.of(output)));
        output.chance = 5000;
        assertNull(PrimordialThreadBatchMerge.merge(first, second));
        output.chance = 10000;
        output.tierChanceBoost = 1;
        assertNull(PrimordialThreadBatchMerge.merge(first, second));
    }

    @Test void rejectsOverflowAndMismatchedExecutionSemantics() {
        GTRecipe first = recipe("sulfur", Long.MAX_VALUE, BigInteger.ZERO);
        GTRecipe second = recipe("sulfur", 1L, BigInteger.ZERO);
        assertNull(PrimordialThreadBatchMerge.merge(first, second));
        first.data.putLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY, 1L);
        second.duration++;
        assertNull(PrimordialThreadBatchMerge.merge(first, second));
        second.duration--;
        second.data.putBoolean("mode", true);
        assertNull(PrimordialThreadBatchMerge.merge(first, second));
        second.data.remove("mode");
        second.inputs.put(null, new ArrayList<>(List.of(content(1L))));
        assertNull(PrimordialThreadBatchMerge.merge(first, second));
        second.inputs.clear();
        second.tickOutputs.put(null, new ArrayList<>(List.of(content(1L))));
        assertNull(PrimordialThreadBatchMerge.merge(first, second));
    }

    @Test void rejectsOppositeEnergyDirectionsAndUnknownPaidParallel() {
        assertNull(PrimordialThreadBatchMerge.merge(recipe("sulfur", 1L, BigInteger.ONE),
                recipe("sulfur", 1L, BigInteger.ONE.negate())));
        assertNull(PrimordialThreadBatchMerge.merge(recipe("sulfur", 0L, BigInteger.ZERO),
                recipe("sulfur", 1L, BigInteger.ZERO)));
    }

    private static GTRecipe recipe(String source, long parallel, BigInteger energy) {
        GTRecipe recipe = new GTRecipe(TYPE, new ResourceLocation("gtceu", "dummy"),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new ArrayList<>(), new ArrayList<>(), new CompoundTag(), 20, false);
        recipe.data.putLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY, parallel);
        PrimordialThreadRecipeIdentity.store(recipe, List.of(
                new PrimordialThreadRecipeIdentity.Identity("gt_shanhai:test", "gt_shanhai:" + source)));
        return new WirelessGTRecipe(recipe, energy);
    }

    private static Content content(long amount) {
        try {
            var content = (CopiedContent) UNSAFE.allocateInstance(CopiedContent.class);
            var payload = new CompoundTag();
            payload.putLong("amount", amount);
            content.content = payload;
            content.chance = content.maxChance = 10000;
            return content;
        } catch (InstantiationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class CopiedContent extends Content {
        private CopiedContent() { super(null, 10000, 10000, 0, null, null); }
        @Override public Content copy(RecipeCapability<?> capability, ContentModifier modifier) {
            return content(((CompoundTag) content).getLong("amount"));
        }
    }

    private static GTRecipeType recipeType() {
        try {
            var type = (GTRecipeType) UNSAFE.allocateInstance(GTRecipeType.class);
            UNSAFE.putObject(type, UNSAFE.objectFieldOffset(GTRecipeType.class.getField("registryName")),
                    new ResourceLocation("gt_shanhai", "test"));
            return type;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
