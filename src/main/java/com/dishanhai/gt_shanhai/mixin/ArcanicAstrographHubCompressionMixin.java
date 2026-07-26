package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.util.HubMachineHelper;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gtladd.gtladditions.common.machine.multiblock.controller.ArcanicAstrograph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MetaMachine.class, remap = false)
public class ArcanicAstrographHubCompressionMixin {

    private static final long GT_SHANHAI_COMPRESSION_COLLECT_INTERVAL = 10L;
    private static final long GT_SHANHAI_COMPRESSION_FINISH_INTERVAL = 600L;

    @Inject(method = "serverTick", at = @At("TAIL"))
    private void gtShanhai$driveHubCompression(CallbackInfo ci) {
        MetaMachine meta = (MetaMachine) (Object) this;
        if (!(meta instanceof ArcanicAstrograph machine)) {
            return;
        }
        // 节流必须放在 hasHub 之前：hasHub 要完整遍历 getParts()，而本方法挂在 MetaMachine#serverTick
        // 上，原先每 tick 都扫一遍。两个工作间隔是 10 与 600，600 % 10 == 0，所以用 10 做统一闸门
        // 不会漏掉 finishCompression 的时机。
        // 行为差异：工作被关闭时 resetCompression 由每 tick 一次变为每 10 tick 一次——它本身是幂等复位，
        // 且压缩推进本来就只发生在 %10 的 tick 上，不影响结果。
        long timer = machine.getOffsetTimer();
        if (timer % GT_SHANHAI_COMPRESSION_COLLECT_INTERVAL != 0L) {
            return;
        }
        if (!HubMachineHelper.hasHub(machine)) {
            return;
        }
        RecipeLogic logic = machine.getRecipeLogic();
        if (!logic.isWorkingEnabled()) {
            machine.getAstralArrayCompression().resetCompression();
            return;
        }

        machine.getAstralArrayCompression().handleCompressionWorking();
        if (timer % GT_SHANHAI_COMPRESSION_FINISH_INTERVAL == 0L) {
            machine.getAstralArrayCompression().finishCompression();
        }
    }
}
