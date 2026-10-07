package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Size;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Square toggle beside the query slot. Opening it lists recent searches.
 * The group is taller than the button so the list can hang over the cards;
 * clicks miss both pieces and fall through to the widgets underneath.
 */
public final class ShanhaiRecipeHistoryWidget extends WidgetGroup {

    static final int POPUP_W = 220;
    static final int BUTTON = 22;
    static final int ROW = 16;
    static final int VISIBLE = 6;

    private final ShanhaiRecipeEditorWidget host;
    private final Toggle toggle;
    private final Popup popup;
    private boolean open;

    public ShanhaiRecipeHistoryWidget(ShanhaiRecipeEditorWidget host, int x, int y) {
        super(x, y, POPUP_W, BUTTON + 2 + VISIBLE * ROW);
        this.host = host;
        toggle = new Toggle(POPUP_W - BUTTON, 0);
        popup = new Popup(0, BUTTON + 2, POPUP_W, ROW);
        addWidget(toggle);
        addWidget(popup);
        popup.setVisible(false);
        popup.setActive(false);
    }

    boolean isOpen() {
        return open;
    }

    void setOpen(boolean next) {
        open = next;
        if (next) popup.scroll = 0;
        popup.fit();
        popup.setVisible(next);
        popup.setActive(next);
    }

    boolean hit(double mouseX, double mouseY) {
        if (toggle.isMouseOverElement(mouseX, mouseY)) return true;
        return open && popup.isMouseOverElement(mouseX, mouseY);
    }

    @Override
    public Widget getHoverElement(double mouseX, double mouseY) {
        for (int i = widgets.size() - 1; i >= 0; i--) {
            Widget child = widgets.get(i);
            if (!child.isVisible()) continue;
            Widget hovered = child.getHoverElement(mouseX, mouseY);
            if (hovered != null) return hovered;
        }
        return null;
    }

    private final class Toggle extends Widget {
        private Toggle(int x, int y) {
            super(x, y, BUTTON, BUTTON);
        }

        @Override
        public void updateScreen() {
            super.updateScreen();
            setHoverTooltips(List.of(Component.literal(open ? "收起查询历史" : "展开查询历史")));
        }

        @Override
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            int x = getPositionX();
            int y = getPositionY();
            boolean hover = isMouseOverElement(mouseX, mouseY);
            int frame = open || hover ? 0xFF69E8FF : ShanhaiIOWidget.FRAME_ITEM;
            graphics.fill(x, y, x + BUTTON, y + BUTTON, frame);
            graphics.fill(x + 1, y + 1, x + BUTTON - 1, y + BUTTON - 1, 0xFF20374B);
            graphics.drawString(Minecraft.getInstance().font, open ? "收" : "历",
                    x + 7, y + 7, 0xFFEAF7FF, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0 || !isMouseOverElement(mouseX, mouseY)) return false;
            setOpen(!open);
            return true;
        }
    }

    private final class Popup extends Widget {
        private int scroll;

        private Popup(int x, int y, int width, int height) {
            super(x, y, width, height);
        }

        void fit() {
            int rows = Math.max(1, host.queryHistoryCount());
            int shown = Math.min(VISIBLE, rows);
            setSize(new Size(POPUP_W, shown * ROW));
            int max = Math.max(0, host.queryHistoryCount() - VISIBLE);
            if (scroll > max) scroll = max;
        }

        @Override
        public boolean mouseWheelMove(double mouseX, double mouseY, double wheelDelta) {
            if (!isMouseOverElement(mouseX, mouseY)) return false;
            int max = Math.max(0, host.queryHistoryCount() - VISIBLE);
            if (max == 0) return true;
            int dir = wheelDelta > 0d ? -1 : 1;
            scroll = Math.max(0, Math.min(max, scroll + dir));
            return true;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0 || !isMouseOverElement(mouseX, mouseY)) return false;
            int row = scroll + (int) ((mouseY - getPositionY()) / ROW);
            if (row >= 0 && row < host.queryHistoryCount()) host.applyQueryHistory(row);
            return true;
        }

        @Override
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            fit();
            int x = getPositionX();
            int y = getPositionY();
            int height = getSizeHeight();
            graphics.fill(x, y, x + POPUP_W, y + height, 0xFF69E8FF);
            graphics.fill(x + 1, y + 1, x + POPUP_W - 1, y + height - 1, 0xFF111B2D);
            Font font = Minecraft.getInstance().font;
            int count = host.queryHistoryCount();
            if (count == 0) {
                graphics.drawString(font, "尚无查询历史", x + 6, y + 4, 0xFF8AA6B9, false);
                return;
            }
            int shown = Math.min(VISIBLE, count);
            for (int i = 0; i < shown; i++) {
                int index = scroll + i;
                if (index >= count) break;
                int rowY = y + i * ROW;
                boolean hover = mouseX >= x && mouseX < x + POPUP_W
                        && mouseY >= rowY && mouseY < rowY + ROW;
                if (hover) graphics.fill(x + 2, rowY + 1, x + POPUP_W - 2, rowY + ROW - 1, 0xFF19324A);
                graphics.drawString(font, fitText(font, host.queryHistoryLabel(index), POPUP_W - 12),
                        x + 6, rowY + 4, 0xFFEAF7FF, false);
            }
        }
    }

    private static String fitText(Font font, String text, int width) {
        if (text == null) return "";
        if (font.width(text) <= width) return text;
        String dots = "...";
        int budget = width - font.width(dots);
        if (budget <= 0) return dots;
        String cut = text;
        while (!cut.isEmpty() && font.width(cut) > budget) cut = cut.substring(0, cut.length() - 1);
        return cut + dots;
    }
}
