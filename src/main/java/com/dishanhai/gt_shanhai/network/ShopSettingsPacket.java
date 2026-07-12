package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopEditPermission;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 商店设置包（C→S）：运行期可调的商店行为，目前有「奖励抽取次数上限」「SDA 打包阈值」两项，
 * 写回 {@link DShanhaiConfig#COMMON} 对应字段并落盘。字段随后续设置项增多再加，
 * 别为了「可扩展」先造一堆现在用不上的东西。
 *
 * <p>服务端用 {@link ShopEditPermission#canEdit} 强校验权限，和 {@link ShopEditPacket} 同一套门槛。</p>
 */
public class ShopSettingsPacket {

    private final long rewardRollCap;
    private final long sdaPackThreshold;

    public ShopSettingsPacket(long rewardRollCap, long sdaPackThreshold) {
        this.rewardRollCap = Math.max(1L, rewardRollCap);
        this.sdaPackThreshold = Math.max(1L, sdaPackThreshold);
    }

    public ShopSettingsPacket(FriendlyByteBuf buf) {
        this.rewardRollCap = Math.max(1L, buf.readVarLong());
        this.sdaPackThreshold = Math.max(1L, buf.readVarLong());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarLong(rewardRollCap);
        buf.writeVarLong(sdaPackThreshold);
    }

    public static void handle(ShopSettingsPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            apply(pkt, player);
        });
        context.setPacketHandled(true);
    }

    private static void apply(ShopSettingsPacket pkt, ServerPlayer player) {
        if (!ShopEditPermission.canEdit(player)) {
            player.sendSystemMessage(Component.literal("§c[山海商店] 无编辑权限"));
            return;
        }
        DShanhaiConfig.COMMON.shopRewardRollCap.set(pkt.rewardRollCap);
        DShanhaiConfig.COMMON.shopRewardRollCap.save();
        DShanhaiConfig.COMMON.shopSdaPackThreshold.set(pkt.sdaPackThreshold);
        DShanhaiConfig.COMMON.shopSdaPackThreshold.save();
        player.sendSystemMessage(Component.literal("§b[山海商店] §a已更新奖励抽取次数上限: §f"
                + com.dishanhai.gt_shanhai.common.shop.ShopPurchase.formatCount(pkt.rewardRollCap)
                + " §a、SDA 打包阈值: §f"
                + com.dishanhai.gt_shanhai.common.shop.ShopPurchase.formatCount(pkt.sdaPackThreshold)));
    }
}
