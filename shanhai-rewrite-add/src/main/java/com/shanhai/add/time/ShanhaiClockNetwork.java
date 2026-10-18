package com.shanhai.add.time;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.shanhai.ShanhaiMod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 山海重构 · 世界时钟的网络同步。
 *
 * <h2>为什么需要它</h2>
 * 服务端权威，客户端只读镜像（见 {@link ShanhaiWorldClock}）。客户端<b>不许</b>自己推断"现在是不是暂停了"
 * —— 那会在丢包/重连时出现"服务端冻了、客户端在跑"的半冻状态（普查 A② 的坑）。
 *
 * <h2>两条消息</h2>
 * <ul>
 *   <li>{@link ClockStateMsg}（S→C）：服务端下发 {@link ShanhaiWorldClock.Snapshot}；</li>
 *   <li>{@link ClockAckMsg}（C→S）：客户端回报"哪几处时钟确认被钩住了"（位掩码），
 *       用来让 {@code /shanhai_add clock status} 能打出<b>可验证</b>的"已钉住"，而不是我自称钉住了。</li>
 * </ul>
 *
 * <h2>🔴 失败方向</h2>
 * 解码时 mode 名不认识 ⇒ {@link ShanhaiWorldClock#modeOrFallback} 回退到 {@code LIVE}。
 * 网络异常时宁可"不冻"，也绝不能把世界留在"看起来已暂停"里（那就是永久卡死）。
 */
public final class ShanhaiClockNetwork {

    /** 协议版本；改了消息体就一定要改它（两端不一致会直接握手失败，而不是解析出错）。 */
    private static final String PROTOCOL = "1";

    private static final ResourceLocation CHANNEL_ID =
            new ResourceLocation(ShanhaiMod.MOD_ID, "clock");

    private static SimpleChannel channel;

    /** 每个玩家的客户端 ack 位掩码（status 用）。 */
    private static final Map<UUID, Integer> ACK = new ConcurrentHashMap<>();

    /** 上一次广播出去的状态（用来判断"要不要再发"）。 */
    private static volatile ShanhaiWorldClock.Snapshot lastSent;

    public static void register() {
        channel = NetworkRegistry.newSimpleChannel(CHANNEL_ID, () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

        channel.registerMessage(0, ClockStateMsg.class,
                ClockStateMsg::encode, ClockStateMsg::decode, ClockStateMsg::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        channel.registerMessage(1, ClockAckMsg.class,
                ClockAckMsg::encode, ClockAckMsg::decode, ClockAckMsg::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));

        ShanhaiMod.LOGGER.info("[SHANHAI-CLOCK] 网络通道已注册：{}（protocol {}）", CHANNEL_ID, PROTOCOL);
    }

    // ------------------------------------------------------------------ 服务端 → 客户端

    /** 把当前服务端状态发给一个玩家。 */
    public static void sendStateTo(ServerPlayer player) {
        if (channel == null || player == null) {
            return;
        }
        ShanhaiWorldClock.Snapshot snap = ShanhaiWorldClock.SERVER.snapshot();
        lastSent = snap;
        channel.send(PacketDistributor.PLAYER.with(() -> player), new ClockStateMsg(snap));
    }

    /** 状态有变化时广播给所有人（每个外层 tick 调一次，内部自己判"要不要发"）。 */
    public static void broadcastIfChanged(Iterable<ServerPlayer> players) {
        if (channel == null) {
            return;
        }
        ShanhaiWorldClock.Snapshot snap = ShanhaiWorldClock.SERVER.snapshot();
        ShanhaiWorldClock.Snapshot prev = lastSent;
        boolean changed = prev == null
                || prev.epoch() != snap.epoch()
                || Math.abs(prev.rate() - snap.rate()) > 1.0E-9D
                || prev.mode() != snap.mode();
        if (!changed) {
            return;
        }
        lastSent = snap;
        for (ServerPlayer player : players) {
            channel.send(PacketDistributor.PLAYER.with(() -> player), new ClockStateMsg(snap));
        }
    }

    /** 玩家登录/换维度时补发一次。 */
    public static void onPlayerReady(ServerPlayer player) {
        ACK.remove(player.getUUID());
        sendStateTo(player);
    }

    public static void onPlayerGone(ServerPlayer player) {
        if (player != null) {
            ACK.remove(player.getUUID());
        }
    }

    public static int ackOf(ServerPlayer player) {
        return ACK.getOrDefault(player.getUUID(), 0);
    }

    // ------------------------------------------------------------------ 消息体

    /**
     * S→C：一条完整快照。
     *
     * <p>刻意<b>不</b>只发一个枚举：客户端还要知道 {@code epoch}（丢旧包）与
     * {@code clientMsPerTick}（下一轮的加速路径）。
     */
    public record ClockStateMsg(ShanhaiWorldClock.Snapshot snapshot) {

        public static void encode(ClockStateMsg msg, FriendlyByteBuf buf) {
            ShanhaiWorldClock.Snapshot s = msg.snapshot();
            buf.writeUtf(s.mode() == null ? "LIVE" : s.mode().name());
            buf.writeDouble(s.rate());
            buf.writeVarLong(s.epoch());
            buf.writeVarLong(s.anchorTick());
            buf.writeVarLong(s.replicaTick());
            buf.writeFloat(s.clientMsPerTick());
        }

        public static ClockStateMsg decode(FriendlyByteBuf buf) {
            // 🔴 名称解析用 modeOrFallback：坏包 ⇒ LIVE（失败方向绝不能是"暂停"）
            ShanhaiClockMode mode = ShanhaiWorldClock.modeOrFallback(buf.readUtf(32));
            double rate = buf.readDouble();
            long epoch = buf.readVarLong();
            long anchorTick = buf.readVarLong();
            long replicaTick = buf.readVarLong();
            float msPerTick = buf.readFloat();
            return new ClockStateMsg(new ShanhaiWorldClock.Snapshot(
                    mode, rate, epoch, anchorTick, replicaTick, msPerTick, 0.0D));
        }

        public static void handle(ClockStateMsg msg, Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            // 客户端类只能在这个 lambda 真正执行时才被加载 ⇒ 用 DistExecutor 隔离，
            // 专用服务端不会因为这条消息而碰任何 net.minecraft.client.* 类。
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                var snapshot = msg.snapshot();
                ShanhaiWorldClock.CLIENT.applySnapshot(snapshot);
                com.shanhai.add.client.time.ShanhaiClientClock.onStateApplied(snapshot);
            });
            ctx.setPacketHandled(true);
        }
    }

    /**
     * C→S：客户端 ack（位掩码，见 {@code ShanhaiClientClock#ackMask()}）。
     */
    public record ClockAckMsg(int mask, long epoch) {

        public static void encode(ClockAckMsg msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.mask());
            buf.writeVarLong(msg.epoch());
        }

        public static ClockAckMsg decode(FriendlyByteBuf buf) {
            return new ClockAckMsg(buf.readVarInt(), buf.readVarLong());
        }

        public static void handle(ClockAckMsg msg, Supplier<NetworkEvent.Context> ctxSupplier) {
            NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> {
                ServerPlayer sender = ctx.getSender();
                if (sender != null) {
                    ACK.put(sender.getUUID(), msg.mask());
                }
            });
            ctx.setPacketHandled(true);
        }
    }

    /** 客户端发 ack（只会从客户端代码里被调到）。 */
    public static void sendAckFromClient(int mask, long epoch) {
        if (channel == null) {
            return;
        }
        channel.sendToServer(new ClockAckMsg(mask, epoch));
    }

    private ShanhaiClockNetwork() {
    }
}
