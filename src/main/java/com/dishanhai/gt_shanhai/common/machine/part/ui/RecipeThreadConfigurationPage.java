package com.dishanhai.gt_shanhai.common.machine.part.ui;

import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.dishanhai.sh_better_ui.client.glass.GlassPalette;
import com.dishanhai.sh_better_ui.compat.ldlib.GlassPanelTexture;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.gui.fancy.TitleBarWidget;
import com.gregtechceu.gtceu.api.gui.fancy.VerticalTabsWidget;
import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.gui.editor.Icons;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
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
                if (LDLib.isRemote()) {
                    RecipeThreadPanelWidget.applyGlassSlots(this);
                    applyGlassTitleBar(getTitleBar(), getCurrentHomePage().getTitle());
                }
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
    private static void applyGlassTitleBar(TitleBarWidget titleBar, Component title) {
        // 山海：原生标题列的按钮区与标题区各有白底，必须一并替换。
        IGuiTexture glass = (graphics, mouseX, mouseY, x, y, width, height) ->
                RecipeThreadPanelWidget.drawGlass(graphics, (int) x, (int) y, width, height);
        for (var section : titleBar.getWidgetsByType(WidgetGroup.class)) {
            section.setBackground(glass);
        }
        for (var image : titleBar.getWidgetsByType(ImageWidget.class)) {
            if (image.getImage() instanceof TextTexture text) {
                // updateState 会重建带黑色格式码的标题，去掉该前缀才可使用玻璃文字色。
                text.updateText(title.getString());
                text.setColor(GlassPalette.TEXT);
            }
        }
        for (var button : titleBar.getWidgetsByType(ButtonWidget.class)) {
            if (button.getBackgroundTexture() instanceof TextTexture text) {
                text.setColor(GlassPalette.TEXT);
            }
        }
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
