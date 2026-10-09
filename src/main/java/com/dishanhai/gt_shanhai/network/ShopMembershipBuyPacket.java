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
            WalletAccountAPI.MemberBuyResult result = WalletAccountAPI.buyMemberTier(
                    player.getServer(), player.getUUID(), pkt.tier);
            player.sendSystemMessage(Component.literal(memberBuyMessage(pkt.tier, result)));
            WalletAccountAPI.sync(player);
        });
        context.setPacketHandled(true);
    }

    private static String memberBuyMessage(int tier, WalletAccountAPI.MemberBuyResult result) {
        if (result == WalletAccountAPI.MemberBuyResult.OK) {
            return "§b[银会中心] §a已购买 §f" + ShopMembership.tierNameForTier(tier) + "会员 §7(-"
                    + ShopMembership.discountPercentForTier(tier) + "% 折扣，永久生效)";
        }
        if (result == WalletAccountAPI.MemberBuyResult.INSUFFICIENT) {
            return "§c[银会中心] 购买失败（星火余额不足）";
        }
        if (result == WalletAccountAPI.MemberBuyResult.ALREADY_OWNED) {
            return "§c[银会中心] 购买失败（已拥有该档位或更高档位）";
        }
        return "§c[银会中心] 购买失败（无效档位）";
    }
}
