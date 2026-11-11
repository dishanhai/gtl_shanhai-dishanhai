package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog;
import com.dishanhai.gt_shanhai.client.shop.ClientShopPersonalHidden;
import com.dishanhai.gt_shanhai.client.shop.ShopCatalogView;
import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;
import com.dishanhai.sh_better_ui.client.gui.display.GraphicDisplayLayout.Bounds;
import com.dishanhai.sh_better_ui.client.gui.scaled.PageTransition;
import com.dishanhai.sh_better_ui.client.gui.scaled.UiBottomSheet;
import com.dishanhai.sh_better_ui.client.gui.scaled.UiPreferences;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** 山海：普通浏览时的个人隐藏抽屉，使用原版 GUI 坐标以复用 SHBUI 的裁切和命中范围。 */
final class ShopHiddenDrawer {
    private static final int PAD = 7, HEADER = 24, ROW = 20;
    private static final int RESTORE_WIDTH = 70;
    private static final String KEY = "gt_shanhai.shop.hidden.";

    private final UiBottomSheet sheet = new UiBottomSheet(0, 0f, 1f);
    private final ShopControlInput keys = new ShopControlInput();
    private final Consumer<String> restore;
    private UiBottomSheet.Frame frame;
    private ShopCatalogView catalog;
    private Set<String> ids;
    private List<ShopCatalogManifest.Stub> entries = List.of();
    private int scroll, renderedScroll, selected;

    ShopHiddenDrawer(Consumer<String> restore) {
        this.restore = restore;
    }

    boolean active() {
        return sheet.targetAnchor() != 0 || (frame != null && frame.panel().height() > 0);
    }

    void open() {
        scroll = selected = 0;
        refreshEntries();
        long window = Minecraft.getInstance().getWindow().getWindow();
        keys.reset(keyDown(window, GLFW.GLFW_KEY_ENTER), keyDown(window, GLFW.GLFW_KEY_KP_ENTER),
                keyDown(window, GLFW.GLFW_KEY_SPACE));
        sheet.animateTo(1, PageTransition.nowMs());
        narrate(entries.isEmpty() ? label("empty") : label("row_narration", name(entries.getFirst())));
    }

    void reset() {
        sheet.snapTo(0);
        frame = null;
    }

    void layoutChanged() {
        cancelGesture();
        frame = null;
    }

    void cancelGesture() { sheet.cancelGesture(PageTransition.nowMs()); }

    private void close() { sheet.animateTo(0, PageTransition.nowMs()); }

    private void refreshEntries() {
        var currentCatalog = ClientShopCatalog.view(false);
        var currentIds = ClientShopPersonalHidden.ids();
        if (catalog == currentCatalog && ids == currentIds) return;
        catalog = currentCatalog;
        ids = currentIds;
        entries = catalog.personalHiddenEntries(ids);
        selected = Math.min(selected, Math.max(0, entries.size() - 1));
    }

    void render(GuiGraphics g, Font font, int width, int height, int mx, int my) {
        refreshEntries();
        int w = Math.max(0, Math.min(432, width - 12));
        int h = Math.max(0, Math.min(238, height - 12));
        Bounds viewport = new Bounds((width - w) / 2, height - h - 6, w, h);
        var prefs = UiPreferences.configured();
        g.flush();
        g.pose().pushPose();
        try {
            g.pose().translate(0, 0, 500);
            g.fill(0, 0, width, height, 0x66000000);
            frame = sheet.render(g, viewport, mx, my, PageTransition.nowMs(), prefs,
                    (graphics, content) -> renderContent(graphics, font, content, mx, my, prefs.highContrast()));
            g.flush();
        } finally {
            g.pose().popPose();
        }
    }

    private void renderContent(GuiGraphics g, Font font, Bounds area, int mx, int my, boolean contrast) {
        Bounds close = closeBounds(area);
        Component title = label("title", entries.size());
        g.drawString(font, font.plainSubstrByWidth(title.getString(), Math.max(0, close.x() - area.x() - 2 * PAD)),
                area.x() + PAD, area.y() + 6, 0xFFFFFFFF);
        g.fill(close.x(), close.y(), close.right(), close.bottom(), close.contains(mx, my) ? 0xFF506177 : 0xFF354357);
        g.drawCenteredString(font, "×", close.x() + close.width() / 2, close.y() + 4, 0xFFFFFFFF);

        if (entries.isEmpty()) {
            g.drawWordWrap(font, label("empty"), area.x() + PAD, area.y() + HEADER,
                    Math.max(1, area.width() - 2 * PAD), 0xFFFFFFFF);
            return;
        }
        clampScroll(area);
        renderedScroll = scroll;
        int shown = Math.min(visibleRows(area), entries.size() - scroll);
        for (int i = 0; i < shown; i++) {
            int index = scroll + i;
            Bounds row = rowBounds(area, i);
            boolean focused = index == selected;
            boolean hovered = row.contains(mx, my);
            g.fill(row.x(), row.y(), row.right(), row.bottom(), focused || hovered ? 0xFF364E69 : 0xFF253447);
            if (focused) g.renderOutline(row.x(), row.y(), row.width(), row.height(), contrast ? 0xFFFFFFFF : 0xFF89C9FF);
            Bounds restoreButton = restoreBounds(row);
            String action = font.plainSubstrByWidth(label("restore").getString(), Math.max(0, restoreButton.width() - 4));
            String name = font.plainSubstrByWidth(name(entries.get(index)),
                    Math.max(0, restoreButton.x() - row.x() - 2 * PAD));
            g.drawString(font, name, row.x() + PAD, row.y() + 6, 0xFFFFFFFF);
            g.fill(restoreButton.x(), restoreButton.y(), restoreButton.right(), restoreButton.bottom(),
                    restoreButton.contains(mx, my) ? 0xFF506177 : 0xFF354357);
            g.drawCenteredString(font, action, restoreButton.x() + restoreButton.width() / 2, row.y() + 6,
                    contrast ? 0xFFFFFFFF : 0xFFA6DEFF);
        }
    }

    void click(double x, double y, int button) {
        if (sheet.targetAnchor() == 0 || frame == null) return;
        if (sheet.begin(frame, button, x, y, PageTransition.nowMs()) || button != 0) return;
        Bounds area = frame.content();
        if (!frame.panel().contains(x, y) || closeBounds(area).contains(x, y)) {
            close();
            return;
        }
        if (!area.contains(x, y)) return;
        // 用本帧绘出的条目做命中；若目录刚变，待新一帧布局后再允许恢复。
        if (catalog != ClientShopCatalog.view(false) || ids != ClientShopPersonalHidden.ids()) return;
        for (int i = 0; i < visibleRows(area) && renderedScroll + i < entries.size(); i++) {
            Bounds row = rowBounds(area, i);
            if (!row.contains(x, y)) continue;
            selected = renderedScroll + i;
            if (restoreBounds(row).contains(x, y)) restoreSelected();
            else narrate(label("row_narration", name(entries.get(selected))));
            return;
        }
    }

    void drag(double x, double y, int button) { sheet.drag(button, x, y, PageTransition.nowMs()); }
    void release(double x, double y, int button) { sheet.release(button, x, y, PageTransition.nowMs()); }

    void scroll(double delta) {
        if (frame == null || sheet.targetAnchor() == 0) return;
        scroll -= (int) Math.signum(delta);
        clampScroll(frame.content());
        int last = Math.min(entries.size(), scroll + visibleRows(frame.content())) - 1;
        if (last >= scroll) selected = Math.clamp(selected, scroll, last);
    }

    void keyPressed(int key) {
        if (sheet.targetAnchor() == 0) return;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return;
        }
        refreshEntries();
        if (ShopControlInput.activationKey(key)) {
            if (keys.press(key, !entries.isEmpty())) restoreSelected();
            return;
        }
        if (entries.isEmpty()) return;
        int step = switch (key) {
            case GLFW.GLFW_KEY_UP -> -1;
            case GLFW.GLFW_KEY_DOWN -> 1;
            case GLFW.GLFW_KEY_TAB -> Screen.hasShiftDown() ? -1 : 1;
            default -> 0;
        };
        if (step == 0) return;
        selected = key == GLFW.GLFW_KEY_TAB ? Math.floorMod(selected + step, entries.size())
                : Math.clamp(selected + step, 0, entries.size() - 1);
        if (frame != null) {
            int rows = Math.max(1, visibleRows(frame.content()));
            if (selected < scroll) scroll = selected;
            if (selected >= scroll + rows) scroll = selected - rows + 1;
        }
        narrate(label("row_narration", name(entries.get(selected))));
    }

    void keyReleased(int key) { keys.release(key); }

    private void restoreSelected() {
        if (selected < entries.size()) restore.accept(entries.get(selected).stableId());
    }

    private void clampScroll(Bounds area) {
        scroll = Math.clamp(scroll, 0, Math.max(0, entries.size() - visibleRows(area)));
    }

    private static int visibleRows(Bounds area) { return Math.max(0, (area.height() - HEADER - PAD) / ROW); }

    private static Bounds closeBounds(Bounds area) {
        return new Bounds(area.right() - PAD - 16, area.y() + 2, 16, 16);
    }

    private static Bounds rowBounds(Bounds area, int row) {
        return new Bounds(area.x() + PAD, area.y() + HEADER + row * ROW, Math.max(0, area.width() - 2 * PAD), ROW - 2);
    }

    private static Bounds restoreBounds(Bounds row) {
        int width = Math.min(RESTORE_WIDTH, row.width() / 2);
        return new Bounds(row.right() - width, row.y() + 1, width, row.height() - 2);
    }

    private static String name(ShopCatalogManifest.Stub stub) {
        return stub.displayName().isBlank() ? stub.goodsIds().stream().findFirst().orElse(stub.stableId()) : stub.displayName();
    }

    private static Component label(String key, Object... args) { return Component.translatable(KEY + key, args); }
    private static void narrate(Component text) { Minecraft.getInstance().getNarrator().sayNow(text); }
    private static boolean keyDown(long window, int key) { return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS; }
}
