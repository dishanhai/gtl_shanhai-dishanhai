package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.gui.fancy.VerticalTabsWidget;
import com.gregtechceu.gtceu.api.machine.fancyconfigurator.CombinedDirectionalFancyConfigurator;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import org.gtlcore.gtlcore.api.machine.trait.ICheckPatternMachine;

import static org.gtlcore.gtlcore.api.gui.GuiTextures.BATCH_PROCESSING_DISABLED;
import static org.gtlcore.gtlcore.api.gui.GuiTextures.BATCH_PROCESSING_ENABLED;

/** 摘星控制终端左栏的底板与图标。 */
public final class StarLifterTerminalChrome {
    private static final int PANEL = 0xff1b2037, RULE = 0xff383e61, MUTED = 0xffadb5d6;
    private static final int CYAN = 0xff8de3ec, VIOLET = 0xffc7adff, GOLD = 0xfff5d798;
    private static final IGuiTexture TAB = plate(PANEL, RULE, false);
    private static final IGuiTexture HOVER = plate(0xff323955, MUTED, false);
    private static final IGuiTexture SELECTED = plate(0xff28344e, GOLD, true);
    private static final IGuiTexture CONFIGURATOR = (graphics, mx, my, x, y, width, height) -> {
        if (Widget.isMouseOver(Math.round(x) + width - 24, Math.round(y), 24, 24, mx, my)) {
            HOVER.draw(graphics, mx, my, x, y, width, height);
        } else {
            TAB.draw(graphics, mx, my, x, y, width, height);
        }
    };
    private static final IGuiTexture HOME = glyph("overview", MUTED), HOME_SELECTED = glyph("overview", GOLD);
    private static final IGuiTexture PORTS = glyph("render", MUTED), PORTS_SELECTED = glyph("render", GOLD);
    private static final IGuiTexture POWER = glyph("power", MUTED), POWER_ON = glyph("power", CYAN);
    private static final IGuiTexture SHARED = glyph("shared_io", MUTED), DISTINCT = glyph("distinct_io", CYAN);
    private static final IGuiTexture UNLOCKED = glyph("unlocked", MUTED), LOCKED = glyph("locked", GOLD);
    private static final IGuiTexture BATCH = glyph("batch", MUTED), BATCH_ON = glyph("batch", VIOLET);
    private static final IGuiTexture CHECK = glyph("diagnostic", CYAN), CHECK_PENDING = glyph("diagnostic", GOLD);

    private StarLifterTerminalChrome() {}

    static void apply(VerticalTabsWidget tabs, ConfiguratorPanel configurators) {
        tabs.setTabTexture(TAB);
        tabs.setTabHoverTexture(HOVER);
        tabs.setTabPressedTexture(SELECTED);
        configurators.setTexture(CONFIGURATOR);
        for (var tab : configurators.getTabs()) tab.setBackground(CONFIGURATOR);
    }

    public static boolean isTerminal(Widget widget) {
        return widget.getGui() != null && widget.getGui().holder instanceof StarLifterControllerMachine;
    }

    public static IGuiTexture pageIcon(IFancyUIProvider page, boolean selected, IGuiTexture original) {
        if (page instanceof StarLifterControllerMachine) return selected ? HOME_SELECTED : HOME;
        if (page instanceof CombinedDirectionalFancyConfigurator) return selected ? PORTS_SELECTED : PORTS;
        return original;
    }

    public static IGuiTexture configuratorIcon(IGuiTexture original) {
        if (original == BATCH_PROCESSING_ENABLED) return BATCH_ON;
        if (original == BATCH_PROCESSING_DISABLED) return BATCH;
        if (original instanceof ResourceTexture texture) {
            // GT and GTLCore send toggle state through the original sprite frame.
            var id = texture.imageLocation;
            boolean lowerFrame = texture.offsetY > 0;
            if (id.equals(GuiTextures.BUTTON_POWER.imageLocation)) return lowerFrame ? POWER_ON : POWER;
            if (id.equals(GuiTextures.BUTTON_DISTINCT_BUSES.imageLocation)) return lowerFrame ? SHARED : DISTINCT;
            if (id.equals(GuiTextures.BUTTON_PUBLIC_PRIVATE.imageLocation)) return lowerFrame ? LOCKED : UNLOCKED;
            if (id.equals(ICheckPatternMachine.STRUCTURE_CHECK.imageLocation)) return lowerFrame ? CHECK : CHECK_PENDING;
        }
        return original;
    }

    private static IGuiTexture plate(int fill, int border, boolean selected) {
        return (graphics, mx, my, x, y, width, height) -> {
            int left = Math.round(x) + 1, top = Math.round(y) + 1;
            int right = left + width - 2, bottom = top + height - 2;
            graphics.fill(left, top, right, bottom, fill);
            graphics.renderOutline(left, top, right - left, bottom - top, border);
            if (selected) graphics.fill(left, top + 3, left + 2, bottom - 3, border);
        };
    }

    private static IGuiTexture glyph(String name, int color) {
        return (graphics, mx, my, x, y, width, height) -> {
            int side = Math.min(width, height) - 2;
            StarLifterTerminalGlyphs.draw(graphics, name, Math.round(x) + (width - side) / 2,
                    Math.round(y) + (height - side) / 2, side, color);
        };
    }
}
