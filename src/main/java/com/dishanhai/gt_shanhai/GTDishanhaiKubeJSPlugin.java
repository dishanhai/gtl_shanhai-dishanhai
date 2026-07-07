package com.dishanhai.gt_shanhai;

import dev.latvian.mods.kubejs.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingsEvent;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.latvian.mods.kubejs.util.ClassFilter;

import com.dishanhai.gt_shanhai.api.*;
import com.dishanhai.gt_shanhai.api.pack.DShanhaiPackRegistry;
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeRegistry;

public class GTDishanhaiKubeJSPlugin extends KubeJSPlugin {

    @Override
    public void registerClasses(ScriptType type, ClassFilter filter) {
        filter.allow("com.dishanhai.gt_shanhai.api.DShanhaiRecipeKJSAPI");
        filter.allow("com.dishanhai.gt_shanhai.api.TooltipEffectAPI");
        filter.allow("com.dishanhai.gt_shanhai.api.TooltipEffectRegistry");
        filter.allow("com.dishanhai.gt_shanhai.api.TooltipEffectLine");
    }

    @Override
    public void registerBindings(BindingsEvent event) {
        event.add("DShanhaiJS", DShanhaiJS.class);
        event.add("DShanhaiTextUtil", DShanhaiTextUtil.class);
        event.add("ShanhaiText", ShanhaiTextAPI.class);
        event.add("ShanhaiStyleRegistry", DShanhaiStyleRegistry.class);
        event.add("TooltipEffectAPI", TooltipEffectAPI.class);
        event.add("DShanhaiItemTooltipAPI", DShanhaiItemTooltipAPI.class);
        event.add("DShanhaiFluidTooltipAPI", DShanhaiFluidTooltipAPI.class);
        event.add("DShanhaiNBTAPI", DShanhaiNBTAPI.class);
        event.add("NBTBuilder", NBTBuilder.class);
        event.add("DShanhaiRecipeRegistry", DShanhaiRecipeRegistry.class);
        event.add("DShanhaiPackRegistry", DShanhaiPackRegistry.class);
        event.add("DShanhaiRecipeEngine", DShanhaiRecipeEngine.class);
        event.add("ShanhaiRecipes", DShanhaiRecipeKJSAPI.class);
        event.add("RecipeModAPI", DShanhaiRecipeModifierAPI.class);
    }
}
