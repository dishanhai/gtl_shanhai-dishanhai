package com.dishanhai.gt_shanhai.common.item;

// Author: dishanhai
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class StellarCloudTaskOwnershipTest {
    private final ResourceLocation dimension = new ResourceLocation("minecraft", "overworld");
    private GTRecipe recipe(String id) {
        return new GTRecipe(null, new ResourceLocation("gtceu", id),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new ArrayList<>(), new ArrayList<>(), new CompoundTag(), 20, false);
    }
    private boolean references(GTRecipe task, GTRecipe expected) throws Exception {
        return (boolean) Class.forName("com.dishanhai.gt_shanhai.common.machine.part.cloud.StellarCloudTaskOwnership")
                .getMethod("references", GTRecipe.class, GTRecipe.class, ResourceLocation.class, BlockPos.class, int.class)
                .invoke(null, task, expected, dimension, BlockPos.ZERO, 7);
    }
    @Test void otherLocalRecipeDoesNotHoldAnUnusedCloudLease() throws Exception {
        assertFalse(references(recipe("macerator/local"), recipe("macerator/cloud")));
    }
    @Test void matchingScopedWorkSurvivesMissingPowerAndOtherSlotsDoNotBlock() throws Exception {
        GTRecipe cloud = recipe("macerator/cloud");
        assertTrue(references(PatternSlotScopedRecipe.scope(cloud, dimension, BlockPos.ZERO, 7), cloud));
        assertFalse(references(PatternSlotScopedRecipe.scope(cloud, dimension, BlockPos.ZERO, 8), cloud));
        assertFalse(references(PatternSlotScopedRecipe.scope(cloud, dimension, new BlockPos(1,0,0), 7), cloud));
    }
    @Test void unscopedSameRecipeIsConservativelyRetained() throws Exception {
        assertTrue(references(recipe("macerator/cloud"), recipe("macerator/cloud")));
        assertFalse(references(null, recipe("macerator/cloud")));
    }
}
