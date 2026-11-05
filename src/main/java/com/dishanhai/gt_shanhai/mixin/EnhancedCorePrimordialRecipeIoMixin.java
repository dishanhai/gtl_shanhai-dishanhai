package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai
import com.dishanhai.gt_shanhai.integration.enhancedcore.EnhancedCorePrimordialContext;
import com.gregtechceu.gtceu.api.capability.recipe.*;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.gtlcore.gtlcore.api.recipe.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.*;

@Pseudo
@Mixin(value = RecipeRunnerHelper.class, remap = false, priority = 1300)
public abstract class EnhancedCorePrimordialRecipeIoMixin {
    @Inject(method = "handleRecipe(Lcom/gregtechceu/gtceu/api/capability/recipe/IO;Lcom/gregtechceu/gtceu/api/capability/recipe/IRecipeCapabilityHolder;Ljava/util/Map;Ljava/util/Map;ZLcom/gregtechceu/gtceu/api/recipe/GTRecipe;ZLorg/gtlcore/gtlcore/api/recipe/RecipeCacheStrategy;)Lorg/gtlcore/gtlcore/api/recipe/RecipeResult;",
            at = @At("HEAD"), cancellable = true)
    private static void shanhai$orderMaterials(IO io, IRecipeCapabilityHolder holder,
            Map<RecipeCapability<?>, List<Content>> contents,
            Map<RecipeCapability<?>, Object2IntMap<?>> chances, boolean tick, GTRecipe recipe,
            boolean simulate, RecipeCacheStrategy strategy, CallbackInfoReturnable<RecipeResult> cir) {
        var context = EnhancedCorePrimordialContext.forMachine(holder);
        if (context == null) return;
        RecipeResult result = context.handle(io, contents, recipe, simulate);
        if (result != null) cir.setReturnValue(result);
    }
}
