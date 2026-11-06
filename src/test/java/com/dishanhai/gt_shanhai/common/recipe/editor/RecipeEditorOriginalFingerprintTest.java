package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RecipeEditorOriginalFingerprintTest {
    @TempDir Path directory;

    private static ShanhaiRecipeBase base(int duration) {
        return new ShanhaiRecipeBase("gtceu:assembler", "gtceu:test", duration, 0,
                new JsonObject(), new JsonObject(), new JsonObject(), new JsonArray());
    }

    @Test
    void successiveEditsKeepOriginalFingerprintAcrossFileReloads() throws Exception {
        Path path = directory.resolve("overrides.json");
        ShanhaiRecipeBase original = base(20);
        AtomicReference<ShanhaiRecipeBase> current = new AtomicReference<>(original);
        for (int duration : new int[]{40, 60, 80}) {
            var store = new ShanhaiRecipeOverrideStore(path);
            var ops = new ShanhaiRecipeEditorOps(store, (type, id) -> current.get(), (type, id) -> original);
            var result = ops.commit(new ShanhaiRecipeEditorOps.Edit(base(duration),
                    ShanhaiRecipeFingerprint.of(current.get())));
            assertEquals(ShanhaiRecipeEditorOps.Result.Status.SUCCESS, result.status());
            var reloaded = new ShanhaiRecipeOverrideStore(path).find("gtceu:assembler", "gtceu:test").orElseThrow();
            assertEquals(ShanhaiRecipeFingerprint.of(original), reloaded.baseFingerprint());
            current.set(ShanhaiRecipeBase.fromPayload(reloaded.payload()));
            assertEquals(duration, current.get().duration());
        }
        var store = new ShanhaiRecipeOverrideStore(path);
        var ops = new ShanhaiRecipeEditorOps(store, (type, id) -> current.get(), (type, id) -> original);
        var stale = ops.commit(new ShanhaiRecipeEditorOps.Edit(base(100), ShanhaiRecipeFingerprint.of(original)));
        assertEquals(ShanhaiRecipeEditorOps.Result.Status.CONFLICT, stale.status());
        assertEquals(80, ShanhaiRecipeBase.fromPayload(store.find("gtceu:test").orElseThrow().payload()).duration());
    }

    @Test
    void reopenedAdditionUsesUpstreamOriginalRatherThanItsEditedFingerprint() throws Exception {
        var store = new ShanhaiRecipeOverrideStore(directory.resolve("overrides.json"));
        ShanhaiRecipeBase original = base(20);
        ShanhaiRecipeBase addition = base(40).withRecipeId("gtceu:copy");
        store.putLinked(addition, ShanhaiRecipeFingerprint.legacyV3Fingerprint(original),
                "gtceu:copy", "gtceu:test", true, "test");
        var ops = new ShanhaiRecipeEditorOps(store, (type, id) -> addition, (type, id) -> {
            assertEquals("gtceu:test", id);
            return original;
        });
        var result = ops.commit(new ShanhaiRecipeEditorOps.Edit(addition.withDuration(60),
                ShanhaiRecipeFingerprint.of(addition)));
        assertEquals(ShanhaiRecipeEditorOps.Result.Status.SUCCESS, result.status());
        var reloaded = store.find("gtceu:copy").orElseThrow();
        assertEquals("gtceu:test", reloaded.sourceRecipeId());
        assertEquals(ShanhaiRecipeFingerprint.of(original), reloaded.baseFingerprint());
    }
}
