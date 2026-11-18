package com.dishanhai.gt_shanhai.common.recipe.editor;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class ShanhaiRecipeRestoreRulesTest {
    @Test void exactRuleCanBeRestoredAfterRestart() {
        assertEquals(ShanhaiRecipeRestoreRules.Decision.RESTORE,
                ShanhaiRecipeRestoreRules.decide("gtceu:test", List.of(Pattern.quote("gtceu:test")), false));
    }
    @Test void neverRemoveASeparateRuleThatAlsoDeletesTheRecipe() {
        assertEquals(ShanhaiRecipeRestoreRules.Decision.BLOCKED,
                ShanhaiRecipeRestoreRules.decide("gtceu:test", List.of(Pattern.quote("gtceu:test"), "gtceu:.*"), false));
    }
    @Test void persistedToggleCanBeRestoredWithoutAnExactRule() {
        assertEquals(ShanhaiRecipeRestoreRules.Decision.RESTORE,
                ShanhaiRecipeRestoreRules.decide("gtceu:test", List.of(), true));
    }
    @Test void unknownRecipeIsNotClaimedAsRestored() {
        assertEquals(ShanhaiRecipeRestoreRules.Decision.NOT_DISABLED,
                ShanhaiRecipeRestoreRules.decide("gtceu:test", List.of(), false));
    }
    @Test void unescapedRegexMetacharactersAreNotTreatedAsSingleRecipe() {
        assertEquals(ShanhaiRecipeRestoreRules.Decision.BLOCKED,
                ShanhaiRecipeRestoreRules.decide("example:one.two", List.of("example:one.two"), false));
    }
    @Test void hyphenOutsideACharacterClassIsAnExactLiteral() {
        assertEquals(ShanhaiRecipeRestoreRules.Decision.RESTORE,
                ShanhaiRecipeRestoreRules.decide("example:my-recipe", List.of("example:my-recipe"), false));
    }
}
