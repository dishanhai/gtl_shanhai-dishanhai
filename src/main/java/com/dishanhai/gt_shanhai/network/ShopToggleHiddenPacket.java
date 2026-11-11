package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopEditPermission;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * 公共目录隐藏开关（C→S）。只允许已开启目录编辑模式的编辑者修改。
 */
public class ShopToggleHiddenPacket {

    private final long catalogRevision;
    private final long entryKey;

    public ShopToggleHiddenPacket(long catalogRevision, long entryKey) {
        this.catalogRevision = catalogRevision;
        this.entryKey = entryKey;
    }

    public ShopToggleHiddenPacket(FriendlyByteBuf buf) {
        this.catalogRevision = buf.readLong();
        this.entryKey = buf.readLong();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(catalogRevision);
        buf.writeLong(entryKey);
    }

    public static void handle(ShopToggleHiddenPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            apply(pkt, player);
        });
        context.setPacketHandled(true);
    }

    private static void apply(ShopToggleHiddenPacket pkt, ServerPlayer player) {
        if (!com.dishanhai.gt_shanhai.common.shop.ShopAvailability.requireEnabled(player)) return;
        if (!ShopEditPermission.canEditCatalog(player)) {
            player.sendSystemMessage(Component.translatable("gt_shanhai.shop.hidden.edit_required"));
            return;
        }
        ShopEntry old = ShopConfig.resolve(pkt.catalogRevision, pkt.entryKey);
        if (old == null) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 商品目录已更新或商品不存在，请重新打开"));
            return;
        }
        boolean hidden = !old.isHidden();
        boolean ok = ShopConfig.batchSetHidden(List.of(old.getStableId()), hidden) > 0;
        player.sendSystemMessage(ok
                ? Component.translatable(hidden ? "gt_shanhai.shop.hidden.global_hidden" : "gt_shanhai.shop.hidden.global_restored")
                : Component.literal("§c[山海商店] 操作失败"));
    }
}
