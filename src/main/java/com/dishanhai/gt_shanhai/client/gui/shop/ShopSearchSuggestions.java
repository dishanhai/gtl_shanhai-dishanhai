package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.shop.ShopCatalogSearchIndex.Suggestion;
import com.dishanhai.sh_better_ui.client.gui.display.GraphicDisplayLayout.Bounds;
import com.dishanhai.sh_better_ui.client.gui.scaled.DarkModeTheme;
import com.dishanhai.sh_better_ui.client.gui.scaled.GuiMotion;
import com.dishanhai.sh_better_ui.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.sh_better_ui.client.gui.scaled.Spring;
import com.dishanhai.sh_better_ui.client.gui.scaled.ThemeEffects;
import com.dishanhai.sh_better_ui.client.gui.scaled.UiPreferences;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.lwjgl.glfw.GLFW;

/** 山海：商店与多选器共用玻璃搜索框、预测及不会向下穿透的联想清单。 */
final class ShopSearchSuggestions {
    private static final int PAGE_FOOTER_HEIGHT = 16;
    private record Row(int index, int y, int height, List<String> lines) {}
    private final Font font;
    private final EditBox box;
    private final int x, y, width, height, bottom;
    private final BooleanSupplier allowed;
    private final Consumer<EditBox> focus;
    private final BiFunction<String, Integer, List<String>> wrap;
    private final ShopDialogUi ui = new ShopDialogUi(ShopTheme.CLASSIC.frameDark, ShopTheme.CLASSIC.frame,
            ShopTheme.CLASSIC.buttonBg, ShopTheme.CLASSIC.buttonHover);
    private final Spring motion = new Spring(Spring.PANEL);
    private final ShopSearchSuggestionPages pages = new ShopSearchSuggestionPages();
    private List<Suggestion> suggestions = List.of();
    private List<Row> rows = List.of();
    private int scroll, contentHeight;
    private boolean dismissed;

    ShopSearchSuggestions(Font font, EditBox box, int x, int y, int width, int height, int bottom,
                          BooleanSupplier allowed, Consumer<EditBox> focus,
                          BiFunction<String, Integer, List<String>> wrap) {
        this.font = font;
        this.box = box;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.bottom = bottom;
        this.allowed = allowed;
        this.focus = focus;
        this.wrap = wrap;
    }

    void update(List<Suggestion> values) {
        pages.update(values);
        rebuildPage();
    }

    private void rebuildPage() {
        suggestions = pages.current();
        scroll = contentHeight = 0;
        List<Row> next = new ArrayList<>();
        for (int i = 0; i < suggestions.size(); i++) {
            Suggestion value = suggestions.get(i);
            List<String> lines = new ArrayList<>(wrap.apply(value.displayName(), Math.max(1, width - 18)));
            if (value.completion().contains(":") && !value.displayName().contains(value.completion()))
                lines.addAll(wrap.apply(value.completion(), Math.max(1, width - 18)));
            int h = Math.max(16, lines.size() * 10 + 6);
            next.add(new Row(i, contentHeight, h, lines));
            contentHeight += h;
        }
        rows = List.copyOf(next);
    }

    private void changePage(int delta) {
        if (pages.movePage(delta)) rebuildPage();
    }

    private boolean canChangePage(int delta) {
        int next = pages.pageIndex() + delta;
        return next >= 0 && next < pages.pageCount();
    }

    void reopen() { dismissed = false; }

    boolean open() {
        return box.isFocused() && allowed.getAsBoolean() && !dismissed
                && !suggestions.isEmpty() && listHeight() > 0;
    }

    boolean dismiss() {
        boolean wasOpen = open();
        dismissed = true;
        box.setSuggestion(null);
        return wasOpen;
    }

    private int listY() {
        return y + height + 2 + (UiPreferences.configured().reducedMotion() ? 0
                : Math.round(3f * (1f - GuiMotion.clamp01(motion.value()))));
    }

    private int footerHeight() { return pages.pageCount() > 1 ? PAGE_FOOTER_HEIGHT : 0; }

    private String pageLabel() {
        return Component.translatable("gt_shanhai.shop.search.page", pages.pageIndex() + 1, pages.pageCount()).getString();
    }

    private Bounds pageButton(int footerY, int labelWidth, int delta) {
        int buttonWidth = Math.min(18, (width - labelWidth) / 2 - 5);
        if (buttonWidth < 10) buttonWidth = 0;
        int buttonX = delta < 0 ? x + 3 : x + width - 3 - buttonWidth;
        return new Bounds(buttonX, footerY + 2, buttonWidth, PAGE_FOOTER_HEIGHT - 4);
    }

    private int listHeight() {
        return Math.max(0, Math.min(contentHeight, Math.min(140, bottom - listY() - 4) - footerHeight()));
    }
    boolean inFrame(double mx, double my) { return GuiRenderUtil.isHovering(mx, my, x, y, width, height); }
    boolean contains(double mx, double my) {
        return open() && GuiRenderUtil.isHovering(mx, my, x, listY(), width, listHeight() + footerHeight() + 4);
    }

    private int rowAt(double mx, double my) {
        if (!contains(mx, my)) return -1;
        double ry = my - listY() - 2 + scroll;
        if (my < listY() + 2 || my >= listY() + 2 + listHeight()) return -1;
        for (Row row : rows) if (ry >= row.y() && ry < row.y() + row.height()) return row.index();
        return -1;
    }

    boolean acceptSelected() {
        if (!open()) return false;
        String completion = suggestions.get(pages.selectedIndex()).completion();
        box.setValue(completion);
        box.moveCursorToEnd();
        box.setFocused(true);
        focus.accept(box);
        dismiss();
        ShopUiSounds.click();
        return true;
    }

    boolean navigationKey(int key) {
        if (!open() || Screen.hasControlDown() || Screen.hasAltDown()) return false;
        if ((key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT)
                && !Screen.hasShiftDown() && pages.pageCount() > 1) {
            changePage(key == GLFW.GLFW_KEY_RIGHT ? 1 : -1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP) {
            pages.moveSelection(key == GLFW.GLFW_KEY_DOWN ? 1 : -1);
            Row row = rows.get(pages.selectedIndex());
            scroll = ShopDialogRows.pixelScrollToShow(row.y(), row.height(), scroll, listHeight(), contentHeight);
            return true;
        }
        return key == GLFW.GLFW_KEY_TAB && !Screen.hasShiftDown() && acceptSelected();
    }

    boolean mouseClicked(double mx, double my, int button) {
        if (contains(mx, my)) {
            int footerY = listY() + 2 + listHeight();
            if (footerHeight() > 0 && my >= footerY) {
                if (button == 0) {
                    int labelWidth = font.width(pageLabel());
                    for (int delta = -1; delta <= 1; delta += 2) {
                        if (canChangePage(delta) && pageButton(footerY, labelWidth, delta).contains(mx, my)) {
                            changePage(delta);
                            break;
                        }
                    }
                }
                return true;
            }
            int index = rowAt(mx, my);
            if (button == 0 && index >= 0) { pages.select(index); acceptSelected(); }
            return true;
        }
        if (inFrame(mx, my)) {
            if (button == 0 || button == 1) {
                reopen();
                box.setFocused(true);
                focus.accept(box);
                if (button == 1) box.setValue("");
                else box.mouseClicked(mx, my, button);
            }
            return true;
        }
        dismiss();
        if (box.isFocused()) { box.setFocused(false); focus.accept(null); }
        return false;
    }

    boolean mouseScrolled(double mx, double my, double delta) {
        if (!contains(mx, my)) return false;
        scroll = ShopDialogRows.clampPixelScroll(scroll - (int) Math.signum(delta) * 12, contentHeight, listHeight());
        return true;
    }

    void drawFrame(GuiGraphics g, int mx, int my) {
        ShopTheme theme = ShopTheme.current();
        ui.frame(mx, my);
        float hv = ui.hoverAt(x, y, width, height, box.isFocused() || inFrame(mx, my));
        theme.controlBackdrop(g, x, y, width, height);
        theme.box(g, x, y, width, height, GuiMotion.lerpColor(theme.frameDark, theme.frame, hv),
                GuiMotion.lerpColor(theme.buttonBg, theme.buttonHover, hv));
        String suffix = null;
        String query = box.getValue();
        if (open() && box.getCursorPosition() == query.length() && box.getHighlighted().isEmpty()) {
            String completion = suggestions.get(pages.selectedIndex()).completion();
            if (completion.length() > query.length() && completion.regionMatches(true, 0, query, 0, query.length()))
                suffix = completion.substring(query.length());
        }
        box.setSuggestion(suffix);
    }

    /** 调用方在模糊后、自己的虚拟坐标顶层绘制；玻璃先于清单全部文字。 */
    void drawPopup(GuiGraphics g, int mx, int my, float scale, int offsetX, int offsetY) {
        long now = System.currentTimeMillis();
        boolean show = open();
        motion.setTarget(show ? 1f : 0f, now);
        motion.update(now);
        if (!show) return;
        ShopTheme theme = ShopTheme.current();
        UiPreferences preferences = UiPreferences.configured();
        int selectionInk = preferences.highContrast() ? -1 : ThemeEffects.material(theme.materialGroup()).accent(theme.frame);
        int selectionFill = preferences.highContrast() ? DarkModeTheme.BORDER : theme.selectBg;
        int hoverFill = preferences.highContrast() ? DarkModeTheme.BORDER_HOVER : theme.rowHover;
        int ly = listY(), h = listHeight();
        scroll = ShopDialogRows.clampPixelScroll(scroll, contentHeight, h);
        theme.rootShadow(g, x, ly, width, h + footerHeight() + 4);
        theme.tooltipPanel(g, x, ly, width, h + footerHeight() + 4);
        g.enableScissor(Math.round((x + 1) * scale) + offsetX, Math.round((ly + 2) * scale) + offsetY,
                Math.round((x + width - 1) * scale) + offsetX, Math.round((ly + h + 2) * scale) + offsetY);
        try {
            int hovered = rowAt(mx, my);
            for (Row row : rows) {
                int ry = ly + 2 + row.y() - scroll;
                if (ry + row.height() <= ly + 2 || ry >= ly + h + 2) continue;
                boolean selected = pages.selectedIndex() == row.index();
                float hv = ui.rowHover(row.index(), hovered == row.index());
                int fill = GuiMotion.lerpColor(selected ? selectionFill : 0, hoverFill, hv);
                g.fill(x + 2, ry, x + width - 3, ry + row.height(), fill);
                if (selected) g.fill(x + 2, ry + 3, x + 4, ry + row.height() - 3, selectionInk);
                for (int i = 0; i < row.lines().size(); i++)
                    g.drawString(font, row.lines().get(i), x + 6, ry + 3 + i * 10, -1, !theme.glass);
            }
            if (contentHeight > h) {
                int thumb = Math.min(h, Math.max(8, h * h / contentHeight));
                int thumbY = ly + 2 + (h - thumb) * scroll / Math.max(1, contentHeight - h);
                g.fill(x + width - 3, thumbY, x + width - 2, thumbY + thumb, theme.frame);
            }
        } finally { g.disableScissor(); }
        if (footerHeight() > 0) {
            int footerY = ly + h + 2;
            g.fill(x + 2, footerY, x + width - 2, footerY + 1, theme.frameDark);
            String label = pageLabel();
            int labelWidth = font.width(label);
            if (labelWidth > width - 4) label = font.plainSubstrByWidth(label, width - 4);
            g.drawString(font, label, x + (width - font.width(label)) / 2, footerY + 4, -1, !theme.glass);
            drawPageButton(g, theme, preferences, footerY, labelWidth, -1, mx, my);
            drawPageButton(g, theme, preferences, footerY, labelWidth, 1, mx, my);
        }
    }

    private void drawPageButton(GuiGraphics g, ShopTheme theme, UiPreferences preferences, int footerY,
                                int labelWidth, int delta, int mx, int my) {
        Bounds bounds = pageButton(footerY, labelWidth, delta);
        if (bounds.width() == 0) return;
        boolean enabled = canChangePage(delta);
        float hover = ui.hoverAt(bounds.x(), bounds.y(), bounds.width(), bounds.height(), enabled && bounds.contains(mx, my));
        if (enabled) {
            theme.box(g, bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                    GuiMotion.lerpColor(theme.frameDark, theme.frame, hover),
                    GuiMotion.lerpColor(theme.buttonBg, theme.buttonHover, hover));
            if (preferences.highContrast() && hover > 0f) {
                g.fill(bounds.x() + 2, bounds.bottom() - 2, bounds.right() - 2, bounds.bottom() - 1,
                        GuiMotion.lerpColor(0, -1, hover));
            }
        }
        Component arrow = Component.translatable(delta < 0
                ? "gt_shanhai.shop.search.previous_page" : "gt_shanhai.shop.search.next_page");
        int ink = enabled ? -1 : preferences.highContrast() ? DarkModeTheme.TEXT_MUTED : theme.tabIdle();
        g.drawString(font, arrow, bounds.x() + (bounds.width() - font.width(arrow)) / 2,
                bounds.y() + 2, ink, !theme.glass);
    }
}
