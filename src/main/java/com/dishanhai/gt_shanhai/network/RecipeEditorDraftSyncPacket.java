package com.dishanhai.gt_shanhai.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server-to-client restore of the current player's editor draft. */
public final class RecipeEditorDraftSyncPacket {

    private final CompoundTag draft;

    public RecipeEditorDraftSyncPacket(CompoundTag draft) {
        this.draft = draft == null ? new CompoundTag() : draft.copy();
    }

    public RecipeEditorDraftSyncPacket(FriendlyByteBuf buffer) {
        CompoundTag value = buffer.readNbt();
        this.draft = value == null ? new CompoundTag() : value;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeNbt(draft);
    }

    public static void handle(RecipeEditorDraftSyncPacket packet,
                              Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() ->
                    com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorWidget
                            .receiveDraft(packet.draft));
        }
        context.setPacketHandled(true);
    }
}
