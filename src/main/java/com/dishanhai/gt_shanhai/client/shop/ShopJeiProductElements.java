package com.dishanhai.gt_shanhai.client.shop;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.gui.overlay.elements.IElement;
import mezz.jei.gui.overlay.elements.IngredientElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reuses JEI's filtered/sorted ingredient list as the order for shop-only results. */
public final class ShopJeiProductElements {

    private static List<IElement<?>> lastJeiElements;
    private static List<IElement<?>> lastResult = List.of();
    private static IIngredientManager lastIngredientManager;
    private static IIngredientType<FluidStack> lastFluidType;
    private static long lastProductRevision = Long.MIN_VALUE;
    private static String lastFilterText;

    private ShopJeiProductElements() {}

    public static List<IElement<?>> filter(List<IElement<?>> jeiElements,
                                           IIngredientManager ingredientManager, String filterText,
                                           IIngredientType<FluidStack> fluidType) {
        long productRevision = ShopJeiProductCatalog.revision();
        if (jeiElements == lastJeiElements && ingredientManager == lastIngredientManager
                && fluidType == lastFluidType
                && productRevision == lastProductRevision && Objects.equals(filterText, lastFilterText)) {
            return lastResult;
        }
        List<ItemStack> products = ShopJeiProductCatalog.stacks();
        List<FluidStack> fluidProducts = ShopJeiProductCatalog.fluids();
        if (fluidType == null) fluidProducts = List.of();
        if (jeiElements == null || (products.isEmpty() && fluidProducts.isEmpty()) || ingredientManager == null) {
            updateCache(jeiElements, ingredientManager, fluidType, productRevision, filterText, List.of());
            return List.of();
        }

        Map<ResourceLocation, List<ItemStack>> productsById = new LinkedHashMap<>();
        for (ItemStack product : products) {
            if (product == null || product.isEmpty()) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(product.getItem());
            if (id != null) productsById.computeIfAbsent(id, ignored -> new ArrayList<>()).add(product);
        }
        Map<ResourceLocation, List<FluidStack>> fluidsById = new LinkedHashMap<>();
        for (FluidStack product : fluidProducts) {
            if (product == null || product.isEmpty()) continue;
            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(product.getFluid());
            if (id != null) fluidsById.computeIfAbsent(id, ignored -> new ArrayList<>()).add(product);
        }
        if (productsById.isEmpty() && fluidsById.isEmpty()) {
            updateCache(jeiElements, ingredientManager, fluidType, productRevision, filterText, List.of());
            return List.of();
        }
        Comparator<ItemStack> productOrder = itemComparator(ingredientManager);
        for (List<ItemStack> variants : productsById.values()) variants.sort(productOrder);
        Comparator<FluidStack> fluidOrder = fluidComparator(ingredientManager);
        for (List<FluidStack> variants : fluidsById.values()) variants.sort(fluidOrder);

        Set<ResourceLocation> emittedItemIds = new HashSet<>();
        Set<ResourceLocation> emittedFluidIds = new HashSet<>();
        List<IElement<?>> result = new ArrayList<>();
        for (IElement<?> element : jeiElements) {
            ItemStack item = element.getTypedIngredient().getIngredient(VanillaTypes.ITEM_STACK).orElse(null);
            if (item != null) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(item.getItem());
                List<ItemStack> matches = productsById.get(id);
                if (matches != null && emittedItemIds.add(id)) addTypedItemProducts(result, matches, ingredientManager);
            }
            FluidStack fluid = fluidType == null ? null
                    : element.getTypedIngredient().getIngredient(fluidType).orElse(null);
            if (fluid != null) {
                ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid.getFluid());
                List<FluidStack> matches = fluidsById.get(id);
                if (matches != null && emittedFluidIds.add(id)) {
                    addTypedFluidProducts(result, matches, ingredientManager, fluidType);
                }
            }
        }

        if (filterText == null || filterText.isBlank()) {
            List<ItemStack> unlistedItems = new ArrayList<>();
            for (Map.Entry<ResourceLocation, List<ItemStack>> entry : productsById.entrySet()) {
                if (!emittedItemIds.contains(entry.getKey())) unlistedItems.addAll(entry.getValue());
            }
            unlistedItems.sort(productOrder);
            addTypedItemProducts(result, unlistedItems, ingredientManager);

            List<FluidStack> unlistedFluids = new ArrayList<>();
            for (Map.Entry<ResourceLocation, List<FluidStack>> entry : fluidsById.entrySet()) {
                if (!emittedFluidIds.contains(entry.getKey())) unlistedFluids.addAll(entry.getValue());
            }
            unlistedFluids.sort(fluidOrder);
            addTypedFluidProducts(result, unlistedFluids, ingredientManager, fluidType);
        }
        List<IElement<?>> immutable = List.copyOf(result);
        updateCache(jeiElements, ingredientManager, fluidType, productRevision, filterText, immutable);
        return immutable;
    }

    public static void clearCache() {
        lastJeiElements = null;
        lastResult = List.of();
        lastIngredientManager = null;
        lastFluidType = null;
        lastProductRevision = Long.MIN_VALUE;
        lastFilterText = null;
    }

    private static void updateCache(List<IElement<?>> jeiElements, IIngredientManager ingredientManager,
                                   IIngredientType<FluidStack> fluidType, long productRevision,
                                   String filterText, List<IElement<?>> result) {
        lastJeiElements = jeiElements;
        lastIngredientManager = ingredientManager;
        lastFluidType = fluidType;
        lastProductRevision = productRevision;
        lastFilterText = filterText;
        lastResult = result;
    }

    private static void addTypedItemProducts(List<IElement<?>> target, List<ItemStack> stacks,
                                             IIngredientManager ingredientManager) {
        for (ItemStack stack : stacks) {
            ingredientManager.createTypedIngredient(VanillaTypes.ITEM_STACK, stack.copy())
                    .ifPresent(typed -> target.add(new IngredientElement<>(typed)));
        }
    }

    private static void addTypedFluidProducts(List<IElement<?>> target, List<FluidStack> stacks,
                                              IIngredientManager ingredientManager,
                                              IIngredientType<FluidStack> fluidType) {
        for (FluidStack stack : stacks) {
            ingredientManager.createTypedIngredient(fluidType, stack.copy())
                    .ifPresent(typed -> target.add(new IngredientElement<>(typed)));
        }
    }

    private static Comparator<ItemStack> itemComparator(IIngredientManager ingredientManager) {
        return Comparator
                .comparing((ItemStack stack) -> ingredientManager.getIngredientHelper(stack)
                        .getDisplayName(stack).toLowerCase(Locale.ROOT))
                .thenComparing(stack -> {
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                    return id == null ? "" : id.toString();
                })
                .thenComparing(stack -> stack.getTag() == null ? "" : stack.getTag().toString());
    }

    private static Comparator<FluidStack> fluidComparator(IIngredientManager ingredientManager) {
        return Comparator
                .comparing((FluidStack stack) -> ingredientManager.getIngredientHelper(stack)
                        .getDisplayName(stack).toLowerCase(Locale.ROOT))
                .thenComparing(stack -> {
                    ResourceLocation id = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
                    return id == null ? "" : id.toString();
                });
    }
}
