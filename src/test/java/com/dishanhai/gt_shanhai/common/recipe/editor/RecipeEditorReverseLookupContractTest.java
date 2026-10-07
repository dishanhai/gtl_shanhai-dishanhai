package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeQuery.Card;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeQuery.Group;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeQuery.IngredientKind;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeQuery.Result;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeQuery.SearchMode;
import com.dishanhai.gt_shanhai.network.RecipeEditorQueryPacket;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipeEditorReverseLookupContractTest {

    @Test
    void resultGroupsCurrentPageCardsByRecipeType() {
        Card first = card("gtceu:chemical_reactor", "gtceu:first");
        Card second = card("gtceu:distillation_tower", "gtceu:second");
        Card third = card("gtceu:chemical_reactor", "gtceu:third");

        Result result = new Result(List.of(first, second, third), 3, 9, SearchMode.INGREDIENT, null);

        assertEquals(SearchMode.INGREDIENT, result.mode());
        assertEquals(2, result.groups().size());
        assertEquals(new Group("gtceu:chemical_reactor", List.of(0, 2)), result.groups().get(0));
        assertEquals(new Group("gtceu:distillation_tower", List.of(1)), result.groups().get(1));
    }

    @Test
    void queryPacketRoundTripsOutputFluidClassification() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            RecipeEditorQueryPacket sent = new RecipeEditorQueryPacket(
                    "", "minecraft:water", 2, 24, SearchMode.OUTPUT, IngredientKind.FLUID);
            sent.encode(buffer);

            RecipeEditorQueryPacket received = new RecipeEditorQueryPacket(buffer);

            assertEquals(SearchMode.OUTPUT, received.mode());
            assertEquals(IngredientKind.FLUID, received.ingredientKind());
        } finally {
            buffer.release();
        }
    }

    private static Card card(String recipeTypeId, String recipeId) {
        return new Card(recipeTypeId, recipeId, 20, 32, "fingerprint", "item", "minecraft:stone");
    }
}
