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
        assertTrue(items.contains("SelectedRecipePatternBehavior.INSTANCE"));
        assertFalse(items.contains("SelectedRecipePattern.Tooltip"));
        assertTrue(lang.contains("tooltip.gt_shanhai.selected_recipe_pattern.open"));
        assertTrue(lang.contains("gui.gt_shanhai.selected_recipe_pattern.shown"));
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
}
