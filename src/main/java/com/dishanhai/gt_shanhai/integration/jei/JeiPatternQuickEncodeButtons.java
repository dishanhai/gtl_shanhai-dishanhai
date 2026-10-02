package com.dishanhai.gt_shanhai.integration.jei;

import appeng.core.definitions.AEItems;
import appeng.menu.me.items.PatternEncodingTermMenu;

import com.dishanhai.gt_shanhai.network.JeiPatternQuickEncodeRequestPacket;
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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class JeiPatternQuickEncodeButtons {

    private JeiPatternQuickEncodeButtons() {}

    public static void register(IAdvancedRegistration registration) {
        IGuiHelper guiHelper = registration.getJeiHelpers().getGuiHelper();
        IDrawable patternIcon = guiHelper.createDrawableItemStack(AEItems.PROCESSING_PATTERN.stack());
        registration.addRecipeButtonFactory(new Factory(patternIcon, false));
        registration.addRecipeButtonFactory(new Factory(patternIcon, true));
    }

    private static GTRecipe extractRecipe(Object recipeBase) {
        if (recipeBase instanceof GTRecipe recipe) {
            return recipe;
        }
        if (recipeBase instanceof GTRecipeWrapper wrapper) {
            return wrapper.recipe;
        }
        return null;
    }

    private record Factory(IDrawable icon, boolean wholeRecipeType)
            implements IRecipeButtonControllerFactory {

        @Override
        public <T> IIconButtonController createButtonController(IRecipeLayoutDrawable<T> recipeLayout) {
            GTRecipe recipe = extractRecipe(recipeLayout.getRecipe());
            if (recipe == null || recipe.id == null || recipe.recipeType == null
                    || recipe.recipeType.registryName == null) {
                return null;
            }
            return new Controller(icon, recipe.id, wholeRecipeType);
        }
    }

    private record Controller(IDrawable icon, ResourceLocation recipeId, boolean wholeRecipeType)
            implements IIconButtonController {

        @Override
        public void initState(IButtonState state) {
            state.setIcon(icon);
            updateState(state);
        }

        @Override
        public void updateState(IButtonState state) {
            Minecraft minecraft = Minecraft.getInstance();
            boolean available = minecraft.player != null
                    && minecraft.player.containerMenu instanceof PatternEncodingTermMenu;
            state.setVisible(available);
            state.setActive(available);
        }

        @Override
        public boolean onPress(IJeiUserInput input) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null
                    || !(minecraft.player.containerMenu instanceof PatternEncodingTermMenu menu)) {
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
            if (!wholeRecipeType) return;
            graphics.drawString(Minecraft.getInstance().font, "+",
                    area.getX() + area.getWidth() - 6,
                    area.getY() + area.getHeight() - 8,
                    0xFFFFFF, true);
        }
    }
}
