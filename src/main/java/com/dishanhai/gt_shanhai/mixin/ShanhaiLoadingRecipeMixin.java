package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.loading.ShanhaiLoadingClient;
import com.dishanhai.gt_shanhai.client.loading.ShanhaiLoadingProfiler;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.common.crafting.conditions.ICondition;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Map;

/** 山海：client 區混入，專用伺服器不會引用客戶端類。 */
@Mixin(value = RecipeManager.class, remap = false)
public abstract class ShanhaiLoadingRecipeMixin {
    @WrapMethod(method = {"apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            "m_5787_(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V"},
            remap = false)
    private void shanhai$recipes(Map<ResourceLocation, JsonElement> recipes, ResourceManager resources,
                                ProfilerFiller profiler, Operation<Void> original) {
        // WrapMethod 涵蓋 KubeJS 的 HEAD cancel；Map 逐筆走訪也涵蓋略過與解析失敗的候選。
        try {
            original.call(ShanhaiLoadingClient.trackRecipes(recipes), resources, profiler);
            ShanhaiLoadingClient.recipesReady();
        } finally { ShanhaiLoadingClient.endRecipes(); }
    }

    // Forge 新增的三參數 overload 保持原名；不重複攔截轉發的 vanilla overload。
    @WrapMethod(method = "fromJson(Lnet/minecraft/resources/ResourceLocation;Lcom/google/gson/JsonObject;Lnet/minecraftforge/common/crafting/conditions/ICondition$IContext;)Lnet/minecraft/world/item/crafting/Recipe;", remap = false)
    private static Recipe<?> shanhai$parsed(ResourceLocation id, JsonObject json, ICondition.IContext context, Operation<Recipe<?>> original) {
        var owner = ShanhaiLoadingProfiler.session();
        long started = owner == null ? 0 : System.nanoTime();
        boolean failed = true;
        try { Recipe<?> result = original.call(id, json, context); failed = false; return result; }
        finally {
            ShanhaiLoadingProfiler.recipe(owner, id, started, failed);
        }
    }
}
