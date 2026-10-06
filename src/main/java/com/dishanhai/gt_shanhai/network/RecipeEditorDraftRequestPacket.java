package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorDraftSavedData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/** Client request to load, save, or clear the player's editor draft. */
public final class RecipeEditorDraftRequestPacket {

    public enum Action {
        LOAD,
        SAVE,
        CLEAR
    }

    private final Action action;
    private final CompoundTag draft;

    public RecipeEditorDraftRequestPacket(Action action, CompoundTag draft) {
        this.action = action == null ? Action.LOAD : action;
        this.draft = draft == null ? new CompoundTag() : draft.copy();
    }

    public RecipeEditorDraftRequestPacket(FriendlyByteBuf buffer) {
        this.action = buffer.readEnum(Action.class);
        this.draft = this.action == Action.SAVE
                ? safeDraft(buffer.readNbt()) : new CompoundTag();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeEnum(action);
        if (action == Action.SAVE) buffer.writeNbt(draft);
    }

    public static void handle(RecipeEditorDraftRequestPacket packet,
                              Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> {
            if (sender == null || !sender.hasPermissions(2)) return;
            ShanhaiRecipeEditorDraftSavedData data =
                    ShanhaiRecipeEditorDraftSavedData.get(sender.server);
            if (packet.action == Action.LOAD) {
                ShanhaiNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> sender),
                        new RecipeEditorDraftSyncPacket(data.getDraft(sender)));
            } else if (packet.action == Action.SAVE) {
                data.setDraft(sender, packet.draft);
            } else {
                data.clear(sender);
            }
        });
        context.setPacketHandled(true);
    }

    private static CompoundTag safeDraft(CompoundTag draft) {
        return draft == null ? new CompoundTag() : draft.copy();
    }
}
