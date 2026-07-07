package com.dishanhai.gt_shanhai.api;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

public class DShanhaiRuntimeRecipeCacheTest {

    @Test
    void cacheKeepsNegativeRecipeLookupResults() throws Exception {
        DShanhaiRuntimeRecipeCache.clear();
        Object key = newKey("gtceu:assembler", "items=a", "fluids=", 1L, "findRecipe");

        DShanhaiRuntimeRecipeCache.put((DShanhaiRuntimeRecipeCache.Key) key, null);

        Optional<GTRecipe> result = DShanhaiRuntimeRecipeCache.get((DShanhaiRuntimeRecipeCache.Key) key);

        try {
            assertFalse(result.isPresent());
            assertEquals(1, DShanhaiRuntimeRecipeCache.size());
        } finally {
            DShanhaiRuntimeRecipeCache.clear();
        }
    }

    @Test
    void cacheKeyIsScopedByTypeRevisionAndLookupScope() throws Exception {
        Object first = newKey("gtceu:assembler", "items=a", "fluids=", 1L, "findRecipe");
        Object same = newKey("gtceu:assembler", "items=a", "fluids=", 1L, "findRecipe");
        Object nextType = newKey("gtceu:centrifuge", "items=a", "fluids=", 1L, "findRecipe");
        Object nextRevision = newKey("gtceu:assembler", "items=a", "fluids=", 2L, "findRecipe");
        Object nextScope = newKey("gtceu:assembler", "items=a", "fluids=", 1L, "find");

        assertEquals(first, same);
        assertFalse(first.equals(nextType));
        assertFalse(first.equals(nextRevision));
        assertFalse(first.equals(nextScope));
    }

    @Test
    void recipeModificationInvalidationClearsRuntimeCache() throws Exception {
        DShanhaiRuntimeRecipeCache.clear();
        Object key = newKey("gtceu:assembler", "items=a", "fluids=", DShanhaiRecipeModifierAPI.getPatternCacheRevision(), "findRecipe");
        DShanhaiRuntimeRecipeCache.put((DShanhaiRuntimeRecipeCache.Key) key, null);

        DShanhaiRecipeModifierAPI.invalidatePatternCaches("runtime-cache-test");

        assertEquals(0, DShanhaiRuntimeRecipeCache.size());
    }

    @Test
    void candidateCacheRerunsPredicateForEachLookup() throws Exception {
        DShanhaiRuntimeRecipeCache.clear();
        DShanhaiRuntimeRecipeCache.Key key = (DShanhaiRuntimeRecipeCache.Key) newKey(
                "gtceu:assembler", "items=a", "fluids=", 1L, "findCandidates");
        GTRecipe first = newRecipe("first");
        GTRecipe second = newRecipe("second");

        DShanhaiRuntimeRecipeCache.putCandidates(key, List.of(first, second));

        try {
            assertSame(second, DShanhaiRuntimeRecipeCache.findFirstCandidate(key, recipe -> recipe == second));
            assertSame(first, DShanhaiRuntimeRecipeCache.findFirstCandidate(key, recipe -> recipe == first));
            assertEquals(1, DShanhaiRuntimeRecipeCache.size());
        } finally {
            DShanhaiRuntimeRecipeCache.clear();
        }
    }

    private static Object newKey(String typeId, String itemFingerprint, String fluidFingerprint, long revision, String scope) throws Exception {
        Constructor<DShanhaiRuntimeRecipeCache.Key> constructor = DShanhaiRuntimeRecipeCache.Key.class.getDeclaredConstructor(
                String.class, String.class, String.class, long.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(typeId, itemFingerprint, fluidFingerprint, revision, scope);
    }

    private static GTRecipe newRecipe(String id) {
        return new GTRecipe(null, new ResourceLocation("gt_shanhai", id),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyList(), Collections.emptyList(), new CompoundTag(), 20, false);
    }
}
