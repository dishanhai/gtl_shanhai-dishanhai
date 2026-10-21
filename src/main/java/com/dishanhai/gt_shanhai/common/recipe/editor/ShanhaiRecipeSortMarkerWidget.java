package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.dishanhai.gt_shanhai.client.gui.glass.GlassPalette;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Cyan bar that slides under the active sort button. The buttons may sit on two rows,
 * so the bar moves in y as well. Coordinates are relative to this widget's parent.
 */
public final class ShanhaiRecipeSortMarkerWidget extends Widget {

    private static final long MOVE_MS = 180L;
    static final String[] KEYS = {"id", "eut", "duration", "inputs", "outputs"};

    private final ShanhaiRecipeEditorWidget host;
    private final int[] xs;
    private final int[] ys;
    private final int[] ws;
    private String key = "";
    private int fromX;
    private int toX;
    private int fromY;
    private int toY;
    private int fromW;
    private int toW;
    private long started;

    /** One entry per {@link #KEYS} key: the bar's left edge, top and width under that button. */
    public ShanhaiRecipeSortMarkerWidget(ShanhaiRecipeEditorWidget host, int[] xs, int[] ys, int[] ws) {
        super(0, 0, 1, 1);
        this.host = host;
        this.xs = xs;
        this.ys = ys;
        this.ws = ws;
    }

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        retarget();
        float progress = progress();
        int left = Math.round(fromX + (toX - fromX) * progress);
        int top = Math.round(fromY + (toY - fromY) * progress);
        int width = Math.round(fromW + (toW - fromW) * progress);
        int x = getPositionX() + left;
        int y = getPositionY() + top;
        graphics.fill(x, y, x + Math.max(8, width), y + 2, GlassPalette.ACCENT);
    }

    private void retarget() {
        String next = host.sortKey == null ? "id" : host.sortKey;
        if (next.equals(key)) return;
        long now = ShanhaiRecipeEditorAnimation.nowMs();
        int shownX = Math.round(fromX + (toX - fromX) * progress());
        int shownY = Math.round(fromY + (toY - fromY) * progress());
        int shownW = Math.round(fromW + (toW - fromW) * progress());
        int index = indexOf(next);
        if (key.isEmpty()) {
            fromX = toX = xs[index];
            fromY = toY = ys[index];
            fromW = toW = ws[index];
        } else {
            fromX = shownX;
            fromY = shownY;
            fromW = shownW;
            toX = xs[index];
            toY = ys[index];
            toW = ws[index];
            started = now;
        }
        key = next;
    }

    private float progress() {
        if (key.isEmpty()) return 1f;
        float linear = Math.min(1f, Math.max(0L, ShanhaiRecipeEditorAnimation.nowMs() - started) / (float) MOVE_MS);
        return linear * linear * (3f - 2f * linear);
    }

    private static int indexOf(String value) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(value)) return i;
        }
        return 0;
    }
}
