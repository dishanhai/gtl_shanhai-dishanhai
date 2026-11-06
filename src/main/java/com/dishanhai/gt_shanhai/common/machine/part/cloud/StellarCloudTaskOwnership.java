package com.dishanhai.gt_shanhai.common.machine.part.cloud;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.item.PatternSlotScopedRecipe;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialThreadRecipeIdentity;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import java.util.Objects;

/** 山海：只保留仍被在途任務引用的雲端租約；無法定位的同配方保守保留。 */
public final class StellarCloudTaskOwnership {
    private StellarCloudTaskOwnership() {}
    public static boolean references(GTRecipe task, GTRecipe expected, ResourceLocation dimension, BlockPos pos, int slot) {
        if (task == null) return false;
        if (PatternSlotScopedRecipe.isScoped(task)) return PatternSlotScopedRecipe.matchesSource(task, dimension, pos, slot);
        if (expected == null) return true;
        var sources = task.data.getList(PrimordialThreadRecipeIdentity.KEY, 10);
        var identity = PrimordialThreadRecipeIdentity.fromRecipe(expected);
        if (!sources.isEmpty() && identity != null) {
            for (int i = 0; i < sources.size(); i++) {
                var source = sources.getCompound(i);
                if (identity.recipeId().equals(source.getString("recipeId"))
                        && identity.recipeType().equals(source.getString("recipeType"))) return true;
            }
            return false;
        }
        if (task.recipeType != null && task.recipeType.registryName != null
                && "gtceu:dummy".equals(task.recipeType.registryName.toString())) return true;
        return Objects.equals(task.recipeType, expected.recipeType)
                && Objects.equals(task.id, PatternSlotScopedRecipe.isScoped(expected)
                        ? PatternSlotScopedRecipe.publicId(expected) : expected.id);
    }
}
