package com.dishanhai.gt_shanhai.client.shop;

import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.gui.ingredients.IngredientFilter;
import mezz.jei.gui.overlay.elements.IElement;
import mezz.jei.gui.overlay.ingredients.IIngredientGridSource;
import net.minecraftforge.fluids.FluidStack;

import java.util.List;

/** Limits the native JEI ingredient grid only; the public ingredient filter remains untouched. */
public final class ShopJeiProductGridSource implements IIngredientGridSource {

    private final IIngredientGridSource delegate;

    public ShopJeiProductGridSource(IIngredientGridSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<IElement<?>> getElements() {
        if (!ClientShopJeiMode.isEnabled()) return delegate.getElements();
        IJeiRuntime runtime = com.dishanhai.gt_shanhai.client.ShanhaiJEIPlugin.getRuntime();
        if (runtime == null) return delegate.getElements();
        String filterText = runtime.getIngredientFilter().getFilterText();
        IIngredientType<FluidStack> fluidType = runtime.getIngredientManager()
                .getIngredientTypeChecked(FluidStack.class).orElse(null);
        return ShopJeiProductElements.filter(
                delegate.getElements(), runtime.getIngredientManager(), filterText, fluidType);
    }

    @Override
    public void addSourceListChangedListener(SourceListChangedListener listener) {
        delegate.addSourceListChangedListener(listener);
    }

    public static boolean isStandardJeiFilter(IIngredientGridSource source) {
        return source instanceof IngredientFilter;
    }
}
