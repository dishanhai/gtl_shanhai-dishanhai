package com.shanhai.common.droplet;

import com.shanhai.ShanhaiMod;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
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

/**
 * 水滴驾驶的<b>唯一网络通道</b>：客户端 → 服务端的「W/S 意图包」（规格：客户端每 tick 把意图发上去）。
 *
 * <h2>一、只发一个包，方向只有一个</h2>
 * <pre>
 *   ID 0  C2S  {@link PilotInputMessage}  载荷 = 1 字节 flags（bit0 = W、bit1 = S）+ varInt seq
 * </pre>
 * <b>没有 S2C 包</b>：驾驶态（自动驾驶开关 / 辅助开关 / 辅助角度 / 攻击模式）全部走实体既有的
 * {@code SynchedEntityData}，不另开一条同步通道 —— 这是"不新增实体列表同步"那条硬口径的延伸。
 *
 * <h2>二、包里为什么带一个 {@code seq}</h2>
 * 它<b>不参与任何逻辑</b>，只用于日志与丢包观测（"服务端收到的最后一个序号"和"客户端发出的序号"一比
 * 就知道是没发还是被限流丢了）。真正的逻辑字段只有 2 个布尔。
 *
 * <h2>三、限流放在 {@link IntentStore}，不放实体里</h2>
 * 服务端处理网络包时，实体可能已经不存在（玩家下车/换维度/水滴被收回）⇒ 若把"意图"直接写进实体，
 * 就会出现"包到了、实体没了"的时序洞。这里先落进一张<b>按骑乘者 UUID 索引</b>的表，
 * 实体每 tick 主动 {@code poll}；查不到就自然是"没按"，不需要任何清理时序假设。
 *
 * <h2>四、四道限流 / 防御（写进报告的口径）</h2>
 * <ol>
 *   <li><b>来源校验</b>：{@code ctx.getSender()} 不是"正在骑我们水滴的乘客" ⇒ 丢弃（计数 {@code not_rider}）。</li>
 *   <li><b>每 tick 最多收 1 个</b>：同一 {@code gameTime} 的第二个包直接丢（计数 {@code flood}）。</li>
 *   <li><b>超时归零</b>：超过 {@link ShanhaiDropletPilotCore#INTENT_TIMEOUT_TICKS} tick 没新包 ⇒ 视为没按。</li>
 *   <li><b>未知位清零</b>：{@code flags & 0x03}，客户端塞进来的高位一律忽略（畸形包不会变成"某个键按下"）。</li>
 * </ol>
 *
 * <h2>五、注册时机</h2>
 * 挂 {@code FMLCommonSetupEvent}（与工程里既有的两条通道同一拍）。通道建不起来也绝不让起不来：
 * 失败只打 error，功能退化成"按了没反应"，不静默假装成功。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ShanhaiDropletPilotNetwork {

    /** 本功能统一日志前缀（与 Entity / Combat 的 {@code [SHANHAI-DROPLET]} 区分开，方便 grep 一整个功能）。 */
    public static final String PREFIX = "[SHANHAI-DROPLET-PILOT]";

    /** 通道名（运行时握手用，与 mods.toml 的 mod id 是两件事）。 */
    public static final String CHANNEL_NAME = "shanhai_droplet_pilot";

    /** 通道版本（两侧必须一致）。 */
    public static final String CHANNEL_VERSION = "1";

    /** 消息 id（两侧顺序必须一致）。 */
    public static final int ID_C2S_INPUT = 0;

    /** flags 的位定义。 */
    public static final int FLAG_FORWARD = 0x01;

    /** flags 的位定义。 */
    public static final int FLAG_BACKWARD = 0x02;

    /**
     * flags 的位定义：Shift（离开水滴）。
     *
     * <p>为什么把 Shift 也塞进同一个包（而不是新建第二个包）：规格要求"客户端每 tick 只发 W/S 意图"，
     * 而 Shift 的语义是"请求下车"——它本来就是驾驶意图的一部分。多带 1 个 bit 的成本是 0，
     * 却让"按 Shift 下车"不再依赖"服务端能否及时看到 sneak 状态"这条<b>外部假设</b>：
     * 实体侧同时认 {@code intent.shift} 与原版 {@code rider.isShiftKeyDown()}，两条都通。
     */
    public static final int FLAG_SHIFT = 0x04;

    /** 已知位掩码（其余位一律丢弃）。 */
    public static final int FLAG_MASK = FLAG_FORWARD | FLAG_BACKWARD | FLAG_SHIFT;

    private static SimpleChannel channel;
    private static boolean registered;
    private static final AtomicInteger NOT_RIDER = new AtomicInteger();
    private static final AtomicInteger SEND_FAILURES = new AtomicInteger();

    private ShanhaiDropletPilotNetwork() {}

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(ShanhaiDropletPilotNetwork::register);
    }

    /** 注册（幂等）。失败不抛：功能退化，主机照常开。 */
    public static synchronized void register() {
        if (registered) {
            ShanhaiMod.LOGGER.info("{} channel_already_registered name={}", PREFIX, CHANNEL_NAME);
            return;
        }
        try {
            channel = NetworkRegistry.newSimpleChannel(
                    new ResourceLocation("shanhai", CHANNEL_NAME),
                    () -> CHANNEL_VERSION,
                    CHANNEL_VERSION::equals,
                    CHANNEL_VERSION::equals);
            channel.registerMessage(ID_C2S_INPUT, PilotInputMessage.class,
                    PilotInputMessage::encode, PilotInputMessage::decode, PilotInputMessage::handle,
                    Optional.of(NetworkDirection.PLAY_TO_SERVER));
            registered = true;
            ShanhaiMod.LOGGER.info("{} channel_registered name={} version={} id={} c2s_id={} timeout_ticks={}",
                    PREFIX, CHANNEL_NAME, CHANNEL_VERSION, new ResourceLocation("shanhai", CHANNEL_NAME),
                    Integer.valueOf(ID_C2S_INPUT), Integer.valueOf(ShanhaiDropletPilotCore.INTENT_TIMEOUT_TICKS));
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} channel_register_failed err={}", PREFIX, t.toString(), t);
        }
    }

    public static boolean isReady() {
        return registered && channel != null;
    }

    // ------------------------------------------------------------------ 发（只在客户端调）

    /**
     * 客户端每 tick 调一次（20 Hz），把 W/S 意图发上去。
     *
     * <p>🔴 这里是<b>上报意图</b>，不是"合成输入"：没有改任何 {@code KeyMapping} 的按下状态，
     * 也没有动 {@code Input} 字段；反向的"强制归零"发生在客户端按键闸门里（另一个类）。
     */
    public static void sendInput(boolean forward, boolean backward, boolean shift, int seq) {
        if (!isReady()) {
            return;
        }
        final int flags = (forward ? FLAG_FORWARD : 0)
                | (backward ? FLAG_BACKWARD : 0)
                | (shift ? FLAG_SHIFT : 0);
        try {
            channel.sendToServer(new PilotInputMessage(flags, seq));
        } catch (Throwable t) {
            final int n = SEND_FAILURES.incrementAndGet();
            if (n <= 5 || n % 200 == 0) {
                ShanhaiMod.LOGGER.warn("{} intent_send_failed n={} err={}", PREFIX, Integer.valueOf(n), t.toString());
            }
        }
    }

    /** 登出/换维度时清掉这个骑乘者的意图槽（由事件层调）。@return 真的清掉了东西 */
    public static boolean forget(UUID rider) {
        return IntentStore.forget(rider);
    }

    /** 诊断读数（命令 status / 日志用）。 */
    public static String statsLine() {
        return "not_rider=" + NOT_RIDER.get()
                + " send_failures=" + SEND_FAILURES.get()
                + " flood_dropped=" + IntentStore.droppedCount();
    }

    // ================================================================ 消息

    /**
     * C2S：W/S 意图。载荷 = 1 字节 flags + varInt seq（共 2–6 字节）。
     *
     * <p>解码时对 {@code flags} 做 {@link #FLAG_MASK} 掩码：畸形包不会变成"某个键按下"。
     */
    public static final class PilotInputMessage {

        private final int flags;
        private final int seq;

        PilotInputMessage(int flags, int seq) {
            this.flags = flags & FLAG_MASK;
            this.seq = seq;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeByte(this.flags);
            buf.writeVarInt(this.seq);
        }

        public static PilotInputMessage decode(FriendlyByteBuf buf) {
            return new PilotInputMessage(buf.readByte(), buf.readVarInt());
        }

        public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
            final NetworkEvent.Context ctx = ctxSupplier.get();
            // 🔴 排到服务端主线程：要读实体、要写共享表，都不许在网络线程做。
            ctx.enqueueWork(() -> {
                try {
                    final ServerPlayer sender = ctx.getSender();
                    if (sender == null) {
                        return;
                    }
                    if (!(sender.getVehicle() instanceof ShanhaiDropletEntity)) {
                        // 不在骑我们的水滴 ⇒ 这个包没有语义（可能是下车瞬间的在途包）
                        final int n = NOT_RIDER.incrementAndGet();
                        if (n <= 5 || n % 200 == 0) {
                            ShanhaiMod.LOGGER.info("{} intent_rejected reason=not_rider n={}",
                                    PREFIX, Integer.valueOf(n));
                        }
                        return;
                    }
                    IntentStore.accept(sender.getUUID(),
                            (this.flags & FLAG_FORWARD) != 0,
                            (this.flags & FLAG_BACKWARD) != 0,
                            (this.flags & FLAG_SHIFT) != 0,
                            this.seq,
                            sender.level().getGameTime());
                } catch (Throwable t) {
                    ShanhaiMod.LOGGER.warn("{} intent_handle_failed err={}", PREFIX, t.toString());
                }
            });
            ctx.setPacketHandled(true);
        }
    }

    // ================================================================ 意图表

    /**
     * 意图表：按骑乘者 UUID 索引，服务端写、实体每 tick 读。
     *
     * <p>线程模型：读写两侧都在<b>服务端主线程</b>（网络包已 {@code enqueueWork} 排过去），
     * 仍然用 {@link ConcurrentHashMap} + 槽内 {@code synchronized} 兜"万一有人从别的线程问一句"。
     */
    public static final class IntentStore {

        /** 一次读取的结果（不可变）。{@link #fresh} 为 false ⇒ 两个布尔必为 false。 */
        public static final class Intent {

            public static final Intent NONE = new Intent(false, false, false, false);

            public final boolean forward;
            public final boolean backward;
            public final boolean shift;
            public final boolean fresh;

            Intent(boolean forward, boolean backward, boolean shift, boolean fresh) {
                this.forward = forward;
                this.backward = backward;
                this.shift = shift;
                this.fresh = fresh;
            }

            @Override
            public String toString() {
                return "forward=" + this.forward + " backward=" + this.backward
                        + " shift=" + this.shift + " fresh=" + this.fresh;
            }
        }

        private static final class Slot {

            private boolean forward;
            private boolean backward;
            private boolean shift;
            private long tick = Long.MIN_VALUE;
            private int seq = -1;
            private int drops;
        }

        private static final Map<UUID, Slot> SLOTS = new ConcurrentHashMap<>();
        private static final AtomicInteger DROPPED = new AtomicInteger();

        private IntentStore() {}

        /**
         * 收下一个意图包（服务端主线程）。
         *
         * @param rider 骑乘者
         * @param forward W
         * @param backward S
         * @param seq 序号（只用于日志）
         * @param gameTime 当前游戏时间（服务端 tick）
         */
        public static void accept(UUID rider, boolean forward, boolean backward, boolean shift,
                                  int seq, long gameTime) {
            if (rider == null) {
                return;
            }
            final Slot slot = SLOTS.computeIfAbsent(rider, key -> new Slot());
            synchronized (slot) {
                if (slot.tick == gameTime) {
                    // 限流：同一 tick 的第二个包（说明客户端发太快或有人重放）
                    slot.drops++;
                    final int total = DROPPED.incrementAndGet();
                    if (total <= 5 || total % 200 == 0) {
                        ShanhaiMod.LOGGER.info("{} intent_dropped reason=flood rider={} total={} seq={}",
                                PREFIX, rider, Integer.valueOf(total), Integer.valueOf(seq));
                    }
                    return;
                }
                slot.tick = gameTime;
                slot.forward = forward;
                slot.backward = backward;
                slot.shift = shift;
                slot.seq = seq;
            }
        }

        /**
         * 读当前意图（实体每 tick 调）。
         *
         * @param rider 骑乘者
         * @param now 当前游戏时间
         * @return 超时/没有记录 ⇒ {@link Intent#NONE}
         */
        public static Intent poll(UUID rider, long now) {
            if (rider == null) {
                return Intent.NONE;
            }
            final Slot slot = SLOTS.get(rider);
            if (slot == null) {
                return Intent.NONE;
            }
            synchronized (slot) {
                if (slot.tick == Long.MIN_VALUE) {
                    return Intent.NONE;
                }
                final long age = now - slot.tick;
                if (age > (long) ShanhaiDropletPilotCore.INTENT_TIMEOUT_TICKS) {
                    return new Intent(false, false, false, false);
                }
                // 注意：age 为负（时钟回退/换维度）也当作有效，但两个布尔照实给 —— 不对时钟做额外假设
                return new Intent(slot.forward, slot.backward, slot.shift, true);
            }
        }

        /** 清掉某个骑乘者的槽（登出/换维度）。返回是否清掉了东西（日志可判）。 */
        public static boolean forget(UUID rider) {
            return rider != null && SLOTS.remove(rider) != null;
        }

        /** 被限流丢掉的包总数（诊断读数）。 */
        public static int droppedCount() {
            return DROPPED.get();
        }

        /** 当前有几个骑乘者的意图在表里（诊断读数；正常应 ≤ 在线驾驶人数）。 */
        public static int slotCount() {
            return SLOTS.size();
        }
    }
}
