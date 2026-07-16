package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.item.WalletItem;
import com.dishanhai.gt_shanhai.common.shop.ShopPurchase;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * 货币 ATM「AE 网络余额」预览请求（C→S）：客户端在 ATM 界面选中币种 / 切换 AE 模式时发起，
 * 服务端用 {@link ShopPurchase#aeAvailableCoin} 算一次只读余量后用 {@link CurrencyAeBalancePacket} 推回，
 * 好让玩家在「从 AE 抽取」前就知道网络里实际有多少、该输多少，不用靠试错。
 * 纯展示辅助，未持钱包/参数无效时静默丢弃，不像结算动作需要提示玩家重试。
 */
public class CurrencyAeBalanceRequestPacket {

    private final ResourceLocation currency;

    public CurrencyAeBalanceRequestPacket(ResourceLocation currency) {
        this.currency = currency == null ? new ResourceLocation("minecraft:air") : currency;
    }

    public CurrencyAeBalanceRequestPacket(FriendlyByteBuf buf) {
        this.currency = buf.readResourceLocation();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(currency);
    }

    public static void handle(CurrencyAeBalanceRequestPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> apply(pkt, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void apply(CurrencyAeBalanceRequestPacket pkt, ServerPlayer player) {
        if (player == null || !WalletItem.isCarrying(player)) return;
        long available = ShopPurchase.aeAvailableCoin(player, pkt.currency);
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new CurrencyAeBalancePacket(pkt.currency, available));
    }
}
