package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopActionOutcome;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 详情页买卖的结构化回执（S→C）：{@link ShopActionPacket} 带了请求号（{@code requestId!=0}）的 BUY / SELL
 * 处理完后，把成交次数与结果推回客户端，让确认按钮自己显示等待 → 成功 / 部分 / 未成交。
 *
 * <p>与购物车的 {@link ShopCartPurchaseResultPacket} 分开：那条会改购物车数量，这条只驱动按钮反馈。
 * 聊天消息（横幅）照发，先于本包到达。{@code reason} 是给日志与排查看的内部字串，不上界面，超长截断。</p>
 */
public class ShopActionResultPacket {

    private static final int REASON_MAX = 256;

    private final int requestId;
    private final ShopActionPacket.Action action;
    private final long entryKey;
    private final long requested;
    private final long done;
    private final ShopActionOutcome outcome;
    private final String reason;

    public ShopActionResultPacket(int requestId, ShopActionPacket.Action action, long entryKey,
                                  long requested, long done, String reason) {
        this.requestId = requestId;
        this.action = action;
        this.entryKey = entryKey;
        this.requested = requested;
        this.done = done;
        this.outcome = ShopActionOutcome.classify(requested, done);
        this.reason = clip(reason);
    }

    public ShopActionResultPacket(FriendlyByteBuf buf) {
        this.requestId = buf.readVarInt();
        this.action = buf.readEnum(ShopActionPacket.Action.class);
        this.entryKey = buf.readLong();
        this.requested = buf.readVarLong();
        this.done = buf.readVarLong();
        this.outcome = buf.readEnum(ShopActionOutcome.class);
        this.reason = buf.readUtf(REASON_MAX);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(requestId);
        buf.writeEnum(action);
        buf.writeLong(entryKey);
        buf.writeVarLong(requested);
        buf.writeVarLong(done);
        buf.writeEnum(outcome);
        buf.writeUtf(reason, REASON_MAX);
    }

    public ShopActionOutcome outcome() {
        return outcome;
    }

    public String reason() {
        return reason;
    }

    private static String clip(String reason) {
        if (reason == null) return "";
        return reason.length() <= REASON_MAX ? reason : reason.substring(0, REASON_MAX);
    }

    public static void handle(ShopActionResultPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> applyClient(pkt));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyClient(ShopActionResultPacket pkt) {
        com.dishanhai.gt_shanhai.client.shop.ClientShopActionResults.offer(
                new com.dishanhai.gt_shanhai.client.shop.ClientShopActionResults.Result(
                        pkt.requestId, pkt.action, pkt.entryKey, pkt.requested, pkt.done,
                        pkt.outcome, pkt.reason, System.currentTimeMillis()));
    }
}
