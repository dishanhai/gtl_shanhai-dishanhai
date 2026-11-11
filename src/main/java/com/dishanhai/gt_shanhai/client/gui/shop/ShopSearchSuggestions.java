package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.shop.ShopCatalogSearchIndex.Suggestion;
import com.dishanhai.sh_better_ui.client.gui.scaled.GuiMotion;
import com.dishanhai.sh_better_ui.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.sh_better_ui.client.gui.scaled.Spring;
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
        return y + height + 2 + (com.dishanhai.sh_better_ui.client.gui.scaled.UiPreferences.configured().reducedMotion() ? 0
                : Math.round(3f * (1f - GuiMotion.clamp01(motion.value()))));
    }

    private int footerHeight() { return pages.pageCount() > 1 ? 16 : 0; }
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
            if (footerHeight() > 0 && my >= listY() + 2 + listHeight()) {
                if (button == 0) {
                    if (mx < x + width / 3.0) changePage(-1);
                    else if (mx >= x + width * 2 / 3.0) changePage(1);
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
                float hv = ui.rowHover(row.index(), hovered == row.index() || pages.selectedIndex() == row.index());
                g.fill(x + 2, ry, x + width - 3, ry + row.height(), GuiMotion.lerpColor(0, theme.selectBg, hv));
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
            int fy = ly + h + 2;
            g.fill(x + 2, fy, x + width - 2, fy + 1, theme.frameDark);
            String label = Component.translatable("gt_shanhai.shop.search.page", pages.pageIndex() + 1, pages.pageCount()).getString();
            g.drawString(font, label, x + (width - font.width(label)) / 2, fy + 4, -1, !theme.glass);
            g.drawString(font, Component.translatable("gt_shanhai.shop.search.previous_page"), x + 6, fy + 4,
                    pages.pageIndex() > 0 ? -1 : theme.frameDark, !theme.glass);
            var next = Component.translatable("gt_shanhai.shop.search.next_page");
            g.drawString(font, next, x + width - 6 - font.width(next), fy + 4,
                    pages.pageIndex() + 1 < pages.pageCount() ? -1 : theme.frameDark, !theme.glass);
        }
    }
}
