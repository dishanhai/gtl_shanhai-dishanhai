package com.dishanhai.gt_shanhai.mixin;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingLink;
import appeng.me.service.CraftingService;
import com.dishanhai.gt_shanhai.common.ae2.GraphCraftingRedispatch;
import org.cgse.core.GraphJobRuntime;
import org.cgse.core.ReplanCoordinator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.graph.GraphCpuController", remap = false)
public abstract class GtlCoreGraphRedispatchMixin implements GraphCraftingRedispatch {

    @Shadow private GraphJobRuntime<AEKey> runtime;
    @Shadow private CraftingLink link;
    @Shadow @Final private ReplanCoordinator<AEKey, ICraftingPlan> replanning;

    @Shadow
    private void startReplan(CraftingService service) {
        throw new AssertionError();
    }

    @Unique
    private GraphJobRuntime<AEKey> gtShanhai$queuedRedispatch;

    @Override
    public boolean gtShanhai$requestRedispatch() {
        if (!GraphCraftingRedispatch.canRetry(runtime) || link == null || link.isCanceled()) return false;
        gtShanhai$queuedRedispatch = runtime;
        return true;
    }

    @Inject(method = "updateReplan", at = @At("TAIL"))
    private void gtShanhai$redispatchPending(CraftingService service, CallbackInfo ci) {
        GraphJobRuntime<AEKey> queued = gtShanhai$queuedRedispatch;
        gtShanhai$queuedRedispatch = null;
        if (queued != null && queued == runtime && link != null && !link.isCanceled()) {
            GraphCraftingRedispatch.retryPending(queued, replanning, () -> startReplan(service));
        }
    }
}
