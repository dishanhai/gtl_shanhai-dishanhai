package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SelectableRecipeTypeSetPacket {

    public static final int ACTION_SET_INDEX_SELECTED = 0;
    public static final int ACTION_SELECT_ALL = 1;
    public static final int ACTION_SELECT_FIRST = 2;
    public static final int ACTION_SELECT_ONLY_INDEX = 3;
    public static final int ACTION_SELECT_NONE = 4;

    private final BlockPos pos;
    private final int action;
    private final int typeIndex;
    private final boolean selected;

    public SelectableRecipeTypeSetPacket(BlockPos pos, int action, int typeIndex) {
        this(pos, action, typeIndex, false);
    }

    public SelectableRecipeTypeSetPacket(BlockPos pos, int action, int typeIndex, boolean selected) {
        this.pos = pos;
        this.action = action;
        this.typeIndex = typeIndex;
        this.selected = selected;
    }

    public SelectableRecipeTypeSetPacket(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.action = buf.readVarInt();
        this.typeIndex = buf.readVarInt();
        this.selected = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeVarInt(action);
        buf.writeVarInt(typeIndex);
        buf.writeBoolean(selected);
    }

    public static void handle(SelectableRecipeTypeSetPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> applyOnServer(packet, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void applyOnServer(SelectableRecipeTypeSetPacket packet, ServerPlayer player) {
        if (player == null) {
            return;
        }
        Level level = player.level();
        if (level == null || level.isClientSide || !level.isLoaded(packet.pos)) {
            return;
        }
        MetaMachine metaMachine = MetaMachine.getMachine(level, packet.pos);
        if (!(metaMachine instanceof SelectableRecipeTypeSetMachine machine)) {
            return;
        }

        if (packet.action == ACTION_SELECT_ALL) {
            machine.selectAllRecipeTypes();
            return;
        }
        if (packet.action == ACTION_SELECT_FIRST) {
            machine.selectFirstRecipeType();
            return;
        }
        if (packet.action == ACTION_SELECT_NONE) {
            machine.selectNoRecipeTypes();
            return;
        }

        GTRecipeType type = getRecipeTypeByIndex(machine, packet.typeIndex);
        if (type == null) {
            return;
        }
        if (packet.action == ACTION_SET_INDEX_SELECTED) {
            machine.setRecipeTypeSelected(type, packet.selected);
        } else if (packet.action == ACTION_SELECT_ONLY_INDEX) {
            machine.selectOnlyRecipeType(type);
        }
    }

    private static GTRecipeType getRecipeTypeByIndex(SelectableRecipeTypeSetMachine machine, int typeIndex) {
        GTRecipeType[] types = machine.getAllSelectableRecipeTypes();
        if (typeIndex < 0 || typeIndex >= types.length) {
            return null;
        }
        return types[typeIndex];
    }
}
