package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeQuery;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public final class RecipeEditorQueryPacket {

    private final String typeFilter;
    private final String text;
    private final int page;
    private final int pageSize;

    public RecipeEditorQueryPacket(String typeFilter, String text, int page, int pageSize) {
        this.typeFilter = typeFilter == null ? "" : typeFilter;
        this.text = text == null ? "" : text;
        this.page = Math.max(0, page);
        this.pageSize = Math.max(1, Math.min(256, pageSize));
    }

    public RecipeEditorQueryPacket(FriendlyByteBuf buf) {
        this.typeFilter = buf.readUtf(256);
        this.text = buf.readUtf(256);
        this.page = Math.max(0, buf.readVarInt());
        this.pageSize = Math.max(1, Math.min(256, buf.readVarInt()));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(typeFilter, 256);
        buf.writeUtf(text, 256);
        buf.writeVarInt(page);
        buf.writeVarInt(pageSize);
    }

    public static void handle(RecipeEditorQueryPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> {
            if (sender == null) return;
            if (!sender.hasPermissions(2)) {
                ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender),
                        new RecipeEditorResultPacket(
                                RecipeEditorResultPacket.Status.PERMISSION_DENIED,
                                "permission-denied",
                                ShanhaiRecipeQuery.currentRevision(),
                                ""));
                return;
            }
            ShanhaiRecipeQuery.Result result = ShanhaiRecipeQuery.query(
                    packet.typeFilter, packet.text, packet.page, packet.pageSize);
            String payload = new com.google.gson.Gson().toJson(result);
            ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender),
                    new RecipeEditorResultPacket(
                            RecipeEditorResultPacket.Status.SUCCESS,
                            "query",
                            result.revision(),
                            payload));
        });
        context.setPacketHandled(true);
    }
}
