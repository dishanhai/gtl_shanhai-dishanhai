package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeBase;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeQuery;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/** Requests the full immutable snapshot for the selected editor card. */
public final class RecipeEditorDetailPacket {

    private final String recipeTypeId;
    private final String recipeId;

    public RecipeEditorDetailPacket(String recipeTypeId, String recipeId) {
        this.recipeTypeId = recipeTypeId == null ? "" : recipeTypeId;
        this.recipeId = recipeId == null ? "" : recipeId;
    }

    public RecipeEditorDetailPacket(FriendlyByteBuf buffer) {
        this.recipeTypeId = buffer.readUtf(256);
        this.recipeId = buffer.readUtf(256);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(recipeTypeId, 256);
        buffer.writeUtf(recipeId, 256);
    }

    public static void handle(RecipeEditorDetailPacket packet,
                              Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> {
            if (sender == null) return;
            if (!sender.hasPermissions(2)) {
                sendPermissionDenied(sender);
                return;
            }
            ShanhaiRecipeQuery.get(packet.recipeTypeId, packet.recipeId)
                    .ifPresentOrElse(
                            base -> send(sender, base),
                            () -> sendInvalid(sender));
        });
        context.setPacketHandled(true);
    }

    private static void send(ServerPlayer player, ShanhaiRecipeBase base) {
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new RecipeEditorResultPacket(
                        RecipeEditorResultPacket.Status.SUCCESS,
                        "detail",
                        ShanhaiRecipeQuery.currentRevision(),
                        base.payloadJson().toString()));
    }

    private static void sendInvalid(ServerPlayer player) {
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new RecipeEditorResultPacket(
                        RecipeEditorResultPacket.Status.VALIDATION_ERROR,
                        "recipe-not-found",
                        ShanhaiRecipeQuery.currentRevision(),
                        ""));
    }

    private static void sendPermissionDenied(ServerPlayer player) {
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new RecipeEditorResultPacket(
                        RecipeEditorResultPacket.Status.PERMISSION_DENIED,
                        "permission-denied",
                        ShanhaiRecipeQuery.currentRevision(),
                        ""));
    }
}
