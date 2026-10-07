package com.dishanhai.gt_shanhai.client.recipe;

import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeStackPickerBridge;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import dev.ftb.mods.ftblibrary.config.FluidConfig;
import dev.ftb.mods.ftblibrary.config.ItemStackConfig;
import dev.ftb.mods.ftblibrary.config.ui.SelectFluidScreen;
import dev.ftb.mods.ftblibrary.config.ui.SelectItemStackScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;

/**
 * Same item and fluid picker the shop editor uses. Opening it must not close the recipe
 * editor container: FTBLib's screen close calls {@code player.closeContainer()}.
 */
public final class ShanhaiRecipeStackPicker {

    private ShanhaiRecipeStackPicker() {}

    public static void install() {
        ShanhaiRecipeStackPickerBridge.setHost(new ShanhaiRecipeStackPickerBridge.Host() {
            @Override
            public void openItem(ItemStack current, java.util.function.Consumer<ItemStack> onPicked) {
                Screen returnTo = Minecraft.getInstance().screen;
                ItemStackConfig config = new ItemStackConfig(true, true);
                config.setValue(current == null ? ItemStack.EMPTY : current.copy());
                ShanhaiRecipeStackPickerBridge.hold(true);
                new SelectItemStackScreen(config, changed -> {
                    onPicked.accept(config.getValue());
                    Minecraft.getInstance().setScreen(returnTo);
                }).openGui();
            }

            @Override
            public void openFluid(FluidStack current, java.util.function.Consumer<FluidStack> onPicked) {
                Screen returnTo = Minecraft.getInstance().screen;
                FluidConfig config = new FluidConfig(true).showAmount(true);
                config.setValue(toPicker(current));
                ShanhaiRecipeStackPickerBridge.hold(true);
                new SelectFluidScreen(config, changed -> {
                    onPicked.accept(fromPicker(config.getValue()));
                    Minecraft.getInstance().setScreen(returnTo);
                }).openGui();
            }
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

    private static dev.architectury.fluid.FluidStack toPicker(FluidStack stack) {
        if (stack == null || stack.isEmpty()) return dev.architectury.fluid.FluidStack.empty();
        return dev.architectury.fluid.FluidStack.create(stack.getFluid(), stack.getAmount(), stack.getTag());
    }

    private static FluidStack fromPicker(dev.architectury.fluid.FluidStack stack) {
        if (stack == null || stack.isEmpty()) return FluidStack.empty();
        return FluidStack.create(stack.getFluid(), stack.getAmount(), stack.getTag());
    }
}
