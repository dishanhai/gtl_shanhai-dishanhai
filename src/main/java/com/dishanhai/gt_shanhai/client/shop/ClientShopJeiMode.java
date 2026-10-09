package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.mixin.JeiIngredientFilterApiAccessor;
import com.dishanhai.gt_shanhai.mixin.JeiIngredientFilterAccessor;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.runtime.IIngredientFilter;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.gui.ingredients.IngredientFilter;
import mezz.jei.gui.ingredients.IngredientFilterApi;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class ClientShopJeiMode {

    private static boolean enabled;
    private static IDrawable buttonOffIcon;
    private static IDrawable buttonOnIcon;

    private ClientShopJeiMode() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static IDrawable buttonOffIcon() {
        return buttonOffIcon;
    }

    public static IDrawable buttonOnIcon() {
        return buttonOnIcon;
    }

    public static void setButtonIcons(IDrawable offIcon, IDrawable onIcon) {
        buttonOffIcon = offIcon;
        buttonOnIcon = onIcon;
    }

    public static void toggle() {
        enabled = !enabled;
        ShopJeiProductElements.clearCache();
        refreshIngredientList();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(
                    enabled ? "message.gt_shanhai.jei.shop_mode.enabled"
                            : "message.gt_shanhai.jei.shop_mode.disabled"), true);
        }
        if (enabled) ClientShopCatalog.startJeiProductScan();
    }

    public static void productsChanged() {
        ShopJeiProductElements.clearCache();
        if (enabled) refreshIngredientList();
    }

    public static void reset() {
        enabled = false;
        ShopJeiProductElements.clearCache();
        refreshIngredientList();
    }

    private static void refreshIngredientList() {
        IJeiRuntime runtime = com.dishanhai.gt_shanhai.client.ShanhaiJEIPlugin.getRuntime();
        if (runtime == null) return;
        IIngredientFilter api = runtime.getIngredientFilter();
        if (!(api instanceof IngredientFilterApi)) return;
        IngredientFilter ingredientFilter =
                ((JeiIngredientFilterApiAccessor) api).gtShanhai$getIngredientFilter();
        ((JeiIngredientFilterAccessor) ingredientFilter).gtShanhai$notifyListenersOfChange();
    }
}
