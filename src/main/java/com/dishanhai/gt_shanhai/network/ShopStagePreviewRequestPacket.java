package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopCost;
import com.dishanhai.gt_shanhai.common.shop.ShopPurchase;
import com.dishanhai.gt_shanhai.common.shop.ShopStageConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.math.BigInteger;
import java.util.function.Supplier;

/** 阶段解锁页库存预览请求；复用商品花费预览的背包/精妙背包/AE 统计口径。 */
public final class ShopStagePreviewRequestPacket {
    private final String stagePath;
    private final boolean aeMode;

    public ShopStagePreviewRequestPacket(String stagePath, boolean aeMode) {
        this.stagePath = stagePath == null ? "" : stagePath;
        this.aeMode = aeMode;
    }

    public ShopStagePreviewRequestPacket(FriendlyByteBuf buf) {
        this.stagePath = buf.readUtf(256);
        this.aeMode = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(stagePath, 256);
        buf.writeBoolean(aeMode);
    }

    public static void handle(ShopStagePreviewRequestPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> apply(packet, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void apply(ShopStagePreviewRequestPacket packet, ServerPlayer player) {
        if (player == null || packet.stagePath.isBlank() || !ShopStageConfig.has(packet.stagePath)) return;
        ShopCost cost = new ShopCost(BigInteger.ZERO, null, ShopStageConfig.get(packet.stagePath));
        ShopPurchase.CostPreview preview = ShopPurchase.previewHave(player, cost, packet.aeMode);
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ShopStagePreviewPacket(packet.stagePath, packet.aeMode, preview.items()));
    }
}
