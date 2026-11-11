package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.recipe.RecipeOriginalSnapshotStore;
import com.google.gson.JsonElement;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Map;

/** 山海：仅在真实数据包 apply 开始时开启新一代原始配方快照。 */
@Mixin(value = RecipeManager.class, remap = false)
public abstract class RecipeOriginalGenerationMixin {
    @WrapMethod(method = {
            "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            "m_5787_(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V"
    }, remap = false)
    private void gtShanhai$beginRecipeGeneration(Map<ResourceLocation, JsonElement> recipes,
            ResourceManager resources, ProfilerFiller profiler, Operation<Void> original) {
        RecipeOriginalSnapshotStore.beginLoading();
        boolean loaded = false;
        try {
            original.call(recipes, resources, profiler);
            loaded = true;
        } finally {
            RecipeOriginalSnapshotStore.endLoading((RecipeManager) (Object) this, loaded);
        }
    }
}
