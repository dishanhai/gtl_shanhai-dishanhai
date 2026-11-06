package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import appeng.api.crafting.IPatternDetails;
import com.dishanhai.sh_better_ui.client.glass.GlassColors;
import com.dishanhai.sh_better_ui.compat.ldlib.GlassPanelTexture;
import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ComponentPanelWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SwitchWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.gtlcore.gtlcore.client.gui.widget.PatternCycleWidget;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.BiFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** 山海：雲端設定呈現；來源、啟停與樣板狀態仍由星律提供。 */
public final class StellarCloudConfiguratorUi {
    private static final String PREFIX = "gui.gt_shanhai.stellar_cloud.";
    private static final GlassColors COLORS = GlassColors.SMOKE;

    public static WidgetGroup create(java.util.function.Function<Player, StellarCloudUiContract.ConfiguratorView> state,
                                     BiFunction<Player, Boolean, String> setEnabled,
                                     Supplier<List<IPatternDetails>> patterns) {
        var bridge = new StellarCloudUiSync.Configurator(state, setEnabled);
        var controller = bridge.controller;
        var root = create(() -> controller.state().enabled(), setEnabled,
                () -> {
                    var view = controller.state();
                    return Component.translatable(view.synchronizedReady() ? view.statusKey()
                            : StellarCloudUiContract.WAITING, view.publishedCount());
                }, () -> controller.state().publishedCount(), patterns,
                () -> Component.translatable(controller.state().sourceKey()), controller);
        root.addWidget(bridge);
        return root;
    }

    public static WidgetGroup create(BooleanSupplier enabled, BiFunction<Player, Boolean, String> setEnabled,
                                     Supplier<Component> status, IntSupplier count,
                                     Supplier<List<IPatternDetails>> patterns) {
        return create(enabled, setEnabled, status, count, patterns,
                () -> Component.translatable(PREFIX + "source_native"), null);
    }

    private static WidgetGroup create(BooleanSupplier enabled, BiFunction<Player, Boolean, String> setEnabled,
                                      Supplier<Component> status, IntSupplier count,
                                      Supplier<List<IPatternDetails>> patterns, Supplier<Component> source,
                                      StellarCloudUiContract.ConfiguratorController controller) {
        var root = new WidgetGroup(0, 0, 176, 176);
        root.setBackground(GlassPanelTexture.smokePanel());
        String[] feedback = {PREFIX + "settings_idle"};
        // LDLib 的 Component 建構子未設定 textSupplier；此處沿用安全的 supplier 形式。
        root.addWidget(new LabelWidget(8, 8, () -> Component.translatable(PREFIX + "title").getString())
                .setTextColor(COLORS.TEXT_HEAD).setDropShadow(false));
        if (controller != null) {
            // 單一意圖入口；選取標記只跟隨伺服端快照，不預先翻轉開關。
            StellarCloudUiWidgets.intent(root, 16, 26, 152, 22, PREFIX + "enabled",
                    () -> controller.setEnabled(!controller.state().enabled()), controller::toggleControl);
            root.addWidget(new ImageWidget(8, 26, 3, 22, (graphics, mx, my, x, y, w, h) ->
                    graphics.fill((int) x, (int) y, (int) (x + w), (int) (y + h),
                            controller.state().synchronizedReady() && controller.state().enabled()
                                    ? COLORS.ACCENT : COLORS.TEXT_OFF)));
        } else {
            // 保留舊 supplier 入口的服務端回呼相容性；同步入口不會建立此開關。
            var toggle = new SwitchWidget(8, 26, 160, 22, (click, value) -> {
                if (!click.isRemote && click.button == 0) {
                    feedback[0] = setEnabled.apply(root.getGui().entityPlayer, value);
                }
            }) {
                @Override @OnlyIn(Dist.CLIENT)
                public boolean mouseClicked(double x, double y, int button) {
                    if (!isMouseOverElement(x, y)) return false;
                    return button != 0 || super.mouseClicked(x, y, button);
                }
            }.setSupplier(enabled::getAsBoolean);
            toggle.setTexture(new GuiTextureGroup(GlassPanelTexture.smokeButtonOverlay(),
                            new TextTexture(PREFIX + "enabled", COLORS.TEXT).setWidth(148).setDropShadow(false)),
                    new GuiTextureGroup(GlassPanelTexture.smokeButtonOverlay(), new ColorBorderTexture(1, COLORS.ACCENT),
                            new TextTexture(PREFIX + "enabled", COLORS.TEXT).setWidth(148).setDropShadow(false)));
            toggle.setHoverBorderTexture(1, COLORS.ACCENT);
            toggle.setHoverTooltips(Component.translatable(PREFIX + "toggle_hint"));
            root.addWidget(toggle);
        }
        text(root, 8, 51, 160, 12, COLORS.TEXT_MUTED, () -> {
            if (controller == null) return Component.translatable(feedback[0]);
            if (!controller.state().synchronizedReady()) return Component.translatable(StellarCloudUiContract.WAITING);
            String key = controller.lastResult() == null && !controller.toggleControl().enabled()
                    ? controller.toggleControl().reasonKey() : controller.feedbackKey();
            return Component.translatable(key);
        }, controller != null);

        var content = StellarCloudUiWidgets.viewport(8, 66, 160, 102);
        content.setDraggable(false).setYScrollBarWidth(3).setYBarStyle(
                new ColorRectTexture(COLORS.TEXT_OFF), new ColorRectTexture(COLORS.ACCENT).setRadius(1));
        content.setBackground(StellarCloudUiWidgets.surface());
        content.setHoverTooltips(Component.translatable(PREFIX + "toggle_hint"));
        text(content, 6, 5, 144, 16, COLORS.TEXT_MUTED, source, controller != null);
        text(content, 6, 25, 144, 28, COLORS.TEXT, status, controller != null);
        text(content, 6, 59, 144, 12, COLORS.ACCENT,
                () -> Component.translatable(controller != null && !controller.state().synchronizedReady()
                        ? StellarCloudUiContract.WAITING : PREFIX + "entries", count.getAsInt()), controller != null);
        text(content, 6, 81, 144, 12, COLORS.TEXT_HEAD,
                () -> Component.translatable(PREFIX + "preview"), controller != null);
        content.addWidget(new PatternCycleWidget(6, 99, 144, 36, patterns));
        content.addWidget(new WidgetGroup(0, 140, 1, 1));
        root.addWidget(content);
        return root;
    }

    private static ComponentPanelWidget text(WidgetGroup root, int x, int y, int width, int height, int color,
                                             Supplier<Component> value, boolean clientState) {
        var label = StellarCloudUiWidgets.text(root, x, y, width, height, color, value);
        // 快照與回執已由 bridge 同步；不能再由伺服端的空 controller 覆蓋文字。
        if (clientState) label.setClientSideWidget();
        return label;
    }

    private StellarCloudConfiguratorUi() {}
}
