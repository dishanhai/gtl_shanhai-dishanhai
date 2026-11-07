package com.dishanhai.gt_shanhai.api.machine;

// Author: dishanhai
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class RecipeTypeSearchRoundTest {
    @Test void coldLookupSpreadsTypesAcrossTicksAndDoesNotPublishPartialResults() {
        var round = new SelectableRecipeTypeSetRecipeLogic.TypeSearchRound(new GTRecipeType[19]);
        var calls = new AtomicInteger();
        Function<GTRecipeType, Set<com.gregtechceu.gtceu.api.recipe.GTRecipe>> search = type -> {
            calls.incrementAndGet();
            return Set.of();
        };

        assertNull(round.advance(40L, 8, search));
        assertNull(round.advance(40L, 8, search));
        assertEquals(8, calls.get());
        assertNull(round.advance(41L, 8, search));
        assertEquals(16, calls.get());
        assertEquals(Set.of(), round.advance(42L, 8, search));
        assertEquals(19, calls.get());
    }
}
