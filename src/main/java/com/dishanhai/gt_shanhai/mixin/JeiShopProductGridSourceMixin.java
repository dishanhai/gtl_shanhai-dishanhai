package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.shop.ShopJeiProductGridSource;
import mezz.jei.common.config.IClientConfig;
import mezz.jei.common.config.IClientToggleState;
import mezz.jei.common.config.IIngredientGridConfig;
import mezz.jei.common.gui.elements.DrawableNineSliceTexture;
import mezz.jei.common.network.IConnectionToServer;
import mezz.jei.gui.overlay.ingredients.IIngredientGridSource;
import mezz.jei.gui.overlay.ingredients.IngredientGrid;
import mezz.jei.gui.overlay.ingredients.IngredientGridScrollController;
import mezz.jei.gui.overlay.ingredients.IngredientGridWithNavigation;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IScreenHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = IngredientGridWithNavigation.class, remap = false)
public abstract class JeiShopProductGridSourceMixin {

    @Shadow @Final @Mutable private IIngredientGridSource ingredientSource;
    @Shadow @Final @Mutable private IngredientGridScrollController scrollController;
    @Shadow @Final private IngredientGrid ingredientGrid;
    @Shadow @Final private IIngredientGridConfig gridConfig;
    @Shadow @Final private IClientConfig clientConfig;

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void gtShanhai$useShopAwareGridSource(String debugName,
                                                 IIngredientGridSource source,
                                                 IngredientGrid ingredientGrid,
                                                 IClientToggleState toggleState,
                                                 IClientConfig clientConfig,
                                                 IConnectionToServer connection,
                                                 IIngredientGridConfig gridConfig,
                                                 DrawableNineSliceTexture background,
                                                 DrawableNineSliceTexture slotBackground,
                                                 IScreenHelper screenHelper,
                                                 IIngredientManager ingredientManager,
                                                 CallbackInfo ci) {
        if (!ShopJeiProductGridSource.isStandardJeiFilter(source)) return;
        ShopJeiProductGridSource shopSource = new ShopJeiProductGridSource(source);
        this.ingredientSource = shopSource;
        this.scrollController = new IngredientGridScrollController(
                shopSource, this.ingredientGrid, this.gridConfig, this.clientConfig);
    }
}
