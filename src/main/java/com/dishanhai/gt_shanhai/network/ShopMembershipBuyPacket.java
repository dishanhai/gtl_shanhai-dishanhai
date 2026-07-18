package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopMembership;
import com.dishanhai.gt_shanhai.common.shop.WalletAccountAPI;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 会员中心购买请求（C→S）：花星火直接购买/升级会员档位，永久买断（见 {@link ShopMembership}）。
 */
public class ShopMembershipBuyPacket {

    private final int tier;

    public ShopMembershipBuyPacket(int tier) {
        this.tier = tier;
    }

    public ShopMembershipBuyPacket(FriendlyByteBuf buf) {
        this.tier = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(tier);
    }

    public static void handle(ShopMembershipBuyPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            boolean ok = WalletAccountAPI.buyMemberTier(player.getServer(), player.getUUID(), pkt.tier);
            player.sendSystemMessage(ok
                    ? Component.literal("§b[会员中心] §a已购买 §f" + ShopMembership.tierNameForTier(pkt.tier) + "会员 §7(-"
                        + ShopMembership.discountPercentForTier(pkt.tier) + "% 折扣，永久生效)")
                    : Component.literal("§c[会员中心] 购买失败（星火余额不足 / 已拥有该档位或更高档位）"));
            WalletAccountAPI.sync(player);
        });
        context.setPacketHandled(true);
    }
}
