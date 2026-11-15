package com.dishanhai.gt_shanhai.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.execution.CraftingSubmitResult;
import com.dishanhai.gt_shanhai.common.item.GraphVirtualPresence;
import com.dishanhai.gt_shanhai.common.item.VirtualProviderSoftLocks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.cgse.core.GraphCompiler;
import org.gtlcore.gtlcore.integration.ae2.graph.CapturedPatternCatalog;
import org.gtlcore.gtlcore.integration.ae2.graph.GtlPatternCatalog;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.ToLongFunction;

@Pseudo
@Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.graph.GtlPatternCatalog$Capture", remap = false)
public abstract class GtlCoreGraphVirtualPresenceMixin {

    @Shadow @Final private IGrid grid;
    @Unique private boolean gtShanhai$supplyMode;
    @Unique private ToLongFunction<AEKey> gtShanhai$supplyPresence;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void gtShanhai$captureSupplyOnServer(CallbackInfo ci) {
        this.gtShanhai$supplyMode = VirtualProviderSoftLocks.usesSupplyMachineMode();
        this.gtShanhai$supplyPresence = VirtualProviderSoftLocks.snapshotSupplyPresence(this.grid);
    }

    @Inject(method = "result", at = @At("RETURN"))
    private void gtShanhai$attachSupplySnapshot(CallbackInfoReturnable<GtlPatternCatalog.Snapshot> cir) {
        var stock = cir.getReturnValue().stock();
        ToLongFunction<AEKey> presence = this.gtShanhai$supplyMode ? this.gtShanhai$supplyPresence
                : key -> stock.getOrDefault(key, 0L);
        ((GraphVirtualPresence.SnapshotAccess) (Object) cir.getReturnValue())
                .gtShanhai$setExternalPresence(presence);
    }

    @Pseudo
    @Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.graph.GtlPatternCatalog$Snapshot", remap = false)
    public static class Snapshot implements GraphVirtualPresence.SnapshotAccess {

        @Unique private ToLongFunction<AEKey> gtShanhai$externalPresence;

        @Override
        public void gtShanhai$setExternalPresence(ToLongFunction<AEKey> presence) {
            this.gtShanhai$externalPresence = presence;
        }

        @Override
        public long gtShanhai$getExternalPresence(AEKey key) {
            return this.gtShanhai$externalPresence == null ? 0L : this.gtShanhai$externalPresence.applyAsLong(key);
        }
    }

    @Pseudo
    @Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.graph.CraftingEngineRouter$RequestWork", remap = false)
    public static class RequestWork {

        @Shadow private GtlPatternCatalog.Snapshot snapshot;
        @Shadow private CapturedPatternCatalog.Prepared prepared;

        @ModifyExpressionValue(method = "advance", at = @At(value = "INVOKE",
                target = "Lorg/gtlcore/gtlcore/integration/ae2/graph/CapturedPatternCatalog$Prepared;compiler()Lorg/cgse/core/GraphCompiler;"))
        private GraphCompiler<AEKey> gtShanhai$planPhysicalInputs(GraphCompiler<AEKey> compiler) {
            var presence = (GraphVirtualPresence.SnapshotAccess) (Object) this.snapshot;
            return GraphVirtualPresence.externalize(compiler, this.prepared.bindings(),
                    presence::gtShanhai$getExternalPresence);
        }
    }

    @Pseudo
    @Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.graph.GraphCpuController", remap = false)
    public static class Cpu {

        @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
        private void gtShanhai$recheckExternalPresence(IGrid grid, ICraftingPlan supplied, IActionSource source,
                ICraftingRequester requester, CallbackInfoReturnable<ICraftingSubmitResult> cir) {
            GenericStack missing = GraphVirtualPresence.missingExternalPresence(supplied, grid, source);
            if (missing != null) cir.setReturnValue(CraftingSubmitResult.missingIngredient(missing));
        }
    }
}
