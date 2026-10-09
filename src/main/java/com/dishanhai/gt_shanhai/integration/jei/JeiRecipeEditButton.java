package com.dishanhai.gt_shanhai.integration.jei;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.item.RecipeModifierDevItem;
import com.dishanhai.gt_shanhai.network.RecipeEditorJeiOpenPacket;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeWrapper;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.buttons.IButtonState;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;
import mezz.jei.api.registration.IAdvancedRegistration;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public final class JeiRecipeEditButton {

    private JeiRecipeEditButton() {}

    public static void register(IAdvancedRegistration registration) {
        IGuiHelper guiHelper = registration.getJeiHelpers().getGuiHelper();
        IDrawable icon = guiHelper.createDrawableItemStack(
                new ItemStack(GTDishanhaiMod.RECIPE_MODIFIER_DEV.get()));
        registration.addRecipeButtonFactory(new Factory(icon));
    }

    private static String[] targetOf(Object recipeBase) {
        GTRecipe recipe = null;
        if (recipeBase instanceof GTRecipe gt) {
            recipe = gt;
        } else if (recipeBase instanceof GTRecipeWrapper wrapper) {
            recipe = wrapper.recipe;
        }
        if (recipe == null || recipe.getId() == null
                || recipe.recipeType == null || recipe.recipeType.registryName == null) {
            return null;
        }
        return new String[] {
                recipe.recipeType.registryName.toString(),
                recipe.getId().toString()
        };
    }

    private record Factory(IDrawable icon) implements IRecipeButtonControllerFactory {
        @Override
        public <T> IIconButtonController createButtonController(IRecipeLayoutDrawable<T> recipeLayout) {
            String[] target = targetOf(recipeLayout.getRecipe());
            if (target == null) return null;
            return new Controller(icon, target[0], target[1]);
        }
    }

    private record Controller(IDrawable icon, String recipeTypeId, String recipeId)
            implements IIconButtonController {
        @Override
        public void initState(IButtonState state) {
            state.setIcon(icon);
            updateState(state);
        }

        @Override
        public void updateState(IButtonState state) {
            Minecraft minecraft = Minecraft.getInstance();
            boolean held = minecraft.player != null && RecipeModifierDevItem.inInventory(minecraft.player);
            state.setVisible(held);
            state.setActive(held);
        }

        @Override
        public boolean onPress(IJeiUserInput input) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || !RecipeModifierDevItem.inInventory(minecraft.player)) {
                if (!input.isSimulate() && minecraft.player != null) {
                    minecraft.player.displayClientMessage(Component.translatable(
                            "message.gt_shanhai.jei.edit_recipe.need_tool"), false);
                }
                return false;
            }
            if (!minecraft.player.hasPermissions(2)) {
                if (!input.isSimulate()) {
                    minecraft.player.displayClientMessage(Component.translatable(
                            "message.gt_shanhai.jei.edit_recipe.need_permission"), false);
                }
                return false;
            }
            if (!input.isSimulate()) {
                ShanhaiNetwork.CHANNEL.sendToServer(
                        new RecipeEditorJeiOpenPacket(recipeTypeId, recipeId));
            }
            return true;
        }

        @Override
        public void getTooltips(ITooltipBuilder tooltip) {
            tooltip.add(Component.translatable("tooltip.gt_shanhai.jei.edit_recipe"));
        }
    }
}
