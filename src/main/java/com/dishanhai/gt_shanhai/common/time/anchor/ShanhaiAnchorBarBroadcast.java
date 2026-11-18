package com.dishanhai.gt_shanhai.common.time.anchor;

import java.util.Optional;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.time.ShanhaiClockNetwork;
import com.dishanhai.gt_shanhai.common.time.replay.ShanhaiReplayAnchorRuntime;
import com.dishanhai.gt_shanhai.common.time.replay.ShanhaiReplayConst;
import com.dishanhai.gt_shanhai.common.time.replay.ShanhaiReplayEngine;
import com.dishanhai.gt_shanhai.common.time.replay.ShanhaiReplayJournal;
import com.dishanhai.gt_shanhai.common.time.replay.ShanhaiReplayRecorder;
import com.dishanhai.gt_shanhai.common.time.replay.ShanhaiReplayService;
import com.dishanhai.gt_shanhai.common.time.anchor.ShanhaiAnchorBarSchedule.Event;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;

/**
 * 山海重构 · 锚点条的<b>服务端外壳</b>：读"时钟状态" → 装成 {@link ShanhaiAnchorBarFrame} → 发。
 *
 * <h2>1. 它只做三件必须碰游戏的事</h2>
 * <ol>
 *   <li><b>读</b>：锚点来自 {@code ShanhaiReplayJournal#anchor()}，倒流进度来自
 *       {@code ShanhaiReplayService} 的四个<b>只读</b>读数（{@code isActive/window/remaining/cursor()}）。
 *       🔴 一条都不改、一条都不算新口径 —— 回放与锚点逻辑仍然只有那一份；</li>
 *   <li><b>筛</b>：只发给"锚点所在维度"的玩家（{@value #SEND_MODE_TEXT}）；</li>
 *   <li><b>发</b>：走 {@link ShanhaiClockNetwork} 的 id 2（见 {@link ShanhaiAnchorBarMsg} §3）。</li>
 * </ol>
 *
 * <h2>2. 🔴 为什么 startTick 没有 getter 也不改回放代码</h2>
 * 帧里要 {@code replayFromTick}（倒流起点）与 {@code replayToTick}（目标），而
 * {@code ShanhaiReplayService} 只暴露 {@code window/remaining/cursor}。三者是同一组状态：
 * <pre>
 *   done  = window - remaining                 // 已经走了几拍
 *   from  = cursor + done                      // 起点 tick（倒流开始那一 tick）
 *   to    = cursor - remaining                 // 目标 tick
 * </pre>
 * 起点与终点各验一次就能确认它自洽：
 * <pre>
 *   刚进倒流： cursor=startTick, done=0        ⇒ from=startTick ✔        to=startTick-window ✔
 *   走到末尾： cursor=anchorTick, remaining=0  ⇒ from=anchorTick+window ✔ to=anchorTick ✔
 * </pre>
 * 于是分数 {@code (from-cursor)/(from-to)} 恰好是 {@code done/window}（= 动作栏那条百分比），
 * 而<b>回放侧一行都不用改</b>（那是用户点名不许碰的地方）。
 *
 * <h2>3. 五个触发点（判据 ② 数"4 个事件各一次 + 倒流每 5 tick"）</h2>
 * <table border="1">
 *   <caption>入口</caption>
 *   <tr><th>事件</th><th>调用方</th></tr>
 *   <tr><td>{@link #onAnchorSet}</td><td>{@code ShanhaiClockCommands#anchorSet} 成功之后</td></tr>
 *   <tr><td>{@link #onAnchorClear}</td><td>{@code ShanhaiClockCommands#anchorClear} 之后（清之前先取维度）</td></tr>
 *   <tr><td>{@link #tick}</td><td>{@code ShanhaiClockInit#onServerTick}（每外层 tick，倒流期间照跑）——
 *       它<b>自己判</b>开始/结束的边沿，并在倒流中每 5 tick、在窗口态每 20 tick 发一条</td></tr>
 *   <tr><td>{@link #onLogin}</td><td>{@code ShanhaiClockInit#onPlayerLoggedIn}（补发一次，否则重连后看不到条）</td></tr>
 *   <tr><td>{@link #onPlayerChangedDimension}</td><td>{@code ShanhaiClockInit} 的 {@code PlayerChangedDimensionEvent}
 *       （按维度广播 ⇒ 走出去的人再也收不到帧，必须当场清一次）</td></tr>
 * </table>
 *
 * <h2>4. 失败方向</h2>
 * 全部 {@code try/catch(Throwable)} 自吞 + 记一行：HUD 的同步<b>绝不能</b>让倒流本身歇菜
 * （宁可这一帧没发出去，也不能让 {@code ServerTickEvent} 抛）。
 */
public final class ShanhaiAnchorBarBroadcast {

    private static final String PREFIX = "[SHANHAI-ANCHORBAR]";

    /** 维度口径说明（写进类注释，判据/日志可 grep）。 */
    public static final String SEND_MODE_TEXT = "只发给锚点所在维度的玩家";

    private static final ShanhaiAnchorBarSchedule SCHEDULE = new ShanhaiAnchorBarSchedule();

    /** 上一次 {@code ShanhaiReplayService#isActive()}（用来判"开始/结束"的边沿，不需要回放侧加回调）。 */
    private static boolean lastReplayActive;

    /** 最近一次真的发出去的目标维度（收尾时锚点已被清空，靠它把"条消失"那一帧送到正确的维度）。 */
    private static int lastDim = -1;

    /** 周期帧的日志节流（每 20 条打一行 INFO，其余降 DEBUG）。 */
    private static int periodicLog;

    private ShanhaiAnchorBarBroadcast() {
    }

    // ------------------------------------------------------------------ 触发点

    /** {@code time anchor set} 成功之后：让条出现。 */
    public static void onAnchorSet(MinecraftServer srv) {
        flush(Event.ANCHOR_SET, srv, -1);
    }

    /**
     * {@code time anchor clear} 之后：让条消失。
     *
     * @param dimBefore 清掉<b>之前</b>那个锚点的维度（清完就查不到了；{@code -1} = 本来就没有锚点）
     */
    public static void onAnchorClear(MinecraftServer srv, int dimBefore) {
        flush(Event.ANCHOR_CLEAR, srv, dimBefore);
    }

    /**
     * 每外层 tick（{@code TickEvent.ServerTickEvent} END，暂停/倒流期间照跑）。
     *
     * <p>开始/结束靠<b>边沿</b>判：{@code isActive()} 由 false 变 true ⇒ 发一条（条变青）；
     * 由 true 变 false ⇒ 发一条（条消失）。这样无论倒流是命令发起、还是被
     * {@code clock pause} / 停机收尾，都能收到那一帧 —— 不需要在回放侧挂任何回调。
     */
    public static void tick(MinecraftServer srv) {
        try {
            final boolean active = ShanhaiReplayService.isActive();
            if (active != lastReplayActive) {
                lastReplayActive = active;
                flush(active ? Event.REPLAY_START : Event.REPLAY_STOP, srv, -1);
                return;
            }
            final ShanhaiAnchorBarFrame frame = currentFrame(srv);
            final Optional<ShanhaiAnchorBarFrame> out =
                    active ? SCHEDULE.onReplayTick(frame) : SCHEDULE.onWindowTick(frame);
            if (out.isPresent()) {
                send(srv, out.get(), targetDim(out.get(), -1), active ? "replay_tick" : "window_tick");
            }
        } catch (Throwable t) {
            GTDishanhaiMod.LOGGER.error("{} tick_failed err={}（这一帧没发出去，倒流本身不受影响）",
                    PREFIX, t.toString());
        }
    }

    /** 玩家登录：补发一次（重连/换维度之后条还能对上，不用等下一次事件）。 */
    public static void onLogin(ServerPlayer player) {
        syncToPlayer(player, "login");
    }

    /**
     * 玩家换维度：<b>先把这一局的条清掉</b>。
     *
     * <h2>为什么必须有这一条（与"退出世界清零"同一类真机问题）</h2>
     * 广播是<b>按维度</b>过滤的（只发给锚点所在维度的玩家）。玩家一旦走出那个维度，
     * 就再也收不到任何一帧 ⇒ 屏幕上那条会<b>永远停在离开时的比例</b>（不是 60 秒后过期，是永远）。
     *
     * <h2>为什么"先清后用 1 秒自动补回"，而不是当场判断新维度</h2>
     * {@code PlayerChangedDimensionEvent} 派发时玩家实体到底已经在新维度还是还在旧维度，
     * 取决于上游实现的顺序。这里<b>不赌那个顺序</b>：
     * <ol>
     *   <li>无条件先把条清掉（跨维度那一刻，这条本来就不该继续显示）；</li>
     *   <li>如果新维度<b>就是</b>锚点维度，下一次窗口滴（≤ 1 秒，{@code WINDOW_PERIOD_TICKS}）会自动补回来
     *       —— 那一路只发给锚点维度的玩家，正好是需要的语义。</li>
     * </ol>
     * 于是"事件早派发还是晚派发"都能收敛到正确显示。
     */
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        try {
            if (event == null || !(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            final ShanhaiAnchorBarFrame frame = currentFrame(player.getServer());
            final ShanhaiAnchorBarFrame cleared =
                    ShanhaiAnchorBarFrame.noAnchor(frame.nowTick(), frame.windowTicks());
            ShanhaiClockNetwork.sendAnchorBarTo(player, new ShanhaiAnchorBarMsg(cleared));
            GTDishanhaiMod.LOGGER.info("{} send reason=dim_change dim_reset=1 player={} {}",
                    PREFIX, player.getGameProfile().getName(), cleared.toLine());
        } catch (Throwable t) {
            GTDishanhaiMod.LOGGER.error("{} dim_change_failed err={}", PREFIX, t.toString());
        }
    }

    /**
     * 给<b>一个</b>玩家补一帧当前状态（登录用）。
     *
     * <p>他若不在锚点维度：发"无锚点帧"而不是什么都不发 —— 后者会让缓存里上一个世界的帧继续显示。
     */
    private static void syncToPlayer(ServerPlayer player, String reason) {
        if (player == null) {
            return;
        }
        try {
            final MinecraftServer srv = player.getServer();
            final ShanhaiAnchorBarFrame frame = currentFrame(srv);
            final int dim = targetDim(frame, -1);
            final int playerDim = ShanhaiReplayEngine.dimIdOf(player.serverLevel().dimension());
            final ShanhaiAnchorBarFrame out = (dim < 0 || playerDim == dim)
                    ? frame
                    : ShanhaiAnchorBarFrame.noAnchor(frame.nowTick(), frame.windowTicks());
            ShanhaiClockNetwork.sendAnchorBarTo(player, new ShanhaiAnchorBarMsg(out));
            GTDishanhaiMod.LOGGER.info("{} send reason={} dim={} player={} {}", PREFIX, reason, playerDim,
                    player.getGameProfile().getName(), out.toLine());
        } catch (Throwable t) {
            GTDishanhaiMod.LOGGER.error("{} sync_failed reason={} err={}", PREFIX, reason, t.toString());
        }
    }

    /** 换世界/停机：节拍与边沿记忆一起清零（新世界不许继承上一个世界的"正在倒流"）。 */
    public static void reset() {
        SCHEDULE.reset();
        lastReplayActive = false;
        lastDim = -1;
        periodicLog = 0;
    }

    /**
     * 倒流收尾（{@code ShanhaiReplayService#stop} 的 finally）：<b>主动发一条"无锚点"帧</b>，
     * 让客户端 HUD 立刻消失。
     *
     * <h2>🔴 为什么必须主动发（2026-11-14 C 组，用户原话「下面的锚点进度条也没消失」）</h2>
     * 收尾前的两条通路都靠不住：
     * <ul>
     *   <li>{@link #tick} 的边沿只判"倒流开始/结束"。它确实会发一条，但那条帧的锚点状态取决于
     *       {@code stop()} 有没有跑到 {@code journal.clearAnchor()} —— 旧版 {@code stop()} 在
     *       第一行就 return 了 ⇒ 锚点<b>从来没被清</b> ⇒ 发出去的是"还有锚点"的窗口态帧
     *       （真机日志 12:30：{@code ANCHORBAR send reason=window_tick anchor_tick=7073 window=1200
     *       now=8085}，倒流结束 6 分钟还在每 20 tick 推一次）；</li>
     *   <li>{@link ShanhaiAnchorBarSchedule#onWindowTick} 只在 {@code frame.hasAnchor()} 时才滴，
     *       所以"锚点被清掉之后"不会再有任何帧替客户端擦屏。</li>
     * </ul>
     * ⇒ 收尾时<b>无条件</b>发一条 {@link ShanhaiAnchorBarFrame#noAnchor}（{@code anchor_tick=-1}）。
     * 维度取 {@link #targetDim}（锚点已清则回落到上一次真的发成功的那一维；都没有就全维度兜底）。
     *
     * @param srv    服务端（{@code null} = 只记日志不发）
     * @param reason 收尾原因（只进日志）
     */
    public static void onReplayEnd(MinecraftServer srv, String reason) {
        try {
            lastReplayActive = false;      // 与 tick() 的边沿判据对齐，避免下一拍再补一条
            final ShanhaiAnchorBarFrame frame = currentFrame(srv);
            final long now = frame == null ? 0L : frame.nowTick();
            final int window = frame == null ? ShanhaiReplayConst.WINDOW_TICKS : frame.windowTicks();
            final ShanhaiAnchorBarFrame cleared = ShanhaiAnchorBarFrame.noAnchor(now, window);
            send(srv, cleared, targetDim(frame, -1), "replay_end");
        } catch (Throwable t) {
            GTDishanhaiMod.LOGGER.error("{} replay_end_failed reason={} err={}（HUD 可能停在最后一帧）",
                    PREFIX, reason, t.toString());
        }
    }

    /** 已放行帧数（status/判据用）。 */
    public static int sentCount() {
        return SCHEDULE.sent();
    }

    // ------------------------------------------------------------------ 组装一帧

    /**
     * 服务端此刻眼里的锚点条状态。
     *
     * <p>🔴 全部是<b>只读</b>读数：{@code journal.anchor()} / {@code service.isActive()/window()/remaining()/cursor()}。
     * 没有一步会改变回放状态。
     */
    public static ShanhaiAnchorBarFrame currentFrame(MinecraftServer srv) {
        final ShanhaiReplayJournal journal = ShanhaiReplayRecorder.journal();
        final ShanhaiReplayJournal.Anchor anchor = journal == null ? null : journal.anchor();
        final long now = ShanhaiReplayAnchorRuntime.nowTick(srv);

        long anchorTick = ShanhaiAnchorBarFrame.NO_ANCHOR;
        byte flags = 0;
        if (anchor != null) {
            anchorTick = anchor.tick();
            flags |= ShanhaiAnchorBarFrame.FLAG_HAS_ANCHOR;
            if (now - anchorTick >= ShanhaiReplayConst.WINDOW_TICKS) {
                flags |= ShanhaiAnchorBarFrame.FLAG_EXPIRED;
            }
            if (anchor.stale()) {
                flags |= ShanhaiAnchorBarFrame.FLAG_STALE;
            }
        }

        boolean replaying = false;
        long from = 0L;
        long to = 0L;
        long cursor = 0L;
        try {
            replaying = ShanhaiReplayService.isActive();
            if (replaying) {
                final int win = ShanhaiReplayService.window();
                final int remaining = ShanhaiReplayService.remaining();
                final int cur = ShanhaiReplayService.cursor();
                final int done = Math.max(0, win - remaining);
                cursor = cur;
                from = (long) cur + (long) done;   // 见类注释 §2
                to = (long) cur - (long) remaining;
            }
        } catch (Throwable t) {
            // 取不到倒流读数 ⇒ 当作"没在倒流"（条回到窗口态，绝不假装在倒流）
            replaying = false;
            from = 0L;
            to = 0L;
            cursor = 0L;
        }

        return new ShanhaiAnchorBarFrame(anchorTick, ShanhaiReplayConst.WINDOW_TICKS, now,
                replaying, from, to, cursor, flags);
    }

    // ------------------------------------------------------------------ 发

    private static void flush(Event event, MinecraftServer srv, int dimOverride) {
        try {
            final ShanhaiAnchorBarFrame frame = currentFrame(srv);
            final Optional<ShanhaiAnchorBarFrame> out = SCHEDULE.onEvent(event, frame);
            if (out.isEmpty()) {
                return;
            }
            send(srv, out.get(), targetDim(out.get(), dimOverride), reasonOf(event));
        } catch (Throwable t) {
            GTDishanhaiMod.LOGGER.error("{} event_failed event={} err={}", PREFIX, event, t.toString());
        }
    }

    /** 这一帧该发给哪个维度：显式给的 > 锚点所在维度 > 上一次发成功的目标维度。 */
    private static int targetDim(ShanhaiAnchorBarFrame frame, int override) {
        if (override >= 0) {
            return override;
        }
        if (frame != null && frame.hasAnchor()) {
            final ShanhaiReplayJournal journal = ShanhaiReplayRecorder.journal();
            final ShanhaiReplayJournal.Anchor anchor = journal == null ? null : journal.anchor();
            if (anchor != null) {
                return anchor.dim();
            }
        }
        // 收尾（stop 会清锚点）时已经查不到维度了 ⇒ 用上一次真的发成功的那个维度。
        // 没有它，倒流结束那一帧会发给"没人" ⇒ 客户端把条永远停在 100%。
        return lastDim;
    }

    private static void send(MinecraftServer srv, ShanhaiAnchorBarFrame frame, int dim, String reason) {
        if (srv == null || frame == null) {
            return;
        }
        final ShanhaiAnchorBarMsg msg = new ShanhaiAnchorBarMsg(frame);
        int players = 0;
        for (ServerPlayer player : srv.getPlayerList().getPlayers()) {
            if (player == null) {
                continue;
            }
            if (dim >= 0) {
                final int playerDim;
                try {
                    playerDim = ShanhaiReplayEngine.dimIdOf(player.serverLevel().dimension());
                } catch (Throwable t) {
                    continue;
                }
                if (playerDim != dim) {
                    continue;                   // 🔴 只在锚点所在维度广播，不全服刷
                }
            }
            ShanhaiClockNetwork.sendAnchorBarTo(player, msg);
            ++players;
        }
        if (dim >= 0) {
            // 只有"定向"那一次才记住维度；dim<0（全维度兜底帧）不许把 lastDim 冲成 -1
            lastDim = dim;
        }
        logSend(reason, dim, players, frame);
    }

    private static void logSend(String reason, int dim, int players, ShanhaiAnchorBarFrame frame) {
        final boolean periodic = "replay_tick".equals(reason) || "window_tick".equals(reason);
        if (periodic && (++periodicLog % 20) != 0) {
            GTDishanhaiMod.LOGGER.debug("{} send reason={} dim={} players={} {}", PREFIX, reason, dim, players,
                    frame.toLine());
            return;
        }
        GTDishanhaiMod.LOGGER.info("{} send reason={} dim={} players={} {}", PREFIX, reason, dim, players,
                frame.toLine());
    }

    private static String reasonOf(Event event) {
        return switch (event) {
            case ANCHOR_SET -> "anchor_set";
            case ANCHOR_CLEAR -> "anchor_clear";
            case REPLAY_START -> "replay_start";
            case REPLAY_STOP -> "replay_stop";
        };
    }
}
