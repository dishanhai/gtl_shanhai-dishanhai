package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;
import com.dishanhai.gt_shanhai.common.shop.ShopPurchase;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * 花费预览请求（C→S）：客户端在商店详情页选中商品 / 切换 AE 模式时发起，服务端算一次只读余量
 * （{@link ShopPurchase#previewHave}）后用 {@link ShopCostPreviewPacket} 推回同一玩家。
 * 版本过期 / 商品不存在时静默丢弃——预览是纯展示辅助，不像购买动作需要提示玩家重试。
 */
public class ShopCostPreviewRequestPacket {

    private final long catalogRevision;
    private final long entryKey;
    private final boolean aeMode;

    public ShopCostPreviewRequestPacket(long catalogRevision, long entryKey, boolean aeMode) {
        this.catalogRevision = catalogRevision;
        this.entryKey = entryKey;
        this.aeMode = aeMode;
    }

    public ShopCostPreviewRequestPacket(FriendlyByteBuf buf) {
        this.catalogRevision = buf.readLong();
        this.entryKey = buf.readLong();
        this.aeMode = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(catalogRevision);
        buf.writeLong(entryKey);
        buf.writeBoolean(aeMode);
    }

    public static void handle(ShopCostPreviewRequestPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> apply(pkt, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void apply(ShopCostPreviewRequestPacket pkt, ServerPlayer player) {
        if (player == null) return;
        ShopEntry entry = ShopConfig.resolve(pkt.catalogRevision, pkt.entryKey);
        if (entry == null || !entry.isValid()) return;
        ShopPurchase.CostPreview preview = ShopPurchase.previewHave(player, entry.getCost(), pkt.aeMode);
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ShopCostPreviewPacket(pkt.entryKey, pkt.aeMode, preview));
    }
}
