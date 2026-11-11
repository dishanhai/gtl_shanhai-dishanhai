package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.shop.ShopAppearance;
import com.dishanhai.sh_better_ui.client.api.BackgroundLayer;
import com.dishanhai.sh_better_ui.client.api.ShBetterUiApi;
import com.dishanhai.sh_better_ui.client.glass.GlassBlur;
import com.dishanhai.sh_better_ui.client.glass.GuiRect;
import com.dishanhai.sh_better_ui.client.gui.display.GraphicDisplayLayout.Bounds;
import com.dishanhai.sh_better_ui.client.gui.display.GraphicIcon;
import com.dishanhai.sh_better_ui.client.gui.scaled.*;
import com.dishanhai.sh_better_ui.config.ClientUiSettings;
import com.dishanhai.sh_better_ui.config.TabScrollSettings;
import com.dishanhai.sh_better_ui.config.ThemeOverrides;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** 山海：商店和主题预览共用的装饰层。每个界面拥有独立效果状态。 */
final class ShopVisuals implements AutoCloseable {
    private static final int ACCENT = 0xFF9ACDFF;
    private final BackgroundLayer background = ShBetterUiApi.backgrounds().layer(GraphicIcon.SHOP.texture());
    private final ClickRipple ripple = new ClickRipple();
    private final UiPageSlide slide = new UiPageSlide();
    private final UiRecipePageTransition recipe = new UiRecipePageTransition();
    private final UiCapsuleReveal capsule = new UiCapsuleReveal();
    private boolean pointerEnabled;
    private boolean firstFrame = true;

    static void scoped(ShopAppearance appearance, boolean reduceMotion, Runnable render) {
        ClientUiSettings settings = settings(appearance, reduceMotion);
        if (settings == ThemeEffects.settings()) render.run();
        else ThemeEffects.preview(settings, render);
    }

    static ClientUiSettings settings(ShopAppearance appearance, boolean reduceMotion) {
        ClientUiSettings current = ThemeEffects.settings();
        var settings = reduceMotion && !current.reducedMotion()
                ? current.withDisplay(current.highContrast(), true, current.widgetBackdropBlur()) : current;
        if (!appearance.effects().equals(ThemeOverrides.EMPTY)) {
            settings = settings.withTheme(current.theme().overlayEffects(appearance.effects()));
        }
        return settings;
    }

    static UiPreferences preferences() {
        return UiPreferences.fromSettings(settings(com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings.shopAppearance(),
                com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings.reduceMotion()));
    }

    static TabScrollSettings tabs(ShopAppearance appearance) {
        ClientUiSettings settings = settings(appearance, false);
        return ThemeEffects.profile(settings.theme(), "tabs", settings.tabs());
    }

    static boolean drawMaterial(GuiGraphics g, Bounds area, ShopTheme theme) {
        int x = area.x(), y = area.y(), w = area.width(), h = area.height();
        switch (theme.materialGroup()) {
            case "slate", "liquidGlass", "network" -> theme.rootPanel(g, x, y, w, h, x, y, 0, 0);
            default -> { return false; }
        }
        return true;
    }

    void transition(ShopAppearance appearance, Bounds area, UiPreferences prefs) {
        long now = PageTransition.nowMs();
        firstFrame = true;
        if (enabled(appearance, "recipeTransition")) {
            recipe.start(UiPageSlide.Direction.RIGHT, area, now, prefs);
        } else if (enabled(appearance, "pageSlide")) {
            slide.start(UiPageSlide.Direction.RIGHT, UiPageSlide.Motion.FLAT, area.width(), now, prefs);
        }
    }

    void draw(GuiGraphics g, Font font, Bounds area, ShopAppearance appearance, double mx, double my,
              boolean interactive, UiPreferences prefs) {
        pointerEnabled = interactive && !prefs.reducedMotion() && !prefs.highContrast();
        if (!pointerEnabled) ripple.clear();
        if (prefs.highContrast() || area.width() == 0 || area.height() == 0) {
            background.close();
            return;
        }
        boolean hasBackground = appearance.hasBackground();
        if (!hasBackground) background.close();
        if (!hasBackground
                && !appearance.effects().contains("flowBorder") && !appearance.effects().contains("breathingGlow")
                && !appearance.effects().contains("clickRipple") && !appearance.effects().contains("capsule")) return;

        var pose = new Matrix4f(g.pose().last().pose());
        var start = pose.transformPosition(area.x(), area.y(), 0, new Vector3f());
        var end = pose.transformPosition(area.right(), area.bottom(), 0, new Vector3f());
        int left = (int) Math.floor(start.x), top = (int) Math.floor(start.y);
        int right = (int) Math.ceil(end.x), bottom = (int) Math.ceil(end.y);
        Bounds screenArea = new Bounds(left, top, right - left, bottom - top);
        g.enableScissor(left, top, right, bottom);
        try {
            Runnable art = () -> drawArt(g, font, area, appearance, mx, my, hasBackground, prefs);
            if (!prefs.reducedMotion() && enabled(appearance, "capsule")) {
                if (firstFrame) capsule.startAt(left + screenArea.width() / 2f, top + screenArea.height() / 2f);
                firstFrame = false;
                g.pose().pushPose();
                try {
                    // SH-BUI 的胶囊裁切使用 Screen 坐标，装饰内容仍使用宿主的缩放坐标。
                    g.pose().last().pose().identity();
                    if (capsule.renderInset(g, screenArea, prefs, () -> {
                        g.pose().pushPose();
                        try {
                            g.pose().mulPoseMatrix(pose);
                            art.run();
                        } finally {
                            g.pose().popPose();
                        }
                    })) return;
                } finally {
                    g.pose().popPose();
                }
            } else {
                capsule.finish();
                firstFrame = false;
            }
            art.run();
        } finally {
            g.disableScissor();
        }
    }

    private void drawArt(GuiGraphics g, Font font, Bounds area, ShopAppearance appearance, double mx, double my,
                         boolean hasBackground, UiPreferences prefs) {
        int x = area.x(), y = area.y(), w = area.width(), h = area.height();
        long now = PageTransition.nowMs();
        if (hasBackground) {
            var transition = enabled(appearance, "recipeTransition") ? recipe.sample(now, prefs) : null;
            float shift = 0;
            if (transition != null) shift = transition.incomingX();
            else if (enabled(appearance, "pageSlide")) shift = slide.sample(now, prefs).offsetX();
            UiMouseState mouse = pointerEnabled && shift == 0 && !capsule.active() ? UiMouseState.read() : null;
            if (mouse != null && !mouse.available()) mouse = null;
            g.pose().pushPose();
            try {
                g.pose().translate(shift, 0, 0);
                background.draw(appearance.backgroundId(), g, font, area, mouse, mx, my, now, prefs);
            } finally {
                g.pose().popPose();
            }
            if (transition != null && transition.active() && prefs.useBackdropBlur()) {
                GlassBlur.pageSlideNow(g, new GuiRect(x, y, w, h), transition.blur());
            }
        }
        if (appearance.effects().contains("flowBorder")) {
            GlassEffectRenderer.drawFlowBorder(g, x, y, w, h, now, prefs.reducedMotion(), ACCENT);
        }
        if (appearance.effects().contains("breathingGlow")) {
            GlassEffectRenderer.drawBreathingGlow(g, x + 2, y + 2, w - 4, h - 4, 1f, now,
                    prefs.reducedMotion(), ACCENT);
        }
        if (appearance.effects().contains("clickRipple")) {
            GlassEffectRenderer.drawRipple(g, x, y, w, h, ripple, now, prefs.reducedMotion(), ACCENT);
        }
    }

    private static boolean enabled(ShopAppearance appearance, String group) {
        return appearance.effects().contains(group) && ThemeEffects.local(group).enabled();
    }

    void click(Bounds area, ShopAppearance appearance, double x, double y) {
        if (!pointerEnabled || capsule.active() || !area.contains(x, y)) return;
        long now = PageTransition.nowMs();
        if (appearance.effects().contains("clickRipple")) {
            ripple.trigger(x, y, area.x(), area.y(), area.width(), area.height(), now);
        }
        scoped(appearance, false, () -> background.click(appearance.backgroundId(), area, x, y, now));
    }

    @Override
    public void close() {
        pointerEnabled = false;
        firstFrame = true;
        capsule.finish();
        ripple.clear();
        background.close();
    }
}
