package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings;
import com.dishanhai.sh_better_ui.client.gui.scaled.ThemeEffects;
import com.dishanhai.sh_better_ui.client.gui.scaled.UiPreferences;
import com.dishanhai.sh_better_ui.client.gui.scaled.UiScreenReturn;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 山海：保留实际可见帧，并在父页重建后查找可用的原操作控件归还焦点。 */
final class ShopConfirmationReturn {
    private final Screen parent;
    private final UiScreenReturn handoff;
    private final GuiEventListener previousFocus;
    private final int focusIndex;
    private final Component focusMessage;

    ShopConfirmationReturn(Screen owner, Screen parent) {
        this.parent = parent;
        handoff = new UiScreenReturn(owner);
        previousFocus = parent == null ? null : parent.getFocused();
        focusIndex = parent == null ? -1 : parent.children().indexOf(previousFocus);
        focusMessage = previousFocus instanceof AbstractWidget widget ? widget.getMessage().copy() : null;
        ShopVisuals.scoped(ClientShopUiSettings.shopAppearance(), ClientShopUiSettings.reduceMotion(), () -> {
            if (ThemeEffects.local("screenReturn").enabled()) {
                handoff.beginHandoff(parent, UiPreferences.configured());
            }
        });
    }

    void render(GuiGraphics graphics) {
        handoff.renderHandoff(graphics, UiPreferences.configured());
    }

    void restore() {
        Minecraft client = Minecraft.getInstance();
        client.setScreen(parent);
        if (parent == null || client.screen != parent || previousFocus == null) return;
        GuiEventListener target = null;
        if (parent.children().contains(previousFocus) && available(previousFocus)) target = previousFocus;
        if (target == null && focusIndex >= 0 && focusIndex < parent.children().size()) {
            GuiEventListener candidate = parent.children().get(focusIndex);
            if (matches(candidate) && available(candidate)) target = candidate;
        }
        if (target == null) {
            target = parent.children().stream().filter(this::matches).filter(ShopConfirmationReturn::available)
                    .findFirst().orElse(null);
        }
        if (target != null) parent.setFocused(target);
    }

    private boolean matches(GuiEventListener candidate) {
        return candidate.getClass() == previousFocus.getClass()
                && (focusMessage == null || candidate instanceof AbstractWidget widget
                && focusMessage.equals(widget.getMessage()));
    }

    private static boolean available(GuiEventListener candidate) {
        return !(candidate instanceof AbstractWidget widget) || widget.active && widget.visible;
    }

    void close() {
        handoff.clearHandoff();
    }
}
