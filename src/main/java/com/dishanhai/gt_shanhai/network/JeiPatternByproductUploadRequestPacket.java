package com.dishanhai.gt_shanhai.network;

import appeng.menu.me.items.PatternEncodingTermMenu;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.item.JeiPatternQuickEncodeService;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** JEI 上单独的副产物上传。不走普通快速编写那条包。 */
public final class JeiPatternByproductUploadRequestPacket {

    private static final int MAX_RECIPE_ID_LENGTH = 512;

    private final int menuId;
    private final String recipeId;

    public JeiPatternByproductUploadRequestPacket(int menuId, String recipeId) {
        this.menuId = menuId;
        this.recipeId = recipeId == null ? "" : recipeId;
    }

    public JeiPatternByproductUploadRequestPacket(FriendlyByteBuf buffer) {
        this(buffer.readVarInt(), buffer.readUtf(MAX_RECIPE_ID_LENGTH));
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(menuId);
        buffer.writeUtf(recipeId, MAX_RECIPE_ID_LENGTH);
    }

    public static void handle(JeiPatternByproductUploadRequestPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> apply(packet, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void apply(JeiPatternByproductUploadRequestPacket packet, ServerPlayer player) {
        if (player == null) return;
        if (!(player.containerMenu instanceof PatternEncodingTermMenu menu)) {
            GTDishanhaiMod.LOGGER.warn("[JEIByproductUpload] current menu is not the pattern terminal");
            return;
        }
        if (menu.containerId != packet.menuId || !menu.stillValid(player)) return;
        JeiPatternQuickEncodeService.encodeAndUploadWithByproductOrder(player, menu, packet.recipeId);
    }
}
