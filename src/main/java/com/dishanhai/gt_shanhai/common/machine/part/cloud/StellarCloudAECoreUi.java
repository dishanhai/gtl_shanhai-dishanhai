package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import com.dishanhai.sh_better_ui.client.glass.GlassColors;
import com.dishanhai.sh_better_ui.compat.ldlib.GlassPanelTexture;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.machine.feature.IFancyUIMachine;
import com.lowdragmc.lowdraglib.gui.editor.Icons;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** 山海：AE 核心身份與連線呈現；綁定權限及校驗仍由原機器負責。 */
final class StellarCloudAECoreUi {
    private static final String PREFIX = "gt_shanhai.cloud.ae_core.";
    private static final GlassColors COLORS = GlassColors.SMOKE;

    static ModularUI createUI(IFancyUIMachine machine, Player player) {
        return new ModularUI(280, 188, machine, player).widget(new SmokeFrame(machine));
    }

    static WidgetGroup create(Supplier<Component> identity, BooleanSupplier online, BooleanSupplier hostReady,
                              BooleanSupplier bound, Function<Player, String> disabledReason,
                              BiFunction<Player, Boolean, String> changeBinding) {
        var root = new WidgetGroup(0, 0, 280, 188);
        root.setBackground(GlassPanelTexture.smokePanel());
        String[] status = {PREFIX + "status.idle"};
        StellarCloudUiWidgets.text(root, 12, 10, 256, 12, COLORS.TEXT_HEAD,
                () -> Component.translatable(PREFIX + "title"));

        var identityPanel = new WidgetGroup(8, 30, 264, 48);
        identityPanel.setBackground(StellarCloudUiWidgets.surface());
        identityPanel.setHoverTooltips(Component.translatable(PREFIX + "binding_hint"));
        StellarCloudUiWidgets.text(identityPanel, 6, 5, 252, 12, COLORS.TEXT_MUTED,
                () -> Component.translatable(PREFIX + "identity"));
        StellarCloudUiWidgets.text(identityPanel, 6, 21, 252, 23, COLORS.TEXT, identity);
        root.addWidget(identityPanel);

        var connectionPanel = new WidgetGroup(8, 84, 264, 36);
        connectionPanel.setBackground(StellarCloudUiWidgets.surface());
        StellarCloudUiWidgets.text(connectionPanel, 6, 5, 252, 12, COLORS.TEXT,
                () -> Component.translatable(PREFIX + (online.getAsBoolean() ? "network.online" : "network.offline")));
        StellarCloudUiWidgets.text(connectionPanel, 6, 20, 252, 12, COLORS.TEXT_MUTED,
                () -> Component.translatable(PREFIX + (hostReady.getAsBoolean() ? "host.ready" : "host.invalid")));
        root.addWidget(connectionPanel);

        Supplier<String> reason = () -> disabledReason.apply(root.getGui() == null ? null : root.getGui().entityPlayer);
        StellarCloudUiWidgets.action(root, 12, 126, 120, 22, PREFIX + "bind",
                () -> status[0] = changeBinding.apply(root.getGui().entityPlayer, true), reason,
                reasonKey -> status[0] = reasonKey);
        StellarCloudUiWidgets.action(root, 148, 126, 120, 22, PREFIX + "unbind",
                () -> status[0] = changeBinding.apply(root.getGui().entityPlayer, false), () -> {
                    String denied = reason.get();
                    return !denied.isEmpty() ? denied : bound.getAsBoolean() ? "" : PREFIX + "status.already_unbound";
                }, reasonKey -> status[0] = reasonKey);
        StellarCloudUiWidgets.text(root, 12, 154, 256, 26, COLORS.TEXT,
                () -> Component.translatable(status[0]));
        return root;
    }

    private StellarCloudAECoreUi() {}

    /** 只替換 Fancy 的外觀，頁面導航、配置器及其同步繼續委派原生實作。 */
    private static final class SmokeFrame extends FancyMachineUIWidget {
        private SmokeFrame(IFancyUIMachine machine) {
            super(machine, 280, 188);
            setBackground(GlassPanelTexture.smokePanel());
        }

        @Override
        protected void setupFancyUI(IFancyUIProvider provider, boolean hasInventory) {
            super.setupFancyUI(provider, hasInventory);
            if (isRemote()) styleChrome();
        }

        @OnlyIn(Dist.CLIENT)
        private void styleChrome() {
            IGuiTexture panel = GlassPanelTexture.smokePanel();
            for (var section : getTitleBar().getWidgetsByType(WidgetGroup.class)) section.setBackground(panel);
            for (var image : getTitleBar().getWidgetsByType(ImageWidget.class)) {
                if (image.getImage() instanceof TextTexture text) {
                    text.updateText(getCurrentHomePage().getTitle().getString());
                    text.setColor(COLORS.TEXT_HEAD).setDropShadow(false);
                }
            }
            for (var button : getTitleBar().getWidgetsByType(ButtonWidget.class)) {
                if (button.getBackgroundTexture() instanceof TextTexture text) {
                    text.setColor(COLORS.TEXT).setDropShadow(false);
                    button.setButtonTexture(GlassPanelTexture.smokeButtonOverlay(), text);
                    button.setHoverBorderTexture(1, COLORS.ACCENT);
                }
            }
            var tabs = getSideTabsWidget();
            tabs.setTabTexture(panel);
            tabs.setTabHoverTexture(GlassPanelTexture.smokeButtonOverlay());
            tabs.setTabPressedTexture(StellarCloudUiWidgets.surface());
            tabs.setLeftButtonTexture(new GuiTextureGroup(GlassPanelTexture.smokeButtonOverlay(),
                    Icons.LEFT.copy().setColor(COLORS.TEXT).scale(.7f)));
            tabs.setLeftButtonHoverTexture(new GuiTextureGroup(GlassPanelTexture.smokeButtonOverlay(),
                    Icons.LEFT.copy().setColor(COLORS.ACCENT).scale(.7f)));
            tabs.setRightButtonTexture(new GuiTextureGroup(GlassPanelTexture.smokeButtonOverlay(),
                    Icons.RIGHT.copy().setColor(COLORS.TEXT).scale(.7f)));
            tabs.setRightButtonHoverTexture(new GuiTextureGroup(GlassPanelTexture.smokeButtonOverlay(),
                    Icons.RIGHT.copy().setColor(COLORS.ACCENT).scale(.7f)));
            getConfiguratorPanel().setTexture(panel);
            for (var tab : getConfiguratorPanel().getTabs()) {
                tab.setBackground(panel);
                for (var view : tab.getWidgetsByType(WidgetGroup.class)) view.setBackground(panel);
            }
        }
    }
}
