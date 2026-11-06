package com.dishanhai.gt_shanhai.common.machine.part.ui;

import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferPartMachine;
import com.dishanhai.sh_better_ui.client.glass.GlassPalette;
import com.dishanhai.sh_better_ui.compat.ldlib.GlassPanelTexture;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.machine.feature.IFancyUIMachine;
import com.gregtechceu.gtceu.api.gui.fancy.TitleBarWidget;
import com.gregtechceu.gtceu.api.gui.fancy.VerticalTabsWidget;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.gui.editor.Icons;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.utils.Position;
import com.lowdragmc.lowdraglib.utils.Size;
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
        return createGlassUI(machine, player);
    }

    /** Fancy 機器與遠端 holder 共用玻璃外框，保留原頁籤和配置流程。 */
    public static ModularUI createGlassUI(IFancyUIMachine machine, Player player) {
        return createGlassUI(machine, player, machine);
    }

    public static ModularUI createGlassUI(IFancyUIMachine machine, Player player, IUIHolder holder) {
        boolean stellar = machine instanceof RecipeTypePatternBufferPartMachine;
        var root = new FancyMachineUIWidget(machine, 176, 166) {
            private Boolean lastStellarGlass;
            private boolean sideTabsStyled;

            @OnlyIn(Dist.CLIENT)
            private void updateStellarAppearance() {
                if (!stellar) return;
                RecipeThreadPanelWidget.styleStellarControls(this);
                boolean neutral = StellarGlassTexture.neutral();
                if (lastStellarGlass != null && lastStellarGlass == neutral) return;
                lastStellarGlass = neutral;
                RecipeThreadPanelWidget.applyStellarGlassSlots(this);
                applyGlassTitleBar(getTitleBar(), getCurrentHomePage().getTitle(), true, true);
                getConfiguratorPanel().setTexture(StellarGlassTexture.panel());
                for (var tab : getConfiguratorPanel().getTabs()) {
                    tab.setBackground(StellarGlassTexture.panel());
                }
                VerticalTabsWidget tabs = getSideTabsWidget();
                applyGlassSideTabs(tabs, true);
            }

            @Override
            protected void setupFancyUI(IFancyUIProvider provider, boolean hasInventory) {
                if (stellar) {
                    setupStellarPage(provider, hasInventory, (RecipeTypePatternBufferPartMachine) machine);
                } else {
                    super.setupFancyUI(provider, hasInventory);
                }
                if (stellar) {
                    var panel = getConfiguratorPanel();
                    panel.attachConfigurators(StellarGlassTexture.appearanceConfigurator());
                    panel.setSelfPosition(new Position(panel.getSelfPositionX(),
                            getSize().height - panel.getSize().height - 4));
                }
                if (LDLib.isRemote()) {
                    if (stellar) {
                        lastStellarGlass = null;
                        updateStellarAppearance();
                    } else {
                        RecipeThreadPanelWidget.applyGlassSlots(this);
                        applyGlassTitleBar(getTitleBar(), getCurrentHomePage().getTitle(), false, true);
                    }
                }
            }

            /** 山海：所有星律頁籤共用主樣板畫布，隱藏背包時仍保留其高度。 */
            private void setupStellarPage(IFancyUIProvider provider, boolean hasInventory,
                                          RecipeTypePatternBufferPartMachine machine) {
                clearUI();
                sideTabsWidget.selectTab(provider);
                titleBar.updateState(currentHomePage, !previousPages.isEmpty(),
                        allPages.size() > 1 && currentPage != pageSwitcher);
                Widget page = provider.createMainPage(this);
                Size canvas = new Size(Math.max(172, machine.stellarPatternUiWidth() + border * 2),
                        Math.max(86, machine.stellarPatternUiHeight() + border * 2));
                int inventoryHeight = playerInventory == null ? 0 : playerInventory.getSize().height;
                setSize(new Size(canvas.width, canvas.height + inventoryHeight));
                if (LDLib.isRemote() && getGui() != null) {
                    getGui().setSize(getSize().width, getSize().height);
                }
                pageContainer.setSize(canvas);
                sideTabsWidget.setSize(new Size(24,
                        stellarSidebarHeight(canvas.height, sideTabsWidget.getSubTabs().size())));
                tooltipsPanel.setSelfPosition(new Position(canvas.width + 2, 2));
                if (playerInventory != null) {
                    playerInventory.setSelfPosition(new Position(
                            (canvas.width - playerInventory.getSize().width) / 2, canvas.height));
                    playerInventory.setActive(hasInventory);
                    playerInventory.setVisible(hasInventory);
                }
                int availableWidth = canvas.width - border * 2;
                int availableHeight = canvas.height - border * 2;
                if (page.getSize().width > availableWidth || page.getSize().height > availableHeight) {
                    // 捲動條佔用額外空間，避免遮住內容最後一列／行。
                    var viewport = new DraggableScrollableWidgetGroup(border, border, availableWidth, availableHeight);
                    viewport.setXScrollBarHeight(4).setYScrollBarWidth(4);
                    viewport.setXBarStyle(StellarGlassTexture.display(), StellarGlassTexture.button());
                    viewport.setYBarStyle(StellarGlassTexture.display(), StellarGlassTexture.button());
                    page.setSelfPosition(new Position(0, 0));
                    viewport.addWidget(page);
                    viewport.addWidget(new WidgetGroup(page.getSize().width + 3, page.getSize().height + 3, 1, 1));
                    pageContainer.addWidget(viewport);
                } else {
                    page.setSelfPosition(new Position((canvas.width - page.getSize().width) / 2,
                            (canvas.height - page.getSize().height) / 2));
                    pageContainer.addWidget(page);
                }
                provider.attachConfigurators(configuratorPanel);
                provider.attachTooltips(tooltipsPanel);
                titleBar.setSize(new Size(canvas.width, titleBar.getSize().height));
            }

            @Override
            @OnlyIn(Dist.CLIENT)
            public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
                if (LDLib.isRemote()) {
                    if (stellar) updateStellarAppearance();
                    else if (!sideTabsStyled) {
                        applyGlassSideTabs(getSideTabsWidget(), false);
                        sideTabsStyled = true;
                    }
                }
                var normal = backgroundTexture;
                backgroundTexture = null;
                try {
                    if (stellar) StellarGlassTexture.root().draw(graphics, mouseX, mouseY,
                            getPosition().x, getPosition().y, getSize().width, getSize().height);
                    else RecipeThreadPanelWidget.drawGlass(graphics, getPosition().x, getPosition().y, getSize().width, getSize().height);
                    super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
                } finally {
                    backgroundTexture = normal;
                }
            }
        };
        return new ModularUI(176, 166, holder, player).widget(root);
    }

    /** 山海：原生側欄從 y=8 繪製每格 24px；命中高度必須包含主頁籤及全部子頁籤。 */
    static int stellarSidebarHeight(int canvasHeight, int subTabCount) {
        return Math.max(canvasHeight, 8 + 24 * (1 + subTabCount));
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyGlassTitleBar(TitleBarWidget titleBar, Component title,
                                           boolean stellar, boolean glassMode) {
        // 山海：原生标题列的按钮区与标题区各有白底，必须一并替换。
        IGuiTexture glass = !glassMode ? GuiTextures.TITLE_BAR_BACKGROUND :
                stellar ? StellarGlassTexture.page() :
                (graphics, mouseX, mouseY, x, y, width, height) ->
                        RecipeThreadPanelWidget.drawGlass(graphics, (int) x, (int) y, width, height);
        for (var section : titleBar.getWidgetsByType(WidgetGroup.class)) {
            section.setBackground(glass);
        }
        for (var image : titleBar.getWidgetsByType(ImageWidget.class)) {
            if (image.getImage() instanceof TextTexture text) {
                // updateState 会重建带黑色格式码的标题，去掉该前缀才可使用玻璃文字色。
                text.updateText(title.getString());
                text.setColor(glassMode ? stellar ? StellarGlassTexture.textColor() : GlassPalette.TEXT : 0xFF000000);
            }
        }
        for (var button : titleBar.getWidgetsByType(ButtonWidget.class)) {
            if (button.getBackgroundTexture() instanceof TextTexture text) {
                text.setColor(glassMode ? stellar ? StellarGlassTexture.textColor() : GlassPalette.TEXT : 0xFF000000);
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyGlassSideTabs(VerticalTabsWidget tabs, boolean stellar) {
        int textColor = stellar ? StellarGlassTexture.textColor() : GlassPalette.TEXT;
        int accent = stellar ? StellarGlassTexture.accentColor() : GlassPalette.ACCENT;
        tabs.setTabTexture(stellar ? StellarGlassTexture.page() : GlassPanelTexture.page());
        tabs.setTabHoverTexture(stellar ? StellarGlassTexture.display() : GlassPanelTexture.display());
        tabs.setTabPressedTexture(stellar ? StellarGlassTexture.panel() : GlassPanelTexture.panel());

        tabs.setLeftButtonTexture(new GuiTextureGroup(stellar ? StellarGlassTexture.button() : GlassPanelTexture.button(), Icons.LEFT.copy().setColor(textColor).scale(0.7f)));
        tabs.setLeftButtonHoverTexture(new GuiTextureGroup(stellar ? StellarGlassTexture.button() : GlassPanelTexture.button(),
                Icons.LEFT.copy().setColor(accent).scale(0.7f)));
        tabs.setRightButtonTexture(new GuiTextureGroup(stellar ? StellarGlassTexture.button() : GlassPanelTexture.button(), Icons.RIGHT.copy().setColor(textColor).scale(0.7f)));
        tabs.setRightButtonHoverTexture(new GuiTextureGroup(stellar ? StellarGlassTexture.button() : GlassPanelTexture.button(),
                Icons.RIGHT.copy().setColor(accent).scale(0.7f)));
    }

    @Override
    public Widget createMainPage(FancyMachineUIWidget widget) {
        return new RecipeThreadPanelWidget(0, 0, 244, 132, machine, machine.getThreadBoostSlot(),
                machine::isRecipeThreadsEnabled, machine::setRecipeThreadsEnabled, machine::getRecipeThreadCapacity, true);
    }

    @Override public IGuiTexture getTabIcon() { return machine.getTabIcon(); }
    @Override public Component getTitle() { return Component.translatable("gt_shanhai.recipe_threads.ui.title"); }
}
