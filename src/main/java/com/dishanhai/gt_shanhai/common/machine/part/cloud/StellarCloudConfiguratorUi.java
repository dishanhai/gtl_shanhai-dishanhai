package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import appeng.api.crafting.IPatternDetails;
import com.dishanhai.sh_better_ui.client.glass.GlassColors;
import com.dishanhai.sh_better_ui.compat.ldlib.GlassPanelTexture;
import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
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

    public static WidgetGroup create(BooleanSupplier enabled, BiFunction<Player, Boolean, String> setEnabled,
                                     Supplier<Component> status, IntSupplier count,
                                     Supplier<List<IPatternDetails>> patterns) {
        var root = new WidgetGroup(0, 0, 176, 176);
        root.setBackground(GlassPanelTexture.smokePanel());
        String[] feedback = {PREFIX + "settings_idle"};
        // LDLib 的 Component 建構子未設定 textSupplier；此處沿用安全的 supplier 形式。
        root.addWidget(new LabelWidget(8, 8, () -> Component.translatable(PREFIX + "title").getString())
                .setTextColor(COLORS.TEXT_HEAD).setDropShadow(false));
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
        StellarCloudUiWidgets.text(root, 8, 51, 160, 12, COLORS.TEXT_MUTED,
                () -> Component.translatable(feedback[0]));

        var content = StellarCloudUiWidgets.viewport(8, 66, 160, 102);
        content.setDraggable(false).setYScrollBarWidth(3).setYBarStyle(
                new ColorRectTexture(COLORS.TEXT_OFF), new ColorRectTexture(COLORS.ACCENT).setRadius(1));
        content.setBackground(StellarCloudUiWidgets.surface());
        StellarCloudUiWidgets.text(content, 6, 5, 144, 16, COLORS.TEXT_MUTED,
                () -> Component.translatable(PREFIX + "source_native"));
        StellarCloudUiWidgets.text(content, 6, 25, 144, 40, COLORS.TEXT, status);
        StellarCloudUiWidgets.text(content, 6, 69, 144, 12, COLORS.ACCENT,
                () -> Component.translatable(PREFIX + "entries", count.getAsInt()));
        StellarCloudUiWidgets.text(content, 6, 87, 144, 12, COLORS.TEXT_HEAD,
                () -> Component.translatable(PREFIX + "preview"));
        content.addWidget(new PatternCycleWidget(6, 105, 144, 36, patterns));
        content.addWidget(new WidgetGroup(0, 146, 1, 1));
        root.addWidget(content);
        return root;
    }

    private StellarCloudConfiguratorUi() {}
}
