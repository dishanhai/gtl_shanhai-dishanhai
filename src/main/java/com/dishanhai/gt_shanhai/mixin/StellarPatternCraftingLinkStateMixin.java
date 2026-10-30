package com.dishanhai.gt_shanhai.mixin;

import appeng.crafting.CraftingLink;
import com.dishanhai.gt_shanhai.common.machine.part.StellarPatternOrderInfo;
import com.dishanhai.gt_shanhai.common.machine.part.StellarPatternWarningHistory;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 山海：直接取消訂單也更新歷史，不必等待 CPU 再執行一次 tick。 */
@Mixin(value = CraftingLink.class, remap = false)
public abstract class StellarPatternCraftingLinkStateMixin {
    @Inject(method = {"cancel", "markDone"}, at = @At("RETURN"), remap = false)
    private void gtShanhai$recordActualLinkState(CallbackInfo callback) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) return;
        CraftingLink link = (CraftingLink) (Object) this;
        StellarPatternWarningHistory.observeTerminal(server, link,
                StellarPatternOrderInfo.stateOf(link).resolved() ? 0L : -1L);
    }
}
