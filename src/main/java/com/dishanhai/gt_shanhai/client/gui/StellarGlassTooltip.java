package com.dishanhai.gt_shanhai.client.gui;

import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiStellarRemoteUIHolder;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferPartMachine;
import com.dishanhai.gt_shanhai.common.machine.part.ui.StellarGlassTexture;
import com.dishanhai.sh_better_ui.client.glass.GlassTooltip;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderTooltipEvent;

/** 星律實體與遠端頁的 tooltip 共用 SH-Better-UI 玻璃材質。 */
public final class StellarGlassTooltip {
    private StellarGlassTooltip() {}

    public static void onTooltipPre(RenderTooltipEvent.Pre event) {
        if (Minecraft.getInstance().screen instanceof ModularUIGuiContainer screen
                && (screen.modularUI.holder instanceof RecipeTypePatternBufferPartMachine
                || screen.modularUI.holder instanceof ShanhaiStellarRemoteUIHolder)) {
            GlassTooltip.render(event, StellarGlassTexture.neutral());
        }
    }
}
