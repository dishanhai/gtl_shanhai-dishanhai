package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterTerminalChrome;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.gui.fancy.TabsWidget;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = TabsWidget.class, remap = false)
public abstract class StarLifterPageTabIconMixin {
    @WrapOperation(method = "drawTab", at = @At(value = "INVOKE", target =
            "Lcom/gregtechceu/gtceu/api/gui/fancy/IFancyUIProvider;getTabIcon()Lcom/lowdragmc/lowdraglib/gui/texture/IGuiTexture;"))
    private IGuiTexture shanhai$drawTerminalPageIcon(IFancyUIProvider page, Operation<IGuiTexture> original) {
        var tabs = (TabsWidget) (Object) this;
        var icon = original.call(page);
        return StarLifterTerminalChrome.isTerminal(tabs)
                ? StarLifterTerminalChrome.pageIcon(page, tabs.getSelectedTab() == page, icon) : icon;
    }
}
