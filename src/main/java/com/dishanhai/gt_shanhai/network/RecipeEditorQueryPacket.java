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
    private final String sort;
    private final ShanhaiRecipeQuery.SearchMode mode;
    private final ShanhaiRecipeQuery.IngredientKind ingredientKind;

    public RecipeEditorQueryPacket(String typeFilter, String text, int page, int pageSize) {
        this(typeFilter, text, page, pageSize, "id");
    }

    public RecipeEditorQueryPacket(String typeFilter, String text, int page, int pageSize, String sort) {
        this(typeFilter, text, page, pageSize, sort,
                ShanhaiRecipeQuery.SearchMode.RECIPE_ID, ShanhaiRecipeQuery.IngredientKind.ITEM);
    }

    public RecipeEditorQueryPacket(
            String typeFilter,
            String text,
            int page,
            int pageSize,
            ShanhaiRecipeQuery.SearchMode mode,
            ShanhaiRecipeQuery.IngredientKind ingredientKind) {
        this(typeFilter, text, page, pageSize, "id", mode, ingredientKind);
    }

    public RecipeEditorQueryPacket(
            String typeFilter,
            String text,
            int page,
            int pageSize,
            String sort,
            ShanhaiRecipeQuery.SearchMode mode,
            ShanhaiRecipeQuery.IngredientKind ingredientKind) {
        this.typeFilter = typeFilter == null ? "" : typeFilter;
        this.text = text == null ? "" : text;
        this.page = Math.max(0, page);
        this.pageSize = Math.max(1, Math.min(256, pageSize));
        this.sort = sort == null || sort.isEmpty() ? "id" : sort;
        this.mode = mode == null ? ShanhaiRecipeQuery.SearchMode.RECIPE_ID : mode;
        this.ingredientKind = ingredientKind == null
                ? ShanhaiRecipeQuery.IngredientKind.ITEM : ingredientKind;
    }

    public RecipeEditorQueryPacket(FriendlyByteBuf buf) {
        this.typeFilter = buf.readUtf(256);
        this.text = buf.readUtf(256);
        this.page = Math.max(0, buf.readVarInt());
        this.pageSize = Math.max(1, Math.min(256, buf.readVarInt()));
        this.sort = buf.readUtf(32);
        this.mode = enumValue(buf.readVarInt(), ShanhaiRecipeQuery.SearchMode.values(),
                ShanhaiRecipeQuery.SearchMode.RECIPE_ID);
        this.ingredientKind = enumValue(buf.readVarInt(), ShanhaiRecipeQuery.IngredientKind.values(),
                ShanhaiRecipeQuery.IngredientKind.ITEM);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(typeFilter, 256);
        buf.writeUtf(text, 256);
        buf.writeVarInt(page);
        buf.writeVarInt(pageSize);
        buf.writeUtf(sort, 32);
        buf.writeVarInt(mode.ordinal());
        buf.writeVarInt(ingredientKind.ordinal());
    }

    public String sort() {
        return sort;
    }

    public ShanhaiRecipeQuery.SearchMode mode() {
        return mode;
    }

    public ShanhaiRecipeQuery.IngredientKind ingredientKind() {
        return ingredientKind;
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
                    sender.getServer(), packet.typeFilter, packet.text, packet.page, packet.pageSize,
                    packet.mode, packet.ingredientKind);
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

    private static <T> T enumValue(int index, T[] values, T fallback) {
        return index < 0 || index >= values.length ? fallback : values[index];
    }
}
