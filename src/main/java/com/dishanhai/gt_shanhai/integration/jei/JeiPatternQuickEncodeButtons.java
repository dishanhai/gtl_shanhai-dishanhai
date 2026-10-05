package com.dishanhai.gt_shanhai.integration.jei;

import appeng.api.stacks.GenericStack;
import appeng.core.definitions.AEItems;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.me.items.PatternEncodingTermMenu;

import com.dishanhai.gt_shanhai.client.ShanhaiJEIPlugin;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig.ConfigValues.JeiBookmarkMode;
import com.dishanhai.gt_shanhai.network.JeiBookmarkMissingItemsRequestPacket;
import com.dishanhai.gt_shanhai.network.JeiPatternQuickEncodeRequestPacket;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeWrapper;

import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.buttons.IButtonState;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.common.transfer.RecipeTransferUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class JeiPatternQuickEncodeButtons {

    private JeiPatternQuickEncodeButtons() {}

    public static void register(IAdvancedRegistration registration) {
        IGuiHelper guiHelper = registration.getJeiHelpers().getGuiHelper();
        IDrawable patternIcon = guiHelper.createDrawableItemStack(AEItems.PROCESSING_PATTERN.stack());
        registration.addRecipeButtonFactory(new Factory(patternIcon, false));
        IDrawable recipeTypeIcon = guiHelper.createDrawableItemStack(AEItems.CRAFTING_PATTERN.stack());
        registration.addRecipeButtonFactory(new Factory(recipeTypeIcon, true));
        IDrawable bookmarkIcon = guiHelper.createDrawableItemStack(new ItemStack(Items.BOOK));
        registration.addRecipeButtonFactory(new BookmarkFactory(bookmarkIcon));
    }

    private static ResourceLocation extractRecipeId(Object recipeBase) {
        if (recipeBase instanceof GTRecipe recipe) {
            return recipe.id;
        }
        if (recipeBase instanceof GTRecipeWrapper wrapper) {
            return wrapper.recipe == null ? null : wrapper.recipe.id;
        }
        if (recipeBase instanceof Recipe<?> recipe) {
            return recipe.getId();
        }
        return null;
    }

    private static <T> ResourceLocation extractRecipeId(IRecipeLayoutDrawable<T> recipeLayout) {
        T recipe = recipeLayout.getRecipe();
        try {
            ResourceLocation categoryRecipeId = recipeLayout.getRecipeCategory().getRegistryName(recipe);
            if (categoryRecipeId != null) {
                return categoryRecipeId;
            }
        } catch (RuntimeException ignored) {
            // 个别第三方分类的包装器可能无法读取 ID，继续使用兼容性解析。
        }
        return extractRecipeId(recipe);
    }

    private record Factory(IDrawable icon, boolean wholeRecipeType)
            implements IRecipeButtonControllerFactory {

        @Override
        public <T> IIconButtonController createButtonController(IRecipeLayoutDrawable<T> recipeLayout) {
            ResourceLocation recipeId = extractRecipeId(recipeLayout);
            if (recipeId == null) {
                return null;
            }
            return new Controller(icon, recipeId, wholeRecipeType, recipeLayout);
        }
    }

    private static final class Controller implements IIconButtonController {

        private static final long CONFIRMATION_WINDOW_MILLIS = 3000L;

        private final IDrawable icon;
        private final ResourceLocation recipeId;
        private final boolean wholeRecipeType;
        private final IRecipeLayoutDrawable<?> recipeLayout;
        private long confirmationExpiresAt;

        private Controller(IDrawable icon, ResourceLocation recipeId, boolean wholeRecipeType,
                           IRecipeLayoutDrawable<?> recipeLayout) {
            this.icon = icon;
            this.recipeId = recipeId;
            this.wholeRecipeType = wholeRecipeType;
            this.recipeLayout = recipeLayout;
        }

        private boolean confirmationPending() {
            return wholeRecipeType && System.currentTimeMillis() < confirmationExpiresAt;
        }

        private void armConfirmation() {
            confirmationExpiresAt = System.currentTimeMillis() + CONFIRMATION_WINDOW_MILLIS;
        }

        private void clearConfirmation() {
            confirmationExpiresAt = 0L;
        }

        @Override
        public void initState(IButtonState state) {
            state.setIcon(icon);
            updateState(state);
        }

        @Override
        public void updateState(IButtonState state) {
            // JEI 配方页始终显示入口；只有打开样板编码终端时才允许真正发送请求。
            state.setVisible(true);
            state.setActive(true);
        }

        @Override
        public boolean onPress(IJeiUserInput input) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null
                    || !(minecraft.player.containerMenu instanceof PatternEncodingTermMenu menu)) {
                if (!input.isSimulate() && minecraft.player != null) {
                    minecraft.player.displayClientMessage(Component.translatable(
                            "message.gt_shanhai.jei.quick_encode.open_terminal"), false);
                }
                return false;
            }
            if (!input.isSimulate()) {
                if (wholeRecipeType && !confirmationPending()) {
                    armConfirmation();
                    minecraft.player.displayClientMessage(Component.translatable(
                            "message.gt_shanhai.jei.quick_encode.recipe_type_confirm"), false);
                    return true;
                }
                clearConfirmation();
                ShanhaiNetwork.CHANNEL.sendToServer(new JeiPatternQuickEncodeRequestPacket(
                        menu.containerId, recipeId.toString(), wholeRecipeType));
            }
            return true;
        }

        @Override
        public void getTooltips(ITooltipBuilder tooltip) {
            if (wholeRecipeType && confirmationPending()) {
                tooltip.add(Component.translatable(
                        "tooltip.gt_shanhai.jei.quick_encode_recipe_type_confirm"));
            } else {
                tooltip.add(Component.translatable(wholeRecipeType
                        ? "tooltip.gt_shanhai.jei.quick_encode_recipe_type"
                        : "tooltip.gt_shanhai.jei.quick_encode_pattern"));
            }
        }

        @Override
        public void drawExtras(GuiGraphics graphics, Rect2i area, int mouseX, int mouseY, float partialTick) {
            if (mouseX >= area.getX()
                    && mouseX < area.getX() + area.getWidth()
                    && mouseY >= area.getY()
                    && mouseY < area.getY() + area.getHeight()) {
                drawNativeTransferError(graphics, mouseX, mouseY);
            }
            if (wholeRecipeType) {
                graphics.drawString(Minecraft.getInstance().font, "+",
                        area.getX() + area.getWidth() - 6,
                        area.getY() + area.getHeight() - 8,
                        0xFFFFFF, true);
            }
        }

        private void drawNativeTransferError(GuiGraphics graphics, int mouseX, int mouseY) {
            Minecraft minecraft = Minecraft.getInstance();
            if (!(minecraft.player != null
                    && minecraft.player.containerMenu instanceof PatternEncodingTermMenu menu)) {
                return;
            }
            IJeiRuntime runtime = ShanhaiJEIPlugin.getRuntime();
            if (runtime == null) {
                return;
            }

            IRecipeTransferError transferError = RecipeTransferUtil.getTransferRecipeError(
                    runtime.getRecipeTransferManager(), menu, recipeLayout, minecraft.player).orElse(null);
            if (transferError == null || transferError.getType() != IRecipeTransferError.Type.COSMETIC) {
                return;
            }

            Rect2i recipeRect = recipeLayout.getRect();
            graphics.pose().pushPose();
            try {
                transferError.showError(graphics, mouseX, mouseY, recipeLayout.getRecipeSlotsView(),
                        recipeRect.getX(), recipeRect.getY());
            } finally {
                graphics.pose().popPose();
            }
        }
    }

    private record BookmarkFactory(IDrawable icon) implements IRecipeButtonControllerFactory {
        @Override
        public <T> IIconButtonController createButtonController(IRecipeLayoutDrawable<T> recipeLayout) {
            return new BookmarkController(icon, extractRecipeId(recipeLayout), recipeLayout);
        }
    }

    private record BookmarkController(IDrawable icon, ResourceLocation recipeId,
                                      IRecipeLayoutDrawable<?> recipeLayout)
            implements IIconButtonController {
        @Override
        public void initState(IButtonState state) {
            state.setIcon(icon);
            updateState(state);
        }

        @Override
        public void updateState(IButtonState state) {
            state.setVisible(true);
            state.setActive(true);
        }

        @Override
        public boolean onPress(IJeiUserInput input) {
            List<GenericStack> inputs = collectDisplayedInputs(recipeLayout);
            JeiBookmarkMode mode = DShanhaiConfig.COMMON.jeiBookmarkMode.get();
            if (input.isSimulate()) return !inputs.isEmpty();
            Minecraft minecraft = Minecraft.getInstance();
            if (inputs.isEmpty()) {
                if (minecraft.player != null) {
                    minecraft.player.displayClientMessage(Component.translatable(
                            "message.gt_shanhai.jei.bookmark.no_items"), false);
                }
                return false;
            }
            if (minecraft.player == null
                    || !(minecraft.player.containerMenu instanceof MEStorageMenu menu)) {
                if (minecraft.player != null) {
                    minecraft.player.displayClientMessage(Component.translatable(
                            "message.gt_shanhai.jei.bookmark.open_terminal"), false);
                }
                return false;
            }
            int menuId = menu.containerId;
            String id = recipeId == null ? "" : recipeId.toString();
            GTDishanhaiMod.LOGGER.info("[JEIBookmarkDiag] request recipe={} mode={} inputs={}",
                    id, mode, inputs.size());
            ShanhaiNetwork.CHANNEL.sendToServer(
                    new JeiBookmarkMissingItemsRequestPacket(menuId, id, mode, mergeInputs(inputs)));
            return true;
        }

        @Override
        public void getTooltips(ITooltipBuilder tooltip) {
            JeiBookmarkMode mode = DShanhaiConfig.COMMON.jeiBookmarkMode.get();
            String key;
            if (mode == JeiBookmarkMode.NO_RECIPE_ITEMS) {
                key = "tooltip.gt_shanhai.jei.bookmark_no_recipe";
            } else if (mode == JeiBookmarkMode.COMBINED) {
                key = "tooltip.gt_shanhai.jei.bookmark_combined";
            } else {
                key = "tooltip.gt_shanhai.jei.bookmark_missing";
            }
            tooltip.add(Component.translatable(key));
        }

        @Override
        public void drawExtras(GuiGraphics graphics, Rect2i area, int mouseX, int mouseY, float partialTick) {}
    }

    private static List<GenericStack> collectDisplayedInputs(IRecipeLayoutDrawable<?> recipeLayout) {
        List<GenericStack> result = new ArrayList<>();
        IIngredientType<FluidStack> fluidType = getFluidIngredientType();
        for (IRecipeSlotView slot : recipeLayout.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.INPUT)) {
            Optional<ITypedIngredient<?>> displayed = slot.getDisplayedIngredient();
            if (displayed.isEmpty()) continue;
            Object ingredient = displayed.get().getIngredient();
            if (ingredient instanceof ItemStack itemStack && !itemStack.isEmpty()) {
                GenericStack stack = GenericStack.fromItemStack(itemStack.copy());
                if (stack != null && stack.what() != null && stack.amount() > 0) result.add(stack);
            } else if (ingredient instanceof FluidStack fluidStack
                    && !fluidStack.isEmpty()
                    && (fluidType == null || displayed.get().getType() == fluidType)) {
                GenericStack stack = GenericStack.fromFluidStack(fluidStack.copy());
                if (stack != null && stack.what() != null && stack.amount() > 0) result.add(stack);
            }
        }
        return result;
    }

    private static List<GenericStack> mergeInputs(List<GenericStack> inputs) {
        Map<appeng.api.stacks.AEKey, Long> amounts = new LinkedHashMap<>();
        for (GenericStack input : inputs) {
            if (input == null || input.what() == null || input.amount() <= 0) continue;
            Long previous = amounts.get(input.what());
            long next = previous == null ? input.amount() : saturatingAdd(previous, input.amount());
            amounts.put(input.what(), next);
        }
        List<GenericStack> result = new ArrayList<>(amounts.size());
        for (Map.Entry<appeng.api.stacks.AEKey, Long> entry : amounts.entrySet()) {
            result.add(new GenericStack(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private static long saturatingAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
        return left + right;
    }

    @SuppressWarnings("unchecked")
    private static IIngredientType<FluidStack> getFluidIngredientType() {
        IJeiRuntime runtime = ShanhaiJEIPlugin.getRuntime();
        if (runtime != null) {
            try {
                return (IIngredientType<FluidStack>) runtime.getJeiHelpers()
                        .getPlatformFluidHelper().getFluidIngredientType();
            } catch (Throwable ignored) {
                // JEI helper API 变化时回退到当前版本的 Forge 类型常量。
            }
        }
        return ForgeTypes.FLUID_STACK;
    }
}
