package com.dishanhai.gt_shanhai.integration;

// Author: dishanhai
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gtladd.gtladditions.common.data.ParallelData;
import com.gtladd.gtladditions.utils.recipe.RecipeCalculationHelper;
import it.unimi.dsi.fastutil.longs.LongBooleanPair;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class GtlAdditionsFairAllocationTest {
    @Test
    void missingInputsReturnNoBatch() {
        assertNull(allocate(32, 0, 0));
    }

    @Test
    void spareCapacityMovesToRecipesThatStillHaveInputs() {
        ParallelData data = allocate(10, 1, 9, 0);
        assertNotNull(data);
        assertArrayEquals(new long[]{1, 9}, data.getParallels());
        assertEquals(2, data.getOriginRecipeList().size());
        assertTrue(data.getShouldProcess(), "原生构建阶段仍负责加工和扣料");
    }

    @Test
    void smallBudgetDropsZeroAllocationsAndKeepsRecipeIndicesAligned() {
        ParallelData data = allocate(2, 0, 8, 8, 8);
        assertNotNull(data);
        assertEquals(data.getOriginRecipeList().size(), data.getParallels().length);
        assertEquals(2, Arrays.stream(data.getParallels()).sum());
        assertTrue(Arrays.stream(data.getParallels()).allMatch(n -> n > 0));
        assertTrue(data.getOriginRecipeList().stream().noneMatch(r -> r.id.getPath().equals("allocation_0")));
    }

    @Test
    void longParallelCapacityDoesNotOverflowOrExceedTheMachineBudget() {
        ParallelData data = allocate(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 1);
        assertNotNull(data);
        BigInteger total = Arrays.stream(data.getParallels()).mapToObj(BigInteger::valueOf)
                .reduce(BigInteger.ZERO, BigInteger::add);
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE), total);
        assertTrue(Arrays.stream(data.getParallels()).allMatch(n -> n > 0));
    }

    private static ParallelData allocate(long budget, long... limits) {
        List<GTRecipe> recipes = IntStream.range(0, limits.length).mapToObj(i -> new GTRecipe(null,
                new ResourceLocation("gt_shanhai", "allocation_" + i),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(), List.of(), new CompoundTag(), 20, false)).toList();
        return RecipeCalculationHelper.INSTANCE.calculateParallelsWithFairAllocation(recipes, budget,
                recipe -> LongBooleanPair.of(limits[recipes.indexOf(recipe)], true));
    }
}
