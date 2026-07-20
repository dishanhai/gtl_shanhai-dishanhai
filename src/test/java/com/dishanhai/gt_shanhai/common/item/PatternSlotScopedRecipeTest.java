package com.dishanhai.gt_shanhai.common.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

class PatternSlotScopedRecipeTest {

    private static final ResourceLocation ORIGINAL_ID = new ResourceLocation("gtceu", "distort/test_recipe");
    private static final ResourceLocation DIMENSION = new ResourceLocation("minecraft", "overworld");

    @Test
    void duplicateRecipePatternsKeepIndependentSlotIdentity() {
        GTRecipe original = emptyRecipe();

        GTRecipe slotThree = PatternSlotScopedRecipe.scope(original, DIMENSION, new BlockPos(1, 2, 3), 3);
        GTRecipe slotFour = PatternSlotScopedRecipe.scope(original, DIMENSION, new BlockPos(1, 2, 3), 4);
        GTRecipe slotThreeAgain = PatternSlotScopedRecipe.scope(original, DIMENSION, new BlockPos(1, 2, 3), 3);
        Set<GTRecipe> candidates = new HashSet<>();
        candidates.add(slotThree);
        candidates.add(slotFour);

        assertFalse(PatternSlotScopedRecipe.isScoped(original));
        assertTrue(PatternSlotScopedRecipe.isScoped(slotThree));
        assertTrue(PatternSlotScopedRecipe.represents(slotThree, original));
        assertFalse(PatternSlotScopedRecipe.represents(slotThree, slotFour));
        assertNotEquals(original, slotThree);
        assertNotEquals(slotThree, slotFour);
        assertEquals(slotThree, slotThreeAgain);
        assertEquals(2, candidates.size());
        assertEquals(ORIGINAL_ID, slotThree.getId());
        assertEquals(ORIGINAL_ID, slotFour.getId());
        assertTrue(PatternSlotScopedRecipe.matchesSource(
                slotThree, DIMENSION, new BlockPos(1, 2, 3), 3));
        assertFalse(PatternSlotScopedRecipe.matchesSource(
                slotThree, DIMENSION, new BlockPos(1, 2, 3), 4));
        assertFalse(PatternSlotScopedRecipe.matchesSource(
                slotThree, DIMENSION, new BlockPos(9, 2, 3), 3));
    }

    @Test
    void copiesPreserveTheSlotScopedIdentityAndOriginalPublicId() {
        GTRecipe scoped = PatternSlotScopedRecipe.scope(emptyRecipe(), DIMENSION, new BlockPos(-5, 6, 7), 9);

        GTRecipe plainCopy = scoped.copy();
        GTRecipe multipliedCopy = scoped.copy(ContentModifier.multiplier(16), false);

        assertEquals(scoped, plainCopy);
        assertEquals(scoped, multipliedCopy);
        assertEquals(ORIGINAL_ID, plainCopy.getId());
        assertEquals(ORIGINAL_ID, multipliedCopy.getId());
    }

    @Test
    void plainRecipeWithScopedInternalIdStillKeepsItsSourceSlot() {
        BlockPos sourcePos = new BlockPos(-80, 99, -15);
        GTRecipe scoped = PatternSlotScopedRecipe.scope(emptyRecipe(), DIMENSION, sourcePos, 14);
        GTRecipe chanceProcessed = new GTRecipe(scoped.recipeType, scoped.id,
                scoped.inputs, scoped.outputs, scoped.tickInputs, scoped.tickOutputs,
                scoped.inputChanceLogics, scoped.outputChanceLogics,
                scoped.tickInputChanceLogics, scoped.tickOutputChanceLogics,
                scoped.conditions, scoped.ingredientActions, scoped.data, scoped.duration, scoped.isFuel);

        assertFalse(chanceProcessed instanceof PatternSlotScopedRecipe);
        assertTrue(PatternSlotScopedRecipe.isScoped(chanceProcessed));
        assertTrue(PatternSlotScopedRecipe.matchesSource(chanceProcessed, DIMENSION, sourcePos, 14));
        assertFalse(PatternSlotScopedRecipe.matchesSource(chanceProcessed, DIMENSION, sourcePos, 12));
    }

    private static GTRecipe emptyRecipe() {
        return new GTRecipe(null, ORIGINAL_ID,
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new ArrayList<>(), new ArrayList<>(), new CompoundTag(), 20, false);
    }
}
