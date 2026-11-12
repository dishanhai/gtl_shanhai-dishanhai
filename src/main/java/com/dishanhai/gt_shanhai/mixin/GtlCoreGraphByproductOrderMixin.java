package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.dishanhai.gt_shanhai.common.ae2.PatternByproductOrder;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Pseudo
@Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.graph.ByproductPatternIndex", remap = false)
public abstract class GtlCoreGraphByproductOrderMixin {

    @Shadow @Final private Map<AEKey, List<IPatternDetails>> outputs;
    @Unique private Map<Object, List<IPatternDetails>> gtShanhai$allOutputFamilies;

    // fix4 reads AE's exact-output index directly with discovery off. These lookups were added in 1.2.3.3.
    @Inject(method = "<init>(Ljava/util/Map;Ljava/util/Map;Ljava/util/Map;Ljava/util/Map;Ljava/util/Map;)V",
            at = @At("RETURN"), require = 0, expect = 0)
    private void gtShanhai$captureOutputFamilies(Map<AEKey, List<IPatternDetails>> primary,
            Map<AEKey, List<IPatternDetails>> secondary, Map<Object, List<IPatternDetails>> primaryFamilies,
            Map<Object, List<IPatternDetails>> allFamilies, Map<AEKey, Boolean> candidates, CallbackInfo ci) {
        this.gtShanhai$allOutputFamilies = allFamilies;
    }

    @WrapOperation(method = "primarySources", at = @At(value = "INVOKE",
            target = "Lorg/gtlcore/gtlcore/integration/ae2/graph/ByproductPatternIndex;ordered(Ljava/util/Collection;Ljava/util/List;)Ljava/util/List;"),
            require = 0, expect = 0)
    private List<IPatternDetails> gtShanhai$includeMarkedExactSources(Collection<IPatternDetails> live,
            List<IPatternDetails> captured, Operation<List<IPatternDetails>> original, AEKey key) {
        return original.call(live, gtShanhai$includeMarked(captured, this.outputs.getOrDefault(key, List.of())));
    }

    @WrapOperation(method = "fuzzySources", at = @At(value = "INVOKE",
            target = "Lorg/gtlcore/gtlcore/integration/ae2/graph/ByproductPatternIndex;ordered(Ljava/util/Collection;Ljava/util/List;)Ljava/util/List;"),
            require = 0, expect = 0)
    private List<IPatternDetails> gtShanhai$includeMarkedFuzzySources(Collection<IPatternDetails> live,
            List<IPatternDetails> captured, Operation<List<IPatternDetails>> original,
            AEKey key, Collection<IPatternDetails> primary, boolean includeSecondary) {
        if (includeSecondary) return original.call(live, captured);
        return original.call(live, gtShanhai$includeMarked(captured,
                this.gtShanhai$allOutputFamilies.getOrDefault(key.getPrimaryKey(), List.of())));
    }

    @WrapOperation(method = "candidates", at = @At(value = "INVOKE",
            target = "Ljava/util/stream/Stream;filter(Ljava/util/function/Predicate;)Ljava/util/stream/Stream;"),
            require = 0, expect = 0)
    private Stream<Map.Entry<AEKey, Boolean>> gtShanhai$includeMarkedCandidates(
            Stream<Map.Entry<AEKey, Boolean>> stream, Predicate<Map.Entry<AEKey, Boolean>> filter,
            Operation<Stream<Map.Entry<AEKey, Boolean>>> original) {
        return original.call(stream, filter.or(entry -> this.outputs.getOrDefault(entry.getKey(), List.of())
                .stream().anyMatch(PatternByproductOrder::isMarked)));
    }

    @Unique
    private static List<IPatternDetails> gtShanhai$includeMarked(List<IPatternDetails> captured,
            List<IPatternDetails> secondary) {
        List<IPatternDetails> merged = null;
        for (IPatternDetails pattern : secondary) {
            if (!PatternByproductOrder.isMarked(pattern)) continue;
            if (merged == null) merged = new ArrayList<>(captured);
            merged.add(pattern);
        }
        // The native ordered() deduplicates and applies the current provider order.
        return merged == null ? captured : merged;
    }
}
