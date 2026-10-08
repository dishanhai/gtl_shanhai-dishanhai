package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.recipe.RecipeRebuildService;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeBase;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeConditions;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorOps;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorValidation;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeOverrideStore;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeQuery;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
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
                RecipeEditorResultPacket denied = new RecipeEditorResultPacket(
                        RecipeEditorResultPacket.Status.PERMISSION_DENIED,
                        "permission-denied",
                        ShanhaiRecipeQuery.currentRevision(),
                        "");
                notifyPlayer(sender, packet, denied);
                send(sender, denied);
                return;
            }
            RecipeEditorResultPacket result = commit(packet);
            notifyPlayer(sender, packet, result);
            send(sender, result);
        });
        context.setPacketHandled(true);
    }

    private static void notifyPlayer(
            ServerPlayer player, RecipeEditorCommitPacket packet, RecipeEditorResultPacket result) {
        if (player == null || result == null) return;
        if (result.status() != RecipeEditorResultPacket.Status.SUCCESS) {
            player.sendSystemMessage(Component.literal(
                    "§b[配方修改器] §c提交失败：" + result.message()));
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(packet.payload).getAsJsonObject();
            JsonObject inputs = json.has("inputs") && json.get("inputs").isJsonObject()
                    ? json.getAsJsonObject("inputs") : new JsonObject();
            JsonObject outputs = json.has("outputs") && json.get("outputs").isJsonObject()
                    ? json.getAsJsonObject("outputs") : new JsonObject();
            boolean createNew = json.has("createNew")
                    && json.get("createNew").isJsonPrimitive()
                    && json.get("createNew").getAsBoolean();
            if (createNew) {
                player.sendSystemMessage(Component.literal(
                        "§b[配方修改器] §a已新增配方并重建\n"
                                + "§7配方：§f" + packet.recipeId
                                + "  §7类型：§f" + packet.recipeTypeId + "\n"
                                + "§7未改动已有配方\n"
                                + "§7耗时：§f" + json.get("duration").getAsInt() + " tick"
                                + "  §7EU/t：§f" + json.get("eut").getAsLong() + "\n"
                                + "§7物品输入 §f" + arraySize(inputs, "item")
                                + "  §7流体输入 §f" + arraySize(inputs, "fluid")
                                + "  §7物品输出 §f" + arraySize(outputs, "item")
                                + "  §7流体输出 §f" + arraySize(outputs, "fluid") + "\n"
                                + (result.payload().isEmpty() ? "" : result.payload() + "\n")
                                + "§7GT 配方表与 JEI 刷新请求已发送"));
                return;
            }
            boolean keepOriginal = json.has("keepOriginal")
                    && json.get("keepOriginal").isJsonPrimitive()
                    && json.get("keepOriginal").getAsBoolean();
            String liveId = json.has("liveRecipeId") && json.get("liveRecipeId").isJsonPrimitive()
                    ? json.get("liveRecipeId").getAsString() : packet.recipeId;
            String presence = keepOriginal
                    ? "§7原配方保留，新配方同时存在于 JEI 和配方层"
                    : "§7原配方已从 JEI 和配方层移除";
            if (!packet.recipeId.equals(liveId)) {
                presence = presence + "\n§7新配方：§f" + liveId;
            }
            String message = "§b[配方修改器] §a已修改并重建配方\n"
                    + "§7配方：§f" + packet.recipeId
                    + "  §7类型：§f" + packet.recipeTypeId + "\n"
                    + presence + "\n"
                    + "§7耗时：§f" + json.get("duration").getAsInt() + " tick"
                    + "  §7EU/t：§f" + json.get("eut").getAsLong() + "\n"
                    + "§7物品输入 §f" + arraySize(inputs, "item")
                    + "  §7流体输入 §f" + arraySize(inputs, "fluid")
                    + "  §7物品输出 §f" + arraySize(outputs, "item")
                    + "  §7流体输出 §f" + arraySize(outputs, "fluid") + "\n"
                    + (result.payload().isEmpty() ? "" : result.payload() + "\n")
                    + "§7GT 配方表与 JEI 刷新请求已发送";
            player.sendSystemMessage(Component.literal(message));
        } catch (RuntimeException invalidSummary) {
            player.sendSystemMessage(Component.literal(
                    "§b[配方修改器] §a配方已提交并重建，刷新请求已发送"));
        }
    }

    private static int arraySize(JsonObject table, String key) {
        return table.has(key) && table.get(key).isJsonArray()
                ? table.getAsJsonArray(key).size() : 0;
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
            boolean createNew = json.has("createNew")
                    && json.get("createNew").isJsonPrimitive()
                    && json.get("createNew").getAsBoolean();
            if (createNew) return createNew(packet, json);
            var effective = RecipeRebuildService.editableOf(packet.recipeTypeId, packet.recipeId);
            ShanhaiRecipeBase original = effective == null ? null : ShanhaiRecipeBase.from(effective);
            if (original == null) return invalid("recipe-not-found");
            boolean keepOriginal = json.has("keepOriginal")
                    && json.get("keepOriginal").isJsonPrimitive()
                    && json.get("keepOriginal").getAsBoolean();
            String liveRaw = json.has("liveRecipeId") && json.get("liveRecipeId").isJsonPrimitive()
                    ? json.get("liveRecipeId").getAsString() : packet.recipeId;
            if (RecipeEditorExportPacket.exportLocation(liveRaw, packet.recipeId) == null) {
                return invalid("配方 id 不合法");
            }

            JsonObject normalized = json.deepCopy();
            if (!normalized.has("tickInputs")) normalized.add("tickInputs", original.tickInputs());
            if (!normalized.has("tickOutputs")) normalized.add("tickOutputs", original.tickOutputs());
            if (!normalized.has("conditions")) normalized.add("conditions", original.conditions());
            ShanhaiRecipeBase base = ShanhaiRecipeBase.fromPayload(normalized);
            String conditionDiff = json.has("conditionNote") && json.get("conditionNote").isJsonPrimitive()
                    ? json.get("conditionNote").getAsString()
                    : ShanhaiRecipeConditions.diffLine(original.conditions(), base.conditions());
            String validation = ShanhaiRecipeEditorValidation.validateBase(base);
            if (validation != null) return invalid(validation);
            ShanhaiRecipeEditorOps ops = new ShanhaiRecipeEditorOps(
                    new ShanhaiRecipeOverrideStore(FMLPaths.GAMEDIR.get()
                            .resolve("config/gt_shanhai/recipe_overrides.json")));
            ShanhaiRecipeEditorOps.Result result = ops.commit(
                    new ShanhaiRecipeEditorOps.Edit(base, packet.baseFingerprint),
                    net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer(),
                    keepOriginal,
                    RecipeEditorExportPacket.exportLocation(liveRaw, packet.recipeId).toString());
            return new RecipeEditorResultPacket(
                    RecipeEditorResultPacket.Status.valueOf(result.status().name()),
                    result.message(),
                    result.revision(),
                    conditionDiff);
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[配方编辑器] 拒绝非法提交包", e);
            return invalid("invalid-editor-payload");
        }
    }

    private static RecipeEditorResultPacket createNew(RecipeEditorCommitPacket packet, JsonObject json) {
        String liveRaw = json.has("liveRecipeId") && json.get("liveRecipeId").isJsonPrimitive()
                ? json.get("liveRecipeId").getAsString() : packet.recipeId;
        ResourceLocation live = RecipeEditorExportPacket.exportLocation(liveRaw, packet.recipeId);
        if (live == null || !live.toString().equals(packet.recipeId)) {
            return invalid("配方 id 不合法");
        }
        ShanhaiRecipeBase base = ShanhaiRecipeBase.fromPayload(json);
        String validation = ShanhaiRecipeEditorValidation.validateBase(base);
        if (validation != null) return invalid(validation);
        ShanhaiRecipeEditorOps ops = new ShanhaiRecipeEditorOps(
                new ShanhaiRecipeOverrideStore(FMLPaths.GAMEDIR.get()
                        .resolve("config/gt_shanhai/recipe_overrides.json")));
        ShanhaiRecipeEditorOps.Result result = ops.create(
                new ShanhaiRecipeEditorOps.Edit(base, packet.baseFingerprint),
                net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer());
        return new RecipeEditorResultPacket(
                RecipeEditorResultPacket.Status.valueOf(result.status().name()),
                result.message(),
                result.revision(),
                "");
    }

    private static RecipeEditorResultPacket invalid(String message) {
        return new RecipeEditorResultPacket(
                RecipeEditorResultPacket.Status.VALIDATION_ERROR,
                message,
                ShanhaiRecipeQuery.currentRevision(),
                "");
    }
}
