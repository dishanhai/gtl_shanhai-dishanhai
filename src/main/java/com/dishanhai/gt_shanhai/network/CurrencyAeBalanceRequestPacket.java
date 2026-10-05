package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.item.WalletItem;
import com.dishanhai.gt_shanhai.common.shop.CurrencyRateConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopPurchase;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * 货币 ATM「AE 网络余额」预览请求（C→S）：客户端在 ATM 界面打开或刷新时发起，
 * 服务端用 {@link ShopPurchase#aeAvailableCoin} 算一次只读余量后用 {@link CurrencyAeBalancePacket} 推回，
 * 让货币中心同时显示钱包与 AE 网络中的余额。
 * 纯展示辅助，未持钱包/参数无效时静默丢弃，不像结算动作需要提示玩家重试。
 */
public class CurrencyAeBalanceRequestPacket {

    private final ResourceLocation currency;
    private final boolean all;

    public CurrencyAeBalanceRequestPacket(ResourceLocation currency) {
        this.currency = currency == null ? new ResourceLocation("minecraft:air") : currency;
        this.all = false;
    }

    public CurrencyAeBalanceRequestPacket() {
        this.currency = new ResourceLocation("minecraft:air");
        this.all = true;
    }

    public CurrencyAeBalanceRequestPacket(FriendlyByteBuf buf) {
        this.all = buf.readBoolean();
        this.currency = buf.readResourceLocation();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(all);
        buf.writeResourceLocation(currency);
    }

    public static void handle(CurrencyAeBalanceRequestPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> apply(pkt, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void apply(CurrencyAeBalanceRequestPacket pkt, ServerPlayer player) {
        if (player == null || !WalletItem.isCarrying(player)) return;
        if (pkt.all) {
            for (ResourceLocation currency : CurrencyRateConfig.getCurrencies()) {
                sendBalance(player, currency);
            }
            return;
        }
        sendBalance(player, pkt.currency);
    }

    private static void sendBalance(ServerPlayer player, ResourceLocation currency) {
        long available = ShopPurchase.aeAvailableCoin(player, currency);
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new CurrencyAeBalancePacket(currency, available));
    }
}
