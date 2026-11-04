package com.dishanhai.gt_shanhai.common.machine.primordial;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.dishanhai.gt_shanhai.common.item.PatternSlotScopedRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.LinkedHashSet;

/** 山海：保留真實已扣料來源，DUMMY 僅是運行包裝，不能作為 Jade 的配方身份。 */
public final class PrimordialThreadRecipeIdentity {
    public static final String KEY = "sh_primordial_source_recipes";
    private static final ResourceLocation DUMMY = new ResourceLocation("gtceu", "dummy");

    private PrimordialThreadRecipeIdentity() {}

    public record Identity(String recipeType, String recipeId) {
        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("recipeType", recipeType);
            tag.putString("recipeId", recipeId);
            return tag;
        }
    }

    public static Identity fromRecipe(GTRecipe recipe) {
        if (recipe == null) return null;
        String taggedType = recipe.data.getString(PatternRecipeTypeHelper.TAG_RECIPE_TYPE);
        ResourceLocation type = taggedType.isBlank() ? null : ResourceLocation.tryParse(taggedType);
        if (type == null && recipe.recipeType != null) type = recipe.recipeType.registryName;
        if (type == null || DUMMY.equals(type)) return null;
        String taggedId = recipe.data.getString(PatternRecipeTypeHelper.TAG_RECIPE_ID);
        ResourceLocation id = taggedId.isBlank() ? null : ResourceLocation.tryParse(taggedId);
        if (id == null) id = PatternSlotScopedRecipe.isScoped(recipe) ? PatternSlotScopedRecipe.publicId(recipe) : recipe.id;
        return id == null ? null : new Identity(type.toString(), id.toString());
    }

    public static void store(GTRecipe result, Collection<Identity> sources) {
        if (result == null || sources.isEmpty()) return;
        ListTag list = new ListTag();
        for (Identity identity : new LinkedHashSet<>(sources)) {
            if (identity != null) list.add(identity.save());
        }
        result.data = result.data.copy();
        result.data.put(KEY, list);
    }

    public static void appendDisplay(CompoundTag row, GTRecipe recipe) {
        if (recipe == null) return;
        ListTag sources = recipe.data.getList(KEY, 10);
        if (sources.isEmpty()) {
            Identity identity = fromRecipe(recipe);
            sources = new ListTag();
            if (identity != null) sources.add(identity.save());
        }
        ListTag visible = new ListTag();
        for (int i = 0; i < Math.min(4, sources.size()); i++) visible.add(sources.getCompound(i).copy());
        row.put("recipes", visible);
        row.putInt("moreRecipes", sources.size() - visible.size());
        if (!visible.isEmpty()) {
            row.putString("recipeType", visible.getCompound(0).getString("recipeType"));
            row.putString("recipe", visible.getCompound(0).getString("recipeId"));
        }
    }
}
