package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.dishanhai.gt_shanhai.client.gui.glass.GlassPalette;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Cyan bars that slide under the search-mode and ingredient-kind buttons.
 * Each row has its own top; coordinates are relative to this widget's parent.
 */
public final class ShanhaiRecipeChoiceMarkerWidget extends Widget {

    private static final long MOVE_MS = 180L;

    private final ShanhaiRecipeEditorWidget host;
    private final int[] modeXs;
    private final int[] modeWs;
    private final int modeY;
    private final int[] kindXs;
    private final int[] kindWs;
    private final int kindY;
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

    /** Mode entries follow RECIPE_ID, INGREDIENT, OUTPUT; kind entries follow ITEM, FLUID. */
    public ShanhaiRecipeChoiceMarkerWidget(ShanhaiRecipeEditorWidget host,
                                           int[] modeXs, int[] modeWs, int modeY,
                                           int[] kindXs, int[] kindWs, int kindY) {
        super(0, 0, 1, 1);
        this.host = host;
        this.modeXs = modeXs;
        this.modeWs = modeWs;
        this.modeY = modeY;
        this.kindXs = kindXs;
        this.kindWs = kindWs;
        this.kindY = kindY;
    }

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        retargetMode();
        retargetKind();
        fill(graphics, modeFromX, modeToX, modeFromW, modeToW, modeY, progress(modeStarted));
        fill(graphics, kindFromX, kindToX, kindFromW, kindToW, kindY, progress(kindStarted));
    }

    private void retargetMode() {
        int index = modeIndex();
        if (mode < 0) {
            mode = index;
            modeFromX = modeToX = modeXs[index];
            modeFromW = modeToW = modeWs[index];
            return;
        }
        if (index == mode) return;
        float shown = progress(modeStarted);
        modeFromX = Math.round(modeFromX + (modeToX - modeFromX) * shown);
        modeFromW = Math.round(modeFromW + (modeToW - modeFromW) * shown);
        modeToX = modeXs[index];
        modeToW = modeWs[index];
        mode = index;
        modeStarted = ShanhaiRecipeEditorAnimation.nowMs();
    }

    private void retargetKind() {
        boolean on = host.searchMode != ShanhaiRecipeQuery.SearchMode.RECIPE_ID;
        int index = host.ingredientKind == ShanhaiRecipeQuery.IngredientKind.FLUID ? 1 : 0;
        if (kind < 0) {
            kind = index;
            kindOn = on;
            kindFromX = kindToX = kindXs[index];
            kindFromW = kindToW = on ? kindWs[index] : 0;
            return;
        }
        if (on == kindOn && index == kind) return;
        float shown = progress(kindStarted);
        kindFromX = Math.round(kindFromX + (kindToX - kindFromX) * shown);
        kindFromW = Math.round(kindFromW + (kindToW - kindFromW) * shown);
        kindToX = kindXs[index];
        kindToW = on ? kindWs[index] : 0;
        kind = index;
        kindOn = on;
        kindStarted = ShanhaiRecipeEditorAnimation.nowMs();
    }

    private int modeIndex() {
        if (host.searchMode == ShanhaiRecipeQuery.SearchMode.INGREDIENT) return 1;
        if (host.searchMode == ShanhaiRecipeQuery.SearchMode.OUTPUT) return 2;
        return 0;
    }

    private void fill(GuiGraphics graphics, int fromX, int toX, int fromW, int toW, int top, float shown) {
        int left = Math.round(fromX + (toX - fromX) * shown);
        int width = Math.round(fromW + (toW - fromW) * shown);
        if (width <= 0) return;
        int x = getPositionX() + left;
        int y = getPositionY() + top;
        graphics.fill(x, y, x + width, y + 2, GlassPalette.ACCENT);
    }

    private static float progress(long started) {
        float linear = Math.min(1f, Math.max(0L, ShanhaiRecipeEditorAnimation.nowMs() - started) / (float) MOVE_MS);
        return linear * linear * (3f - 2f * linear);
    }
}
