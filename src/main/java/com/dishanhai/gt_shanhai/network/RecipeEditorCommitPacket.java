package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeBase;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorOps;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeOverrideStore;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeQuery;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.nio.file.Path;
import java.util.function.Supplier;

public final class RecipeEditorCommitPacket {

    private final String recipeTypeId;
    private final String recipeId;
    private final String baseFingerprint;
    private final String payload;

    public RecipeEditorCommitPacket(String recipeTypeId, String recipeId, String baseFingerprint, String payload) {
        this.recipeTypeId = recipeTypeId == null ? "" : recipeTypeId;
        this.recipeId = recipeId == null ? "" : recipeId;
        this.baseFingerprint = baseFingerprint == null ? "" : baseFingerprint;
        this.payload = payload == null ? "{}" : payload;
    }

    public RecipeEditorCommitPacket(FriendlyByteBuf buf) {
        this.recipeTypeId = buf.readUtf(256);
        this.recipeId = buf.readUtf(256);
        this.baseFingerprint = buf.readUtf(512);
        this.payload = buf.readUtf(32767);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(recipeTypeId, 256);
        buf.writeUtf(recipeId, 256);
        buf.writeUtf(baseFingerprint, 512);
        buf.writeUtf(payload, 32767);
    }

    public static void handle(RecipeEditorCommitPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> {
            if (sender == null) return;
            RecipeEditorResultPacket result = commit(packet);
            ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender), result);
        });
        context.setPacketHandled(true);
    }

    private static RecipeEditorResultPacket commit(RecipeEditorCommitPacket packet) {
        try {
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(packet.payload).getAsJsonObject();
            ShanhaiRecipeBase base = new ShanhaiRecipeBase(
                    packet.recipeTypeId,
                    packet.recipeId,
                    json.get("duration").getAsInt(),
                    json.has("eut") ? json.get("eut").getAsLong() : 0L,
                    json.getAsJsonObject("inputs"),
                    json.getAsJsonObject("outputs"),
                    json.getAsJsonObject("tickInputs"),
                    json.has("conditions") ? json.getAsJsonArray("conditions") : new com.google.gson.JsonArray());
            ShanhaiRecipeEditorOps ops = new ShanhaiRecipeEditorOps(
                    new ShanhaiRecipeOverrideStore(FMLPaths.GAMEDIR.get()
                            .resolve("config/gt_shanhai/recipe_overrides.json")));
            ShanhaiRecipeEditorOps.Result result = ops.commit(
                    new ShanhaiRecipeEditorOps.Edit(base, packet.baseFingerprint),
                    net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer());
            return new RecipeEditorResultPacket(
                    RecipeEditorResultPacket.Status.valueOf(result.status().name()),
                    result.message(),
                    result.revision(),
                    "");
        } catch (Exception e) {
            return new RecipeEditorResultPacket(
                    RecipeEditorResultPacket.Status.VALIDATION_ERROR,
                    "invalid-editor-payload",
                    ShanhaiRecipeQuery.currentRevision(),
                    "");
        }
    }
}
