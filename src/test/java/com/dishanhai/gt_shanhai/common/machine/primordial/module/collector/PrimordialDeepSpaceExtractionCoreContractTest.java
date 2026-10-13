package com.dishanhai.gt_shanhai.common.machine.primordial.module.collector;

import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialModuleRecipeLogic;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.PrimordialParallelProcessingModuleBase;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialDeepSpaceExtractionCoreContractTest {

    @Test
    void moduleUsesThePrimordialParallelHierarchy() {
        assertEquals(PrimordialParallelProcessingModuleBase.class,
                PrimordialDeepSpaceExtractionCore.class.getSuperclass());
        assertEquals(PrimordialModuleRecipeLogic.class,
                PrimordialDeepSpaceExtractionCoreLogic.class.getSuperclass());
    }

    @Test
    void registrationMountsTheFiveRecipeTypesAndTheEngineSlot() throws Exception {
        String machines = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "common", "machine", "DShanhaiMachines.java"));
        int factory = machines.indexOf("private static GTRecipeType[] buildDeepSpaceExtractionRecipeTypes()");
        assertTrue(factory >= 0);
        String body = machines.substring(factory, machines.indexOf("public static void init()", factory));
        int gas = body.indexOf("GTRecipeTypes.GAS_COLLECTOR_RECIPES");
        int large = body.indexOf("GTLRecipeTypes.LARGE_GAS_COLLECTOR_RECIPES");
        int voidflux = body.indexOf("GtlAddCompat.voidfluxReaction()");
        int drill = body.indexOf("GTLRecipeTypes.VOID_FLUID_DRILLING_RIG_RECIPES");
        int deep = body.indexOf("DShanhaiRecipeTypes.PRIMORDIAL_DEEP_SPACE_EXTRACTION");
        assertTrue(gas >= 0 && gas < large && large < voidflux && voidflux < drill && drill < deep);

        String structure = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "common", "machine", "primordial", "PrimordialOmegaEngineStructure.java"));
        assertTrue(structure.contains("\"primordial_deep_space_extraction_core\""));
        assertTrue(structure.contains("Predicates.blocks(deepSpaceExtractionCore)"));
    }

    @Test
    void sixteenRecipesStayInsideTheDeclaredIoAndKeepTheTailOutputs() throws Exception {
        Path dir = Path.of("src", "main", "resources", "data", "gt_shanhai", "recipes",
                "primordial_deep_space_extraction");
        try (Stream<Path> files = Files.list(dir)) {
            Path[] recipes = files.filter(path -> path.toString().endsWith(".json")).sorted().toArray(Path[]::new);
            assertEquals(16, recipes.length);
            int maxItems = 0;
            int maxFluids = 0;
            for (Path path : recipes) {
                JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                assertEquals("gtceu:primordial_deep_space_extraction", json.get("type").getAsString());
                assertEquals(1, json.get("duration").getAsInt());
                assertEquals(8, json.getAsJsonObject("tickInputs").getAsJsonArray("eu")
                        .get(0).getAsJsonObject().get("content").getAsInt());
                var inputs = json.getAsJsonObject("inputs").getAsJsonArray("item");
                assertEquals(2, inputs.size());
                assertEquals(0, inputs.get(0).getAsJsonObject().get("chance").getAsInt());
                assertEquals(0, inputs.get(1).getAsJsonObject().get("chance").getAsInt());
                var items = json.getAsJsonObject("outputs").getAsJsonArray("item");
                var fluids = json.getAsJsonObject("outputs").getAsJsonArray("fluid");
                assertTrue(items.size() <= 108, path.getFileName() + " item outs " + items.size());
                assertTrue(fluids.size() <= 8, path.getFileName() + " fluid outs " + fluids.size());
                maxItems = Math.max(maxItems, items.size());
                maxFluids = Math.max(maxFluids, fluids.size());
                JsonObject self = items.get(items.size() - 3).getAsJsonObject();
                JsonObject mining = items.get(items.size() - 2).getAsJsonObject();
                JsonObject treasure = items.get(items.size() - 1).getAsJsonObject();
                assertEquals(5000, self.get("chance").getAsInt());
                assertEquals(0, self.get("tierChanceBoost").getAsInt());
                assertTrue(self.toString().contains("gtlcore:world_fragments_"));
                assertEquals(500, mining.get("chance").getAsInt());
                assertEquals(5, mining.get("tierChanceBoost").getAsInt());
                assertTrue(mining.toString().contains("gtlcore:mining_crystal"));
                assertEquals(500, treasure.get("chance").getAsInt());
                assertEquals(5, treasure.get("tierChanceBoost").getAsInt());
                assertTrue(treasure.toString().contains("gtlcore:treasures_crystal"));
            }
            assertEquals(94, maxItems);
            assertEquals(8, maxFluids);
        }
    }

    @Test
    void jeiUsesTheCompactDeepSpaceRtui() throws Exception {
        Path rtui = Path.of("src", "main", "resources", "assets", "gtceu", "ui", "recipe_type",
                "primordial_deep_space_extraction.rtui");
        String text = new String(Files.readAllBytes(rtui), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("gtceu:primordial_deep_space_extraction"));
        assertTrue(text.contains("item_in_0"));
        assertTrue(text.contains("item_in_1"));
        assertTrue(text.contains("item_out_0"));
        assertTrue(text.contains("item_out_107"));
        assertTrue(text.contains("fluid_out_0"));
        assertTrue(text.contains("fluid_out_7"));
    }
}
