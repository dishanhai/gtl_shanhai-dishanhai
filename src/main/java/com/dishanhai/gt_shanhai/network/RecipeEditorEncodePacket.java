package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.item.JeiPatternQuickEncodeService;
import com.dishanhai.gt_shanhai.common.recipe.RecipeOriginalSnapshotStore;
import com.dishanhai.gt_shanhai.common.recipe.RecipeRebuildService;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeBase;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorValidation;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * Encodes the review-page draft as one AE2 pattern and uploads it.
 * The draft is applied onto the original recipe the same way export does,
 * so a changed or added recipe is what gets encoded.
 */
public final class RecipeEditorEncodePacket {

    private final String recipeTypeId;
    private final String sourceRecipeId;
    private final String payload;

    public RecipeEditorEncodePacket(String recipeTypeId, String sourceRecipeId, String payload) {
        this.recipeTypeId = recipeTypeId == null ? "" : recipeTypeId;
        this.sourceRecipeId = sourceRecipeId == null ? "" : sourceRecipeId;
        this.payload = payload == null ? "{}" : payload;
    }

    public RecipeEditorEncodePacket(FriendlyByteBuf buf) {
        this.recipeTypeId = buf.readUtf(256);
        this.sourceRecipeId = buf.readUtf(256);
        this.payload = buf.readUtf(32767);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(recipeTypeId, 256);
        buf.writeUtf(sourceRecipeId, 256);
        buf.writeUtf(payload, 32767);
    }

    public static void handle(RecipeEditorEncodePacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> {
            if (sender == null) return;
            if (!sender.hasPermissions(2)) {
                send(sender, new RecipeEditorResultPacket(
                        RecipeEditorResultPacket.Status.PERMISSION_DENIED,
                        "permission-denied", 0L, ""));
                return;
            }
            send(sender, encode(sender, packet));
        });
        context.setPacketHandled(true);
    }

    private static RecipeEditorResultPacket encode(ServerPlayer player, RecipeEditorEncodePacket packet) {
        try {
            if (packet.payload.length() > ShanhaiRecipeEditorValidation.MAX_PAYLOAD_CHARS) {
                return failed("editor-payload-too-large");
            }
            if (ResourceLocation.tryParse(packet.recipeTypeId) == null
                    || ResourceLocation.tryParse(packet.sourceRecipeId) == null) {
                return failed("invalid-recipe-identity");
            }
            JsonObject json = JsonParser.parseString(packet.payload).getAsJsonObject();
            if (!json.has("recipeTypeId")
                    || !packet.recipeTypeId.equals(json.get("recipeTypeId").getAsString())) {
                return failed("recipe-identity-mismatch");
            }
            json.addProperty("recipeId", packet.sourceRecipeId);
            ShanhaiRecipeBase edited = ShanhaiRecipeBase.fromPayload(json);
            if (edited == null) return failed("invalid-editor-payload");
            String validation = ShanhaiRecipeEditorValidation.validateBase(edited);
            if (validation != null) return failed(validation);
            boolean createNew = json.has("createNew")
                    && json.get("createNew").isJsonPrimitive()
                    && json.get("createNew").getAsBoolean();
            GTRecipe recipe;
            if (createNew) {
                recipe = RecipeRebuildService.materializeFresh(packet.recipeTypeId, edited);
                if (recipe == null) return failed("invalid-recipe-type");
            } else {
                GTRecipe snapshot = RecipeOriginalSnapshotStore.copyOf(packet.recipeTypeId, packet.sourceRecipeId);
                if (snapshot == null) return failed("recipe-not-found");
                GTRecipe original = RecipeRebuildService.buildCanonical(packet.recipeTypeId, snapshot);
                if (original == null) return failed("recipe-not-found");
                recipe = edited.toGtRecipe(original);
                if (recipe == null) return failed("recipe-not-found");
            }

            boolean wrote = JeiPatternQuickEncodeService.encodeEdited(player, recipe);
            return wrote
                    ? new RecipeEditorResultPacket(
                            RecipeEditorResultPacket.Status.SUCCESS, "encode", 0L, "")
                    : failed("encode-failed");
        } catch (Exception exception) {
            GTDishanhaiMod.LOGGER.warn("[配方编辑器] 快速编写样板失败", exception);
            return failed("encode-failed");
        }
    }

    private static RecipeEditorResultPacket failed(String message) {
        return new RecipeEditorResultPacket(
                RecipeEditorResultPacket.Status.VALIDATION_ERROR, message, 0L, "");
    }

    private static void send(ServerPlayer player, RecipeEditorResultPacket packet) {
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
