package com.dishanhai.gt_shanhai.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.cgse.core.GraphRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;

@Pseudo
@Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.graph.GraphPlanSummary", remap = false)
public abstract class GtlCoreGraphPresenceSummaryMixin {

    // Filter reusable returns before GTLCore groups tool states and totals production.
    @WrapOperation(method = "products", at = @At(value = "INVOKE",
            target = "Lorg/cgse/core/GraphRecipe;outputs()Ljava/util/Map;"),
            require = 0, expect = 0, remap = false)
    private static <K> Map<K, Long> gtShanhai$physicalProducts(GraphRecipe<K> recipe,
            Operation<Map<K, Long>> original) {
        return recipe.executionOutputs();
    }

    @Pseudo
    @Mixin(targets = "org.cgse.core.PlanSummary", remap = false)
    public static class Legacy {

        // GTLCore 1.2.3.2-fix4 uses the two-argument summary without a product selector.
        @WrapOperation(method = "lambda$entries$1(Lorg/cgse/core/GraphPlan;Ljava/util/Map;Ljava/util/Map;Ljava/lang/String;Ljava/math/BigInteger;)V",
                at = @At(value = "INVOKE", target = "Lorg/cgse/core/GraphRecipe;outputs()Ljava/util/Map;"),
                require = 0, expect = 0, remap = false)
        private static <K> Map<K, Long> gtShanhai$physicalProducts(GraphRecipe<K> recipe,
                Operation<Map<K, Long>> original) {
            return recipe.executionOutputs();
        }
    }
}
