package com.dishanhai.gt_shanhai.common.recipe.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ShanhaiRecipeFingerprintTest {

    @Test
    void fingerprintChangesWhenDurationOrIoChanges() {
        ShanhaiRecipeBase base = fixture("dishanhai:test", 100, 32L, 1);
        String first = ShanhaiRecipeFingerprint.of(base);

        assertNotEquals(first, ShanhaiRecipeFingerprint.of(base.withDuration(200)));
        assertNotEquals(first, ShanhaiRecipeFingerprint.of(base.withItemAmount(2)));
    }

    private static ShanhaiRecipeBase fixture(String id, int duration, long eut, int itemAmount) {
        return ShanhaiRecipeBase.simple("gtceu:assembler", id, duration, eut, itemAmount);
    }
}
