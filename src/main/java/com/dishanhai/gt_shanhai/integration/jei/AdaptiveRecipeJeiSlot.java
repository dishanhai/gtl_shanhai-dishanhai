package com.dishanhai.gt_shanhai.integration.jei;

// Author: dishanhai

import com.lowdragmc.lowdraglib.gui.widget.Widget;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.inputs.RecipeSlotUnderMouse;
import mezz.jei.api.gui.widgets.ISlottedRecipeWidget;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenPosition;

import java.util.Optional;

/** JEI still indexes each ingredient, while only the on-screen LDLib slot supplies its hit box. */
public record AdaptiveRecipeJeiSlot(Widget widget, IRecipeSlotDrawable drawable) implements ISlottedRecipeWidget {
    private static final ScreenPosition ORIGIN = new ScreenPosition(0, 0);

    public static boolean isScrollableSlot(Widget widget) {
        return widget.getParent() != null && widget.getParent().getParent() instanceof AdaptiveRecipeUI.ScrollViewport;
    }

    @SuppressWarnings("deprecation")
    public static IRecipeSlotBuilder bind(IRecipeLayoutBuilder builder, RecipeIngredientRole role, Widget widget) {
        return builder.addSlotToWidget(role, (extras, recipe, slots) ->
                extras.addWidget(new AdaptiveRecipeJeiSlot(widget, slots.get(0))));
    }

    @Override
    public ScreenPosition getPosition() {
        return ORIGIN;
    }

    @Override
    public Optional<RecipeSlotUnderMouse> getSlotUnderMouse(double x, double y) {
        var viewport = widget.getParent().getParent();
        if (!widget.isMouseOverElement(x, y)
                || widget.getPositionY() < viewport.getPositionY()
                || widget.getPositionY() + widget.getSizeHeight() > viewport.getPositionY() + viewport.getSizeHeight()) {
            return Optional.empty();
        }
        drawable.setPosition(widget.getPositionX(), widget.getPositionY());
        return Optional.of(new RecipeSlotUnderMouse(drawable, ORIGIN));
    }

    @Override
    public void drawWidget(GuiGraphics graphics, double mouseX, double mouseY) {
        // The native LDLib renderer only updates the displayed JEI ingredient. LDLib draws the clipped slot itself.
        var viewport = widget.getParent().getParent();
        int y = widget.getPositionY();
        if (y < viewport.getPositionY() + viewport.getSizeHeight() && y + widget.getSizeHeight() > viewport.getPositionY()) {
            drawable.setPosition(widget.getPositionX(), y);
            drawable.draw(graphics);
        }
    }
}
