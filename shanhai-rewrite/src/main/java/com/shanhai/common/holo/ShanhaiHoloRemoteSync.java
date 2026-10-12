package com.shanhai.common.holo;

import com.shanhai.ShanhaiMod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 山海重构 · 联机全息的<b>服务端那一半</b>：收下"你长什么样"，再发给别的持模块者。
 *
 * <h2>1. 它与客户端的职责切分（一句话）</h2>
 * <pre>
 *   客户端  只说"我是怎么设的"（{@code HoloPublishMessage}），<b>不判断任何权限</b>
 *   服务端  ①用 {@code ctx.getSender()} 认人（不读载荷里的 UUID）
 *           ②用 {@link ShanhaiHoloMenuNetwork#moduleInInventory} 复核他到底有没有模块
 *           ③把"他的样子"发给【其余持模块者】
 * </pre>
 *
 * <h2>2. 为什么要一条 {@code ASK}（而不是让客户端定期重发）</h2>
 * 用户拍板②的口径是"15 项<b>一人一次</b> 61 字节" ⇒ 不能让客户端每几秒就重发一遍。
 * 但"我进来的时候，别人已经在这里站着了"这个场景必须有人补一刀：
 * <pre>
 *   服务端 记住每个人的样子；有人登录/换维度时
 *     · 把【服务端已经知道的样子】推给新来的人（{@link #pushKnownTo}）
 *     · 对"服务端手上还没有/已经过期"的其他人，发一条 ASK（16 字节）让他们各报一次
 * </pre>
 * ⇒ 稳态下 <b>一个字节都不发</b>（10 tick 的巡检只做内存比较）；只在"人进出/换维度"时才动。
 *
 * <h2>3. 10 tick 巡检为什么必须兜底（照抄 {@code ShanhaiHoloCommandTreeSync} §4 的结论）</h2>
 * <b>背包变动没有单一事件能覆盖</b>：捡起 / 合成 / 漏斗 / 物品栏拖动 / 丢弃 / 死亡掉落 /
 * {@code /give} / 创造模式拿取 —— 那一整轮已经付过账了，不要重新发明。
 * 代价：每玩家每 0.5 秒 41 次 {@code ItemStack.is(...)}，可忽略。
 *
 * <h2>4. 🔴 隐私（要写进给用户看的报告）</h2>
 * 收件人 = <b>同服所有持模块者</b>（用户拍板③），<b>不按距离过滤</b> ⇒
 * 他们的客户端会收到你的 15 项参数、距离、开关。
 * "看不见的人"在<b>渲染端</b>被 64 格剔掉（{@link ShanhaiHoloMenuNetwork#RENDER_DISTANCE_SQR}），
 * 但数据确实发过去了 —— 这条是用户点单的口径，记在这里免得以后当成 bug。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiHoloRemoteSync {

    /** 日志前缀（与客户端那张表同族）。 */
    public static final String PREFIX = "[SHANHAI-HOLO-MP]";

    /** 巡检间隔（tick）。0.5 秒 —— 与 {@code ShanhaiHoloCommandTreeSync} 同一条口径。 */
    public static final int SWEEP_TICKS = 10;

    /**
     * "服务端手上这份有多旧"的上限。超过它 ⇒ 巡检会发一条 ASK 让那个人重报一次。
     * <p>5 分钟：正常玩法里永远打不到（值一变就发、换维度就发、问一次就补），
     * 只有"包丢了/客户端重连过"这类异常才用得上。
     */
    public static final long STALE_NANOS = 300_000_000_000L;

    /** 每个人的 DEF / STATE 原始载荷（编解码只有 {@link ShanhaiHoloRemoteCodec} 一份，这里只存结果）。 */
    private static final Map<UUID, byte[]> DEF = new ConcurrentHashMap<>();
    private static final Map<UUID, byte[]> STATE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> ACCEPTED_NANOS = new ConcurrentHashMap<>();

    /** 这一 tick 要"立刻处理"的人（登录/换维度塞进来，处理完就移除）。 */
    private static final Set<UUID> FORCED = ConcurrentHashMap.newKeySet();

    private static long tick;
    private static int publishAccepted;
    private static int publishRejected;
    private static int forwarded;

    private ShanhaiHoloRemoteSync() {
    }

    // ================================================================== 收

    /**
     * 收到一条 {@code PUBLISH}（<b>已经在服务端主线程上</b>，由消息的 handle 排过来）。
     *
     * <p>🔴 三条拒绝线，全部用<b>服务端自己算</b>的东西：
     * <ol>
     *   <li>发送者为空 / 载荷长度不对 ⇒ 拒；</li>
     *   <li>他身上没有那块模块 ⇒ 拒（{@code moduleInInventory}，注册表没好时也当没有）；</li>
     *   <li>载荷解不出来 ⇒ 拒。</li>
     * </ol>
     */
    public static void onPublish(ServerPlayer from, byte[] def, byte[] state) {
        if (from == null || def == null || state == null) {
            publishRejected++;
            ShanhaiMod.LOGGER.warn("{} holo_mp_publish_rejected reason=empty sender={}", PREFIX, from);
            return;
        }
        if (!ShanhaiHoloMenuNetwork.moduleInInventory(from)) {
            publishRejected++;
            ShanhaiMod.LOGGER.info("{} holo_mp_publish_rejected reason=no_module who={}", PREFIX, from.getUUID());
            return;
        }
        final ShanhaiHoloRemoteCodec.Def d = ShanhaiHoloRemoteCodec.decodeDef(def);
        final ShanhaiHoloRemoteCodec.State s = ShanhaiHoloRemoteCodec.decodeState(state);
        if (d == null || s == null) {
            publishRejected++;
            ShanhaiMod.LOGGER.warn("{} holo_mp_publish_rejected reason=decode who={} def_len={} state_len={}",
                    PREFIX, from.getUUID(), def.length, state.length);
            return;
        }
        // 🔴 认人只认 ctx.getSender()：载荷里那个 UUID 一个字节都不信
        final UUID who = from.getUUID();
        DEF.put(who, def);
        STATE.put(who, state);
        ACCEPTED_NANOS.put(who, System.nanoTime());
        publishAccepted++;

        final List<ServerPlayer> to = ShanhaiHoloMenuNetwork.moduleHoldersExcept(from);
        for (ServerPlayer p : to) {
            ShanhaiHoloMenuNetwork.sendDefTo(p, who, d.surroundPreset(), d.params());
            ShanhaiHoloMenuNetwork.sendStateTo(p, who, s.enabled(), s.layout(), s.distance(),
                    s.facing(), s.surroundPreset(), s.panelOpen());
            forwarded += 2;
        }
        ShanhaiMod.LOGGER.info("{} holo_mp_broadcast who={} enabled={} layout={} distance={} preset={} "
                        + "params={} receivers={} def_bytes={} state_bytes={}",
                PREFIX, who, s.enabled(), s.layout(), s.distance(), s.surroundPreset(),
                d.params().length, to.size(),
                ShanhaiHoloRemoteCodec.DEF_BYTES, ShanhaiHoloRemoteCodec.STATE_BYTES);
    }

    /** 把服务端手上<b>已经知道的</b>每个人的样子推给一个新来的人（他进世界时用）。 */
    public static void pushKnownTo(ServerPlayer newcomer) {
        if (newcomer == null || newcomer.hasDisconnected()) {
            return;
        }
        int n = 0;
        for (Map.Entry<UUID, byte[]> kv : DEF.entrySet()) {
            final UUID who = kv.getKey();
            if (who.equals(newcomer.getUUID())) {
                continue;                                   // 他自己那份由他自己报（避免回声）
            }
            final byte[] st = STATE.get(who);
            if (st == null) {
                continue;
            }
            final ShanhaiHoloRemoteCodec.Def d = ShanhaiHoloRemoteCodec.decodeDef(kv.getValue());
            final ShanhaiHoloRemoteCodec.State s = ShanhaiHoloRemoteCodec.decodeState(st);
            if (d == null || s == null) {
                continue;
            }
            ShanhaiHoloMenuNetwork.sendDefTo(newcomer, who, d.surroundPreset(), d.params());
            ShanhaiHoloMenuNetwork.sendStateTo(newcomer, who, s.enabled(), s.layout(), s.distance(),
                    s.facing(), s.surroundPreset(), s.panelOpen());
            n++;
        }
        ShanhaiMod.LOGGER.info("{} holo_mp_push_known to={} peers={} tracked={}",
                PREFIX, newcomer.getUUID(), n, DEF.size());
    }

    // ================================================================== 事件

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            final UUID id = player.getUUID();
            FORCED.add(id);
            ShanhaiMod.LOGGER.info("{} holo_mp_login who={} tracked={}", PREFIX, id, DEF.size());
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            FORCED.add(player.getUUID());
            ShanhaiMod.LOGGER.info("{} holo_mp_dimension who={} from={} to={}", PREFIX,
                    player.getUUID(), event.getFrom(), event.getTo());
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            final UUID id = player.getUUID();
            DEF.remove(id);
            STATE.remove(id);
            ACCEPTED_NANOS.remove(id);
            FORCED.remove(id);
            ShanhaiMod.LOGGER.info("{} holo_mp_logout who={} tracked={}", PREFIX, id, DEF.size());
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tick++;
        final boolean interval = (tick % SWEEP_TICKS) == 0;
        if (!interval && FORCED.isEmpty()) {
            return;                                     // 绝大多数 tick 在这里就返回了（两次比较）
        }
        final MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        final long now = System.nanoTime();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            final UUID id = p.getUUID();
            final boolean forced = FORCED.remove(id);
            if (!forced && !interval) {
                continue;
            }
            // ---- ① 他登录/换维度：把已经知道的人推给他 ----
            if (forced) {
                pushKnownTo(p);
            }
            // ---- ② 模块不在了 ⇒ 通知别人"他不显示了" ----
            if (!ShanhaiHoloMenuNetwork.moduleInInventory(p)) {
                final byte[] st = STATE.get(id);
                if (st != null) {
                    final ShanhaiHoloRemoteCodec.State s = ShanhaiHoloRemoteCodec.decodeState(st);
                    DEF.remove(id);
                    STATE.remove(id);
                    ACCEPTED_NANOS.remove(id);
                    if (s != null && s.enabled()) {
                        for (ServerPlayer other : ShanhaiHoloMenuNetwork.moduleHoldersExcept(p)) {
                            ShanhaiHoloMenuNetwork.sendStateTo(other, id, false, s.layout(),
                                    s.distance(), s.facing(), s.surroundPreset(), false);
                            forwarded++;
                        }
                        ShanhaiMod.LOGGER.info("{} holo_mp_broadcast_off who={} reason=module_gone",
                                PREFIX, id);
                    }
                }
                continue;
            }
            // ---- ③ 手上还没有他的样子 / 太旧了 ⇒ 请他自己报一次 ----
            final Long at = ACCEPTED_NANOS.get(id);
            if (at == null || (interval && now - at > STALE_NANOS)) {
                ShanhaiHoloMenuNetwork.askPublish(p);
            }
        }
    }

    // ================================================================== 读数

    /** 一行机器可判读数（命令与日志共用）。 */
    public static String describe() {
        return "tracked=" + DEF.size() + " forced_pending=" + FORCED.size()
                + " accepted=" + publishAccepted + " rejected=" + publishRejected
                + " forwarded=" + forwarded + " sweep_ticks=" + SWEEP_TICKS;
    }

    /** 表里每个人都来一行（排查用，最多 8 个）。 */
    public static List<String> lines() {
        final List<String> out = new ArrayList<>();
        for (Map.Entry<UUID, byte[]> kv : STATE.entrySet()) {
            if (out.size() >= 8) {
                break;
            }
            final ShanhaiHoloRemoteCodec.State s = ShanhaiHoloRemoteCodec.decodeState(kv.getValue());
            if (s == null) {
                continue;
            }
            out.add(String.format(Locale.ROOT, "uuid=%s enabled=%s layout=%d distance=%.2f preset=%d",
                    kv.getKey().toString().substring(0, 8), s.enabled(), s.layout(), s.distance(),
                    s.surroundPreset()));
        }
        return out;
    }
}
