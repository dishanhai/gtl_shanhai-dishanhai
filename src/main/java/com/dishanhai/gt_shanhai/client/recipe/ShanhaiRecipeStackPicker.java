package com.dishanhai.gt_shanhai.client.recipe;

import com.dishanhai.gt_shanhai.client.gui.shop.MultiPickerScreen;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeStackPickerBridge;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;

import java.util.ArrayList;
import java.util.List;

/**
 * Shop resource picker ({@link MultiPickerScreen}) for the recipe editor grids.
 * Opening it must not close the recipe editor container.
 */
public final class ShanhaiRecipeStackPicker {

    private ShanhaiRecipeStackPicker() {}

    public static void install() {
        ShanhaiRecipeStackPickerBridge.setHost((browseFluid, onConfirm) -> {
            Screen returnTo = Minecraft.getInstance().screen;
            List<ItemStack> items = new ArrayList<>();
            List<FluidStack> fluids = new ArrayList<>();
            ShanhaiRecipeStackPickerBridge.hold(true);
            MultiPickerScreen screen = new MultiPickerScreen(returnTo, browseFluid,
                    (stack, count) -> {
                        if (stack == null || stack.isEmpty()) return;
                        ItemStack copy = stack.copy();
                        copy.setCount((int) Math.min(Integer.MAX_VALUE, Math.max(1L, count)));
                        items.add(copy);
                    },
                    stack -> {
                        FluidStack fluid = fromPicker(stack);
                        if (!fluid.isEmpty()) fluids.add(fluid);
                    });
            screen.setAfterConfirm(() -> {
                if (onConfirm != null) onConfirm.accept(items, fluids);
            });
            Minecraft.getInstance().setScreen(screen);
        });
        MinecraftForge.EVENT_BUS.addListener(ShanhaiRecipeStackPicker::onOpening);
        MinecraftForge.EVENT_BUS.addListener(ShanhaiRecipeStackPicker::onLogout);
    }

    private static void onOpening(ScreenEvent.Opening event) {
        if (!ShanhaiRecipeStackPickerBridge.holding()) return;
        Screen next = event.getNewScreen();
        if (next == null || next instanceof ModularUIGuiContainer) {
            ShanhaiRecipeStackPickerBridge.hold(false);
        }
    }

    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ShanhaiRecipeStackPickerBridge.hold(false);
    }

    private static FluidStack fromPicker(dev.architectury.fluid.FluidStack stack) {
        if (stack == null || stack.isEmpty()) return FluidStack.empty();
        return FluidStack.create(stack.getFluid(), stack.getAmount(), stack.getTag());
    }
}
