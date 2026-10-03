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
    }
}
