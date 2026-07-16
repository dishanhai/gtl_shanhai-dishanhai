package com.dishanhai.gt_shanhai.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 货币 ATM「AE 网络余额」预览响应（S→C）：{@link CurrencyAeBalanceRequestPacket} 的回包，
 * 写入 {@code ClientAeCurrencyBalance} 缓存，供 {@code CurrencyAtmScreen} 在详情面板显示。
 */
public class CurrencyAeBalancePacket {

    private final ResourceLocation currency;
    private final long available;

    public CurrencyAeBalancePacket(ResourceLocation currency, long available) {
        this.currency = currency;
        this.available = available;
    }

    public CurrencyAeBalancePacket(FriendlyByteBuf buf) {
        this.currency = buf.readResourceLocation();
        this.available = buf.readVarLong();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(currency);
        buf.writeVarLong(available);
    }

    public static void handle(CurrencyAeBalancePacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> handleClient(pkt));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(CurrencyAeBalancePacket pkt) {
        com.dishanhai.gt_shanhai.client.shop.ClientAeCurrencyBalance.apply(pkt.currency, pkt.available);
    }
}
