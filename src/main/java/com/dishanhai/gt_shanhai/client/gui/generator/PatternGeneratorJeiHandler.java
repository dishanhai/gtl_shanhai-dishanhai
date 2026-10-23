package com.dishanhai.gt_shanhai.client.gui.generator;

import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import java.util.List;

/** 山海：原生 Screen 需明確登記 JEI 畫面範圍與 ghost 目標。 */
public final class PatternGeneratorJeiHandler implements IGhostIngredientHandler<PatternGeneratorScreen> {
    private PatternGeneratorJeiHandler() {}
    public static void register(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(PatternGeneratorScreen.class,new PatternGeneratorJeiHandler());
        registration.addGuiScreenHandler(PatternGeneratorScreen.class,screen -> new IGuiProperties() {
            private final Rect2i rect=screen.panelBounds();
            public Class<? extends Screen> getScreenClass() { return PatternGeneratorScreen.class; }
            public int getGuiLeft() { return rect.getX(); }
            public int getGuiTop() { return rect.getY(); }
            public int getGuiXSize() { return rect.getWidth(); }
            public int getGuiYSize() { return rect.getHeight(); }
            public int getScreenWidth() { return screen.width; }
            public int getScreenHeight() { return screen.height; }
        });
    }
    @Override public <I> List<Target<I>> getTargetsTyped(PatternGeneratorScreen screen,ITypedIngredient<I> ingredient,boolean start) {
        var stack=GeneratorGhostSlot.identify(ingredient.getIngredient());
        if (stack==null) return List.of();
        return screen.ghostTargets(stack).stream().map(target -> (Target<I>)new Target<I>() {
            @Override public Rect2i getArea() { return target.area(); }
            @Override public void accept(I accepted) {
                var value=GeneratorGhostSlot.identify(accepted);
                if (value!=null) target.accept().accept(value);
            }
        }).toList();
    }
    @Override public void onComplete() {}
}
