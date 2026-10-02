package com.dishanhai.gt_shanhai.network;

import appeng.menu.me.items.PatternEncodingTermMenu;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.item.JeiPatternQuickEncodeService;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class JeiPatternQuickEncodeRequestPacket {

    private static final int MAX_RECIPE_ID_LENGTH = 512;

    private final int menuId;
    private final String recipeId;
    private final boolean wholeRecipeType;

    public JeiPatternQuickEncodeRequestPacket(int menuId, String recipeId, boolean wholeRecipeType) {
        this.menuId = menuId;
        this.recipeId = recipeId == null ? "" : recipeId;
        this.wholeRecipeType = wholeRecipeType;
    }

    public JeiPatternQuickEncodeRequestPacket(FriendlyByteBuf buffer) {
        this(buffer.readVarInt(), buffer.readUtf(MAX_RECIPE_ID_LENGTH), buffer.readBoolean());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(menuId);
        buffer.writeUtf(recipeId, MAX_RECIPE_ID_LENGTH);
        buffer.writeBoolean(wholeRecipeType);
    }

    public static void handle(JeiPatternQuickEncodeRequestPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> apply(packet, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void apply(JeiPatternQuickEncodeRequestPacket packet, ServerPlayer player) {
        if (player == null) {
            GTDishanhaiMod.LOGGER.warn("[JEIQuickEncode] request rejected: missing server player menuId={} recipeId={}",
                    packet.menuId, packet.recipeId);
            return;
        }
        if (!(player.containerMenu instanceof PatternEncodingTermMenu menu)) {
            GTDishanhaiMod.LOGGER.warn("[JEIQuickEncode] request rejected: current menu is {} menuId={} recipeId={}",
                    player.containerMenu == null ? "null" : player.containerMenu.getClass().getName(),
                    packet.menuId, packet.recipeId);
            return;
        }
        if (menu.containerId != packet.menuId) {
            GTDishanhaiMod.LOGGER.warn("[JEIQuickEncode] request rejected: menu id mismatch packet={} current={} recipeId={}",
                    packet.menuId, menu.containerId, packet.recipeId);
            return;
        }
        if (!menu.stillValid(player)) {
            GTDishanhaiMod.LOGGER.warn("[JEIQuickEncode] request rejected: menu no longer valid menuId={} recipeId={}",
                    packet.menuId, packet.recipeId);
            return;
        }
        GTDishanhaiMod.LOGGER.info("[JEIQuickEncode] request accepted player={} menuId={} recipeId={} wholeRecipeType={}",
                player.getGameProfile().getName(), packet.menuId, packet.recipeId, packet.wholeRecipeType);
        JeiPatternQuickEncodeService.encodeAndUpload(
                player, menu, packet.recipeId, packet.wholeRecipeType);
    }
}
