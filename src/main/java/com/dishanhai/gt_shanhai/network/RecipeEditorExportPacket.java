package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
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
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
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
 * <p>
 * {@code developerMode} writes straight into the mod datapack
 * {@code data/gt_shanhai/recipes/<type>/<recipe path>.json}.
 * A recipe path that does not already start with {@code <type>/} gets that folder.
 */
public final class RecipeEditorExportPacket {

    /** 集中存放导出 json 的文件夹。不是配方命名空间。 */
    private static final String EXPORT_BUCKET = "Exported_Recipe";

    /**
     * 聊天路径的点击命令。客户端拦截后用资源管理器定位文件，不会发给服务器。
     * {@code OPEN_FILE} 从服务器下发会被客户端丢掉，所以这里用允许下发的 {@code RUN_COMMAND}。
     */
    public static final String REVEAL_COMMAND = "/gtshanhai_reveal_export ";

    /** 开发模式的模组源码配方目录。只在 developerMode 打开时使用。 */
    private static final Path DEV_RECIPE_ROOT = Path.of(
            "C:/Users/dishanhai/Desktop/gt_shanhai/src/main/resources/data/gt_shanhai/recipes");

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
                sender.sendSystemMessage(exportChat(result.payload()));
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

    /**
     * {@code recipes/<type>/<body>}. Body keeps a subcategory already present in the id.
     * {@code sps_crafting/metals/dust} stays. {@code dust} becomes {@code sps_crafting/dust}.
     */
    public static String pathUnderType(ResourceLocation typeId, ResourceLocation exportId) {
        String typePath = typeId.getPath();
        String recipePath = exportId.getPath();
        String body = recipePath.startsWith(typePath + "/")
                ? recipePath.substring(typePath.length() + 1)
                : recipePath;
        if (body.isEmpty()) body = typePath;
        return typePath + "/" + body;
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
            boolean createNew = json.has("createNew")
                    && json.get("createNew").isJsonPrimitive()
                    && json.get("createNew").getAsBoolean();
            GTRecipe recipe;
            String conditionDiff = conditionNote;
            if (createNew) {
                recipe = RecipeRebuildService.materializeFresh(packet.recipeTypeId, edited);
                if (recipe == null) return invalid("invalid-recipe-type");
            } else {
                GTRecipe snapshot = RecipeOriginalSnapshotStore.copyOf(packet.recipeTypeId, packet.sourceRecipeId);
                if (snapshot == null) {
                    // Added recipes are not in the original snapshot. Export the draft itself.
                    recipe = RecipeRebuildService.materializeFresh(packet.recipeTypeId, edited);
                    if (recipe == null) return invalid("recipe-not-found");
                } else {
                    GTRecipe original = RecipeRebuildService.buildCanonical(packet.recipeTypeId, snapshot);
                    if (original == null) return invalid("recipe-not-found");
                    if (conditionNote.isEmpty()) {
                        conditionDiff = ShanhaiRecipeConditions.diffLine(
                                ShanhaiRecipeBase.from(original).conditions(), edited.conditions());
                    }
                    recipe = edited.toGtRecipe(original);
                    if (recipe == null) return invalid("recipe-not-found");
                }
            }

            JsonElement encoded = GTRecipeSerializer.CODEC.encodeStart(JsonOps.INSTANCE, recipe)
                    .getOrThrow(false, GTDishanhaiMod.LOGGER::warn);
            if (!encoded.isJsonObject()
                    || GTRecipeSerializer.CODEC.parse(JsonOps.INSTANCE, encoded).result().isEmpty()) {
                return invalid("export-codec-rejected");
            }
            writeEnabledCondition(encoded.getAsJsonObject(), exportId);

            boolean developer = developerExport();
            String placed = pathUnderType(typeId, exportId);
            String typePath = typeId.getPath();
            String body = placed.substring(typePath.length() + 1);
            Path root = developer
                    ? DEV_RECIPE_ROOT.toAbsolutePath().normalize()
                    : FMLPaths.GAMEDIR.get()
                            .resolve("kubejs").resolve("data").resolve(EXPORT_BUCKET)
                            .toAbsolutePath().normalize();
            Path file = developer
                    ? root.resolve(placed + ".json").normalize()
                    : root.resolve(typePath)
                            .resolve(exportId.getNamespace())
                            .resolve(body + ".json")
                            .normalize();
            if (!file.startsWith(root)) return invalid("invalid-export-path");
            Files.createDirectories(file.getParent());
            Files.writeString(file, PRETTY.toJson(encoded), StandardCharsets.UTF_8);

            String relative = developer
                    ? file.toString()
                    : "kubejs/data/" + EXPORT_BUCKET + "/" + typePath
                            + "/" + exportId.getNamespace() + "/" + body + ".json";
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
        condition.addProperty("defaultEnabled", true);
        conditions.add(condition);
        recipe.add("conditions", conditions);
    }

    private static boolean developerExport() {
        try {
            return DShanhaiConfig.COMMON.developerMode.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    private static RecipeEditorResultPacket invalid(String message) {
        return new RecipeEditorResultPacket(
                RecipeEditorResultPacket.Status.VALIDATION_ERROR, message, 0L, "");
    }

    private static Component exportChat(String payload) {
        String[] lines = payload == null ? new String[0] : payload.split("\n", 3);
        String id = lines.length > 0 ? lines[0] : "";
        String path = lines.length > 1 ? lines[1] : "";
        String diff = lines.length > 2 ? lines[2] : "";
        MutableComponent message = Component.literal("§b[配方修改器] §a已导出\n§7" + id);
        if (!path.isEmpty()) {
            message.append(Component.literal("\n"));
            message.append(exportPathLink(path));
        }
        if (!diff.isEmpty()) message.append(Component.literal("\n" + diff));
        return message;
    }

    /** 路径行可点击。点击值是绝对路径，显示文字仍是原来的相对路径或开发模式绝对路径。 */
    private static Component exportPathLink(String shown) {
        Path file;
        try {
            file = shownPath(shown);
        } catch (java.nio.file.InvalidPathException ex) {
            return Component.literal("§7" + shown);
        }
        return Component.literal(shown).withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(
                        ClickEvent.Action.RUN_COMMAND, REVEAL_COMMAND + file))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("点击在资源管理器中定位").withStyle(ChatFormatting.YELLOW))));
    }

    private static Path shownPath(String shown) {
        Path path = Path.of(shown.trim());
        if (!path.isAbsolute()) {
            path = FMLPaths.GAMEDIR.get().resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    /** 只允许定位本次导出会写到的两个目录里的 json。 */
    public static boolean revealable(Path file) {
        if (file == null) return false;
        Path normalized = file.toAbsolutePath().normalize();
        Path name = normalized.getFileName();
        if (name == null || !name.toString().endsWith(".json")) return false;
        Path exportRoot = FMLPaths.GAMEDIR.get()
                .resolve("kubejs").resolve("data").resolve(EXPORT_BUCKET)
                .toAbsolutePath().normalize();
        if (normalized.startsWith(exportRoot)) return true;
        return normalized.startsWith(DEV_RECIPE_ROOT.toAbsolutePath().normalize());
    }

    private static void send(ServerPlayer player, RecipeEditorResultPacket packet) {
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
