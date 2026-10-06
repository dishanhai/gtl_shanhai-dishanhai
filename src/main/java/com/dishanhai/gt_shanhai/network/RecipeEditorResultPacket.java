package com.dishanhai.gt_shanhai.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class RecipeEditorResultPacket {

    public enum Status {
        SUCCESS,
        CONFLICT,
        VALIDATION_ERROR,
        REBUILD_FAILED,
        PERMISSION_DENIED
    }

    private final Status status;
    private final String message;
    private final long revision;
    private final String payload;

    public RecipeEditorResultPacket(Status status, String message, long revision, String payload) {
        this.status = status == null ? Status.VALIDATION_ERROR : status;
        this.message = message == null ? "" : message;
        this.revision = revision;
        this.payload = payload == null ? "" : payload;
    }

    public RecipeEditorResultPacket(FriendlyByteBuf buf) {
        int statusId = buf.readVarInt();
        this.status = statusId < 0 || statusId >= Status.values().length
                ? Status.VALIDATION_ERROR : Status.values()[statusId];
        this.message = buf.readUtf(256);
        this.revision = buf.readVarLong();
        this.payload = buf.readUtf(32767);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(status.ordinal());
        buf.writeUtf(message, 256);
        buf.writeVarLong(revision);
        buf.writeUtf(payload, 32767);
    }

    public static void handle(RecipeEditorResultPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() ->
                    com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorWidget
                            .receiveClientResult(packet));
        }
        context.setPacketHandled(true);
    }

    public Status status() { return status; }
    public String message() { return message; }
    public long revision() { return revision; }
    public String payload() { return payload; }
}
