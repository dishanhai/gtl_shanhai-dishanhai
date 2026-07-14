package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopAutoCraft;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 自动合成确认框「确认合成」/「取消」（C→S）：confirm=true 真正提交
 * （{@link ShopAutoCraft#confirmPlan}），false 只是丢弃待确认会话（{@link ShopAutoCraft#cancel}）。
 */
public class ShopAutoCraftConfirmPacket {

    private final boolean confirm;

    public ShopAutoCraftConfirmPacket(boolean confirm) {
        this.confirm = confirm;
    }

    public ShopAutoCraftConfirmPacket(FriendlyByteBuf buf) {
        this.confirm = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(confirm);
    }

    public static void handle(ShopAutoCraftConfirmPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            if (pkt.confirm) ShopAutoCraft.confirmPlan(player);
            else ShopAutoCraft.cancel(player);
        });
        context.setPacketHandled(true);
    }
}
