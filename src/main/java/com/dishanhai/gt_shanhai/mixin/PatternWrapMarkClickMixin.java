package com.dishanhai.gt_shanhai.mixin;

import appeng.client.gui.AEBaseScreen;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.menu.slot.FakeSlot;

import com.dishanhai.gt_shanhai.client.PatternWrapClientState;
import com.dishanhai.gt_shanhai.common.item.PatternWrapControlMenu;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 样板编码终端：Shift+右键点击一个已放料的处理输入槽位 = 手动标记"不消耗自动包裹"。
 * 这个手势原版对 FakeSlot 完全没意义：AEBaseScreen 对 FakeSlot 点击只看 mouseButton，
 * 不看 clickType/是否按 Shift —— 左键、右键、Shift+左键都已经是原版的"清空槽位"，
 * 只有 Shift+右键 从没被读取过，可以安全征用（m_6597_ = slotClicked）。
 */
@Mixin(value = AEBaseScreen.class, remap = false)
public abstract class PatternWrapMarkClickMixin {

    @Inject(method = "m_6597_", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$handleMarkClick(Slot slot, int slotIdx, int mouseButton, ClickType clickType, CallbackInfo ci) {
        if (mouseButton != 1 || clickType != ClickType.QUICK_MOVE || !Screen.hasShiftDown()) return;
        if (!(slot instanceof FakeSlot)) return;

        AEBaseScreen<?> self = (AEBaseScreen<?>) (Object) this;
        AbstractContainerMenu menu = self.getMenu();
        if (!(menu instanceof PatternEncodingTermMenu patternMenu)) return;
        if (!gtShanhai$isProcessingInputSlot(patternMenu, slot)) return;

        int containerSlot = slot.getContainerSlot();
        if (!PatternWrapClientState.markedSlots.remove(containerSlot)) {
            PatternWrapClientState.markedSlots.add(containerSlot);
        }
        ((PatternWrapControlMenu) menu).gtShanhai$toggleMark(containerSlot);
        ci.cancel();
    }

    @Unique
    private static boolean gtShanhai$isProcessingInputSlot(PatternEncodingTermMenu menu, Slot slot) {
        for (FakeSlot candidate : menu.getProcessingInputSlots()) {
            if (candidate == slot) return true;
        }
        return false;
    }
}
