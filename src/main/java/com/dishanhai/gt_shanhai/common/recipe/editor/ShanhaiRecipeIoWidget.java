package com.dishanhai.gt_shanhai.common.recipe.editor;

import java.util.function.IntSupplier;

/** Compatibility name for {@link ShanhaiIOWidget}. */
public final class ShanhaiRecipeIoWidget extends ShanhaiIOWidget {

    public ShanhaiRecipeIoWidget(ShanhaiIoTable table, IntSupplier indexSupplier,
                                 int x, int y, Runnable changed) {
        super(table, indexSupplier, x, y, changed);
    }
}
