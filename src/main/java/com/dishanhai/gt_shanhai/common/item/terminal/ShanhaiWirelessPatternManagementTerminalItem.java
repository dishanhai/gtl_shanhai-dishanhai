package com.dishanhai.gt_shanhai.common.item.terminal;

import appeng.api.config.Settings;
import appeng.api.config.ShowPatternProviders;
import appeng.api.util.IConfigManager;
import de.mari_023.ae2wtlib.terminal.ItemWT;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

public final class ShanhaiWirelessPatternManagementTerminalItem extends ItemWT implements ICurioItem {

    @Override
    public @NotNull MenuType<?> getMenuType(@NotNull ItemStack stack) {
        return ShanhaiPatternManagementMenu.TYPE;
    }

    @Override
    public @NotNull MenuType<?> getMenuType() {
        return ShanhaiPatternManagementMenu.TYPE;
    }

    @Override
    public @NotNull IConfigManager getConfigManager(@NotNull ItemStack stack) {
        IConfigManager config = super.getConfigManager(stack);
        config.registerSetting(Settings.TERMINAL_SHOW_PATTERN_PROVIDERS, ShowPatternProviders.VISIBLE);
        config.readFromNBT(stack.getOrCreateTag().copy());
        return config;
    }
}
