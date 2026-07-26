package com.dishanhai.gt_shanhai.mixin;

import appeng.menu.AEBaseMenu;
import appeng.menu.me.crafting.CraftConfirmMenu;
import appeng.menu.me.crafting.CraftingPlanSummary;

import com.dishanhai.gt_shanhai.common.ae2.CraftingPlanOverflowDetector;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端兜底：数量溢出的计划一律拒绝下单。
 *
 * <p>客户端那侧（{@link CraftConfirmScreenOverflowGuardMixin}）已经把「开始」按钮禁掉了，
 * 这里防的是绕过 UI 的路径——伪造的 client action、自动合成队列、以及别的 mod 直接调 {@code startJob()}。
 * 溢出的计划下出去只会得到完全错误的结果，宁可不下。
 */
@Mixin(value = CraftConfirmMenu.class, remap = false)
public abstract class CraftConfirmMenuOverflowGuardMixin {

    @Shadow
    private CraftingPlanSummary plan;

    @Inject(method = "startJob", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$blockOverflowJob(CallbackInfo ci) {
        if (!CraftingPlanOverflowDetector.hasOverflow(plan)) return;
        ci.cancel();
        AEBaseMenu self = (AEBaseMenu) (Object) this;
        // 客户端这次调用只是发 action，真正的拒绝在服务端；提示也只在服务端发一次，避免重复。
        if (self.isClientSide()) return;
        Player player = self.getPlayer();
        if (player == null) return;
        player.sendSystemMessage(Component.literal(
                "§c[山海] 本次合成计划的数量已溢出 64 位整数，下单只会得到完全错误的结果，已阻止。"));
        player.sendSystemMessage(Component.literal(
                "§7请大幅调低单次下单量后重试（悬停标红的格子可以看到是哪一项溢出）。"));
    }
}
