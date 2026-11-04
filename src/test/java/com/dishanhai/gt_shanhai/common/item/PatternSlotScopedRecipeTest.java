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

    @Test void patternNbtIdentitySurvivesScopedCopiesWithoutChangingTheRegisteredRecipe() {
        GTRecipe original = emptyRecipe();
        GTRecipe scoped = PatternSlotScopedRecipe.scope(original, DIMENSION, BlockPos.ZERO, 3);
        CompoundTag pattern = new CompoundTag();
        pattern.putString(PatternRecipeTypeHelper.TAG_RECIPE_ID,
                "gt_shanhai:photon_matrix_etch/wafer/ulpic_wafer_6");
        pattern.putString(PatternRecipeTypeHelper.TAG_RECIPE_TYPE, "gtceu:photon_matrix_etch");
        scoped = PatternSlotScopedRecipe.withPatternIdentity(scoped,
                pattern.getString(PatternRecipeTypeHelper.TAG_RECIPE_ID),
                pattern.getString(PatternRecipeTypeHelper.TAG_RECIPE_TYPE));
        GTRecipe copy = scoped.copy();
        assertEquals(pattern.getString(PatternRecipeTypeHelper.TAG_RECIPE_ID),
                copy.data.getString(PatternRecipeTypeHelper.TAG_RECIPE_ID));
        assertEquals("gtceu:photon_matrix_etch", copy.data.getString(PatternRecipeTypeHelper.TAG_RECIPE_TYPE));
        assertTrue(original.data.isEmpty(), "樣板來源不能污染全域登錄的配方 data");
    }

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
        assertNotEquals(ORIGINAL_ID, chanceProcessed.id);
        assertEquals(ORIGINAL_ID, PatternSlotScopedRecipe.publicId(chanceProcessed));
        assertFalse(PatternRecipeIdMatchPolicy.matches(
                "gtceu:distort", ORIGINAL_ID.toString(), "gtceu:distort", chanceProcessed.id.toString()));
        assertTrue(PatternRecipeIdMatchPolicy.matches(
                "gtceu:distort", ORIGINAL_ID.toString(), "gtceu:distort",
                PatternSlotScopedRecipe.publicId(chanceProcessed).toString()));
    }

    private static GTRecipe emptyRecipe() {
        return new GTRecipe(null, ORIGINAL_ID,
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new ArrayList<>(), new ArrayList<>(), new CompoundTag(), 20, false);
    }
}
