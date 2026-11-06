package com.dishanhai.gt_shanhai.common.recipe.editor;

import org.junit.jupiter.api.Test;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ShanhaiRecipeFingerprintTest {

    @Test
    void fingerprintChangesWhenDurationOrIoChanges() {
        ShanhaiRecipeBase base = fixture("dishanhai:test", 100, 32L, 1);
        String first = ShanhaiRecipeFingerprint.of(base);

        assertNotEquals(first, ShanhaiRecipeFingerprint.of(base.withDuration(200)));
        assertNotEquals(first, ShanhaiRecipeFingerprint.of(base.withItemAmount(2)));
    }

    @Test
    void hashesMiddleOfLongRecipeInsteadOfTruncatingIt() {
        JsonObject first = new JsonObject();
        first.addProperty("a", "x".repeat(300));
        first.addProperty("itemAmount", 1);
        first.addProperty("z", "y".repeat(300));
        JsonObject second = first.deepCopy();
        second.addProperty("itemAmount", 2);
        assertNotEquals(ShanhaiRecipeFingerprint.fingerprint(first), ShanhaiRecipeFingerprint.fingerprint(second));
        assertTrue(ShanhaiRecipeFingerprint.fingerprint(first).length() < 512);
    }

    @Test
    void sortsNestedObjectKeysButPreservesArrayOrder() {
        JsonObject first = new JsonObject();
        first.addProperty("z", 1);
        first.addProperty("a", 2);
        JsonObject second = new JsonObject();
        second.addProperty("a", 2);
        second.addProperty("z", 1);
        JsonArray a = new JsonArray(); a.add(first); a.add(3);
        JsonArray b = new JsonArray(); b.add(second); b.add(3);
        assertEquals(ShanhaiRecipeFingerprint.fingerprint(a), ShanhaiRecipeFingerprint.fingerprint(b));
        JsonArray reversed = new JsonArray(); reversed.add(3); reversed.add(second);
        assertNotEquals(ShanhaiRecipeFingerprint.fingerprint(a), ShanhaiRecipeFingerprint.fingerprint(reversed));
    }

    @Test
    void legacySnapshotFingerprintsKeepTheirOriginalFormat() {
        ShanhaiRecipeBase base = fixture("dishanhai:test", 100, 32L, 1);
        assertTrue(ShanhaiRecipeFingerprint.of(base).startsWith("v4.sorted:sha256:"));
        assertTrue(ShanhaiRecipeFingerprint.legacyV3Fingerprint(base).startsWith("v3.sorted:json:"));
        assertTrue(ShanhaiRecipeFingerprint.legacySnapshotFingerprint(base).startsWith("v3.sorted:json:"));
    }

    private static ShanhaiRecipeBase fixture(String id, int duration, long eut, int itemAmount) {
        return ShanhaiRecipeBase.simple("gtceu:assembler", id, duration, eut, itemAmount);
    }
}
