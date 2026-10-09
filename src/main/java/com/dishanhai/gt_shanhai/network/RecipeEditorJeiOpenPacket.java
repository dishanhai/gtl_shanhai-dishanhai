package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.item.RecipeModifierDevItem;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorFactory;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeQuery;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** JEI side button: open the recipe modifier on one existing recipe. */
public final class RecipeEditorJeiOpenPacket {

    private final String recipeTypeId;
    private final String recipeId;

    public RecipeEditorJeiOpenPacket(String recipeTypeId, String recipeId) {
        this.recipeTypeId = recipeTypeId == null ? "" : recipeTypeId;
        this.recipeId = recipeId == null ? "" : recipeId;
    }

    public RecipeEditorJeiOpenPacket(FriendlyByteBuf buffer) {
        this.recipeTypeId = buffer.readUtf(256);
        this.recipeId = buffer.readUtf(256);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(recipeTypeId, 256);
        buffer.writeUtf(recipeId, 256);
    }

    public static void handle(RecipeEditorJeiOpenPacket packet,
                              Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> open(packet, sender));
        context.setPacketHandled(true);
    }

    private static void open(RecipeEditorJeiOpenPacket packet, ServerPlayer player) {
        if (player == null) return;
        if (!RecipeModifierDevItem.inInventory(player)) {
            tell(player, "message.gt_shanhai.jei.edit_recipe.need_tool");
            return;
        }
        if (!player.hasPermissions(2)) {
            tell(player, "message.gt_shanhai.jei.edit_recipe.need_permission");
            return;
        }
        ShanhaiRecipeQuery.Located located =
                ShanhaiRecipeQuery.locate(packet.recipeTypeId, packet.recipeId);
        if (packet.recipeTypeId.isEmpty() || packet.recipeId.isEmpty() || located == null) {
            player.displayClientMessage(Component.translatable(
                    "message.gt_shanhai.jei.edit_recipe.not_found",
                    packet.recipeTypeId, packet.recipeId).withStyle(ChatFormatting.RED), true);
            return;
        }
        if (!ShanhaiRecipeEditorFactory.open(player, located.typeId(), located.recipeId())) {
            tell(player, "message.gt_shanhai.jei.edit_recipe.unavailable");
        }
    }

    private static void tell(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.RED), true);
    }
}
