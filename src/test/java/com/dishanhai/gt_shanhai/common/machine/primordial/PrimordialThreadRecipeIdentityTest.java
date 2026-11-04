package com.dishanhai.gt_shanhai.common.machine.primordial;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PrimordialThreadRecipeIdentityTest {
    @Test void mergedDummyKeepsActualPatternNbtIdAndTypeAfterThePatternChanges() throws Exception {
        GTRecipe source = recipe("gtceu:photon_matrix_etch", "gt_shanhai:resolved_recipe");
        source.data.putString(PatternRecipeTypeHelper.TAG_RECIPE_ID,
                "gt_shanhai:photon_matrix_etch/wafer/ulpic_wafer_6");
        source.data.putString(PatternRecipeTypeHelper.TAG_RECIPE_TYPE, "gtceu:photon_matrix_etch");
        GTRecipe merged = recipe("gtceu:dummy", "gtceu:dummy_generated");
        PrimordialThreadRecipeIdentity.store(merged, List.of(PrimordialThreadRecipeIdentity.fromRecipe(source)));
        source.data.putString(PatternRecipeTypeHelper.TAG_RECIPE_ID, "gt_shanhai:later_pattern");
        CompoundTag row = new CompoundTag();
        PrimordialThreadRecipeIdentity.appendDisplay(row, merged);
        var identities = row.getList("recipes", 10);
        assertEquals(1, identities.size());
        assertEquals("gtceu:photon_matrix_etch", identities.getCompound(0).getString("recipeType"));
        assertEquals("gt_shanhai:photon_matrix_etch/wafer/ulpic_wafer_6",
                identities.getCompound(0).getString("recipeId"));
    }

    @Test void mixedBatchShowsEachRealTypeAndBoundsTheDisplayPage() throws Exception {
        var sources = new ArrayList<PrimordialThreadRecipeIdentity.Identity>();
        for (int i = 0; i < 7; i++) {
            sources.add(PrimordialThreadRecipeIdentity.fromRecipe(recipe("gtceu:type_" + i, "gt_shanhai:recipe_" + i)));
        }
        GTRecipe merged = recipe("gtceu:dummy", "gtceu:dummy_generated");
        PrimordialThreadRecipeIdentity.store(merged, sources);
        CompoundTag row = new CompoundTag();
        PrimordialThreadRecipeIdentity.appendDisplay(row, merged);
        assertEquals(4, row.getList("recipes", 10).size());
        assertEquals(3, row.getInt("moreRecipes"));
        assertEquals("gtceu:type_0", row.getList("recipes", 10).getCompound(0).getString("recipeType"));
        assertEquals("gtceu:type_3", row.getList("recipes", 10).getCompound(3).getString("recipeType"));
    }

    @Test void legacyDummyDoesNotInventARealTypeOrExposeItsGeneratedId() throws Exception {
        CompoundTag row = new CompoundTag();
        PrimordialThreadRecipeIdentity.appendDisplay(row, recipe("gtceu:dummy", "gtceu:dummy_generated"));
        assertTrue(row.getList("recipes", 10).isEmpty());
        assertFalse(row.contains("recipeType"));
        assertFalse(row.contains("recipe"));
    }

    private static GTRecipe recipe(String typeId, String id) throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        GTRecipeType type = (GTRecipeType) unsafe.allocateInstance(GTRecipeType.class);
        unsafe.putObject(type, unsafe.objectFieldOffset(GTRecipeType.class.getField("registryName")),
                new ResourceLocation(typeId));
        return new GTRecipe(type, new ResourceLocation(id), new HashMap<>(), new HashMap<>(),
                new HashMap<>(), new HashMap<>(), Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(), List.of(), new CompoundTag(), 20, false);
    }
}
