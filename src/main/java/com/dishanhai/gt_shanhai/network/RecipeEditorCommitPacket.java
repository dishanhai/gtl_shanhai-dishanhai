package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.recipe.RecipeRebuildService;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeBase;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorOps;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorValidation;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeOverrideStore;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeQuery;
import com.dishanhai.gt_shanhai.common.recipe.RecipeOriginalSnapshotStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

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
            if (!sender.hasPermissions(2)) {
                send(sender, new RecipeEditorResultPacket(
                        RecipeEditorResultPacket.Status.PERMISSION_DENIED,
                        "permission-denied",
                        ShanhaiRecipeQuery.currentRevision(),
                        ""));
                return;
            }
            RecipeEditorResultPacket result = commit(packet);
            send(sender, result);
        });
        context.setPacketHandled(true);
    }

    private static void send(ServerPlayer player, RecipeEditorResultPacket packet) {
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    private static RecipeEditorResultPacket commit(RecipeEditorCommitPacket packet) {
        try {
            if (packet.payload.length() > ShanhaiRecipeEditorValidation.MAX_PAYLOAD_CHARS) {
                return invalid("editor-payload-too-large");
            }
            if (ResourceLocation.tryParse(packet.recipeTypeId) == null
                    || ResourceLocation.tryParse(packet.recipeId) == null) {
                return invalid("invalid-recipe-identity");
            }
            JsonObject json = JsonParser.parseString(packet.payload).getAsJsonObject();
            if (!json.has("recipeTypeId") || !json.has("recipeId")
                    || !packet.recipeTypeId.equals(json.get("recipeTypeId").getAsString())
                    || !packet.recipeId.equals(json.get("recipeId").getAsString())) {
                return invalid("recipe-identity-mismatch");
            }
            ShanhaiRecipeBase original = null;
            var snapshot = RecipeOriginalSnapshotStore.copyOf(packet.recipeTypeId, packet.recipeId);
            if (snapshot != null) {
                var effective = RecipeRebuildService.buildCanonical(packet.recipeTypeId, snapshot);
                if (effective != null) original = ShanhaiRecipeBase.from(effective);
            }
            if (original == null) return invalid("recipe-not-found");

            JsonObject normalized = json.deepCopy();
            if (!normalized.has("tickInputs")) normalized.add("tickInputs", original.tickInputs());
            if (!normalized.has("tickOutputs")) normalized.add("tickOutputs", original.tickOutputs());
            if (!normalized.has("conditions")) normalized.add("conditions", original.conditions());
            ShanhaiRecipeBase base = ShanhaiRecipeBase.fromPayload(normalized);
            String validation = ShanhaiRecipeEditorValidation.validateBase(base);
            if (validation != null) return invalid(validation);
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
            GTDishanhaiMod.LOGGER.warn("[配方编辑器] 拒绝非法提交包", e);
            return invalid("invalid-editor-payload");
        }
    }

    private static RecipeEditorResultPacket invalid(String message) {
        return new RecipeEditorResultPacket(
                RecipeEditorResultPacket.Status.VALIDATION_ERROR,
                message,
                ShanhaiRecipeQuery.currentRevision(),
                "");
    }
}
