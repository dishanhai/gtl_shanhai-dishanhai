package com.dishanhai.gt_shanhai.common.item.terminal;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferPartMachine;
import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import appeng.api.networking.IGridNode;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public final class ShanhaiStellarRemoteUIHolder implements IUIHolder {

    public enum Mode {
        FULL_PATTERN,
        SLOT_CATALYST,
        STOCK_INPUT
    }

    private final GlobalPos targetPos;
    private final Mode mode;
    private final int slot;
    private final @Nullable RecipeTypePatternBufferPartMachine target;
    private final @Nullable ShanhaiPatternManagementMenuHost terminalHost;
    private boolean invalidLogged;

    public ShanhaiStellarRemoteUIHolder(GlobalPos targetPos, Mode mode, int slot,
            @Nullable RecipeTypePatternBufferPartMachine target,
            @Nullable ShanhaiPatternManagementMenuHost terminalHost) {
        this.targetPos = targetPos;
        this.mode = mode;
        this.slot = slot;
        this.target = target;
        this.terminalHost = terminalHost;
    }

    public GlobalPos targetPos() {
        return targetPos;
    }

    public Mode mode() {
        return mode;
    }

    public int slot() {
        return slot;
    }

    @Override
    public ModularUI createUI(Player player) {
        RecipeTypePatternBufferPartMachine machine = target;
        if (machine == null) return null;
        if (mode == Mode.FULL_PATTERN) {
            return machine.createRemotePatternUI(player, this);
        }
        if (mode == Mode.SLOT_CATALYST) {
            return machine.createRemoteSlotCatalystUI(player, slot, this);
        }
        return machine.createRemoteStockInputUI(player, this);
    }

    @Override
    public boolean isInvalid() {
        if (terminalHost == null) return false;
        if (target == null) return reject("missing_server_target");
        if (target.isInValid()) return reject("target_invalid");
        if (target.getLevel() == null) return reject("target_level_missing");
        if (!isTerminalStillPresent()) return reject("terminal_not_present");
        if (!terminalHost.rangeCheck()) return reject("terminal_out_of_range");
        if (!terminalHost.drainPower()) return reject("terminal_power_failed");
        IGridNode node = terminalHost.getActionableNode();
        if (node == null) return reject("terminal_node_missing");
        if (node.getGrid() != target.getGrid()) return reject("grid_mismatch");
        return false;
    }

    private boolean reject(String reason) {
        if (!invalidLogged) {
            invalidLogged = true;
            GTDishanhaiMod.LOGGER.warn(
                    "[StellarRemoteUI] closing mode={} slot={} target={} reason={}",
                    mode, slot, targetPos, reason);
        }
        return true;
    }

    private boolean isTerminalStillPresent() {
        if (terminalHost.stillValid()) return true;
        ItemStack expected = terminalHost.getItemStack();
        Player player = terminalHost.getPlayer();
        for (InteractionHand hand : InteractionHand.values()) {
            if (player.getItemInHand(hand) == expected) return true;
        }
        return false;
    }

    @Override
    public boolean isRemote() {
        return terminalHost == null || target == null || target.isRemote();
    }

    @Override
    public void markAsDirty() {
        if (terminalHost != null && target != null) {
            target.markDirty();
        }
    }
}
