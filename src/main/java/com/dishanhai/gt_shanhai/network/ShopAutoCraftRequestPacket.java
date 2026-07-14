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

    private final long catalogRevision;
    private final long entryKey;
    private final long times;
    private final boolean aeMode;

    public ShopAutoCraftRequestPacket(long catalogRevision, long entryKey, long times, boolean aeMode) {
        this.catalogRevision = catalogRevision;
        this.entryKey = entryKey;
        this.times = times;
        this.aeMode = aeMode;
    }

    public ShopAutoCraftRequestPacket(FriendlyByteBuf buf) {
        this.catalogRevision = buf.readLong();
        this.entryKey = buf.readLong();
        this.times = buf.readVarLong();
        this.aeMode = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(catalogRevision);
        buf.writeLong(entryKey);
        buf.writeVarLong(times);
        buf.writeBoolean(aeMode);
    }

    public static void handle(ShopAutoCraftRequestPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            ShopEntry entry = ShopConfig.resolve(pkt.catalogRevision, pkt.entryKey);
            if (entry == null) return;
            ShopAutoCraft.beginPlan(player, entry, Math.max(1L, pkt.times), pkt.aeMode);
        });
        context.setPacketHandled(true);
    }
}
