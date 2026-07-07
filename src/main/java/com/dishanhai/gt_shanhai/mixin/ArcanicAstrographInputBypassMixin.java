package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.machine.part.IMaintenanceBypassPart;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import org.gtlcore.gtlcore.api.recipe.RecipeCacheStrategy;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper.class, remap = false)
public class ArcanicAstrographInputBypassMixin {

    @Inject(
            method = "handleRecipe(Lcom/gregtechceu/gtceu/api/capability/recipe/IO;Lcom/gregtechceu/gtceu/api/capability/recipe/IRecipeCapabilityHolder;Ljava/util/Map;Ljava/util/Map;ZLcom/gregtechceu/gtceu/api/recipe/GTRecipe;ZLorg/gtlcore/gtlcore/api/recipe/RecipeCacheStrategy;)Lorg/gtlcore/gtlcore/api/recipe/RecipeResult;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private static void gtShanhai$bypassArcanicAstrographHarmonyInputs(
            IO io,
            IRecipeCapabilityHolder holder,
            Map<RecipeCapability<?>, List<Content>> contents,
            Map<RecipeCapability<?>, Object2IntMap<?>> chanceCaches,
            boolean isTick,
            GTRecipe recipe,
            boolean isSimulate,
            RecipeCacheStrategy cacheStrategy,
            CallbackInfoReturnable<RecipeResult> cir) {
        if (io != IO.IN || recipe == null || !(holder instanceof com.gtladd.gtladditions.common.machine.multiblock.controller.ArcanicAstrograph astrograph)) {
            return;
        }
        if (!gtShanhai$hasEnabledHub(astrograph)) {
            return;
        }

        GTRecipe stripped = recipe.copy();
        boolean changed = gtShanhai$stripHarmonyFluids(stripped.inputs);
        changed = gtShanhai$stripHarmonyFluids(stripped.tickInputs) || changed;
        if (!changed) {
            return;
        }

        Map<RecipeCapability<?>, List<Content>> strippedContents = contents;
        if (contents == recipe.inputs) {
            strippedContents = stripped.inputs;
        } else if (contents == recipe.tickInputs) {
            strippedContents = stripped.tickInputs;
        }

        cir.setReturnValue(org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper.handleRecipe(
                io, holder, strippedContents, chanceCaches, isTick, stripped, isSimulate, cacheStrategy));
    }

    private static boolean gtShanhai$hasEnabledHub(IMultiController controller) {
        if (!DShanhaiConfig.COMMON.maintenanceHatchEnabled.get()) return false;
        if (controller == null || !controller.isFormed()) return false;
        for (IMultiPart part : controller.getParts()) {
            if (part instanceof IMaintenanceBypassPart bp && bp.isVoltageBypassEnabled()) {
                return true;
            }
        }
        return false;
    }

    private static boolean gtShanhai$stripHarmonyFluids(Map<RecipeCapability<?>, List<Content>> contents) {
        List<Content> fluids = contents.get(FluidRecipeCapability.CAP);
        if (fluids == null || fluids.isEmpty()) {
            return false;
        }

        boolean changed = false;
        List<Content> kept = new ArrayList<>();
        for (Content content : fluids) {
            if (gtShanhai$isHarmonyFluid(content)) {
                changed = true;
            } else {
                kept.add(content);
            }
        }

        if (!changed) {
            return false;
        }
        if (kept.isEmpty()) {
            contents.remove(FluidRecipeCapability.CAP);
        } else {
            contents.put(FluidRecipeCapability.CAP, kept);
        }
        return true;
    }

    private static boolean gtShanhai$isHarmonyFluid(Content content) {
        if (content == null || content.content == null) {
            return false;
        }
        if (content.content instanceof FluidIngredient ingredient) {
            for (FluidStack stack : ingredient.getStacks()) {
                if (gtShanhai$isHydrogenOrHelium(stack)) {
                    return true;
                }
            }
        }
        if (content.content instanceof FluidStack stack) {
            return gtShanhai$isHydrogenOrHelium(stack);
        }
        return false;
    }

    private static boolean gtShanhai$isHydrogenOrHelium(FluidStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
        if (id == null || !"gtceu".equals(id.getNamespace())) {
            return false;
        }
        String path = id.getPath();
        return "hydrogen".equals(path) || "helium".equals(path);
    }
}
