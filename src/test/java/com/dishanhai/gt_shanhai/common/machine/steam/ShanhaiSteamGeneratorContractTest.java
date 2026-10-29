package com.dishanhai.gt_shanhai.common.machine.steam;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：蒸汽生成機的比例、取得階段與無電力路徑契約。 */
class ShanhaiSteamGeneratorContractTest {
    private static final Path RECIPES = Path.of("src/main/resources/data/gt_shanhai/recipes");
    private static final Path JAVA = Path.of("src/main/java/com/dishanhai/gt_shanhai");

    private JsonObject recipe(String name) throws Exception {
        Path path = RECIPES.resolve(name + ".json");
        assertTrue(Files.exists(path), "缺少蒸汽階段配方：" + path);
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    @Test
    void twoWaterBecomesOneSteamInOneSecondWithoutFuelOrEnergy() throws Exception {
        JsonObject recipe = recipe("steam_generation/water_to_steam");
        assertEquals("gtceu:steam_generation", recipe.get("type").getAsString());
        assertEquals(20, recipe.get("duration").getAsInt());
        JsonObject inputs = recipe.getAsJsonObject("inputs");
        JsonObject outputs = recipe.getAsJsonObject("outputs");
        assertEquals(Set.of("fluid"), inputs.keySet());
        assertEquals(Set.of("fluid"), outputs.keySet());
        JsonObject water = inputs.getAsJsonArray("fluid").get(0).getAsJsonObject().getAsJsonObject("content");
        JsonObject steam = outputs.getAsJsonArray("fluid").get(0).getAsJsonObject().getAsJsonObject("content");
        assertEquals(2000, water.get("amount").getAsInt());
        assertEquals(1000, steam.get("amount").getAsInt());
        assertEquals("minecraft:water", water.getAsJsonArray("value").get(0).getAsJsonObject().get("fluid").getAsString());
        assertEquals("gtceu:steam", steam.getAsJsonArray("value").get(0).getAsJsonObject().get("fluid").getAsString());
        assertFalse(recipe.has("tickInputs"));
        assertFalse(recipe.has("tickOutputs"));
    }

    @Test
    void controllerCanBeHandcraftedWithoutRegisteringExtraFluidHatches() throws Exception {
        Set<String> allowed = Set.of("gtceu:bronze_gearbox", "gtceu:bronze_pipe_casing",
                "gtceu:bronze_machine_casing", "gtceu:bronze_firebox_casing");
        for (String id : new String[]{"steam_generator"}) {
            JsonObject recipe = recipe("crafting_shaped/steam/" + id);
            assertEquals("minecraft:crafting_shaped", recipe.get("type").getAsString());
            assertEquals("gt_shanhai:" + id, recipe.getAsJsonObject("result").get("item").getAsString());
            for (var entry : recipe.getAsJsonObject("key").entrySet()) {
                assertTrue(allowed.contains(entry.getValue().getAsJsonObject().get("item").getAsString()), entry.toString());
            }
        }
        assertFalse(Files.exists(RECIPES.resolve("crafting_shaped/steam/bronze_fluid_input_hatch.json")));
        assertFalse(Files.exists(RECIPES.resolve("crafting_shaped/steam/bronze_fluid_output_hatch.json")));
        String source = Files.readString(JAVA.resolve("common/machine/steam/ShanhaiSteamGeneratorMachine.java"));
        assertFalse(source.contains("registerHatch("));
        assertTrue(source.contains("Predicates.abilities(PartAbility.IMPORT_FLUIDS)"));
        assertTrue(source.contains("Predicates.abilities(PartAbility.EXPORT_FLUIDS)"));
    }

    @Test
    void registrationUsesOwnFluidOnlyTypeAndExplicitSteamTierStructure() throws Exception {
        Path machine = JAVA.resolve("common/machine/steam/ShanhaiSteamGeneratorMachine.java");
        assertTrue(Files.exists(machine), "缺少蒸汽生成機實作");
        String source = Files.readString(machine);
        assertTrue(Files.readString(JAVA.resolve("common/machine/DShanhaiMachines.java"))
                .contains("ShanhaiSteamGeneratorMachine.register()"));
        assertTrue(Files.readString(JAVA.resolve("api/recipe/DShanhaiRecipeTypes.java"))
                .contains("STEAM_GENERATION = register(\"steam_generation\", \"multiblock\")"));
        for (String casing : new String[]{"BRONZE_HULL", "CASING_BRONZE_GEARBOX", "CASING_BRONZE_PIPE", "FIREBOX_BRONZE"}) {
            assertTrue(source.contains("GTBlocks." + casing), casing);
        }
        assertFalse(source.contains("PartAbility.INPUT_ENERGY"));
        assertFalse(source.contains("Predicates.autoAbilities"));
        assertTrue(source.contains("extends WorkableElectricMultipleRecipesMachine"));
        assertTrue(source.contains("extends MultipleRecipesLogic"));
        assertTrue(source.contains("public void findAndHandleRecipe()"));
        assertTrue(source.contains("public void onRecipeFinish()"));
        assertFalse(source.contains("getOverclockVoltage()"), "零 EU 配方不可經過原生電壓門檻");
        assertTrue(source.contains("RecipeRunnerHelper.matchRecipeOutput"), "完成輸出前必須預檢容量");
    }

    @Test
    void actualBatchUsesLongParallelAndExactScalingWithOutputCapacityChecks() throws Exception {
        String source = Files.readString(JAVA.resolve("common/machine/steam/ShanhaiSteamGeneratorMachine.java"));
        assertTrue(source.contains("IParallelLogic.getParallel(machine, recipe,"),
                "實際批次須使用原生 long 輸入量與輸出容量預檢");
        assertTrue(source.contains("getParallelLimit()"));
        assertTrue(source.contains("IAdvancedContentModifier.preciseMultiplier(parallel)"),
                "long 倍率不可轉 double 而損失精度");
        assertTrue(source.contains("setRealParallels(parallel)"));
        for (String language : new String[]{"zh_cn", "en_us"}) {
            JsonObject lang = JsonParser.parseString(Files.readString(Path.of(
                    "src/main/resources/assets/gt_shanhai/lang/" + language + ".json")))
                    .getAsJsonObject();
            assertTrue(lang.get("gt_shanhai.steam_generator.parallel_rule").getAsString()
                    .contains("Long.MAX_VALUE"), "提示須說明模組並行乘四，溢位飽和至 long 上限");
        }
    }

    @Test
    void structureExtendsBehindControllerWithFireboxesBelowGears() throws Exception {
        String source = Files.readString(JAVA.resolve("common/machine/steam/ShanhaiSteamGeneratorMachine.java"));
        assertTrue(source.contains("RelativeDirection.RIGHT, RelativeDirection.DOWN, RelativeDirection.BACK"),
                "圖樣按上至下、前至後書寫，不能使用原生 UP／FRONT 預設方向");
    }
}
