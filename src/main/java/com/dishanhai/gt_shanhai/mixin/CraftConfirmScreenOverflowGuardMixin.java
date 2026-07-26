package com.dishanhai.gt_shanhai.mixin;

import appeng.client.gui.me.crafting.CraftConfirmScreen;
import appeng.menu.me.crafting.CraftConfirmMenu;

import com.dishanhai.gt_shanhai.common.ae2.CraftingPlanOverflowDetector;

import net.minecraft.client.gui.components.Button;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 数量溢出的计划不给按「开始」。
 *
 * <p>AE2 原生只在 {@code plan.isSimulation()} 时禁用按钮：
 * {@code start.active = !hasNoCPU() && !plan.isSimulation()}。
 * 但溢出是 AE2 自己察觉不到的——数量回绕后计划照样「算成功」，{@code isSimulation()} 是 false，
 * 按钮于是正常可点，下出去的单必然是错的。这里补上这一刀。
 */
@Mixin(value = CraftConfirmScreen.class, remap = false)
public abstract class CraftConfirmScreenOverflowGuardMixin {

    @Shadow
    @Final
    private Button start;

    @Inject(method = "updateBeforeRender", at = @At("TAIL"), remap = false)
    private void gtShanhai$blockOverflowStart(CallbackInfo ci) {
        if (!start.active) return;
        CraftConfirmMenu menu = ((CraftConfirmScreen) (Object) this).getMenu();
        if (menu != null && CraftingPlanOverflowDetector.hasOverflow(menu.getPlan())) {
            start.active = false;
        }
    }
}
