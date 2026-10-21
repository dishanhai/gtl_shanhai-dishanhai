package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.dishanhai.gt_shanhai.client.gui.glass.GlassPalette;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Size;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import com.dishanhai.gt_shanhai.client.gui.glass.GlassBlur;
import com.dishanhai.gt_shanhai.client.gui.glass.GuiRect;
import com.dishanhai.gt_shanhai.client.gui.scaled.Spring;

/**
 * Square toggle beside the query slot. Opening it lists recent searches.
 * The group is only as big as the button; the list hangs below it, opening
 * rightward over the content column, and is drawn in the foreground so the
 * sidebar rows after it cannot paint over it. Each entry wraps in full and
 * its row is as tall as its text. Clicks that miss both pieces fall through.
 */
public final class ShanhaiRecipeHistoryWidget extends WidgetGroup {

    static final int POPUP_W = 300;
    static final int BUTTON = 22;
    static final int ROW = 16;
    static final int VISIBLE = 6;
    private static final int TEXT_W = POPUP_W - 12;
    /** Above the 400 that TextTexture button faces draw at. */
    private static final float POPUP_Z = 500f;

    private final ShanhaiRecipeEditorWidget host;
    private final Toggle toggle;
    private final Popup popup;
    private final Spring blurSpring = new Spring(Spring.PANEL);
    private boolean open;

    public ShanhaiRecipeHistoryWidget(ShanhaiRecipeEditorWidget host, int x, int y) {
        super(x, y, BUTTON, BUTTON);
        this.host = host;
        toggle = new Toggle(0, 0);
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
        blurSpring.snap(0f);
        if (next) {
            popup.scroll = 0;
            blurSpring.setTarget(1f, System.currentTimeMillis());
        }
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
            int frame = open || hover ? GlassPalette.ACCENT : GlassPalette.BUTTON_EDGE;
            graphics.fill(x, y, x + BUTTON, y + BUTTON, frame);
            graphics.fill(x + 1, y + 1, x + BUTTON - 1, y + BUTTON - 1, GlassPalette.ROW_ICON_BG);
            graphics.drawString(Minecraft.getInstance().font, open ? "收" : "历",
                    x + 7, y + 7, GlassPalette.TEXT, false);
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

        /** Height of one entry: every wrapped line of it, never below {@link #ROW}. */
        private int rowHeight(int index) {
            int lines = GlassWrapLabel.lineCount(host.queryHistoryLabel(index), TEXT_W);
            return Math.max(ROW, lines * 10 + 6);
        }

        void fit() {
            int count = host.queryHistoryCount();
            int max = Math.max(0, count - VISIBLE);
            if (scroll > max) scroll = max;
            int height = 0;
            for (int i = 0; i < VISIBLE && scroll + i < count; i++) height += rowHeight(scroll + i);
            setSize(new Size(POPUP_W, Math.max(ROW, height)));
        }

        @Override
        public boolean mouseWheelMove(double mouseX, double mouseY, double wheelDelta) {
            if (!isMouseOverElement(mouseX, mouseY)) return false;
            int max = Math.max(0, host.queryHistoryCount() - VISIBLE);
            if (max == 0) return true;
            int dir = wheelDelta > 0d ? -1 : 1;
            scroll = Math.max(0, Math.min(max, scroll + dir));
            fit();
            return true;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0 || !isMouseOverElement(mouseX, mouseY)) return false;
            int count = host.queryHistoryCount();
            int rowY = getPositionY();
            for (int i = 0; i < VISIBLE && scroll + i < count; i++) {
                int height = rowHeight(scroll + i);
                if (mouseY < rowY + height) {
                    host.applyQueryHistory(scroll + i);
                    return true;
                }
                rowY += height;
            }
            return true;
        }

        @Override
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            // Drawn in the foreground instead; see the class comment.
        }

        @Override
        public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            fit();
            int x = getPositionX();
            int y = getPositionY();
            int height = getSizeHeight();
            long now = System.currentTimeMillis();
            blurSpring.update(now);
            // The toggle sits right above the popup, so keep it inside the clear area.
            GlassBlur.requestPopup(graphics,
                    new GuiRect(x, y - BUTTON - 2, BUTTON, BUTTON)
                            .union(new GuiRect(x, y, POPUP_W, height)),
                    Math.max(0f, Math.min(1f, blurSpring.value())));
            graphics.pose().pushPose();
            graphics.pose().translate(0f, 0f, POPUP_Z);
            graphics.fill(x, y, x + POPUP_W, y + height, GlassPalette.ACCENT);
            graphics.fill(x + 1, y + 1, x + POPUP_W - 1, y + height - 1, GlassPalette.ROW_FILL);
            Font font = Minecraft.getInstance().font;
            int count = host.queryHistoryCount();
            if (count == 0) {
                graphics.drawString(font, "尚无查询历史", x + 6, y + 4, GlassPalette.TEXT_DIM, false);
                graphics.pose().popPose();
                return;
            }
            int rowY = y;
            for (int i = 0; i < VISIBLE && scroll + i < count; i++) {
                int index = scroll + i;
                int rowHeight = rowHeight(index);
                boolean hover = mouseX >= x && mouseX < x + POPUP_W
                        && mouseY >= rowY && mouseY < rowY + rowHeight;
                if (hover) graphics.fill(x + 2, rowY + 1, x + POPUP_W - 2, rowY + rowHeight - 1, GlassPalette.ROW_FILL_HOVER);
                int lineY = rowY + 4;
                for (FormattedCharSequence line : GlassWrapLabel.lines(host.queryHistoryLabel(index), TEXT_W)) {
                    graphics.drawString(font, line, x + 6, lineY, GlassPalette.TEXT, false);
                    lineY += 10;
                }
                rowY += rowHeight;
            }
            graphics.pose().popPose();
        }
    }
}
