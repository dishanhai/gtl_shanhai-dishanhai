package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.dishanhai.gt_shanhai.client.gui.glass.GlassBlur;
import com.dishanhai.gt_shanhai.client.gui.glass.GuiRect;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.SelectableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.SelectorWidget;
import com.lowdragmc.lowdraglib.utils.Position;
import com.lowdragmc.lowdraglib.utils.Size;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Recipe-type dropdown that opens by revealing its list from the top.
 * The popup stays out of the hit tree until the revealed slice can take the click.
 *
 * <p>Nothing is cut or rolled: the face shows every wrapped line, and each
 * candidate row is as tall as its wrapped text. The list may be wider than the
 * button so long names in a narrow column still read in full.</p>
 */
public final class ShanhaiRecipeTypeSelector extends SelectorWidget {

    private static final long OPEN_MS = 200L;
    private static final int ROW_H = 15;
    private static final int ROW_PAD = 3;

    private boolean targetOpen;
    private float fromOpen;
    private long started;
    private int popupWidth;
    private Consumer<String> changed;

    public ShanhaiRecipeTypeSelector(int x, int y, int width, int height, List<String> candidates, int fontColor) {
        super(x, y, width, height, candidates, fontColor);
        textTexture.setType(TextTexture.TextType.NORMAL);
    }

    /** Popup width when it should be wider than the button. It still opens from the button's left edge. */
    public ShanhaiRecipeTypeSelector setPopupWidth(int width) {
        popupWidth = width;
        computeLayout();
        return this;
    }

    @Override
    public SelectorWidget setOnChanged(Consumer<String> onChanged) {
        changed = onChanged;
        return super.setOnChanged(onChanged);
    }

    /**
     * Same rows as {@link SelectorWidget}, but wrapped instead of rolled and sized to their text.
     * Runs once from the super constructor too, before this class's fields are set.
     */
    @Override
    protected void computeLayout() {
        int width = Math.max(popupWidth, getSize().width);
        int inner = candidates.size() > maxCount ? width - 4 : width;
        int textWidth = Math.max(8, inner - ROW_PAD * 2);
        int[] heights = new int[candidates.size()];
        int shown = 0;
        for (int i = 0; i < candidates.size(); i++) {
            heights[i] = Math.max(ROW_H, GlassWrapLabel.lineCount(candidates.get(i), textWidth) * 9 + 6);
            if (i < maxCount) shown += heights[i];
        }
        popUp.clearAllWidgets();
        selectables.clear();
        popUp.setSize(new Size(width, shown));
        popUp.setSelfPosition(showUp ? new Position(0, -shown) : new Position(0, getSize().height));
        if (candidates.size() > maxCount) {
            popUp.setYScrollBarWidth(4).setYBarStyle(null, new ColorRectTexture(-1));
        }
        int y = 0;
        for (int i = 0; i < candidates.size(); i++) {
            String candidate = candidates.get(i);
            SelectableWidgetGroup select = new SelectableWidgetGroup(0, y, inner, heights[i]);
            select.addWidget(new ImageWidget(ROW_PAD, 0, textWidth, heights[i],
                    new TextTexture(candidate, fontColor).setWidth(textWidth).setType(TextTexture.TextType.NORMAL)));
            select.setSelectedTexture(-1, -1);
            select.setOnSelected(s -> {
                setValue(candidate);
                if (changed != null) changed.accept(candidate);
                writeClientAction(2, buffer -> buffer.writeUtf(candidate));
                setShow(false);
            });
            popUp.addWidget(select);
            selectables.add(select);
            y += heights[i];
        }
        popUp.setScrollYOffset(0);
    }

    @Override
    public void setShow(boolean show) {
        float shown = openAmount();
        if (show == targetOpen && Math.abs(shown - (show ? 1f : 0f)) < 0.001f) return;
        fromOpen = shown;
        targetOpen = show;
        started = ShanhaiRecipeEditorAnimation.nowMs();
        isShow = show;
        if (show) setFocus(true);
        applyPopup();
    }

    @Override
    public boolean isMouseOverElement(double mouseX, double mouseY) {
        if (inVisiblePopup(mouseX, mouseY)) return true;
        boolean saved = popUp.isVisible();
        popUp.setVisible(false);
        boolean over = super.isMouseOverElement(mouseX, mouseY);
        popUp.setVisible(saved);
        return over;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!inVisiblePopup(mouseX, mouseY)) return super.mouseClicked(mouseX, mouseY, button);
        popUp.setVisible(true);
        popUp.setActive(true);
        popUp.mouseClicked(mouseX, mouseY, button);
        applyPopup();
        return true;
    }

    /**
     * The open list is drawn past the scroll box and over the review buttons.
     * Those buttons are later siblings, so they win the click unless the panel
     * asks the popup first.
     */
    public boolean claimPopupClick(double mouseX, double mouseY, int button) {
        if (!inVisiblePopup(mouseX, mouseY)) return false;
        mouseClicked(mouseX, mouseY, button);
        return true;
    }

    public boolean claimPopupWheel(double mouseX, double mouseY, double wheelDelta) {
        if (!inVisiblePopup(mouseX, mouseY)) return false;
        popUp.setVisible(true);
        popUp.setActive(true);
        popUp.mouseWheelMove(mouseX, mouseY, wheelDelta);
        applyPopup();
        return true;
    }

    @Override
    public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        applyPopup();
        boolean savedShow = isShow;
        boolean savedVisible = popUp.isVisible();
        isShow = false;
        popUp.setVisible(false);
        super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
        isShow = savedShow;
        popUp.setVisible(savedVisible);

        float open = openAmount();
        int fullHeight = popUp.getSizeHeight();
        if (open <= 0.001f || fullHeight <= 0) return;
        int shown = Math.max(1, Math.round(fullHeight * open));
        int x = popUp.getPositionX();
        int y = popUp.getPositionY();
        GlassBlur.requestPopup(graphics,
                new GuiRect(getPositionX(), getPositionY(), getSizeWidth(), getSizeHeight())
                        .union(new GuiRect(x, y, popUp.getSizeWidth(), shown)), open);
        clip(graphics, x, y, x + popUp.getSizeWidth(), y + shown);
        graphics.pose().pushPose();
        graphics.pose().translate(0f, 0f, 200f);
        popUp.drawInBackground(graphics, mouseX, mouseY, partialTicks);
        popUp.drawInForeground(graphics, mouseX, mouseY, partialTicks);
        graphics.pose().popPose();
        graphics.disableScissor();
    }

    private void applyPopup() {
        float open = openAmount();
        boolean live = targetOpen && open >= 0.98f;
        popUp.setVisible(live);
        popUp.setActive(live);
        if (!targetOpen && open <= 0.001f) {
            isShow = false;
            popUp.setVisible(false);
            popUp.setActive(false);
        }
    }

    private float openAmount() {
        float linear = Math.min(1f, Math.max(0f, (ShanhaiRecipeEditorAnimation.nowMs() - started) / (float) OPEN_MS));
        float progress = linear * linear * (3f - 2f * linear);
        float to = targetOpen ? 1f : 0f;
        return fromOpen + (to - fromOpen) * progress;
    }

    private boolean inVisiblePopup(double mouseX, double mouseY) {
        float open = openAmount();
        if (open <= 0.02f || popUp.getSizeHeight() <= 0) return false;
        int x = popUp.getPositionX();
        int y = popUp.getPositionY();
        int bottom = y + Math.max(1, Math.round(popUp.getSizeHeight() * open));
        return mouseX >= x && mouseX < x + popUp.getSizeWidth() && mouseY >= y && mouseY < bottom;
    }

    private static void clip(GuiGraphics graphics, int x, int y, int right, int bottom) {
        Matrix4f pose = graphics.pose().last().pose();
        Vector4f min = pose.transform(new Vector4f(x, y, 0f, 1f));
        Vector4f max = pose.transform(new Vector4f(right, bottom, 0f, 1f));
        graphics.enableScissor((int) min.x, (int) min.y, (int) max.x, (int) max.y);
    }
}
