package com.shanhai.add.gravity;

import com.shanhai.ShanhaiMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 重力倍率的<b>两端同步通道</b>（一条 S2C 消息）。
 *
 * <h2>为什么必须有它</h2>
 * 倍率的权威值在<b>服务端</b>（指令改 → 写 config → 广播）。客户端如果拿不到就按 1（安全默认），
 * 但那样本地玩家会按原版 0.08 预测、服务端按新重力算 ⇒ {@code moved wrongly} ⇒ {@code teleport()} 拉回 = <b>橡皮筋</b>。
 * ⇒ 改值后<b>立刻</b>广播，玩家登录时<b>补一次</b>。
 *
 * <h2>为什么消息只有一条</h2>
 * 非生物那些 mixin 在两端装的是<b>同一份类</b>，靠的就是同一个 G；生物那条更省事：
 * 倍率是挂在 {@code forge:entity_gravity} 上的 modifier，Forge 自己会把属性同步给客户端。
 * 所以这里只需要"把 G 告诉客户端"这一件事，不需要回执/不需要逐实体同步。
 *
 * <h2>通道写法</h2>
 * 与 {@code ShanhaiCreativeItemsNetwork} / {@code ShanhaiHoloMenuNetwork} 逐字同款
 * （{@code NetworkRegistry.newSimpleChannel} + {@code registerMessage} + 版本号一致才握手）。
 * <b>不复用别人的通道</b>：那些通道各有自己的消息 id 表，塞进去等于把别人的协议改了。
 */
public final class ShanhaiGravityNetwork {

    public static final String PREFIX = ShanhaiGravity.PREFIX;

    private static final String CHANNEL_NAME = "gravity";
    private static final String CHANNEL_VERSION = "1";

    private static final int ID_S2C_SYNC = 0;

    private static SimpleChannel channel;
    private static boolean registered;

    private ShanhaiGravityNetwork() {}

    /** 注册（两侧各一次；重复调用不会重复注册）。由 {@link ShanhaiGravityBootstrap} 调。 */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        try {
            channel = NetworkRegistry.newSimpleChannel(
                    new ResourceLocation("shanhai", CHANNEL_NAME),
                    () -> CHANNEL_VERSION,
                    CHANNEL_VERSION::equals,
                    CHANNEL_VERSION::equals);
            channel.registerMessage(ID_S2C_SYNC, SyncMessage.class,
                    SyncMessage::encode, SyncMessage::decode, SyncMessage::handle,
                    Optional.of(NetworkDirection.PLAY_TO_CLIENT));
            registered = true;
            ShanhaiMod.LOGGER.info("{} net_channel_registered name={} version={} s2c=[sync]",
                    PREFIX, CHANNEL_NAME, CHANNEL_VERSION);
        } catch (Throwable t) {
            // 通道建不起来不能让服务端起不来：退化成"两端各按自己的值"，单机仍然正确
            ShanhaiMod.LOGGER.error("{} net_channel_register_failed err={}", PREFIX, t.toString(), t);
        }
    }

    public static boolean isReady() {
        return registered && channel != null;
    }

    /** 服务端：把当前倍率推给一个玩家（登录时补一次）。 */
    public static void syncTo(ServerPlayer player) {
        if (!isReady() || player == null) {
            return;
        }
        try {
            channel.send(PacketDistributor.PLAYER.with(() -> player),
                    new SyncMessage(ShanhaiGravity.get()));
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} sync_send_failed player={} err={}",
                    PREFIX, player.getName().getString(), t.toString(), t);
        }
    }

    /** 服务端：改完倍率后广播给所有在线客户端（单人也走这条；单机其实同 JVM，属双保险）。 */
    public static void syncToAll(MinecraftServer server) {
        if (!isReady() || server == null) {
            return;
        }
        try {
            final SyncMessage msg = new SyncMessage(ShanhaiGravity.get());
            int n = 0;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                channel.send(PacketDistributor.PLAYER.with(() -> p), msg);
                n++;
            }
            ShanhaiMod.LOGGER.info("{} sync_broadcast players={} mult={}",
                    PREFIX, n, ShanhaiGravity.fmt(ShanhaiGravity.get()));
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} sync_broadcast_failed err={}", PREFIX, t.toString(), t);
        }
    }

    /** S2C：服务端权威倍率。 */
    public static final class SyncMessage {

        private final double mult;

        SyncMessage(double mult) {
            this.mult = mult;
        }

        void encode(FriendlyByteBuf buf) {
            buf.writeDouble(mult);
        }

        static SyncMessage decode(FriendlyByteBuf buf) {
            return new SyncMessage(buf.readDouble());
        }

        void handle(Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
            final net.minecraftforge.network.NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> {
                // ⚠️ 本消息方向是 PLAY_TO_CLIENT ⇒ 这段只在客户端跑（专服不会加载到客户端类）
                final boolean first = !ShanhaiGravity.isSyncedFromServer();
                final double before = ShanhaiGravity.get();
                final double applied = ShanhaiGravity.setFromServer(this.mult);
                // 主代理按这串判：客户端第一次收到倍率时必定有一行
                ShanhaiMod.LOGGER.info("{} gravity_sync mult={} first={} was={}",
                        PREFIX, ShanhaiGravity.fmt(applied), first, ShanhaiGravity.fmt(before));
            });
            context.setPacketHandled(true);
        }
    }
}
