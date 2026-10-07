package com.dishanhai.gt_shanhai.common.recipe.editor;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeEditorReverseLookupContractSourceTest {

    @Test
    void querySupportsIngredientAndOutputReverseLookupForItemsAndFluids() throws Exception {
        String query = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeQuery.java"));

        assertTrue(query.contains("enum SearchMode"));
        assertTrue(query.contains("INGREDIENT"));
        assertTrue(query.contains("OUTPUT"));
        assertTrue(query.contains("ShanhaiRecipeReverseIndex.query(server, item)"));
        assertTrue(query.contains("ShanhaiRecipeReverseIndex.queryByOutput(server, item)"));
        assertTrue(query.contains("ShanhaiRecipeReverseIndex.queryFluidInput(server, fluid)"));
        assertTrue(query.contains("ShanhaiRecipeReverseIndex.queryFluidOutput(server, fluid)"));
    }

    @Test
    void resultProvidesRecipeTypeGroupsAndSearchModeForFrontend() throws Exception {
        String query = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeQuery.java"));

        assertTrue(query.contains("record Group(String recipeTypeId, List<Integer> cardIndexes)"));
        assertTrue(query.contains("SearchMode mode"));
        assertTrue(query.contains("groupByRecipeType"));
        assertTrue(query.contains("indexesByType.computeIfAbsent(card.recipeTypeId()"));
        assertTrue(query.contains(".add(i)"));
    }

    @Test
    void queryPacketTransportsModeAndIngredientKind() throws Exception {
        String packet = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/network/RecipeEditorQueryPacket.java"));

        assertTrue(packet.contains("SearchMode mode"));
        assertTrue(packet.contains("IngredientKind ingredientKind"));
        assertTrue(packet.contains("buf.writeVarInt(mode.ordinal())"));
        assertTrue(packet.contains("buf.writeVarInt(ingredientKind.ordinal())"));
        assertTrue(packet.contains("packet.mode, packet.ingredientKind"));
    }
}
