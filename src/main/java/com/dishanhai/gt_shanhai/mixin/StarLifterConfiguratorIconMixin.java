package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterTerminalChrome;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfigurator;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ConfiguratorPanel.Tab.class, remap = false)
public abstract class StarLifterConfiguratorIconMixin {
    @WrapOperation(method = "drawInBackground", at = @At(value = "INVOKE", target =
            "Lcom/gregtechceu/gtceu/api/gui/fancy/IFancyConfigurator;getIcon()Lcom/lowdragmc/lowdraglib/gui/texture/IGuiTexture;"))
    private IGuiTexture shanhai$drawTerminalConfiguratorIcon(IFancyConfigurator configurator, Operation<IGuiTexture> original) {
        var icon = original.call(configurator);
        return StarLifterTerminalChrome.isTerminal((ConfiguratorPanel.Tab) (Object) this)
                ? StarLifterTerminalChrome.configuratorIcon(icon) : icon;
    }
}
