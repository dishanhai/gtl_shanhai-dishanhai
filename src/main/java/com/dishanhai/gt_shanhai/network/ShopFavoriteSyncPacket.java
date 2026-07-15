package com.dishanhai.gt_shanhai.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 收藏快照同步（S→C）：稳定身份 ID 集合（保序）。服务端在<b>打开商店</b>及<b>每次收藏变动</b>后向该
 * 玩家推送全量，客户端写入 {@code ClientShopFavorites} 缓存（同 {@link ShopCartSyncPacket} 同款节奏）。
 */
public class ShopFavoriteSyncPacket {

    private final Set<String> stableIds;

    public ShopFavoriteSyncPacket(Set<String> stableIds) {
        this.stableIds = stableIds != null ? stableIds : new LinkedHashSet<>();
    }

    public ShopFavoriteSyncPacket(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Set<String> set = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            set.add(buf.readUtf());
        }
        this.stableIds = set;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(stableIds.size());
        for (String id : stableIds) {
            buf.writeUtf(id);
        }
    }

    public static void handle(ShopFavoriteSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> applyClient(pkt));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyClient(ShopFavoriteSyncPacket pkt) {
        com.dishanhai.gt_shanhai.client.shop.ClientShopFavorites.apply(pkt.stableIds);
    }
}
