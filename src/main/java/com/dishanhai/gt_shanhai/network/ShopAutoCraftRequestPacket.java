package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopAutoCraft;
import com.dishanhai.gt_shanhai.common.shop.ShopConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 花费预览「补齐全部缺口」按钮（C→S）：对选中商品当前不够的成本项，向玩家绑定的在线 AE 网络
 * 起一轮自动合成计算（{@link ShopAutoCraft#beginPlan}），算完服务端另推 {@link ShopAutoCraftPlanPacket}
 * 回来让玩家确认用料后再提交。
 */
public class ShopAutoCraftRequestPacket {

    public enum Target { COST, STAGE, ENTRY_SUBMISSION, CART }

    private final Target target;
    private final long catalogRevision;
    private final long entryKey;
    private final long times;
    private final boolean aeMode;
    private final String stagePath;
    private final int requestId;

    public ShopAutoCraftRequestPacket(long catalogRevision, long entryKey, long times, boolean aeMode) {
        this(Target.COST, catalogRevision, entryKey, times, "", aeMode);
    }

    public ShopAutoCraftRequestPacket(long catalogRevision, long entryKey, long times, boolean aeMode, int requestId) {
        this(Target.COST, catalogRevision, entryKey, times, "", aeMode, requestId);
    }

    /** 购物车「AE下单补齐缺口」：购物车内容存在服务端，请求里不带商品。 */
    public static ShopAutoCraftRequestPacket cart(long catalogRevision, boolean aeMode, int requestId) {
        return new ShopAutoCraftRequestPacket(Target.CART, catalogRevision, 0L, 1L, "", aeMode, requestId);
    }

    public ShopAutoCraftRequestPacket(Target target, long catalogRevision, long entryKey,
                                      long times, String stagePath, boolean aeMode) {
        this(target, catalogRevision, entryKey, times, stagePath, aeMode, 0);
    }

    private ShopAutoCraftRequestPacket(Target target, long catalogRevision, long entryKey,
                                      long times, String stagePath, boolean aeMode, int requestId) {
        this.target = target == null ? Target.COST : target;
        this.catalogRevision = catalogRevision;
        this.entryKey = entryKey;
        this.times = times;
        this.aeMode = aeMode;
        this.stagePath = stagePath == null ? "" : stagePath;
        this.requestId = requestId;
    }

    public ShopAutoCraftRequestPacket(FriendlyByteBuf buf) {
        this.target = buf.readEnum(Target.class);
        this.catalogRevision = buf.readLong();
        this.entryKey = buf.readLong();
        this.times = buf.readVarLong();
        this.stagePath = buf.readUtf(256);
        this.aeMode = buf.readBoolean();
        this.requestId = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(target);
        buf.writeLong(catalogRevision);
        buf.writeLong(entryKey);
        buf.writeVarLong(times);
        buf.writeUtf(stagePath, 256);
        buf.writeBoolean(aeMode);
        buf.writeVarInt(requestId);
    }

    public static void handle(ShopAutoCraftRequestPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            if (pkt.target == Target.STAGE) {
                ShopAutoCraft.beginStagePlan(player, pkt.stagePath, pkt.aeMode);
                return;
            }
            if (pkt.target == Target.CART) {
                ShopAutoCraft.beginCartPlan(player, pkt.aeMode, pkt.requestId);
                return;
            }
            ShopEntry entry = ShopConfig.resolve(pkt.catalogRevision, pkt.entryKey);
            if (entry == null) {
                ShopAutoCraft.sendPlanFeedback(player, pkt.requestId, false);
                return;
            }
            if (pkt.target == Target.ENTRY_SUBMISSION) {
                ShopAutoCraft.beginEntrySubmissionPlan(player, entry, pkt.aeMode);
            } else {
                ShopAutoCraft.beginPlan(player, entry, Math.max(1L, pkt.times), pkt.aeMode, pkt.requestId);
            }
        });
        context.setPacketHandled(true);
    }
}
