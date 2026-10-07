package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.world.item.ItemStack;

/**
 * Client installs the shop's own resource picker. The common widget only sees this bridge,
 * so a dedicated server never loads the client screen.
 */
public final class ShanhaiRecipeStackPickerBridge {

    public interface Host {
        void open(boolean browseFluid, BiConsumer<List<ItemStack>, List<FluidStack>> onConfirm);
    }

    private static final Host NONE = (browseFluid, onConfirm) -> {};

    private static Host host = NONE;
    private static boolean holding;

    private ShanhaiRecipeStackPickerBridge() {}

    public static void setHost(Host next) {
        host = next == null ? NONE : next;
    }

    public static void open(boolean browseFluid, BiConsumer<List<ItemStack>, List<FluidStack>> onConfirm) {
        host.open(browseFluid, onConfirm);
    }

    public static boolean holding() {
        return holding;
    }

    public static void hold(boolean next) {
        holding = next;
    }
}
