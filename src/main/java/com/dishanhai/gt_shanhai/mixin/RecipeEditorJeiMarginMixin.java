package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorWidget;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import com.lowdragmc.lowdraglib.utils.Position;
import java.lang.reflect.Field;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Slides the recipe editor left when the right side is too narrow for JEI's search field.
 * The position fields live on AbstractContainerScreen. This mixin config has no refmap, and
 * a shadow is only matched on the target class, so the fields are written by name instead.
 */
@Mixin(ModularUIGuiContainer.class)
public class RecipeEditorJeiMarginMixin {

    @Shadow(remap = false)
    public ModularUI modularUI;

    @Inject(method = "m_7856_", at = @At("RETURN"), remap = false)
    private void gtShanhai$reserveJeiSearch(CallbackInfo ci) {
        if (this.modularUI == null || this.modularUI.getFirstWidgetById("shanhai_recipe_editor") == null) {
            return;
        }
        Screen screen = (Screen) (Object) this;
        int left = ShanhaiRecipeEditorWidget.jeiLeft(screen.width, this.modularUI.getWidth());
        int top = Math.max(0, (screen.height - this.modularUI.getHeight()) / 2);
        setGuiOrigin(screen, left, top);
        this.modularUI.mainGroup.setParentPosition(new Position(left, top));
    }

    private static void setGuiOrigin(Screen screen, int left, int top) {
        setInherited(screen, new String[] {"f_97735_", "leftPos"}, left);
        setInherited(screen, new String[] {"f_97736_", "topPos"}, top);
    }

    private static void setInherited(Object target, String[] names, int value) {
        Class<?> type = target.getClass();
        while (type != null) {
            for (String name : names) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    field.setInt(target, value);
                    return;
                } catch (Exception ignored) {
                    // Try the other mapping, then the superclass.
                }
            }
            type = type.getSuperclass();
        }
    }
}
