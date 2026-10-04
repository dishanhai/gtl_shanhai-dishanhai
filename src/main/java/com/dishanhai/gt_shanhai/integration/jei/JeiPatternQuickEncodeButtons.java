package com.dishanhai.gt_shanhai.integration.jei;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.core.definitions.AEItems;
import appeng.integration.modules.jei.GenericEntryStackHelper;
import appeng.integration.modules.jeirei.TransferHelper;
import appeng.menu.me.items.PatternEncodingTermMenu;

import com.dishanhai.gt_shanhai.client.ShanhaiJEIPlugin;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig.ConfigValues.JeiBookmarkMode;
import com.dishanhai.gt_shanhai.jei.JeiBookmarkBridge;
import com.dishanhai.gt_shanhai.network.JeiPatternQuickEncodeRequestPacket;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeWrapper;

import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.buttons.IButtonState;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IRecipeLookup;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.helpers.IGuiHelper;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class JeiPatternQuickEncodeButtons {

    private JeiPatternQuickEncodeButtons() {}

    public static void register(IAdvancedRegistration registration) {
        IGuiHelper guiHelper = registration.getJeiHelpers().getGuiHelper();
        IDrawable patternIcon = guiHelper.createDrawableItemStack(AEItems.PROCESSING_PATTERN.stack());
        registration.addRecipeButtonFactory(new Factory(patternIcon, false));
        registration.addRecipeButtonFactory(new Factory(patternIcon, true));
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

    private record Controller(IDrawable icon, ResourceLocation recipeId, boolean wholeRecipeType,
                              IRecipeLayoutDrawable<?> recipeLayout)
            implements IIconButtonController {

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
                ShanhaiNetwork.CHANNEL.sendToServer(new JeiPatternQuickEncodeRequestPacket(
                        menu.containerId, recipeId.toString(), wholeRecipeType));
            }
            return true;
        }

        @Override
        public void getTooltips(ITooltipBuilder tooltip) {
            tooltip.add(Component.translatable(wholeRecipeType
                    ? "tooltip.gt_shanhai.jei.quick_encode_recipe_type"
                    : "tooltip.gt_shanhai.jei.quick_encode_pattern"));
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
            return new BookmarkController(icon, recipeLayout);
        }
    }

    private record BookmarkController(IDrawable icon, IRecipeLayoutDrawable<?> recipeLayout)
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
            List<ItemStack> inputs = collectDisplayedInputs(recipeLayout);
            List<ItemStack> selected = filterInputs(inputs);
            if (input.isSimulate()) return !selected.isEmpty();
            Minecraft minecraft = Minecraft.getInstance();
            if (selected.isEmpty()) {
                if (minecraft.player != null) {
                    minecraft.player.displayClientMessage(Component.translatable(
                            "message.gt_shanhai.jei.bookmark.no_items"), false);
                }
                return false;
            }
            int added = JeiBookmarkBridge.addItemStacks(selected);
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.translatable(
                        added > 0
                                ? "message.gt_shanhai.jei.bookmark.added"
                                : "message.gt_shanhai.jei.bookmark.already_bookmarked",
                        selected.size(), added), false);
            }
            return added > 0;
        }

        @Override
        public void getTooltips(ITooltipBuilder tooltip) {
            JeiBookmarkMode mode = DShanhaiConfig.COMMON.jeiBookmarkMode.get();
            tooltip.add(Component.translatable(mode == JeiBookmarkMode.NO_RECIPE_ITEMS
                    ? "tooltip.gt_shanhai.jei.bookmark_no_recipe"
                    : "tooltip.gt_shanhai.jei.bookmark_missing"));
        }

        @Override
        public void drawExtras(GuiGraphics graphics, Rect2i area, int mouseX, int mouseY, float partialTick) {}
    }

    private static List<ItemStack> collectDisplayedInputs(IRecipeLayoutDrawable<?> recipeLayout) {
        List<ItemStack> result = new ArrayList<>();
        for (var slot : recipeLayout.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.INPUT)) {
            Optional<ItemStack> displayed = slot.getDisplayedItemStack();
            if (displayed.isPresent() && !displayed.get().isEmpty()) {
                result.add(displayed.get().copy());
            }
        }
        return result;
    }

    private static List<ItemStack> filterInputs(List<ItemStack> inputs) {
        List<ItemStack> unique = new ArrayList<>();
        for (ItemStack stack : inputs) {
            if (containsSame(unique, stack)) continue;
            unique.add(stack);
        }
        if (DShanhaiConfig.COMMON.jeiBookmarkMode.get() == JeiBookmarkMode.NO_RECIPE_ITEMS) {
            List<ItemStack> result = new ArrayList<>();
            for (ItemStack stack : unique) {
                if (!hasRecipeProcess(stack)) result.add(stack);
            }
            return result;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return unique;
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : unique) {
            int required = 0;
            for (ItemStack input : inputs) {
                if (ItemStack.isSameItemSameTags(stack, input)) required += Math.max(1, input.getCount());
            }
            if (countInInventory(minecraft.player, stack) < required) result.add(stack);
        }
        return result;
    }

    private static boolean containsSame(List<ItemStack> stacks, ItemStack candidate) {
        for (ItemStack stack : stacks) {
            if (ItemStack.isSameItemSameTags(stack, candidate)) return true;
        }
        return false;
    }

    private static int countInInventory(net.minecraft.world.entity.player.Player player, ItemStack wanted) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (ItemStack.isSameItemSameTags(stack, wanted)) count += stack.getCount();
        }
        return count;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean hasRecipeProcess(ItemStack stack) {
        IJeiRuntime runtime = ShanhaiJEIPlugin.getRuntime();
        if (runtime == null) return false;
        IFocus<?> focus;
        try {
            focus = runtime.getJeiHelpers().getFocusFactory()
                    .createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, stack);
        } catch (Throwable ignored) {
            return false;
        }
        Collection<IFocus<?>> focuses = List.of(focus);
        for (var recipeType : runtime.getJeiHelpers().getAllRecipeTypes().toList()) {
            try {
                IRecipeLookup lookup = runtime.getRecipeManager().createRecipeLookup(recipeType)
                        .limitFocus(focuses);
                if (lookup.get().findAny().isPresent()) return true;
            } catch (Throwable ignored) {
                // 某個第三方配方類型失敗時繼續檢查其他類型，避免誤判整體沒有流程。
            }
        }
        return false;
    }
}
