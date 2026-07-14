package com.dishanhai.gt_shanhai.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 购物车快照同步（S→C）：稳定身份 ID → 候选购买次数（保序）。服务端在<b>打开商店</b>及<b>每次
 * 购物车变动</b>后向该玩家推送全量，客户端写入 {@code ClientShopCart} 缓存（见 {@link WalletAccountSyncPacket}
 * 同款"打开即推 + 变动即推"节奏）。
 */
public class ShopCartSyncPacket {

    private final Map<String, Long> items;

    public ShopCartSyncPacket(Map<String, Long> items) {
        this.items = items != null ? items : new LinkedHashMap<>();
    }

    public ShopCartSyncPacket(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<String, Long> map = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String stableId = buf.readUtf();
            long amount = buf.readVarLong();
            map.put(stableId, amount);
        }
        this.items = map;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(items.size());
        for (Map.Entry<String, Long> e : items.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeVarLong(e.getValue() == null ? 0L : e.getValue());
        }
    }

    public static void handle(ShopCartSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> applyClient(pkt));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyClient(ShopCartSyncPacket pkt) {
        com.dishanhai.gt_shanhai.client.shop.ClientShopCart.apply(pkt.items);
    }
}
