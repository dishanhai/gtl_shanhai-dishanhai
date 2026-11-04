package com.dishanhai.gt_shanhai.common.machine.part.ui;

import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.dishanhai.gt_shanhai.client.gui.glass.GlassPalette;
import com.dishanhai.gt_shanhai.client.gui.glass.GlassPanelTexture;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.gui.fancy.VerticalTabsWidget;
import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.gui.editor.Icons;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** 山海：保留枢纽原配置页，配方批次能力放在独立页签。 */
public final class RecipeThreadConfigurationPage implements IFancyUIProvider {
    private final DShanhaiMaintenanceHatchMachine machine;

    public RecipeThreadConfigurationPage(DShanhaiMaintenanceHatchMachine machine) { this.machine = machine; }

    public static ModularUI createUI(DShanhaiMaintenanceHatchMachine machine, Player player) {
        var root = new FancyMachineUIWidget(machine, 176, 166) {
            private boolean glassTabsConfigured;

            @Override
            protected void setupFancyUI(IFancyUIProvider provider, boolean hasInventory) {
                super.setupFancyUI(provider, hasInventory);
                if (LDLib.isRemote()) RecipeThreadPanelWidget.applyGlassSlots(this);
            }

            @Override
            @OnlyIn(Dist.CLIENT)
            public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
                if (LDLib.isRemote() && !glassTabsConfigured) {
                    applyGlassSideTabs(getSideTabsWidget());
                    glassTabsConfigured = true;
                }
                var normal = backgroundTexture;
                backgroundTexture = null;
                try {
                    RecipeThreadPanelWidget.drawGlass(graphics, getPosition().x, getPosition().y, getSize().width, getSize().height);
                    super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
                } finally {
                    backgroundTexture = normal;
                }
            }
        };
        return new ModularUI(176, 166, machine, player).widget(root);
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyGlassSideTabs(VerticalTabsWidget tabs) {
        tabs.setTabTexture(GlassPanelTexture.page());
        tabs.setTabHoverTexture(GlassPanelTexture.display());
        tabs.setTabPressedTexture(GlassPanelTexture.panel());

        tabs.setLeftButtonTexture(new GuiTextureGroup(GlassPanelTexture.button(), Icons.LEFT.copy().scale(0.7f)));
        tabs.setLeftButtonHoverTexture(new GuiTextureGroup(GlassPanelTexture.button(),
                Icons.LEFT.copy().setColor(GlassPalette.ACCENT).scale(0.7f)));
        tabs.setRightButtonTexture(new GuiTextureGroup(GlassPanelTexture.button(), Icons.RIGHT.copy().scale(0.7f)));
        tabs.setRightButtonHoverTexture(new GuiTextureGroup(GlassPanelTexture.button(),
                Icons.RIGHT.copy().setColor(GlassPalette.ACCENT).scale(0.7f)));
    }

    @Override
    public Widget createMainPage(FancyMachineUIWidget widget) {
        return new RecipeThreadPanelWidget(0, 0, 244, 132, machine, machine.getThreadBoostSlot(),
                machine::isRecipeThreadsEnabled, machine::setRecipeThreadsEnabled, machine::getRecipeThreadCapacity, true);
    }

    @Override public IGuiTexture getTabIcon() { return machine.getTabIcon(); }
    @Override public Component getTitle() { return Component.translatable("gt_shanhai.recipe_threads.ui.title"); }
}
