package com.dishanhai.gt_shanhai.common.recipe;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.lookup.Branch;
import com.gregtechceu.gtceu.api.recipe.lookup.GTRecipeLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeOriginalSnapshotStoreTest {

    private static final String TYPE = "gt_shanhai:snapshot_test";

    @AfterEach
    void clearSnapshots() {
        RecipeOriginalSnapshotStore.clear(TYPE);
    }

    @Test
    void singleLookupDoesNotCompleteTheTypeBaseline() {
        RecipeOriginalSnapshotStore.capture(TYPE, recipe("first", 20));

        assertFalse(RecipeOriginalSnapshotStore.hasSnapshot(TYPE));
        assertEquals(20, RecipeOriginalSnapshotStore.copyOf(TYPE, "gt_shanhai:first").duration);
    }

    @Test
    void fullBaselineSupersedesPartialCaptureAndAResetAllowsReloadedRecipes() {
        RecipeOriginalSnapshotStore.capture(TYPE, recipe("first", 10));
        RecipeOriginalSnapshotStore.capture(TYPE, recipe("removed", 30));
        RecipeOriginalSnapshotStore.captureComplete(TYPE, List.of(recipe("first", 20), recipe("second", 40)));

        assertTrue(RecipeOriginalSnapshotStore.hasSnapshot(TYPE));
        assertEquals(Map.of("first", 20, "second", 40), durations());

        RecipeOriginalSnapshotStore.clear(TYPE);
        RecipeOriginalSnapshotStore.captureComplete(TYPE, List.of(recipe("first", 60)));
        assertEquals(Map.of("first", 60), durations());
    }

    @Test
    void emptyTypeIsACompleteBaseline() {
        RecipeOriginalSnapshotStore.captureComplete(TYPE, List.of());

        assertTrue(RecipeOriginalSnapshotStore.hasSnapshot(TYPE));
        assertTrue(RecipeOriginalSnapshotStore.copiesOf(TYPE).isEmpty());
    }

    @Test
    void stagedReloadLeavesActiveBaselineUntilNewManagerIsInstalled() {
        RecipeManager active = new RecipeManager();
        RecipeManager pending = new RecipeManager();
        RecipeOriginalSnapshotStore.activate(active);
        RecipeOriginalSnapshotStore.captureComplete(TYPE, List.of(recipe("first", 20)));

        RecipeOriginalSnapshotStore.beginLoading();
        RecipeOriginalSnapshotStore.capture(TYPE, recipe("first", 30));
        RecipeOriginalSnapshotStore.endLoading(active, false);
        RecipeOriginalSnapshotStore.activate(active);
        assertEquals(Map.of("first", 20), durations());

        RecipeOriginalSnapshotStore.beginLoading();
        RecipeOriginalSnapshotStore.capture(TYPE, recipe("first", 40));
        RecipeOriginalSnapshotStore.endLoading(pending, true);
        assertEquals(Map.of("first", 20), durations());

        RecipeOriginalSnapshotStore.activate(pending);
        assertEquals(Map.of("first", 40), durations());
        assertFalse(RecipeOriginalSnapshotStore.hasSnapshot(TYPE));
    }

    @Test
    void rejectedTreeCandidatesSurviveLookupUntilTheirOwnIdIsRemoved() {
        GTRecipeLookup lookup = new GTRecipeLookup(null) {
            @Override
            public boolean addRecipe(GTRecipe recipe) {
                return false;
            }
        };
        Branch root = lookup.getLookup();
        GTRecipe first = recipe("first", 20);
        GTRecipe second = recipe("second", 40);
        RecipeRebuildService.addLookupRecipe(lookup, first);
        RecipeRebuildService.addLookupRecipe(lookup, second);
        assertEquals(List.of(first, second), RecipeLookupOverflow.recipes(root));

        RecipeLookupOverflow.remove(root, Set.of("gt_shanhai:first"));
        assertEquals(List.of(second), RecipeLookupOverflow.recipes(root));
        RecipeLookupOverflow.clear(root);
        assertTrue(RecipeLookupOverflow.recipes(root).isEmpty());
    }

    private static Map<String, Integer> durations() {
        return RecipeOriginalSnapshotStore.copiesOf(TYPE).stream()
                .collect(Collectors.toMap(recipe -> recipe.getId().getPath(), recipe -> recipe.duration));
    }

    private static GTRecipe recipe(String id, int duration) {
        return new TestRecipe(new ResourceLocation("gt_shanhai", id), duration);
    }

    // The repo's GTCEu file dependency exposes the production SRG Recipe methods.
    private static final class TestRecipe extends GTRecipe {
        private final ResourceLocation recipeId;

        private TestRecipe(ResourceLocation recipeId, int duration) {
            super(null, recipeId, Map.of(), Map.of(), Map.of(), Map.of(),
                    Map.of(), Map.of(), Map.of(), Map.of(),
                    List.of(), List.of(), new CompoundTag(), duration, false);
            this.recipeId = recipeId;
        }

        @Override
        public ResourceLocation getId() {
            return recipeId;
        }

        @Override
        public GTRecipe copy() {
            return new TestRecipe(recipeId, duration);
        }
    }

    @Test
    void snapshotStoreDefinesStableCopyAndClearContract() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/RecipeOriginalSnapshotStore.java"));

        assertTrue(source.contains("capture(String recipeTypeId, GTRecipe recipe)"));
        assertTrue(source.contains("copyOf(String recipeTypeId, String recipeId)"));
        assertTrue(source.contains("copiesOf(String recipeTypeId)"));
        assertTrue(source.contains("ids(String recipeTypeId)"));
        assertTrue(source.contains("clear(String recipeTypeId)"));
        assertTrue(source.contains("clearAll()"));
        assertTrue(source.contains("recipe.copy()"));
    }

    @Test
    void apiNoLongerOwnsTheCanonicalOriginalMap() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/api/DShanhaiRecipeModifierAPI.java"));

        assertTrue(!source.contains("private static final Map<String, List<GTRecipe>> RECIPE_ORIGINALS"),
                "DShanhaiRecipeModifierAPI must delegate canonical snapshots to the store");
    }
}
