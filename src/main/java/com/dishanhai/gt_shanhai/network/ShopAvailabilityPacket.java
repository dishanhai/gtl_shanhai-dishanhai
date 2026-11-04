package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopAvailability;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/** 山海商店启用状态：客户端查询/OP 更新，服务端同步权威状态，双方严格校验方向。 */
public final class ShopAvailabilityPacket {
    public enum Action { QUERY, UPDATE, STATE, RESULT, PERSONAL }

    private final Action action;
    private final boolean disabled;
    private final boolean canConfigureGlobal;

    private ShopAvailabilityPacket(Action action, boolean disabled, boolean canConfigureGlobal) {
        this.action = action;
        this.disabled = disabled;
        this.canConfigureGlobal = canConfigureGlobal;
    }

    public static ShopAvailabilityPacket query() {
        return new ShopAvailabilityPacket(Action.QUERY, false, false);
    }

    public static ShopAvailabilityPacket update(boolean disabled) {
        return new ShopAvailabilityPacket(Action.UPDATE, disabled, false);
    }

    public static ShopAvailabilityPacket personal(boolean disabled) {
        return new ShopAvailabilityPacket(Action.PERSONAL, disabled, false);
    }

    public ShopAvailabilityPacket(FriendlyByteBuf buf) {
        action = buf.readEnum(Action.class);
        disabled = buf.readBoolean();
        canConfigureGlobal = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeBoolean(disabled);
        buf.writeBoolean(canConfigureGlobal);
    }

    public static void sendTo(ServerPlayer player) {
        sendState(player, Action.STATE);
    }

    private static void sendState(ServerPlayer player, Action action) {
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ShopAvailabilityPacket(action, ShopAvailability.isDisabled(), player.hasPermissions(2)));
    }

    public static void handle(ShopAvailabilityPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        if (context.getDirection() == NetworkDirection.PLAY_TO_SERVER
                && (packet.action == Action.QUERY || packet.action == Action.UPDATE || packet.action == Action.PERSONAL)) {
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null) return;
                if (packet.action == Action.QUERY) {
                    sendTo(player);
                    return;
                }
                // 商店编辑白名单不等于 OP；绝不能信任客户端传来的权限位。
                if (packet.action == Action.PERSONAL) {
                    ShopAvailability.setPersonalDisabled(player.getUUID(), packet.disabled);
                    sendTo(player);
                    return;
                }
                if (!player.hasPermissions(2)) {
                    player.sendSystemMessage(Component.literal("§c[山海商店] 只有 OP 可以配置全局禁用"));
                } else {
                    DShanhaiConfig.COMMON.shopDisabled.set(packet.disabled);
                    DShanhaiConfig.COMMON.shopDisabled.save();
                    for (ServerPlayer online : player.getServer().getPlayerList().getPlayers()) {
                        if (online != player) sendTo(online);
                    }
                }
                sendState(player, Action.RESULT);
            });
        } else if (context.getDirection() == NetworkDirection.PLAY_TO_CLIENT
                && (packet.action == Action.STATE || packet.action == Action.RESULT)) {
            context.enqueueWork(() -> applyClient(packet));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyClient(ShopAvailabilityPacket packet) {
        com.dishanhai.gt_shanhai.client.shop.ClientShopAvailability.apply(
                packet.disabled, packet.canConfigureGlobal, packet.action == Action.RESULT);
    }
}
