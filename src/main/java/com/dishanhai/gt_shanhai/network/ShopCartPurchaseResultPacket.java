package com.dishanhai.gt_shanhai.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 购物车结算单项结果回执（S→C）：{@link ShopActionPacket}（{@code fromCart=true}）处理完后，
 * 把这一项的成交次数/原因结构化推回客户端，供购物车面板逐行显示成功/部分/失败（见反馈：
 * 结算后看不到哪个买成了哪个失败了）。
 *
 * <p>{@code done>=requested} 视为全部成交——客户端据此把该项从购物车移除；{@code 0<done<requested}
 * 为部分成交——客户端把购物车数量改成剩余未购的 {@code requested-done}；{@code done<=0} 为完全失败——
 * 购物车数量原样保留，只更新展示的失败原因，方便玩家原地重试。</p>
 */
public class ShopCartPurchaseResultPacket {

    private final long entryKey;
    private final long requested;
    private final long done;
    private final String reason;

    public ShopCartPurchaseResultPacket(long entryKey, long requested, long done, String reason) {
        this.entryKey = entryKey;
        this.requested = requested;
        this.done = done;
        this.reason = reason == null ? "" : reason;
    }

    public ShopCartPurchaseResultPacket(FriendlyByteBuf buf) {
        this.entryKey = buf.readLong();
        this.requested = buf.readVarLong();
        this.done = buf.readVarLong();
        this.reason = buf.readUtf(256);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(entryKey);
        buf.writeVarLong(requested);
        buf.writeVarLong(done);
        buf.writeUtf(reason, 256);
    }

    public static void handle(ShopCartPurchaseResultPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> applyClient(pkt));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyClient(ShopCartPurchaseResultPacket pkt) {
        com.dishanhai.gt_shanhai.client.shop.ClientShopCart.applyPurchaseResult(
                pkt.entryKey, pkt.requested, pkt.done, pkt.reason);
    }
}
