package com.dishanhai.gt_shanhai.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * 钱包快捷键开店请求包（C→S）：由 {@link com.dishanhai.gt_shanhai.client.ShanhaiKeyMappings}
 * 快捷键触发，不要求手持——只要背包（含盔甲栏/副手）或 Curios 饰品栏任意位置有钱包即可打开商店。
 */
public class WalletOpenRequestPacket {

    public WalletOpenRequestPacket() {}

    public WalletOpenRequestPacket(FriendlyByteBuf buf) {}

    public void encode(FriendlyByteBuf buf) {}

    public static void handle(WalletOpenRequestPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            if (!com.dishanhai.gt_shanhai.common.item.WalletItem.isCarrying(player)) {
                player.sendSystemMessage(Component.literal("§c请先获得 §6山海钱包 §c（背包或饰品栏任意位置都行）"));
                return;
            }
            boolean canEdit = com.dishanhai.gt_shanhai.common.shop.ShopEditPermission.canEdit(player);
            boolean catalogEditUnlocked = com.dishanhai.gt_shanhai.common.shop.ShopEditPermission.canEditCatalog(player);
            ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new ShopOpenPacket(canEdit, catalogEditUnlocked,
                            com.dishanhai.gt_shanhai.common.shop.ShopConfig.manifest()));
            // 打开商店即推账户快照，客户端界面/tooltip 立刻有余额
            com.dishanhai.gt_shanhai.common.shop.WalletAccountAPI.sync(player);
            // 购物车跨重登保留（见反馈），同一时机推回去，客户端一开店就能看到之前留下的候选
            com.dishanhai.gt_shanhai.common.shop.ShopCartAPI.sync(player);
            // 收藏同样跨重登保留，同一时机推回去，客户端一开店就能看到之前收藏的角标/筛选状态
            com.dishanhai.gt_shanhai.common.shop.ShopFavoriteAPI.sync(player);
        });
        context.setPacketHandled(true);
    }
}
