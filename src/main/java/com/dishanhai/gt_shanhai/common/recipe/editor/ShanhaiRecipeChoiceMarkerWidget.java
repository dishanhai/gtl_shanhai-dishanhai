package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.lowdragmc.lowdraglib.gui.widget.Widget;
import net.minecraft.client.gui.GuiGraphics;

/** Cyan bars that slide under the search-mode and ingredient-kind buttons. */
public final class ShanhaiRecipeChoiceMarkerWidget extends Widget {

    private static final long MOVE_MS = 180L;
    private static final int[] MODE_X = {4, 58, 104};
    private static final int[] MODE_W = {52, 44, 44};
    private static final int[] KIND_X = {152, 194};
    private static final int[] KIND_W = {40, 40};

    private final ShanhaiRecipeEditorWidget host;
    private int mode = -1;
    private int kind = -1;
    private boolean kindOn;
    private int modeFromX;
    private int modeToX;
    private int modeFromW;
    private int modeToW;
    private int kindFromX;
    private int kindToX;
    private int kindFromW;
    private int kindToW;
    private long modeStarted;
    private long kindStarted;

    public ShanhaiRecipeChoiceMarkerWidget(ShanhaiRecipeEditorWidget host, int x, int y, int width) {
        super(x, y, width, 2);
        this.host = host;
    }

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        retargetMode();
        retargetKind();
        fill(graphics, modeFromX, modeToX, modeFromW, modeToW, progress(modeStarted));
        fill(graphics, kindFromX, kindToX, kindFromW, kindToW, progress(kindStarted));
    }

    private void retargetMode() {
        int index = modeIndex();
        if (mode < 0) {
            mode = index;
            modeFromX = modeToX = MODE_X[index];
            modeFromW = modeToW = MODE_W[index];
            return;
        }
        if (index == mode) return;
        float shown = progress(modeStarted);
        modeFromX = Math.round(modeFromX + (modeToX - modeFromX) * shown);
        modeFromW = Math.round(modeFromW + (modeToW - modeFromW) * shown);
        modeToX = MODE_X[index];
        modeToW = MODE_W[index];
        mode = index;
        modeStarted = ShanhaiRecipeEditorAnimation.nowMs();
    }

    private void retargetKind() {
        boolean on = host.searchMode != ShanhaiRecipeQuery.SearchMode.RECIPE_ID;
        int index = host.ingredientKind == ShanhaiRecipeQuery.IngredientKind.FLUID ? 1 : 0;
        if (kind < 0) {
            kind = index;
            kindOn = on;
            kindFromX = kindToX = KIND_X[index];
            kindFromW = kindToW = on ? KIND_W[index] : 0;
            return;
        }
        if (on == kindOn && index == kind) return;
        float shown = progress(kindStarted);
        kindFromX = Math.round(kindFromX + (kindToX - kindFromX) * shown);
        kindFromW = Math.round(kindFromW + (kindToW - kindFromW) * shown);
        kindToX = KIND_X[index];
        kindToW = on ? KIND_W[index] : 0;
        kind = index;
        kindOn = on;
        kindStarted = ShanhaiRecipeEditorAnimation.nowMs();
    }

    private int modeIndex() {
        if (host.searchMode == ShanhaiRecipeQuery.SearchMode.INGREDIENT) return 1;
        if (host.searchMode == ShanhaiRecipeQuery.SearchMode.OUTPUT) return 2;
        return 0;
    }

    private void fill(GuiGraphics graphics, int fromX, int toX, int fromW, int toW, float shown) {
        int left = Math.round(fromX + (toX - fromX) * shown);
        int width = Math.round(fromW + (toW - fromW) * shown);
        if (width <= 0) return;
        int x = getPositionX() + left;
        int y = getPositionY();
        graphics.fill(x, y, x + width, y + 2, 0xFF69E8FF);
    }

    private static float progress(long started) {
        float linear = Math.min(1f, Math.max(0L, ShanhaiRecipeEditorAnimation.nowMs() - started) / (float) MOVE_MS);
        return linear * linear * (3f - 2f * linear);
    }
}
