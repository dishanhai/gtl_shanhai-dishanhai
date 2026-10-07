package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import java.util.function.Consumer;
import net.minecraft.world.item.ItemStack;

/**
 * Client installs the shop editor's FTBLib picker. The common widget only sees this bridge,
 * so a dedicated server never loads the client screens.
 */
public final class ShanhaiRecipeStackPickerBridge {

    public interface Host {
        void openItem(ItemStack current, Consumer<ItemStack> onPicked);

        void openFluid(FluidStack current, Consumer<FluidStack> onPicked);
    }

    private static final Host NONE = new Host() {
        @Override
        public void openItem(ItemStack current, Consumer<ItemStack> onPicked) {}

        @Override
        public void openFluid(FluidStack current, Consumer<FluidStack> onPicked) {}
    };

    private static Host host = NONE;
    private static boolean holding;

    private ShanhaiRecipeStackPickerBridge() {}

    public static void setHost(Host next) {
        host = next == null ? NONE : next;
    }

    public static void openItem(ItemStack current, Consumer<ItemStack> onPicked) {
        host.openItem(current, onPicked);
    }

    public static void openFluid(FluidStack current, Consumer<FluidStack> onPicked) {
        host.openFluid(current, onPicked);
    }

    public static boolean holding() {
        return holding;
    }

    public static void hold(boolean next) {
        holding = next;
    }
}
