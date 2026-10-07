package com.dishanhai.gt_shanhai.client.recipe;

import net.minecraft.client.Minecraft;

/** Client clipboard. The recipe editor calls this only after an isClient check. */
public final class ShanhaiRecipeClipboard {

    private ShanhaiRecipeClipboard() {}

    public static void copy(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.keyboardHandler == null) return;
        minecraft.keyboardHandler.setClipboard(text == null ? "" : text);
    }
}
