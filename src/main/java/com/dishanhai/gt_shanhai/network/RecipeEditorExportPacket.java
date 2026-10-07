package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.recipe.RecipeOriginalSnapshotStore;
import com.dishanhai.gt_shanhai.common.recipe.RecipeRebuildService;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeBase;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeConditions;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorValidation;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeJsExport;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeSerializer;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Writes the edited recipe as a datapack JSON GTCEu and GTLCore already decode.
 * The namespace is the one in the edited id. A path with no namespace keeps the
 * opened recipe's namespace. Files are gathered under
 * {@code kubejs/data/Exported_Recipe/<type>/<namespace>/<path>.json}.
 * {@code <type>} is the path of the recipe type id ({@code gtceu:qft} → {@code qft}).
 * {@code Exported_Recipe} is only a folder, not a recipe namespace.
 */
public final class RecipeEditorExportPacket {

    /** 集中存放导出 json 的文件夹。不是配方命名空间。 */
    private static final String EXPORT_BUCKET = "Exported_Recipe";

    private static final Gson PRETTY = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private final String recipeTypeId;
    private final String sourceRecipeId;
    private final String exportRecipeId;
    private final String payload;

    public RecipeEditorExportPacket(String recipeTypeId, String sourceRecipeId,
                                    String exportRecipeId, String payload) {
        this.recipeTypeId = recipeTypeId == null ? "" : recipeTypeId;
        this.sourceRecipeId = sourceRecipeId == null ? "" : sourceRecipeId;
        this.exportRecipeId = exportRecipeId == null ? "" : exportRecipeId;
        this.payload = payload == null ? "{}" : payload;
    }

    public RecipeEditorExportPacket(FriendlyByteBuf buf) {
        this.recipeTypeId = buf.readUtf(256);
        this.sourceRecipeId = buf.readUtf(256);
        this.exportRecipeId = buf.readUtf(256);
        this.payload = buf.readUtf(32767);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(recipeTypeId, 256);
        buf.writeUtf(sourceRecipeId, 256);
        buf.writeUtf(exportRecipeId, 256);
        buf.writeUtf(payload, 32767);
    }

    public static void handle(RecipeEditorExportPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> {
            if (sender == null) return;
            if (!sender.hasPermissions(2)) {
                send(sender, new RecipeEditorResultPacket(
                        RecipeEditorResultPacket.Status.PERMISSION_DENIED,
                        "permission-denied",
                        0L,
                        ""));
                return;
            }
            RecipeEditorResultPacket result = export(packet);
            if (result.status() == RecipeEditorResultPacket.Status.SUCCESS) {
                sender.sendSystemMessage(Component.literal(
                        "§b[配方修改器] §a已导出\n" + exportChat(result.payload())));
            } else {
                sender.sendSystemMessage(Component.literal(
                        "§b[配方修改器] §c导出失败：" + result.message()));
            }
            send(sender, result);
        });
        context.setPacketHandled(true);
    }

    public static ResourceLocation exportLocation(String raw, String fallbackId) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty()) return null;
        ResourceLocation parsed;
        if (text.indexOf(':') < 0) {
            ResourceLocation fallback = ResourceLocation.tryParse(fallbackId == null ? "" : fallbackId.trim());
            if (fallback == null) return null;
            parsed = ResourceLocation.tryParse(fallback.getNamespace() + ":" + text);
        } else {
            parsed = ResourceLocation.tryParse(text);
        }
        if (parsed == null) return null;
        String path = parsed.getPath();
        if (!folderSafe(path)) return null;
        return parsed;
    }

    private static boolean folderSafe(String path) {
        if (path == null || path.isEmpty()) return false;
        if (path.charAt(0) == '/' || path.charAt(path.length() - 1) == '/') return false;
        return !path.contains("..") && !path.contains("//");
    }

    private static RecipeEditorResultPacket export(RecipeEditorExportPacket packet) {
        try {
            if (packet.payload.length() > ShanhaiRecipeEditorValidation.MAX_PAYLOAD_CHARS) {
                return invalid("editor-payload-too-large");
            }
            ResourceLocation typeId = ResourceLocation.tryParse(packet.recipeTypeId);
            if (typeId == null || !folderSafe(typeId.getPath())
                    || ResourceLocation.tryParse(packet.sourceRecipeId) == null) {
                return invalid("invalid-recipe-identity");
            }
            ResourceLocation exportId = exportLocation(packet.exportRecipeId, packet.sourceRecipeId);
            if (exportId == null) return invalid("invalid-export-id");

            JsonObject json = JsonParser.parseString(packet.payload).getAsJsonObject();
            if (!json.has("recipeTypeId")
                    || !packet.recipeTypeId.equals(json.get("recipeTypeId").getAsString())) {
                return invalid("recipe-identity-mismatch");
            }
            String conditionNote = json.has("conditionNote") && json.get("conditionNote").isJsonPrimitive()
                    ? json.get("conditionNote").getAsString() : "";
            json.addProperty("recipeId", exportId.toString());
            ShanhaiRecipeBase edited = ShanhaiRecipeBase.fromPayload(json);
            String validation = ShanhaiRecipeEditorValidation.validateBase(edited);
            if (validation != null) return invalid(validation);

            GTRecipe snapshot = RecipeOriginalSnapshotStore.copyOf(packet.recipeTypeId, packet.sourceRecipeId);
            if (snapshot == null) return invalid("recipe-not-found");
            GTRecipe original = RecipeRebuildService.buildCanonical(packet.recipeTypeId, snapshot);
            if (original == null) return invalid("recipe-not-found");
            String conditionDiff = conditionNote.isEmpty()
                    ? ShanhaiRecipeConditions.diffLine(
                            ShanhaiRecipeBase.from(original).conditions(), edited.conditions())
                    : conditionNote;
            GTRecipe recipe = edited.toGtRecipe(original);
            if (recipe == null) return invalid("recipe-not-found");

            JsonElement encoded = GTRecipeSerializer.CODEC.encodeStart(JsonOps.INSTANCE, recipe)
                    .getOrThrow(false, GTDishanhaiMod.LOGGER::warn);
            if (!encoded.isJsonObject()
                    || GTRecipeSerializer.CODEC.parse(JsonOps.INSTANCE, encoded).result().isEmpty()) {
                return invalid("export-codec-rejected");
            }
            writeEnabledCondition(encoded.getAsJsonObject(), exportId);

            Path root = FMLPaths.GAMEDIR.get()
                    .resolve("kubejs").resolve("data").resolve(EXPORT_BUCKET)
                    .toAbsolutePath().normalize();
            Path file = root.resolve(typeId.getPath())
                    .resolve(exportId.getNamespace())
                    .resolve(exportId.getPath() + ".json")
                    .normalize();
            if (!file.startsWith(root)) return invalid("invalid-export-path");
            Files.createDirectories(file.getParent());
            Files.writeString(file, PRETTY.toJson(encoded), StandardCharsets.UTF_8);

            String relative = "kubejs/data/" + EXPORT_BUCKET + "/" + typeId.getPath()
                    + "/" + exportId.getNamespace() + "/" + exportId.getPath() + ".json";
            return new RecipeEditorResultPacket(
                    RecipeEditorResultPacket.Status.SUCCESS,
                    "export",
                    0L,
                    exportId + "\n" + relative + "\n" + conditionDiff);
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[配方编辑器] 导出配方 json 失败", e);
            return invalid("export-failed");
        }
    }

    /**
     * Forge datapack condition, separate from GTCEu {@code recipeConditions}.
     * The key is {@code namespace:leaf}, matching {@code gt_shanhai:ku_ming_yuan_yang}.
     */
    private static void writeEnabledCondition(JsonObject recipe, ResourceLocation exportId) {
        String enabledId = ShanhaiRecipeJsExport.enabledRecipeId(exportId);
        if (enabledId.isEmpty()) return;
        JsonArray conditions = recipe.has("conditions") && recipe.get("conditions").isJsonArray()
                ? recipe.getAsJsonArray("conditions") : new JsonArray();
        for (JsonElement element : conditions) {
            if (!element.isJsonObject()) continue;
            JsonObject existing = element.getAsJsonObject();
            if (existing.has("type")
                    && "gt_shanhai:recipe_enabled".equals(existing.get("type").getAsString())) {
                return;
            }
        }
        JsonObject condition = new JsonObject();
        condition.addProperty("type", "gt_shanhai:recipe_enabled");
        condition.addProperty("recipeId", enabledId);
        condition.addProperty("defaultEnabled", false);
        conditions.add(condition);
        recipe.add("conditions", conditions);
    }

    private static RecipeEditorResultPacket invalid(String message) {
        return new RecipeEditorResultPacket(
                RecipeEditorResultPacket.Status.VALIDATION_ERROR, message, 0L, "");
    }

    private static String exportChat(String payload) {
        String[] lines = payload == null ? new String[0] : payload.split("\n", 3);
        String id = lines.length > 0 ? lines[0] : "";
        String path = lines.length > 1 ? lines[1] : "";
        String diff = lines.length > 2 ? lines[2] : "";
        String message = "§7" + id + "\n§7" + path;
        if (!diff.isEmpty()) message += "\n" + diff;
        return message;
    }

    private static void send(ServerPlayer player, RecipeEditorResultPacket packet) {
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
