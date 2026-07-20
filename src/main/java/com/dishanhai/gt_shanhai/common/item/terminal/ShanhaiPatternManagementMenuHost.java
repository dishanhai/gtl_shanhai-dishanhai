package com.dishanhai.gt_shanhai.common.item.terminal;

import appeng.menu.ISubMenu;
import com.dishanhai.gt_shanhai.common.item.DShanhaiItems;
import de.mari_023.ae2wtlib.terminal.WTMenuHost;
import java.util.function.BiConsumer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public final class ShanhaiPatternManagementMenuHost extends WTMenuHost {

    public ShanhaiPatternManagementMenuHost(Player player, @Nullable Integer inventorySlot, ItemStack stack,
            BiConsumer<Player, ISubMenu> returnToMainMenu) {
        super(player, inventorySlot, stack, returnToMainMenu);
        readFromNbt();
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return new ItemStack(DShanhaiItems.WIRELESS_PATTERN_MANAGEMENT_TERMINAL.get());
    }
}
