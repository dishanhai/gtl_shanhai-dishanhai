package com.dishanhai.gt_shanhai.common.item.terminal;

import appeng.menu.locator.MenuLocator;
import appeng.menu.locator.MenuLocators;
import com.dishanhai.gt_shanhai.common.item.DShanhaiItems;
import de.mari_023.ae2wtlib.curio.CurioHelper;
import de.mari_023.ae2wtlib.wut.ItemWUT;
import de.mari_023.ae2wtlib.wut.WUTHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

public final class ShanhaiPatternTerminalOpenHelper {

    public static boolean openFirst(ServerPlayer player) {
        ShanhaiWirelessPatternManagementTerminalItem terminal =
                DShanhaiItems.WIRELESS_PATTERN_MANAGEMENT_TERMINAL.get();

        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() == terminal
                    && terminal.tryOpen(player, MenuLocators.forHand(player, hand), stack, false)) {
                return true;
            }
        }

        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.getItem() instanceof ItemWUT
                    && WUTHandler.hasTerminal(stack, ShanhaiPatternTerminalIntegration.MODULE_ID)
                    && openCandidate(player, MenuLocators.forInventorySlot(slot), stack)) {
                return true;
            }
        }

        MenuLocator curio = CurioHelper.findTerminal(player, ShanhaiPatternTerminalIntegration.MODULE_ID);
        if (curio != null) {
            ItemStack stack = WUTHandler.getItemStackFromLocator(player, curio);
            if (openCandidate(player, curio, stack)) {
                return true;
            }
        }

        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.getItem() == terminal
                    && terminal.tryOpen(player, MenuLocators.forInventorySlot(slot), stack, false)) {
                return true;
            }
        }
        return false;
    }

    private static boolean openCandidate(ServerPlayer player, MenuLocator locator, ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getItem() instanceof ItemWUT) {
            WUTHandler.setCurrentTerminal(player, locator, stack, ShanhaiPatternTerminalIntegration.MODULE_ID);
            return WUTHandler.open(player, locator, false);
        }
        if (stack.getItem() instanceof ShanhaiWirelessPatternManagementTerminalItem terminal) {
            return terminal.tryOpen(player, locator, stack, false);
        }
        return false;
    }

    private ShanhaiPatternTerminalOpenHelper() {}
}
