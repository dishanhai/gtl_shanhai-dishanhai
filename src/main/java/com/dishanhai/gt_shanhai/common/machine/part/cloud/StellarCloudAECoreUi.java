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
import com.lowdragmc.lowdraglib.gui.widget.ComponentPanelWidget;
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

    static WidgetGroup create(Function<Player, StellarCloudUiContract.CoreView> state,
                              BiFunction<Player, Boolean, String> changeBinding) {
        var bridge = new StellarCloudUiSync.Core(state, changeBinding);
        var controller = bridge.controller;
        var root = create(() -> {
                    if (!controller.state().synchronizedReady()) return Component.translatable(StellarCloudUiContract.WAITING);
                    var id = controller.state().boundPlayer();
                    return Component.translatable(PREFIX + "uuid", id == null
                            ? Component.translatable(PREFIX + "unbound") : id.toString());
                }, () -> controller.state().networkOnline(), () -> controller.state().hostReady(),
                () -> controller.state().boundPlayer() != null,
                player -> controller.bindControl().reasonKey(), changeBinding, controller);
        root.addWidget(bridge);
        return root;
    }

    static WidgetGroup create(Supplier<Component> identity, BooleanSupplier online, BooleanSupplier hostReady,
                              BooleanSupplier bound, Function<Player, String> disabledReason,
                              BiFunction<Player, Boolean, String> changeBinding) {
        return create(identity, online, hostReady, bound, disabledReason, changeBinding, null);
    }

    private static WidgetGroup create(Supplier<Component> identity, BooleanSupplier online, BooleanSupplier hostReady,
                                      BooleanSupplier bound, Function<Player, String> disabledReason,
                                      BiFunction<Player, Boolean, String> changeBinding,
                                      StellarCloudUiContract.CoreController controller) {
        var root = new WidgetGroup(0, 0, 280, 188);
        root.setBackground(GlassPanelTexture.smokePanel());
        String[] status = {PREFIX + "status.idle"};
        text(root, 12, 10, 256, 12, COLORS.TEXT_HEAD,
                () -> Component.translatable(PREFIX + "title"), controller != null);

        var identityPanel = new WidgetGroup(8, 30, 264, 48);
        identityPanel.setBackground(StellarCloudUiWidgets.surface());
        identityPanel.setHoverTooltips(Component.translatable(PREFIX + "binding_hint"));
        text(identityPanel, 6, 5, 252, 12, COLORS.TEXT_MUTED,
                () -> Component.translatable(PREFIX + "identity"), controller != null);
        text(identityPanel, 6, 21, 252, 23, COLORS.TEXT, identity, controller != null);
        root.addWidget(identityPanel);

        var connectionPanel = new WidgetGroup(8, 84, 264, 36);
        connectionPanel.setBackground(StellarCloudUiWidgets.surface());
        text(connectionPanel, 6, 5, 252, 12, COLORS.TEXT,
                () -> Component.translatable(controller != null && !controller.state().synchronizedReady()
                        ? StellarCloudUiContract.WAITING : PREFIX + (online.getAsBoolean() ? "network.online" : "network.offline")),
                controller != null);
        text(connectionPanel, 6, 20, 252, 12, COLORS.TEXT_MUTED,
                () -> Component.translatable(controller != null && !controller.state().synchronizedReady()
                        ? StellarCloudUiContract.WAITING : PREFIX + (hostReady.getAsBoolean() ? "host.ready" : "host.invalid")),
                controller != null);
        root.addWidget(connectionPanel);

        if (controller != null) {
            StellarCloudUiWidgets.intent(root, 12, 126, 120, 22, PREFIX + "bind",
                    controller::bind, controller::bindControl);
            StellarCloudUiWidgets.intent(root, 148, 126, 120, 22, PREFIX + "unbind",
                    controller::unbind, controller::unbindControl);
        } else {
            // 舊 supplier 入口保留服務端動作；同步入口只建立上述意圖控件。
            Supplier<String> reason = () -> disabledReason.apply(root.getGui() == null ? null : root.getGui().entityPlayer);
            StellarCloudUiWidgets.action(root, 12, 126, 120, 22, PREFIX + "bind",
                    () -> status[0] = changeBinding.apply(root.getGui().entityPlayer, true), reason,
                    reasonKey -> status[0] = reasonKey);
            StellarCloudUiWidgets.action(root, 148, 126, 120, 22, PREFIX + "unbind",
                    () -> status[0] = changeBinding.apply(root.getGui().entityPlayer, false), () -> {
                        String denied = reason.get();
                        return !denied.isEmpty() ? denied : bound.getAsBoolean() ? "" : PREFIX + "status.already_unbound";
                    }, reasonKey -> status[0] = reasonKey);
        }
        var resultPanel = new WidgetGroup(8, 152, 264, 30);
        resultPanel.setBackground(StellarCloudUiWidgets.surface());
        text(resultPanel, 4, 3, 256, 24, COLORS.TEXT,
                () -> Component.translatable(controller == null ? status[0]
                        : !controller.state().synchronizedReady() ? StellarCloudUiContract.WAITING : controller.feedbackKey()),
                controller != null);
        root.addWidget(resultPanel);
        return root;
    }

    private static ComponentPanelWidget text(WidgetGroup root, int x, int y, int width, int height, int color,
                                             Supplier<Component> value, boolean clientState) {
        var label = StellarCloudUiWidgets.text(root, x, y, width, height, color, value);
        if (clientState) label.setClientSideWidget();
        return label;
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
