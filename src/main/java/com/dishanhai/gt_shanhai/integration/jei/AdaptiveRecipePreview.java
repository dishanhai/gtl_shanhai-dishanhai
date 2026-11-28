package com.dishanhai.gt_shanhai.integration.jei;

// Author: dishanhai

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeTypeCategory;
import com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeWrapper;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.jei.JEIPlugin;
import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.buttons.IButtonState;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;
import mezz.jei.api.registration.IAdvancedRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** The fullscreen view has its own widgets and keeps the parent JEI page and scroll position. */
public final class AdaptiveRecipePreview extends Screen {
    private static final int HEADER_HEIGHT = 30;
    private final Screen parent;
    private final GTRecipe recipe;
    private GTRecipeWrapper wrapper;
    private IRecipeLayoutDrawable<GTRecipeWrapper> layout;

    private AdaptiveRecipePreview(Screen parent, GTRecipe recipe, Component title) {
        super(title);
        this.parent = parent;
        this.recipe = recipe;
    }

    public static void register(IAdvancedRegistration registration) {
        var icon = new RecipeButtonSvgIcon(RecipeButtonSvgMesh.loadResource(
                "/assets/gt_shanhai/textures/gui/star_lifter/fullscreen.svg"));
        registration.addRecipeButtonFactory(new PreviewFactory(icon));
    }

    @Override
    protected void init() {
        var runtime = JEIPlugin.jeiRuntime;
        var helpers = runtime.getJeiHelpers();
        wrapper = new GTRecipeWrapper(recipe);
        AdaptiveRecipeUI.expand((WidgetGroup) wrapper.getWidget(), recipe, width, height, HEADER_HEIGHT);
        var category = new GTRecipeTypeCategory(helpers, recipe.recipeType) {
            @Override public int getWidth() { return width; }
            @Override public int getHeight() { return height; }
        };
        layout = runtime.getRecipeManager().createRecipeLayoutDrawableOrShowError(
                category, wrapper, helpers.getFocusFactory().getEmptyFocusGroup());
        int buttonWidth = Math.max(50, font.width(CommonComponents.GUI_DONE) + 16);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(width - buttonWidth - 8, 5, buttonWidth, 20).build());
    }

    @Override
    public void tick() {
        layout.tick();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        layout.drawRecipe(graphics, mouseX, mouseY);
        graphics.fill(0, 0, width, HEADER_HEIGHT - 1, 0xb7111929);
        graphics.fill(0, HEADER_HEIGHT - 1, width, HEADER_HEIGHT, 0xff485a73);
        graphics.drawString(font, getTitle(), 10, 11, 0xfff2f5fc);
        super.render(graphics, mouseX, mouseY, partialTick);
        layout.drawOverlays(graphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        return wrapper.mouseScrolled(x, y, delta) || super.mouseScrolled(x, y, delta);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return super.mouseClicked(x, y, button) || wrapper.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return super.mouseDragged(x, y, button, dx, dy) || wrapper.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        return wrapper.mouseReleased(x, y, button) || super.mouseReleased(x, y, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) return true;
        var runtime = JEIPlugin.jeiRuntime;
        var key = InputConstants.getKey(keyCode, scanCode);
        var mappings = runtime.getKeyMappings();
        var role = mappings.getShowRecipe().isActiveAndMatches(key) ? RecipeIngredientRole.OUTPUT
                : mappings.getShowUses().isActiveAndMatches(key) ? RecipeIngredientRole.INPUT : null;
        if (role != null) {
            var window = minecraft.getWindow();
            double x = minecraft.mouseHandler.xpos() * width / window.getScreenWidth();
            double y = minecraft.mouseHandler.ypos() * height / window.getScreenHeight();
            var ingredient = layout.getRecipeSlotUnderMouse(x, y).flatMap(slot -> slot.getDisplayedIngredient());
            if (ingredient.isPresent()) {
                onClose();
                runtime.getRecipesGui().show(runtime.getJeiHelpers().getFocusFactory().createFocus(role, ingredient.get()));
                return true;
            }
        }
        return wrapper.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void onClose() { minecraft.setScreen(parent); }

    private record PreviewFactory(IDrawable icon) implements IRecipeButtonControllerFactory {
        @Override
        public <T> IIconButtonController createButtonController(IRecipeLayoutDrawable<T> layout) {
            if (!(layout.getRecipe() instanceof GTRecipeWrapper wrapper) || !AdaptiveRecipeUI.hasAdaptiveCard(wrapper.getWidget())) {
                return null;
            }
            return new PreviewController(icon, wrapper.recipe, layout.getRecipeCategory().getTitle());
        }
    }

    private record PreviewController(IDrawable icon, GTRecipe recipe, Component title) implements IIconButtonController {
        @Override public void initState(IButtonState state) { state.setIcon(icon); }

        @Override public boolean onPress(IJeiUserInput input) {
            if (!input.isSimulate()) {
                var minecraft = Minecraft.getInstance();
                minecraft.setScreen(new AdaptiveRecipePreview(minecraft.screen, recipe, title));
            }
            return true;
        }

        @Override public void getTooltips(ITooltipBuilder tooltip) {
            tooltip.add(Component.translatable(AdaptiveRecipeUI.TEXT + "fullscreen"));
        }
    }
}
