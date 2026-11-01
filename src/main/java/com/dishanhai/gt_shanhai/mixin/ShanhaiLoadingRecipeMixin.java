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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/** 山海：client 區混入，專用伺服器不會引用客戶端類。 */
@Mixin(value = RecipeManager.class, remap = false)
public abstract class ShanhaiLoadingRecipeMixin {
    @Inject(method = {"apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            "m_5787_(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V"},
            at = @At("HEAD"), remap = false)
    private void shanhai$beginRecipes(Map<ResourceLocation, JsonElement> recipes, ResourceManager resources,
                                      ProfilerFiller profiler, CallbackInfo ci) { ShanhaiLoadingClient.beginRecipes(recipes.size()); }

    @Inject(method = {"apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            "m_5787_(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V"},
            at = @At("RETURN"), remap = false)
    private void shanhai$endRecipes(Map<ResourceLocation, JsonElement> recipes, ResourceManager resources,
                                    ProfilerFiller profiler, CallbackInfo ci) { ShanhaiLoadingClient.endRecipes(); }

    // Forge 新增的三參數 overload 保持原名；不重複攔截轉發的 vanilla overload。
    @WrapMethod(method = "fromJson(Lnet/minecraft/resources/ResourceLocation;Lcom/google/gson/JsonObject;Lnet/minecraftforge/common/crafting/conditions/ICondition$IContext;)Lnet/minecraft/world/item/crafting/Recipe;", remap = false)
    private static Recipe<?> shanhai$parsed(ResourceLocation id, JsonObject json, ICondition.IContext context, Operation<Recipe<?>> original) {
        var owner = ShanhaiLoadingProfiler.session();
        long started = owner == null ? 0 : System.nanoTime();
        Recipe<?> result = null;
        boolean failed = true;
        try { result = original.call(id, json, context); failed = false; return result; }
        finally {
            ShanhaiLoadingProfiler.recipe(owner, id, started, failed);
            // 計時先結束，避免把進度畫面的補幀成本算成這筆配方的解析時間。
            if (result != null) ShanhaiLoadingClient.parsedRecipe(id);
        }
    }
}
