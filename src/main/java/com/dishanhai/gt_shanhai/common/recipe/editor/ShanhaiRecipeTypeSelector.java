package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.lowdragmc.lowdraglib.gui.widget.SelectorWidget;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Recipe-type dropdown that opens by revealing its list from the top.
 * The popup stays out of the hit tree until the revealed slice can take the click.
 */
public final class ShanhaiRecipeTypeSelector extends SelectorWidget {

    private static final long OPEN_MS = 200L;

    private boolean targetOpen;
    private float fromOpen;
    private long started;

    public ShanhaiRecipeTypeSelector(int x, int y, int width, int height, List<String> candidates, int fontColor) {
        super(x, y, width, height, candidates, fontColor);
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
