package com.dishanhai.gt_shanhai.common.recipe.editor;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;

public record ShanhaiVanillaRecipeView(ResourceLocation id, Recipe<?> recipe) {}
