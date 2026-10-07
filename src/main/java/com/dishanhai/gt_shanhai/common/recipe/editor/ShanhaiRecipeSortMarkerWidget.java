package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.lowdragmc.lowdraglib.gui.widget.Widget;
import net.minecraft.client.gui.GuiGraphics;

/** Cyan bar that slides under the active sort button. */
public final class ShanhaiRecipeSortMarkerWidget extends Widget {

    private static final long MOVE_MS = 180L;
    private static final int[] X = {160, 198, 236, 278, 332};
    private static final int[] W = {36, 36, 40, 52, 52};
    private static final String[] KEYS = {"id", "eut", "duration", "inputs", "outputs"};

    private final ShanhaiRecipeEditorWidget host;
    private String key = "";
    private int fromX;
    private int toX;
    private int fromW;
    private int toW;
    private long started;

    public ShanhaiRecipeSortMarkerWidget(ShanhaiRecipeEditorWidget host, int x, int y, int width) {
        super(x, y, width, 2);
        this.host = host;
    }

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        retarget();
        float progress = progress();
        int left = Math.round(fromX + (toX - fromX) * progress);
        int width = Math.round(fromW + (toW - fromW) * progress);
        int x = getPositionX() + left;
        int y = getPositionY();
        graphics.fill(x, y, x + Math.max(8, width), y + 2, 0xFF69E8FF);
    }

    private void retarget() {
        String next = host.sortKey == null ? "id" : host.sortKey;
        if (next.equals(key)) return;
        long now = ShanhaiRecipeEditorAnimation.nowMs();
        int shownX = Math.round(fromX + (toX - fromX) * progress());
        int shownW = Math.round(fromW + (toW - fromW) * progress());
        int index = indexOf(next);
        if (key.isEmpty()) {
            fromX = toX = X[index];
            fromW = toW = W[index];
        } else {
            fromX = shownX;
            fromW = shownW;
            toX = X[index];
            toW = W[index];
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
