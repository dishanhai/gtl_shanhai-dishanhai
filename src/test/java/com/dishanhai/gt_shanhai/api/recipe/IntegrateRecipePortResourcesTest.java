package com.dishanhai.gt_shanhai.api.recipe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IntegrateRecipePortResourcesTest {
    private static final Path RECIPES = Path.of("src/main/resources/data/gt_shanhai/recipes");

    @Test
    void paradigmRecipesKeepDistinctIdsAndOnlyConsumeEnergy() throws Exception {
        List<Path> files;
        try (var walk = Files.walk(RECIPES.resolve("guixu_paradigm_simplification/paradigm"))) {
            files = walk.filter(path -> path.toString().endsWith(".json")).toList();
        }
        assertEquals(97, files.size());
        for (Path file : files) {
            JsonObject recipe = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            assertEquals("gtceu:guixu_paradigm_simplification", recipe.get("type").getAsString());
            assertTrue(recipe.get("duration").getAsInt() > 0);
            assertTrue(recipe.getAsJsonObject("tickInputs").getAsJsonArray("eu").get(0)
                    .getAsJsonObject().get("content").getAsLong() > 0);
            assertFalse(recipe.has("tickOutputs"));
            int circuits = 0;
            for (var input : recipe.getAsJsonObject("inputs").getAsJsonArray("item")) {
                JsonObject entry = input.getAsJsonObject();
                if (!entry.has("chance") || entry.get("chance").getAsInt() != 0) continue;
                JsonObject ingredient = entry.getAsJsonObject("content").getAsJsonObject("ingredient");
                assertEquals("gtceu:circuit", ingredient.get("type").getAsString(), "no removed catalysts may return");
                int configuration = ingredient.get("configuration").getAsInt();
                assertTrue(configuration >= 1 && configuration <= 32);
                circuits++;
            }
            assertEquals(1, circuits, file.toString());
            String relative = RECIPES.relativize(file).toString().replace('\\', '/').replaceAll("\\.json$", "");
            assertEquals("gt_shanhai:" + relative, recipe.getAsJsonArray("conditions").get(0)
                    .getAsJsonObject().get("recipeId").getAsString());
        }
    }

    @Test
    void blackholeExtractionRetainsFullOutputsAndDoesNotAddGeneration() throws Exception {
        JsonObject recipe = JsonParser.parseString(Files.readString(RECIPES.resolve(
                "guixu_matter_decompression/blackhole_element_extract.json"))).getAsJsonObject();
        assertEquals("gtceu:guixu_matter_decompression", recipe.get("type").getAsString());
        assertEquals(109, recipe.getAsJsonObject("outputs").getAsJsonArray("item").size());
        assertEquals(17, recipe.getAsJsonObject("outputs").getAsJsonArray("fluid").size());
        assertEquals(2_147_483_648L, recipe.getAsJsonObject("tickInputs").getAsJsonArray("eu")
                .get(0).getAsJsonObject().get("content").getAsLong());
        JsonObject catalyst = recipe.getAsJsonObject("inputs").getAsJsonArray("item").get(0).getAsJsonObject();
        assertEquals(0, catalyst.get("chance").getAsInt());
        assertEquals("gt_shanhai:blackhole_extraction_module", catalyst.getAsJsonObject("content")
                .getAsJsonObject("ingredient").get("item").getAsString());
        assertFalse(Files.exists(RECIPES.resolve("guixu_energy_extraction/blackhole_engine_power.json")));
    }
}
