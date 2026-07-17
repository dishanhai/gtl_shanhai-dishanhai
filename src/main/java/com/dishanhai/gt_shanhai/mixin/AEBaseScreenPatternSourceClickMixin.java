package com.dishanhai.gt_shanhai.mixin;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.StackWithBounds;
import appeng.client.gui.me.crafting.CraftingCPUScreen;

import com.dishanhai.gt_shanhai.client.PatternSourceClientCache;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.core.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 合成状态详情页"右键传送"的真正注入点。mouseClicked（m_6375_）是 AEBaseScreen 自己声明的，
 * CraftingCPUScreen 只继承没有重写——之前挂在 CraftingCPUScreen.class 上的注入因为目标类里
 * 根本没有这个方法而从未生效，右键点了没反应。这里改挂在真正声明该方法的 AEBaseScreen 上，
 * 用 instanceof 收窄到合成状态详情页，避免影响 AE2 其它继承 AEBaseScreen 的界面（终端/样板编码等）
 * 的右键行为。
 */
@Mixin(value = AEBaseScreen.class, remap = false)
public abstract class AEBaseScreenPatternSourceClickMixin {

    // 本项目无 refmap：m_6375_ 是 MC 继承方法用 SRG 名 + remap=false；getStackUnderMouse 是 AE2 自有方法，用真实名。
    @Inject(method = "m_6375_", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$handlePatternSourceClick(double mouseX, double mouseY, int button,
            CallbackInfoReturnable<Boolean> cir) {
        if (button != 1) return;
        AEBaseScreen<?> self = (AEBaseScreen<?>) (Object) this;
        if (!(self instanceof CraftingCPUScreen)) return;
        StackWithBounds hovered = self.getStackUnderMouse(mouseX, mouseY);
        if (hovered == null || hovered.stack() == null) return;
        PatternSourceClientCache.Info info = PatternSourceClientCache.get(hovered.stack().what());
        if (info == null) return;
        BlockPos pos = info.pos();
        Minecraft.getInstance().setScreen(new ChatScreen(
                "/tp " + pos.getX() + " " + pos.getY() + " " + pos.getZ()));
        cir.setReturnValue(true);
    }
}
