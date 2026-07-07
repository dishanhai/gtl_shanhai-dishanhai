package com.dishanhai.gt_shanhai.mixin;

import appeng.client.gui.me.crafting.CraftingCPUScreen;
import appeng.menu.me.crafting.CraftingStatusMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftingCPUScreen.class, remap = false)
public abstract class CraftingCPUScreenCancelButtonMixin {

    @Shadow
    @Final
    private Button cancel;

    @Inject(method = "m_88315_", at = @At("TAIL"), remap = true)
    private void gtShanhai$enableQuantumStatusCancel(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick,
            CallbackInfo ci) {
        Object menuObject = ((CraftingCPUScreen<?>) (Object) this).getMenu();
        if (!(menuObject instanceof CraftingStatusMenu)) {
            return;
        }
        CraftingStatusMenu menu = (CraftingStatusMenu) menuObject;
        if (gtShanhai$selectedCpuHasJob(menu)) {
            cancel.active = true;
        }
    }

    private static boolean gtShanhai$selectedCpuHasJob(CraftingStatusMenu menu) {
        int selectedSerial = menu.getSelectedCpuSerial();
        for (CraftingStatusMenu.CraftingCpuListEntry cpu : menu.cpuList.cpus()) {
            if (cpu.serial() == selectedSerial) {
                return cpu.currentJob() != null;
            }
        }
        return false;
    }
}
