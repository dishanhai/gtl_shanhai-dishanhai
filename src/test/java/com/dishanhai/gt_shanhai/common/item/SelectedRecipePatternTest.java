package com.dishanhai.gt_shanhai.common.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectedRecipePatternTest {

    @Test
    void roundTripKeepsOrderScaleAndType() {
        CompoundTag tag = SelectedRecipePattern.write(new SelectedRecipePattern.Contents(
                2, 3, "gtceu:lathe", List.of("gtceu:lathe/a", "gtceu:lathe/b", "gtceu:lathe/a")));
        SelectedRecipePattern.Contents contents = SelectedRecipePattern.read(tag);
        assertEquals(2, contents.scale());
        assertEquals(3, contents.outputScale());
        assertEquals("gtceu:lathe", contents.recipeType());
        assertEquals(List.of("gtceu:lathe/a", "gtceu:lathe/b"), contents.recipes());
    }

    @Test
    void rejectsBlankAndUnknownVersions() {
        assertNull(SelectedRecipePattern.write(new SelectedRecipePattern.Contents(
                1, 1, "gtceu:lathe", List.of(" "))));
        assertNull(SelectedRecipePattern.read(null));
        CompoundTag root = new CompoundTag();
        CompoundTag data = new CompoundTag();
        data.putInt("version", 99);
        ListTag recipes = new ListTag();
        recipes.add(StringTag.valueOf("gtceu:lathe/a"));
        data.put("recipes", recipes);
        root.put(SelectedRecipePattern.TAG, data);
        assertNull(SelectedRecipePattern.read(root));
    }

    @Test
    void bufferAndGeneratorWireThePattern() throws Exception {
        String machine = Files.readString(Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
                "common", "machine", "part", "RecipeTypePatternBufferPartMachine.java"));
        String widget = Files.readString(Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
                "common", "gui", "generator", "PatternGeneratorWidget.java"));
        assertTrue(machine.contains("SelectedRecipePattern.isPattern"));
        assertTrue(machine.contains("SelectedRecipePattern.expand"));
        assertTrue(machine.contains("acceptsWildcardMother"));
        assertFalse(machine.contains("setFilter(WildcardPatternBridge::isWildcardPattern)"));
        assertTrue(widget.contains("generateBundle"));
        assertTrue(widget.contains("shanhai_pattern_generator.bundle"));
        String behavior = Files.readString(Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
                "common", "item", "SelectedRecipePatternBehavior.java"));
        String items = Files.readString(Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
                "common", "item", "DShanhaiItems.java"));
        String lang = Files.readString(Path.of("src", "main", "resources", "assets", "gt_shanhai", "lang", "zh_cn.json"));
        assertTrue(behavior.contains("IItemUIFactory"));
        assertTrue(behavior.contains("useOn"));
        assertTrue(behavior.contains("previewStacks"));
        assertTrue(behavior.contains("player.getInventory()"));
        assertTrue(behavior.contains("syncManualRecipes"));
        assertTrue(behavior.contains("matchEncodedPattern"));
        assertTrue(items.contains("SelectedRecipePatternBehavior.INSTANCE"));
        assertFalse(items.contains("SelectedRecipePattern.Tooltip"));
        assertTrue(lang.contains("tooltip.gt_shanhai.selected_recipe_pattern.open"));
        assertTrue(lang.contains("gui.gt_shanhai.selected_recipe_pattern.shown"));
        assertTrue(lang.contains("gui.gt_shanhai.selected_recipe_pattern.group_generated"));
        assertTrue(lang.contains("gui.gt_shanhai.selected_recipe_pattern.group_manual"));
        assertTrue(behavior.contains("group_generated"));
        assertTrue(behavior.contains("group_manual"));
    }

    @Test
    void tooltipKeepsOnlyTheLeadingRecipes() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < SelectedRecipePattern.TOOLTIP_RECIPES + 2; i++) {
            ids.add("gtceu:lathe/recipe_" + i);
        }
        SelectedRecipePattern.Contents contents = new SelectedRecipePattern.Contents(1, 1, "gtceu:lathe", ids);
        assertEquals(SelectedRecipePattern.TOOLTIP_RECIPES, SelectedRecipePattern.tooltipIds(contents).size());
        assertEquals("gtceu:lathe/recipe_0", SelectedRecipePattern.tooltipIds(contents).get(0));
        assertEquals(List.of("gtceu:lathe/a"), SelectedRecipePattern.tooltipIds(
                new SelectedRecipePattern.Contents(1, 1, "", List.of("gtceu:lathe/a"))));
    }

    @Test
    void manualInsertAddsRecipeIdAndRemovalKeepsGeneratedRecipes() {
        CompoundTag root = SelectedRecipePattern.write(new SelectedRecipePattern.Contents(
                2, 3, "gtceu:lathe", List.of("gtceu:lathe/a")));
        root.putString("keep", "yes");

        SelectedRecipePattern.syncManualRecipes(root,
                List.of("gtceu:lathe/a", "gtceu:cutter/b", ""),
                List.of("gtceu:lathe", "gtceu:cutter", ""));
        SelectedRecipePattern.Contents added = SelectedRecipePattern.read(root);
        assertEquals(List.of("gtceu:lathe/a", "gtceu:cutter/b"), added.recipes());
        assertEquals(2, added.scale());
        assertEquals(3, added.outputScale());
        assertEquals("gtceu:lathe", added.recipeType());
        assertEquals("yes", root.getString("keep"));
        assertTrue(root.contains(SelectedRecipePattern.MANUAL_TAG));

        SelectedRecipePattern.syncManualRecipes(root, List.of(""), List.of(""));
        SelectedRecipePattern.Contents removed = SelectedRecipePattern.read(root);
        assertEquals(List.of("gtceu:lathe/a"), removed.recipes());
        assertEquals("gtceu:lathe", removed.recipeType());
        assertFalse(root.contains(SelectedRecipePattern.MANUAL_TAG));
        assertEquals("yes", root.getString("keep"));
    }

    @Test
    void generatedIdsStayApartFromManualInserts() {
        CompoundTag root = SelectedRecipePattern.write(new SelectedRecipePattern.Contents(
                1, 1, "gtceu:lathe", List.of("gtceu:lathe/a", "gtceu:lathe/b")));
        SelectedRecipePattern.syncManualRecipes(root,
                List.of("gtceu:cutter/c"), List.of("gtceu:cutter"));
        assertEquals(List.of("gtceu:lathe/a", "gtceu:lathe/b"), SelectedRecipePattern.generatedRecipeIds(root));
        assertEquals(List.of("gtceu:cutter/c"), SelectedRecipePattern.manualRecipeIds(root));
        assertEquals(List.of(), SelectedRecipePattern.generatedRecipeIds(null));
        assertEquals(List.of(), SelectedRecipePattern.manualRecipeIds(null));
    }

    @Test
    void manualInsertOnEmptyPatternSetsTypeAndClearsWhenRemoved() {
        CompoundTag root = new CompoundTag();
        root.putString("keep", "yes");
        SelectedRecipePattern.syncManualRecipes(root,
                List.of("gtceu:lathe/a", "gtceu:lathe/a"),
                List.of("gtceu:lathe", "gtceu:lathe"));
        SelectedRecipePattern.Contents added = SelectedRecipePattern.read(root);
        assertEquals(List.of("gtceu:lathe/a"), added.recipes());
        assertEquals(1, added.scale());
        assertEquals("gtceu:lathe", added.recipeType());

        SelectedRecipePattern.syncManualRecipes(root, List.of(), List.of());
        assertNull(SelectedRecipePattern.read(root));
        assertEquals("yes", root.getString("keep"));
        assertFalse(root.contains(SelectedRecipePattern.MANUAL_TAG));
    }

    @Test
    void manualInsertDoesNotDropGeneratedRecipesWhenFull() {
        List<String> generated = new ArrayList<>();
        for (int i = 0; i < SelectedRecipePattern.MAX_RECIPES; i++) {
            generated.add("gtceu:lathe/r" + i);
        }
        CompoundTag root = SelectedRecipePattern.write(new SelectedRecipePattern.Contents(
                1, 1, "gtceu:lathe", generated));
        SelectedRecipePattern.syncManualRecipes(root,
                List.of("gtceu:lathe/extra"), List.of("gtceu:lathe"));
        SelectedRecipePattern.Contents contents = SelectedRecipePattern.read(root);
        assertEquals(SelectedRecipePattern.MAX_RECIPES, contents.recipes().size());
        assertEquals("gtceu:lathe/r0", contents.recipes().get(0));
        assertFalse(contents.recipes().contains("gtceu:lathe/extra"));
        assertFalse(root.contains(SelectedRecipePattern.MANUAL_TAG));
    }
}
