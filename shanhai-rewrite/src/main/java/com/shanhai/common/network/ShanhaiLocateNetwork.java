package com.shanhai.common.network;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.locate.ShanhaiLocateCore;
import com.shanhai.common.locate.ShanhaiLocateSearch;

import appeng.api.stacks.AEKey;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 山海重构 · 「合成状态里 Shift+右键点物品 ⇒ 我们的样板总成在哪」的<b>网络通道</b>
 * （本模组的第三条；前两条是 {@code ShanhaiJeiBridge} 与 {@code ShanhaiHoloMenuNetwork}）。
 *
 * <h2>1. 只发两个包（用户抢答的规格原文：「只发我们要的两个包：请求 / 响应」）</h2>
 * <pre>
 *   ID 0  C2S  {@link LocateRequestMessage}   载荷 = 一个 AEKey（{@code AEKey.writeKey}）
 *   ID 1  S2C  {@link LocateResultMessage}    载荷 = n × (种类, 维度, 方块坐标)，n ≤ 8
 * </pre>
 * <b>没有中间包</b>：0 命中时服务端<b>一个字节都不回</b>（用户规格：「若不在我们的样板总成里 ⇒
 * 什么都不做、不发消息、不挡」）。聊天栏那一行也<b>不由客户端拼</b>：
 * 它是服务端算的距离/坐标（客户端没有权威数据）。
 *
 * <h2>2. 🔴 客户端不能猜（用户硬约束②）</h2>
 * C2S 里只有"我指着哪个键"；判定（哪台机器有它的样板）<b>全部在服务端</b>
 * （{@link ShanhaiLocateSearch#onRequest}）。客户端收 S2C 之后只做两件事：
 * 存进 {@code ShanhaiLocateTimeline} + 每帧画框。
 *
 * <h2>3. 为什么不直接 import 客户端那个类</h2>
 * 本类在 {@code common} 包里，<b>专服也会加载它</b>；一旦它在字节码里引用
 * {@code com.shanhai.client.locate.*}，专服上就可能踩到"客户端类被加载"。
 * ⇒ 与 {@code ShanhaiHoloMenuNetwork} 同一个解法：反向注册
 * （{@link #setClientSink}，客户端开机时塞一个实现进来；专服上它永远是 null）。
 *
 * <h2>4. 注册时机</h2>
 * 挂 {@code FMLCommonSetupEvent}（客户端与专用服务端各跑一次，且在玩家进世界之前），与
 * {@code ShanhaiHoloMenuNetwork} 同一拍。通道建不起来也绝不让服务端起不来（那功能退化成"点了没反应"）。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ShanhaiLocateNetwork {

    public static final String PREFIX = "[SHANHAI-LOCATE]";
    public static final String CHANNEL_NAME = "shanhai_locate";
    public static final String CHANNEL_VERSION = "1";

    /** 消息 id（两侧必须一致，顺序也必须一致）。 */
    public static final int ID_C2S_REQUEST = 0;
    public static final int ID_S2C_RESULT = 1;

    /** S2C 一次最多带几行（= 高亮上限；服务端也只算这么多行再发）。 */
    public static final int MAX_ROWS = ShanhaiLocateCore.MAX_HIGHLIGHT;

    /** 维度 id 的字符串上限（{@code writeUtf} 的硬上限，够长且能挡住畸形串）。 */
    public static final int MAX_DIM_CHARS = 128;

    private static SimpleChannel channel;
    private static boolean registered;

    /**
     * 客户端侧的落点（专服上永远是 null）。
     *
     * @param rows 不可变行清单（可能为空 —— 但服务端 0 命中时根本不发这个包）
     */
    public interface ClientSink {

        void accept(List<Row> rows);
    }

    private static volatile ClientSink clientSink;

    /** S2C 的一行：种类 + 维度 + 坐标。<b>不含任何 MC 对象</b>，方便离线断言与日志。 */
    public static final class Row {

        public final int kind;
        public final String dim;
        public final int x;
        public final int y;
        public final int z;

        public Row(int kind, String dim, int x, int y, int z) {
            this.kind = kind;
            this.dim = dim == null ? "" : dim;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public String toString() {
            return "kind=" + this.kind + " dim=" + this.dim + " pos=" + this.x + "," + this.y + "," + this.z;
        }
    }

    private ShanhaiLocateNetwork() {}

    /** 客户端开机时注册（专服永远不调）。 */
    public static void setClientSink(ClientSink sink) {
        clientSink = sink;
        ShanhaiMod.LOGGER.info("{} locate_net_client_sink_registered present={}", PREFIX, sink != null);
    }

    public static boolean hasClientSink() {
        return clientSink != null;
    }

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            register();
            // 玩家下线 ⇒ 把限流表里那一行删掉（否则 UUID 表会跟着"来过多少人"长下去）。
            // ⚠️ 这是 FORGE 总线的事件，本类挂在 MOD 总线上 ⇒ 只能 addListener，不能再用 @SubscribeEvent。
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                    (net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) -> {
                        if (e.getEntity() instanceof ServerPlayer player) {
                            ShanhaiLocateSearch.forget(player.getUUID());
                        }
                    });
            // 开机就把纯逻辑核的两条断言打成一行机器可判读数（专服与客户端都会打）
            ShanhaiMod.LOGGER.info("{} {}", PREFIX, ShanhaiLocateCore.selfTest());
            ShanhaiMod.LOGGER.info("{} {}", PREFIX, com.shanhai.common.locate.ShanhaiLocateRegistry.statsLine());
        });
    }

    /** 注册（幂等）。 */
    public static synchronized void register() {
        if (registered) {
            ShanhaiMod.LOGGER.info("{} locate_net_channel_already_registered name={}", PREFIX, CHANNEL_NAME);
            return;
        }
        try {
            channel = NetworkRegistry.newSimpleChannel(
                    // ⚠️ 命名空间写死 "shanhai"（与既有两条通道同款），不读 mod_id：
                    //    通道名是**运行时**握手用的，和 {@code mods.toml} 里那个 id 是两件事。
                    new ResourceLocation("shanhai", CHANNEL_NAME),
                    () -> CHANNEL_VERSION,
                    CHANNEL_VERSION::equals,
                    CHANNEL_VERSION::equals);
            channel.registerMessage(ID_C2S_REQUEST, LocateRequestMessage.class,
                    LocateRequestMessage::encode, LocateRequestMessage::decode, LocateRequestMessage::handle,
                    Optional.of(NetworkDirection.PLAY_TO_SERVER));
            channel.registerMessage(ID_S2C_RESULT, LocateResultMessage.class,
                    LocateResultMessage::encode, LocateResultMessage::decode, LocateResultMessage::handle,
                    Optional.of(NetworkDirection.PLAY_TO_CLIENT));
            registered = true;
            ShanhaiMod.LOGGER.info("{} locate_net_channel_registered name={} version={} id={} "
                            + "c2s_id={} s2c_id={} max_rows={}",
                    PREFIX, CHANNEL_NAME, CHANNEL_VERSION, new ResourceLocation("shanhai", CHANNEL_NAME),
                    ID_C2S_REQUEST, ID_S2C_RESULT, MAX_ROWS);
        } catch (Throwable t) {
            // 通道建不起来绝不能让服务端起不来（定位那块功能退化，机器照旧跑）
            ShanhaiMod.LOGGER.error("{} locate_net_channel_register_failed err={}", PREFIX, t.toString(), t);
        }
    }

    public static boolean isReady() {
        return registered && channel != null;
    }

    // ------------------------------------------------------------------ 发

    /**
     * C2S：<b>客户端</b>说"我指着这个键，告诉我它的样板在我们哪台总成里"（只能在客户端调）。
     *
     * <p>客户端在这里<b>不判任何东西</b>（不知道、也不许猜）：不发权限、不查表、不改状态。
     */
    public static void requestLocate(AEKey wanted) {
        if (wanted == null) {
            ShanhaiMod.LOGGER.info("{} locate_req_skip reason=no_key", PREFIX);
            return;
        }
        if (!isReady()) {
            ShanhaiMod.LOGGER.warn("{} locate_req_skip reason=channel_not_ready item={}", PREFIX, keyId(wanted));
            return;
        }
        try {
            channel.sendToServer(new LocateRequestMessage(wanted));
            ShanhaiMod.LOGGER.info("{} locate_req_sent item={}", PREFIX, keyId(wanted));
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} locate_req_send_failed err={}", PREFIX, t.toString(), t);
        }
    }

    /**
     * S2C：把"要画哪几台"发给一个玩家（<b>只在服务端调</b>）。
     *
     * <p>0 台时<b>直接返回、不发包</b> —— 这是"0 命中要静默"在传输层的兜底
     * （上层已经用 {@code plan(...)==ACTION_NONE} 挡过一次，这里是第二道）。
     */
    public static void sendResult(ServerPlayer to, List<ShanhaiLocateCore.Hit> hits) {
        if (hits == null || hits.isEmpty() || to == null || to.hasDisconnected()) {
            return;
        }
        if (!isReady()) {
            ShanhaiMod.LOGGER.warn("{} locate_result_skip reason=channel_not_ready player={} rows={}",
                    PREFIX, to.getName().getString(), Integer.valueOf(hits.size()));
            return;
        }
        try {
            final int n = Math.min(hits.size(), MAX_ROWS);
            final Row[] rows = new Row[n];
            for (int i = 0; i < n; i++) {
                final ShanhaiLocateCore.Hit hit = hits.get(i);
                rows[i] = new Row(hit.kind, hit.dim, hit.x, hit.y, hit.z);
            }
            channel.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> to),
                    new LocateResultMessage(rows));
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} locate_result_send_failed player={} err={}",
                    PREFIX, to.getName().getString(), t.toString(), t);
        }
    }

    /** 日志里那个 {@code item=}：{@code 类型@id}（不带 {@code Component}，纯 ASCII，便于 grep）。 */
    public static String keyId(AEKey key) {
        if (key == null) {
            return "null";
        }
        try {
            final String type = key.getType() == null ? "?" : String.valueOf(key.getType().getId());
            return type + "@" + key.getId();
        } catch (Throwable t) {
            return "unreadable";
        }
    }

    // ================================================================ 消息

    /**
     * C2S：一个 AEKey。
     *
     * <p>🔴 编解码走 AE2 自己的 {@code AEKey.writeKey/readKey}（AE2 的内部包也是这么做的）
     * —— <b>不要</b>自己拼 ItemStack：合成状态表里的东西<b>不一定是物品</b>（可能是流体键），
     * 拼 ItemStack 会把流体那一路悄悄丢掉。
     */
    public static final class LocateRequestMessage {

        private final AEKey what;

        LocateRequestMessage(AEKey what) {
            this.what = what;
        }

        public void encode(FriendlyByteBuf buf) {
            AEKey.writeKey(buf, this.what);
        }

        public static LocateRequestMessage decode(FriendlyByteBuf buf) {
            AEKey key = null;
            try {
                key = AEKey.readKey(buf);
            } catch (Throwable t) {
                // 网络线程上抛出去会把连接踢掉 ⇒ 这里吞掉、交给上层当"没指到东西"
                ShanhaiMod.LOGGER.warn("{} locate_req_decode_failed err={}", PREFIX, t.toString());
            }
            return new LocateRequestMessage(key);
        }

        public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
            final NetworkEvent.Context ctx = ctxSupplier.get();
            // 🔴 排到服务端主线程：要念样板槽、要发聊天行、要发包，都不许在网络线程做。
            ctx.enqueueWork(() -> {
                try {
                    ShanhaiLocateSearch.onRequest(ctx.getSender(), this.what);
                } catch (Throwable t) {
                    ShanhaiMod.LOGGER.error("{} locate_req_handle_failed err={}", PREFIX, t.toString(), t);
                }
            });
            ctx.setPacketHandled(true);
        }
    }

    /**
     * S2C：要画的 n 行（{@code n ≤ }{@link #MAX_ROWS}）。
     *
     * <p>载荷 = {@code varInt n} + 每行 {@code byte kind} + {@code utf dim} + {@code blockPos}。
     * <b>不带任何字符串坐标/显示名</b>：显示名由客户端按 lang 键翻，坐标是三个 int（不丢精度）。
     */
    public static final class LocateResultMessage {

        private final Row[] rows;

        LocateResultMessage(Row[] rows) {
            this.rows = rows;
        }

        public void encode(FriendlyByteBuf buf) {
            final int n = this.rows == null ? 0 : this.rows.length;
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                final Row row = this.rows[i];
                buf.writeByte(row.kind);
                buf.writeUtf(row.dim, MAX_DIM_CHARS);
                buf.writeBlockPos(new BlockPos(row.x, row.y, row.z));
            }
        }

        public static LocateResultMessage decode(FriendlyByteBuf buf) {
            final int n = Math.max(0, Math.min(buf.readVarInt(), MAX_ROWS));
            final Row[] rows = new Row[n];
            for (int i = 0; i < n; i++) {
                final int kind = buf.readByte();
                final String dim = buf.readUtf(MAX_DIM_CHARS);
                final BlockPos pos = buf.readBlockPos();
                rows[i] = new Row(kind, dim, pos.getX(), pos.getY(), pos.getZ());
            }
            return new LocateResultMessage(rows);
        }

        public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
            final NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> {
                final ClientSink sink = clientSink;
                if (sink == null) {
                    ShanhaiMod.LOGGER.info("{} locate_result_dropped reason=no_client_sink rows={}",
                            PREFIX, Integer.valueOf(this.rows.length));
                    return;
                }
                final List<Row> list = new ArrayList<>(this.rows.length);
                Collections.addAll(list, this.rows);
                sink.accept(Collections.unmodifiableList(list));
            });
            ctx.setPacketHandled(true);
        }
    }
}
