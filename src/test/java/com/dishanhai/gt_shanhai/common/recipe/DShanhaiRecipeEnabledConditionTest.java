package com.dishanhai.gt_shanhai.common.recipe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.crafting.CraftingHelper;
import net.minecraftforge.common.crafting.conditions.ICondition;
import net.minecraftforge.fml.loading.FMLPaths;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DShanhaiRecipeEnabledConditionTest {
    private static final ResourceLocation LEGACY_ID = new ResourceLocation("dishanhai", "test_placeholder");

    public static void main(String[] args) throws Exception {
        DShanhaiRecipeEnabledConditionTest test = new DShanhaiRecipeEnabledConditionTest();
        test.missingOverrideKeepsDefaultDisabled();
        test.configOverridesTheDefault();
        test.serializerPreservesIdAndDefault();
        test.forgeRejectsDisabledRecipeBeforeParsing();
        test.jsonStatisticsRespectsRecipeOverride();
        test.jsonFailureHintNamesMissingItemAndFluid();
        test.jsonFailureHintReportsUnknownRecipeType();
        test.jsonFailureHintFallsBackWhenIdsResolve();
    }

    @Test
    void missingOverrideKeepsDefaultDisabled() {
        assertFalse(DShanhaiRecipeEnabledCondition.isEnabled(LEGACY_ID, false, new JsonObject()));
        assertTrue(DShanhaiRecipeEnabledCondition.isEnabled(LEGACY_ID, true, new JsonObject()));
    }

    @Test
    void configOverridesTheDefault() {
        JsonObject config = new JsonObject();
        config.addProperty("test_placeholder", true);
        assertTrue(DShanhaiRecipeEnabledCondition.isEnabled(LEGACY_ID, false, config));

        config.addProperty("dishanhai:test_placeholder", false);
        assertFalse(DShanhaiRecipeEnabledCondition.isEnabled(LEGACY_ID, false, config));
    }

    @Test
    void serializerPreservesIdAndDefault() {
        JsonObject json = new JsonObject();
        DShanhaiRecipeEnabledCondition.Serializer.INSTANCE.write(
                json, new DShanhaiRecipeEnabledCondition(LEGACY_ID, false));

        assertTrue(json.has("recipeId"));
        assertFalse(json.get("defaultEnabled").getAsBoolean());
        DShanhaiRecipeEnabledCondition restored =
                DShanhaiRecipeEnabledCondition.Serializer.INSTANCE.read(json);
        JsonObject encodedAgain = new JsonObject();
        DShanhaiRecipeEnabledCondition.Serializer.INSTANCE.write(encodedAgain, restored);
        assertEquals(json, encodedAgain);
    }

    @Test
    void forgeRejectsDisabledRecipeBeforeParsing() throws Exception {
        FMLPaths.loadAbsolutePaths(Path.of("build", "test-game-dir"));
        CraftingHelper.register(DShanhaiRecipeEnabledCondition.Serializer.INSTANCE);

        try (InputStreamReader reader = new InputStreamReader(Objects.requireNonNull(
                getClass().getResourceAsStream(
                        "/data/gt_shanhai/recipes/assembler/test_placeholder/test_placeholder.json")),
                StandardCharsets.UTF_8)) {
            JsonObject recipe = JsonParser.parseReader(reader).getAsJsonObject();
            assertFalse(CraftingHelper.processConditions(recipe, "conditions", ICondition.IContext.EMPTY));
        }
    }

    @Test
    void jsonStatisticsRespectsRecipeOverride() {
        JsonObject recipe = JsonParser.parseString("""
                {"conditions":[{"type":"gt_shanhai:recipe_enabled",
                "recipeId":"dishanhai:test_placeholder","defaultEnabled":false}]}
                """).getAsJsonObject();
        assertTrue(DShanhaiJsonRecipeStats.isDisabled(recipe, new JsonObject()));

        JsonObject enabledConfig = new JsonObject();
        enabledConfig.addProperty("test_placeholder", true);
        assertFalse(DShanhaiJsonRecipeStats.isDisabled(recipe, enabledConfig));
        assertEquals(
                "gt_shanhai:assembler/test_placeholder [dishanhai:test_placeholder]",
                DShanhaiJsonRecipeStats.disabledLabel(
                        "gt_shanhai:assembler/test_placeholder", recipe));
    }

    @Test
    void jsonFailureHintNamesMissingItemAndFluid() {
        JsonObject recipe = JsonParser.parseString("""
                {"type":"gtceu:electric_implosion_compressor",
                 "inputs":{"item":[{"content":{"ingredient":{"item":"4x kubejs:quantum_chromodynamic_charge"}}}],
                           "fluid":[{"content":{"value":[{"fluid":"gtceu:missing_plasma"}]}}]},
                 "outputs":{"item":[{"content":{"ingredient":{"item":"dishanhai:quantum_chromodynamic_charge_super"}}}]}}
                """).getAsJsonObject();
        String hint = DShanhaiJsonRecipeStats.explainLoadFailure(
                recipe,
                "gtceu:electric_implosion_compressor",
                id -> "kubejs:quantum_chromodynamic_charge".equals(id),
                id -> false,
                id -> true);
        assertTrue(hint.contains("未知物品 dishanhai:quantum_chromodynamic_charge_super"));
        assertFalse(hint.contains("kubejs:quantum_chromodynamic_charge"));
        assertTrue(hint.contains("未知流體 gtceu:missing_plasma"));
        assertFalse(hint.contains("未知配方類型"));
    }

    @Test
    void jsonFailureHintReportsUnknownRecipeType() {
        JsonObject recipe = JsonParser.parseString("{\"type\":\"gtceu:no_such_machine\"}").getAsJsonObject();
        String hint = DShanhaiJsonRecipeStats.explainLoadFailure(
                recipe, "gtceu:no_such_machine", id -> true, id -> true, id -> false);
        assertEquals("未知配方類型 gtceu:no_such_machine", hint);
    }

    @Test
    void jsonFailureHintFallsBackWhenIdsResolve() {
        JsonObject recipe = JsonParser.parseString(
                "{\"outputs\":{\"item\":[{\"content\":{\"ingredient\":{\"item\":\"minecraft:stone\"}}}]}}")
                .getAsJsonObject();
        String hint = DShanhaiJsonRecipeStats.explainLoadFailure(
                recipe, "gtceu:electric_implosion_compressor", id -> true, id -> true, id -> true);
        assertEquals("已寫入資料包，但沒有進入配方表", hint);
    }

    @Test
    void jsonFailureHintNamesOversizedSizedIngredient() {
        JsonObject recipe = JsonParser.parseString("""
                {"type":"gtceu:distort",
                 "inputs":{"item":[
                   {"content":{"type":"gtceu:sized","count":1,"ingredient":{"item":"dishanhai:wzcz2"}},"chance":0},
                   {"content":{"type":"gtceu:sized","count":10000,"ingredient":{"item":"gtceu:platinum_group_sludge_dust"}}}
                 ]},
                 "outputs":{"item":[
                   {"content":{"type":"gtceu:sized","count":10000,"ingredient":{"item":"gtceu:osmium_dust"}}}
                 ]},
                 "conditions":[{"type":"gt_shanhai:recipe_enabled","recipeId":"gt_shanhai:wzcz2_iridium_dust","defaultEnabled":true}]}
                """).getAsJsonObject();
        String hint = DShanhaiJsonRecipeStats.explainLoadFailure(
                "gt_shanhai:distort/wzcz2_iridium_dust",
                recipe,
                "gtceu:distort",
                id -> true,
                id -> true,
                id -> true);
        assertTrue(hint.contains("已寫入資料包，但沒有進入配方表"));
        assertTrue(hint.contains("萬數量用了 gtceu:sized"));
        assertTrue(hint.contains("gtceu:osmium_dust"));
        assertTrue(hint.contains("gtceu:long 的 actualAmount"));
        assertTrue(hint.contains("gtceu:distort 缺少 data.ebf_temp"));
        assertTrue(hint.contains("recipe_enabled 寫成 gt_shanhai:wzcz2_iridium_dust"));
        assertTrue(hint.contains("檔案 id 是 gt_shanhai:distort/wzcz2_iridium_dust"));
    }

    @Test
    void jsonFailureHintNamesIllegalIdAndMissingActualAmount() {
        JsonObject recipe = JsonParser.parseString("""
                {"type":"gtceu:distort","data":{"ebf_temp":3000},
                 "inputs":{"item":[
                   {"content":{"type":"gtceu:long","ingredient":{"item":"Minecraft:Stone"}}},
                   {"content":{"type":"gtceu:item","count":1,"ingredient":{"item":"minecraft:stick"}}}
                 ]}}
                """).getAsJsonObject();
        String hint = DShanhaiJsonRecipeStats.explainLoadFailure(
                recipe, "gtceu:distort", id -> true, id -> true, id -> true);
        assertTrue(hint.contains("非法 id Minecraft:Stone"));
        assertFalse(hint.contains("未知物品 Minecraft:Stone"));
        assertTrue(hint.contains("gtceu:long 缺少 actualAmount"));
        assertTrue(hint.contains("未知原料類型 gtceu:item"));
        assertFalse(hint.contains("缺少 data.ebf_temp"));
    }
}
