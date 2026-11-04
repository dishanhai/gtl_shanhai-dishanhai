package com.dishanhai.gt_shanhai.mixin;

import appeng.crafting.CraftingLink;
import com.dishanhai.gt_shanhai.common.machine.part.StellarPatternOrderInfo;
import com.dishanhai.gt_shanhai.common.machine.part.StellarPatternWarningHistory;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 山海：訂單完成或取消後繼續回寫已發出的告警，不必等待 CPU 再執行一次 tick。 */
@Mixin(value = CraftingLink.class, remap = false)
public abstract class StellarPatternCraftingLinkStateMixin {
    @Inject(method = "cancel", at = @At("RETURN"), remap = false)
    private void gtShanhai$recordCancellation(CallbackInfo callback) {
        CraftingLink link = (CraftingLink) (Object) this;
        var observed = StellarPatternOrderInfo.stateOf(link);
        var signal = observed.terminal() ? observed : StellarPatternOrderInfo.State.CANCELLED;
        gtShanhai$observe(link, signal, signal.resolved() ? 0L : -1L);
    }

    @Inject(method = "markDone", at = @At("RETURN"), remap = false)
    private void gtShanhai$recordCompletion(CallbackInfo callback) {
        CraftingLink link = (CraftingLink) (Object) this;
        var signal = link.isCanceled() ? StellarPatternOrderInfo.State.CANCELLED : StellarPatternOrderInfo.State.COMPLETED;
        gtShanhai$observe(link, signal, signal.resolved() ? 0L : -1L);
    }

    private static void gtShanhai$observe(CraftingLink link, StellarPatternOrderInfo.State signal, long remaining) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        StellarPatternWarningHistory.observeTerminal(server, link, signal, remaining);
    }
}
