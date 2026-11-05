package com.dishanhai.gt_shanhai.common.machine.part.ui;

import com.dishanhai.gt_shanhai.config.ShanhaiUiClientConfig;
import com.dishanhai.gt_shanhai.mixin.accessor.StellarSwitchWidgetAccessor;
import com.dishanhai.sh_better_ui.client.glass.GlassColors;
import com.dishanhai.sh_better_ui.client.glass.GlassSurface;
import com.dishanhai.sh_better_ui.compat.ldlib.GlassPanelTexture;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfigurator;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfiguratorButton;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AETextInputButtonWidget;
import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.SwitchWidget;
import com.lowdragmc.lowdraglib.LDLib;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** 山海：星律深藍／中性煙灰玻璃切換；只替換繪製，保留業務控件。 */
public final class StellarGlassTexture {
    private StellarGlassTexture() {}

    private static final IGuiTexture ROOT_GLASS = GlassPanelTexture.smokePanel();
    private static final IGuiTexture ROOT = themed(ROOT_GLASS,
            (g, mx, my, x, y, w, h) -> RecipeThreadPanelWidget.drawGlass(g, (int) x, (int) y, w, h));
    private static final IGuiTexture PANEL = themed(
            plate(), GlassPanelTexture.page());
    private static final IGuiTexture FLOATING_PANEL = themed(ROOT_GLASS, GlassPanelTexture.page());
    private static final IGuiTexture DISPLAY = themed(
            plate(), GlassPanelTexture.display());
    private static final IGuiTexture SLOT = themed(
            plate(), GlassPanelTexture.display());
    private static final IGuiTexture PAGE = themed(
            plate(), GlassPanelTexture.page());

    public static boolean neutral() { return ShanhaiUiClientConfig.stellarGlass(); }

    public static void toggle() { ShanhaiUiClientConfig.setStellarGlass(!neutral()); }

    /** 原生 ConfiguratorPanel 負責 24×24 尺寸、左下排列與點擊區域。 */
    public static IFancyConfiguratorButton appearanceConfigurator() {
        IGuiTexture icon = adapt(new TextTexture(() -> Component.translatable(neutral()
                ? "gui.gt_shanhai.stellar.ui_mode.neutral"
                : "gui.gt_shanhai.stellar.ui_mode.blue").getString()));
        return new IFancyConfiguratorButton() {
            @Override public IGuiTexture getIcon() { return icon; }
            @Override public List<Component> getTooltips() {
                return List.of(Component.translatable("gui.gt_shanhai.stellar.ui_mode.tooltip"));
            }
            @Override public void onClick(com.lowdragmc.lowdraglib.gui.util.ClickData click) {
                if (click.isRemote) StellarGlassTexture.toggle();
            }
        };
    }

    private static GlassColors colors() { return neutral() ? GlassColors.SMOKE : GlassColors.DARK; }

    public static IGuiTexture root() { return ROOT; }

    public static IGuiTexture panel() { return PANEL; }

    /** 主底板外的浮動面板需要自己的煙灰遮罩與模糊，不能只疊內層薄灰。 */
    public static IGuiTexture floatingPanel() { return FLOATING_PANEL; }

    public static IGuiTexture display() { return DISPLAY; }

    public static IGuiTexture slot() { return SLOT; }

    public static IGuiTexture page() { return PAGE; }

    public static int textColor() { return colors().TEXT; }

    public static int accentColor() { return colors().ACCENT; }

    public static IGuiTexture accentIcon(IGuiTexture icon) {
        return (g, mx, my, x, y, w, h) -> icon.setColor(accentColor()).draw(g, mx, my, x, y, w, h);
    }

    private static IGuiTexture plate() {
        return (g, mx, my, x, y, w, h) ->
                GlassSurface.smokeControl(g, (int) x, (int) y, w, h, 0f);
    }

    private static IGuiTexture themed(IGuiTexture smoke, IGuiTexture blue) {
        return (g, mx, my, x, y, w, h) -> {
            if (neutral()) smoke.draw(g, mx, my, x, y, w, h);
            else blue.draw(g, mx, my, x, y, w, h);
        };
    }

    /** 根板已取樣背景；內層控件復用庫內 hover 彈簧，避免逐格重複模糊。 */
    public static IGuiTexture button() {
        return themed(GlassPanelTexture.smokeButtonOverlay(), GlassPanelTexture.button());
    }

    /** 遞迴替換原生底圖，保留 REFUND_OVERLAY 等前景與文字的更新。 */
    public static IGuiTexture adapt(IGuiTexture texture) {
        if (texture == GuiTextures.BUTTON || texture == GuiTextures.VANILLA_BUTTON) {
            return button();
        }
        if (texture == GuiTextures.BACKGROUND || texture == GuiTextures.BACKGROUND_INVERSE) return panel();
        if (texture == GuiTextures.DISPLAY) return display();
        if (texture == GuiTextures.SLOT) return slot();
        if (texture instanceof GuiTextureGroup group) {
            IGuiTexture[] children = null;
            for (int i = 0; i < group.textures.length; i++) {
                IGuiTexture replacement = adapt(group.textures[i]);
                if (replacement != group.textures[i]) {
                    if (children == null) children = group.textures.clone();
                    children[i] = replacement;
                }
            }
            return children != null ? new GuiTextureGroup(children) : texture;
        }
        if (texture instanceof TextTexture text) return new SmokeText(text);
        return texture;
    }

    private record SmokeText(TextTexture text) implements IGuiTexture {
        @Override
        public void updateTick() { text.updateTick(); }

        @Override
        public void draw(GuiGraphics g, int mx, int my, float x, float y, int w, int h) {
            int color = text.color;
            boolean shadow = text.dropShadow;
            String original = text.text;
            String smoke = StellarGlassText.forSmokeGlass(original);
            try {
                text.setColor(textColor()).setDropShadow(false);
                if (smoke != original) text.updateText(smoke);
                text.draw(g, mx, my, x, y, w, h);
            } finally {
                text.setColor(color).setDropShadow(shadow);
                if (smoke != original) text.updateText(original);
            }
        }
    }

    /** SwitchWidget 自繪 base/pressed，不能只替換 Widget.backgroundTexture。 */
    public static void styleSwitch(SwitchWidget widget) {
        var textures = (StellarSwitchWidgetAccessor) widget;
        widget.setTexture(switchFace(textures.gtShanhai$getBaseTexture(), false),
                switchFace(textures.gtShanhai$getPressedTexture(), true));
    }

    private static IGuiTexture switchFace(IGuiTexture nativeTexture, boolean pressed) {
        if (nativeTexture instanceof GuiTextureGroup group && group.textures.length == 1) {
            IGuiTexture child = group.textures[0];
            if (child instanceof ColorBorderTexture) {
                IGuiTexture face = button();
                return (g, mx, my, x, y, w, h) -> {
                    face.draw(g, mx, my, x, y, w, h);
                    g.fill((int) x + 3, (int) y + h - 4, (int) x + w - 3, (int) y + h - 2,
                            pressed ? colors().OK : colors().TEXT_OFF);
                };
            }
        }
        return adapt(nativeTexture);
    }

    public static AETextInputButtonWidget renameButton(int x, int y, int width, int height) {
        return new AETextInputButtonWidget(x, y, width, height) {
            @Override
            public void initWidget() {
                super.initWidget();
                if (LDLib.isRemote()) {
                    for (var toggle : getWidgetsByType(SwitchWidget.class)) styleSwitch(toggle);
                }
            }
        };
    }

    /** Tab.drawInBackground 直接呼叫 getIcon；委派原配置器的所有同步和點擊。 */
    public static IFancyConfigurator configurator(IFancyConfigurator original) {
        if (!(original instanceof IFancyConfiguratorButton action)) return original;
        IGuiTexture icon = adapt(original.getIcon());
        return new IFancyConfiguratorButton() {
            @Override public IGuiTexture getIcon() { return icon; }
            @Override public void onClick(com.lowdragmc.lowdraglib.gui.util.ClickData click) { action.onClick(click); }
            @Override public List<Component> getTooltips() { return original.getTooltips(); }
            @Override public void writeInitialData(FriendlyByteBuf buf) { original.writeInitialData(buf); }
            @Override public void readInitialData(FriendlyByteBuf buf) { original.readInitialData(buf); }
            @Override public void readUpdateInfo(int id, FriendlyByteBuf buf) { original.readUpdateInfo(id, buf); }
            @Override public void detectAndSendChange(BiConsumer<Integer, Consumer<FriendlyByteBuf>> sender) {
                original.detectAndSendChange(sender);
            }
        };
    }
}
