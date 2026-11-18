package com.dishanhai.gt_shanhai.common.time.replay;

import java.util.List;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.time.ShanhaiClockMode;
import com.dishanhai.gt_shanhai.common.time.ShanhaiClockNetwork;
import com.dishanhai.gt_shanhai.common.time.ShanhaiWorldClock;
import com.dishanhai.gt_shanhai.common.time.anchor.ShanhaiAnchorBarBroadcast;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 山海重构 · <b>时间倒流的状态机与收尾</b>（服务端权威；{@code LIVE/PAUSED} ⇄ {@code REPLAY}）。
 *
 * <h2>1. 谁在驱动（零新增 mixin）</h2>
 * 回退步进挂在 <b>{@code TickEvent.ServerTickEvent} END</b>（{@code ShanhaiClockInit#onServerTick}，
 * 已在跑、<b>暂停期间照样来</b>）⇒ 不需要任何新的驱动混入。世界 tick 本身照旧被
 * {@code ShanhaiServerLevelTickClockMixin} 整段取消（{@code isReplay()} 也冻），
 * 玩家仍在 {@code ShanhaiClockHooks#tickPlayersOnly} 里被单独 tick ⇒ 用户口径"玩家的时间不受影响"。
 *
 * <h2>2. 逐 tick 反向播放（A3 的落点）</h2>
 * 真正的节拍在 {@link ShanhaiReplayPacer}：<b>每外层 tick 只退一个时间片</b>（{@code per_tick=1}），
 * 一个时间片 = 记录里同一个 tick 的<b>五层全部改动</b>（方块 / 方块实体差分 / 机器进度 / 实体 / 世界标量），
 * 顺序 LIFO。于是"倒流 N 秒"在墙钟上正好 N 秒 —— 这就是用户要的
 * 「回溯时恰好根据那个数相反的样子回溯」。
 *
 * <h2>3. 收尾三步（顺序不可换，设计 §4.2）</h2>
 * <ol>
 *   <li><b>关账</b>：{@code mode = mode_before}（bump epoch；{@code PAUSED → PAUSED} / {@code LIVE → LIVE}）
 *       → 立刻广播给所有客户端；</li>
 *   <li><b>对账</b>：打一行 {@code [SHANHAI-REPLAY] done …}（可 grep；<b>不允许静默</b>）；</li>
 *   <li><b>落盘</b>：{@code noSave = false} 后<b>显式</b> {@code save(null, true, false)}
 *       （第 3 参 false 才会真写）。</li>
 * </ol>
 *
 * <h2>4. 存档安全</h2>
 * 进 REPLAY <b>先</b> save 一次（磁盘 = 倒流前），<b>再</b> {@code noSave = true}；
 * {@code ServerStoppingEvent} 里必须先恢复 {@code noSave = false}，否则停机那次强制存档会被静默跳过。
 *
 * <h2>5. 两条入口共用同一个状态机</h2>
 * <table border="1">
 *   <caption>入口与口径</caption>
 *   <tr><th>入口</th><th>目标 tick</th><th>到位后</th></tr>
 *   <tr><td>{@link #start}（{@code time rewind &lt;秒&gt;} / {@code clock replay [秒]}）</td>
 *       <td>"最新记录往回退 N×20 个 tick"</td><td>收尾回到<b>进来之前的模式</b>（<b>不</b>传送玩家）</td></tr>
 *   <tr><td>{@link #startAtAnchor}（{@code time rewind} 无参）</td>
 *       <td>玩家事先设的<b>锚点 tick</b></td>
 *       <td>🔴 2026-11-14 用户口径「不要拉回玩家位置」⇒ <b>也不传送</b>
 *       （{@code anchor_restored=0}，{@code anchor_detail=no_teleport_by_design}）</td></tr>
 * </table>
 */
public final class ShanhaiReplayService {

    /** 逐 tick 反向播放的节拍器（A3 的核心；纯逻辑，可离线断言）。 */
    private static final ShanhaiReplayPacer PACER = new ShanhaiReplayPacer();

    /**
     * 这一轮倒流"开始过了、还没收尾"（🔴 2026-11-14 C 组的根因修复）。
     *
     * <h2>为什么不能用 {@code PACER.isActive()} 当收尾的守门</h2>
     * {@link ShanhaiReplayPacer#step} 在算出 {@code Stop.ANCHOR}/{@code Stop.BUDGET} 时会
     * <b>自己</b>把 {@code active} 置 false（{@code ShanhaiReplayPacer.java:490-499}），
     * 而收尾是 {@code tick() → finishAtAnchor() → stop()} —— 于是旧的
     * {@code if (!PACER.isActive()) return false;} 一进门就返回：
     * <b>{@code setMode(LIVE)}（旧第 543 行）永远跑不到</b> ⇒ 世界永久冻结、{@code noSave} 永久 true、
     * 锚点永不消失。真机日志（12:30，倒流结束 6 分钟后）：
     * {@code clock_player_tick kept=1 folded=943}、{@code clock_entity_probe … dy_now=0.0000}、
     * {@code ANCHORBAR send reason=window_tick anchor_tick=7073 … now=8085}。
     * <p>⇒ 守门改成本字段：{@link #begin} 置真，{@link #stop} 置假（先脱闸，保证幂等）。
     */
    private static boolean running;
    private static boolean replayFailed;
    private static String pendingStopReason;

    public static boolean isFailed() { return replayFailed; }

    /**
     * <b>倒流开始前</b>的世界时钟模式（收尾时回到它）。
     *
     * <p>🔴 2026-11-14 用户口径：「暂停 = 时间停在某一刻，倒流 = 从这一刻往回走」
     * ⇒ 走回来还是停在那一刻。旧实现 {@code stop()} 里一律 {@code setMode(LIVE)}
     * ⇒ 用户"先暂停、再倒流"之后，时间被强行恢复了流动（他看到的"不兼容"就是这个）。
     */
    private static ShanhaiClockMode modeBefore = null;

    /** 这次倒流挂在哪个服务端（停机/换世界时用来收尾）。 */
    private static MinecraftServer boundServer;
    /** 这次倒流从哪个 tick 开始（收尾时打 {@code reverted=[a,b]} 用）。 */
    private static int startTick;
    /** 外层 tick 计数（每 20 拍打一行）。 */
    private static long steps;
    private static long startWallMs;
    private static ShanhaiReplayEngine engine;

    /** 五层各自的累计写入（收尾对账用）。 */
    private static long writtenTotal;
    private static long failedTotal;
    private static long skippedTotal;
    private static long alreadyAirTotal;
    private static long blocksTotal;
    private static long entityTotal;
    private static long entityAppliedTotal;
    private static long progressTotal;
    private static long beDiffTotal;
    private static long scalarTotal;
    private static long scalarSkippedTotal;
    private static long entityReasonTotal;
    private static long motionTotal;
    private static long motionAppliedTotal;
    private static long motionSentTotal;
    private static long motionPacketsTotal;
    private static long healthTotal;
    private static long healthAppliedTotal;
    private static long motionAfterRebuildTotal;
    private static long rebuildPosOkTotal;
    private static long rebuildPosMismatchTotal;
    private static long blockWrittenTotal;
    private static long blockVerifiedTotal;
    private static long blockAlreadyCorrectTotal;
    private static long blockWriteFailedTotal;
    private static long blockMismatchTotal;

    // ------------------------------------------------------------------ 锚点回退

    /** 这次倒流的锚点快照（{@code null} = 不是"回到锚点"）。 */
    private static ShanhaiReplayJournal.Anchor activeAnchor;
    /** 锚点回退的目标 tick（{@link ShanhaiReplayAnchor#NO_ANCHOR_TICK} = 非锚点回退）。 */
    private static int anchorTick = ShanhaiReplayAnchor.NO_ANCHOR_TICK;
    /** 这次倒流是谁发起的（回退到位后要把**他**传回锚点；控制台发起时为 {@code null}）。 */
    private static ServerPlayer boundActor;
    private static boolean anchorRestored;
    private static boolean anchorDimMismatch;
    private static String anchorRestoreDetail = "";

    // ------------------------------------------------------------------ 查询

    public static boolean isActive() {
        return running;
    }

    /**
     * 真正"开始了还没收尾"的口径（与 {@link #isActive()} 同义，语义更直白）。
     *
     * <p>⚠️ 它与 {@code PACER.isActive()} <b>不是</b>同一个东西：节拍器在最后一拍会自己停，
     * 而收尾还没做（见 {@link #running} 的注释）。对外一律用本方法。
     */
    public static boolean isRunning() {
        return running;
    }

    public static int window() {
        return PACER.window();
    }

    public static int remaining() {
        return PACER.remaining();
    }

    public static int cursor() {
        return PACER.cursor();
    }

    /** 逐 tick 步进器（status / 离线判据读同一份）。 */
    public static ShanhaiReplayPacer pacer() {
        return PACER;
    }

    public static boolean isAnchorRewind() {
        return anchorTick != ShanhaiReplayAnchor.NO_ANCHOR_TICK;
    }

    public static int anchorTick() {
        return anchorTick;
    }

    public static boolean anchorRestored() {
        return anchorRestored;
    }

    public static boolean anchorDimMismatch() {
        return anchorDimMismatch;
    }

    /** 六条时间线（命令/日志/自检读同一份引用）。 */
    public static ShanhaiReplayPacer.Journals journals() {
        return new ShanhaiReplayPacer.Journals(ShanhaiReplayRecorder.journal(),
                ShanhaiReplayEntityRecorder.journal(),
                ShanhaiReplayProgressRecorder.journal(),
                ShanhaiReplayBlockEntityRecorder.journal(),
                ShanhaiReplayScalarRecorder.journal(),
                ShanhaiReplayEntityMotionRecorder.journal(),
                ShanhaiReplayHealthRecorder.journal());
    }

    private static ShanhaiReplayPacer.Targets targets() {
        final ShanhaiReplayEngine sink = engine();
        // 🔴 运动层的落点就是发包落点（同一个 engine）：写回与发包必须成对（见 ShanhaiReplayMotionDispatch）
        return new ShanhaiReplayPacer.Targets(sink, sink, sink, sink, sink, sink, sink, sink);
    }

    // ------------------------------------------------------------------ 进入

    /**
     * 开始倒流：<b>旧语义</b>（{@code /shanhai time rewind <秒>}、{@code /shanhai clock replay [秒]}）。
     */
    public static synchronized boolean start(MinecraftServer srv, ServerPlayer actor, int seconds) {
        if (!com.dishanhai.gt_shanhai.common.time.ReplayRecording.isEnabled()) {
            notify(actor, "gt_shanhai.time.record.required");
            return false;
        }
        final ShanhaiReplayPacer.Journals journals = journals();
        if (srv == null) {
            return false;
        }
        if (running || PACER.isActive() || ShanhaiWorldClock.SERVER.getMode() == ShanhaiClockMode.REPLAY) {
            notify(actor, "gt_shanhai.time.rewind.already_running");
            return false;
        }
        final int newest = journals.newestTick();
        final int oldest = Math.max(ShanhaiReplayRecorder.journal().oldestTick(), 0);
        if (newest < 0) {
            // 空窗口 ⇒ 立刻拒，不允许"倒流一半发现没素材"（设计 §5.2 最后一行）
            GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] start_refused reason=empty_journal");
            notify(actor, "gt_shanhai.time.rewind.empty");
            return false;
        }
        final ServerLevel probe = srv.overworld();
        final long now = probe == null ? newest : probe.getGameTime();
        final int start = (int) Math.min(now, (long) newest);
        final int available = oldest <= 0 ? start + 1 : start - oldest + 1;
        final int plan = ShanhaiReplayConst.planTicks(seconds, available);
        if (plan <= 0) {
            GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] start_refused reason=window_empty available={}", available);
            notify(actor, "gt_shanhai.time.rewind.empty");
            return false;
        }
        return begin(srv, actor, plan, start, null, seconds);
    }

    /**
     * 开始倒流：<b>锚点语义</b>（{@code /shanhai time rewind} 无参）。
     *
     * <h2>判据链（每一步拒绝都给码，绝不静默截断）</h2>
     * <ol>
     *   <li>没有锚点 ⇒ {@code no_anchor}；</li>
     *   <li>锚点超出 1200 tick 窗口（或来自"未来"）⇒ {@code anchor_expired}
     *       <b>只有"自动暂停被解除之后"才可能到这里</b>（B：到期那一刻会自动暂停，时间不再前进）；</li>
     *   <li>锚点就在当前 tick / 之后世界没有变化 ⇒ {@code already_at_anchor}；</li>
     *   <li>日志是空的 ⇒ {@code empty_journal}。</li>
     * </ol>
     */
    public static synchronized boolean startAtAnchor(MinecraftServer srv, ServerPlayer actor) {
        if (!com.dishanhai.gt_shanhai.common.time.ReplayRecording.isEnabled()) {
            notify(actor, "gt_shanhai.time.record.required");
            return false;
        }
        final ShanhaiReplayJournal journal = ShanhaiReplayRecorder.journal();
        if (srv == null) {
            return false;
        }
        if (running || PACER.isActive() || ShanhaiWorldClock.SERVER.getMode() == ShanhaiClockMode.REPLAY) {
            notify(actor, "gt_shanhai.time.rewind.already_running");
            return false;
        }
        final ShanhaiReplayJournal.Anchor anchor = journal.anchor();
        final int now = ShanhaiReplayAnchorRuntime.nowTick(srv);
        final ShanhaiReplayAnchor.Plan planned =
                ShanhaiReplayAnchor.plan(anchor, now, ShanhaiReplayConst.WINDOW_TICKS);
        if (!planned.ok()) {
            GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] anchor_refused reason={} anchor_tick={} now_tick={}"
                            + " window={} detail={} expiry={}",
                    planned.refusal().code(), planned.anchorTick(), planned.nowTick(),
                    ShanhaiReplayConst.WINDOW_TICKS, planned.detail(),
                    ShanhaiReplayAnchorRuntime.expiryStatusLine());
            notify(actor, "gt_shanhai.time.rewind.refused", planned.refusal().code());
            return false;
        }
        final ShanhaiReplayPacer.Journals js = journals();
        final int newest = js.newestTick();
        if (newest < 0) {
            GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] anchor_refused reason=empty_journal anchor_tick={} now_tick={}",
                    planned.anchorTick(), planned.nowTick());
            notify(actor, "gt_shanhai.time.rewind.empty");
            return false;
        }
        // 🔴 2026-11 真机实测的 bug（本次修）：游标起点<b>不能</b>钳到"某一层日志的最新 tick"。
        //    原来写的是 Math.min(now, blocks.newestTick())：方块日志最后 28 tick 恰好没有变化 ⇒
        //    start 被钳到 43375（now 其实是 43403）⇒ window 被算成 246；而游标随后又被
        //    alignCursor 抬到五层最新的 43403 ⇒ 整个窗口整体上移 28 tick，
        //    246 拍走完停在 43157，锚点是 43129 ⇒ 世界没退到锚点，却因为走 BUDGET 路径
        //    打了 anchor_restored=1（"看着像回去了，其实差 1.4 秒"）。
        //    正解：start = 现在；窗口长 = now − anchorTick（plan 已经校验过它 ≤ WINDOW_TICKS）。
        final int start = planned.nowTick();
        final int rewind = start - planned.anchorTick();
        if (rewind <= 0) {
            GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] anchor_refused reason={} anchor_tick={} now_tick={}"
                            + " start_tick={} detail=no_changes_since_anchor",
                    ShanhaiReplayAnchor.Refusal.ALREADY_AT_ANCHOR.code(),
                    planned.anchorTick(), planned.nowTick(), start);
            notify(actor, "gt_shanhai.time.rewind.no_changes");
            return false;
        }
        if (!begin(srv, actor, rewind, start, anchor, 0)) {
            return false;
        }
        if (anchor != null) {
            notify(actor, "gt_shanhai.time.rewind.anchor_started", anchor.tick(), rewind);
        }
        return true;
    }

    /**
     * 两条路（秒数 / 锚点）<b>共用</b>的进入流程：落盘 → 关写盘闸 → 切 REPLAY → 起节拍器。
     */
    private static boolean begin(MinecraftServer srv, ServerPlayer actor, int plan, int start, 
                                 ShanhaiReplayJournal.Anchor anchor, int seconds) {
        final ShanhaiReplayPacer.Journals journals = journals();
        final int newest = journals.newestTick();
        final ServerLevel probe = srv.overworld();
        final long now = probe == null ? newest : probe.getGameTime();

        // A rewind must not start from known incomplete history or override an existing save lock.
        boolean loss = ReplaySafety.hasLoss(journals, ShanhaiReplayCapture.failedSteps(),
                ShanhaiReplayRecorder.recordFailed() + ShanhaiReplayBlockEntityRecorder.baselineFailures()
                        + ShanhaiReplayEntityRecorder.captureLosses() + ShanhaiReplayProgressRecorder.captureLosses()
                        + ShanhaiReplayEntityMotionRecorder.captureLosses() + ShanhaiReplayHealthRecorder.captureLosses());
        if (loss) {
            notify(actor, "gt_shanhai.time.rewind.history_incomplete");
            GTDishanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] start_refused reason=incomplete_history");
            return false;
        }
        int dimensions = 0;
        boolean saveDisabled = false;
        for (ServerLevel level : srv.getAllLevels()) {
            if (level != null) { dimensions++; saveDisabled |= level.noSave; }
        }
        if (saveDisabled || dimensions == 0) {
            notify(actor, "gt_shanhai.time.rewind.save_failed");
            GTDishanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] start_refused reason=save_disabled_or_no_dimensions");
            return false;
        }
        final ShanhaiClockMode previousMode = ShanhaiWorldClock.SERVER.getMode();
        ShanhaiWorldClock.SERVER.setMode(ShanhaiClockMode.REPLAY);
        broadcastClock(srv);
        final int saved = saveAll(srv, true, false);
        if (!ReplaySafety.mayStart(dimensions, saved, saveDisabled, loss) || !drainStorage(srv)) {
            ShanhaiWorldClock.SERVER.setMode(previousMode);
            broadcastClock(srv);
            notify(actor, "gt_shanhai.time.rewind.save_failed");
            GTDishanhaiMod.LOGGER.error("[SHANHAI-REPLAY] start_refused reason=save_or_barrier_failed saved={} dimensions={}", saved, dimensions);
            return false;
        }
        try {
            ReplaySaveGuard.beginReplay();
        } catch (RuntimeException guardFailure) {
            ShanhaiWorldClock.SERVER.setMode(previousMode);
            broadcastClock(srv);
            notify(actor, "gt_shanhai.time.rewind.save_failed");
            GTDishanhaiMod.LOGGER.error("[SHANHAI-REPLAY] start_refused reason=save_guard", guardFailure);
            return false;
        }

        // ---- ② 关写盘闸（屏蔽每 6000 tick 的自动保存；崩溃 ⇒ 磁盘停在①）
        int noSaveCount = 0;
        for (ServerLevel level : srv.getAllLevels()) {
            if (level == null) {
                continue;
            }
            level.noSave = true;
            ++noSaveCount;
        }

        // ---- ③ 切 REPLAY（bump epoch ⇒ 客户端立刻冻住；所有记录器同步冻结）
        //     🔴 先抄下"进来之前的模式"：收尾要回到它（PAUSED → PAUSED），不是一律 LIVE
        modeBefore = previousMode;
        ShanhaiReplayGate.resetReplayWrite();
        ShanhaiWorldClock.SERVER.setMode(ShanhaiClockMode.REPLAY);
        ShanhaiWorldClock.SERVER.noteAnchorTick(now);

        // ---- ④ 锚点：进倒流就标 stale（位置快照保留，回退到位且同维度时再清掉）
        if (anchor != null) {
            ShanhaiReplayAnchorRuntime.markStale("replay", (int) now);
        }

        // ---- ⑤ 节拍器：默认 1:1（per_tick=1），锚点语义退到锚点 tick 就停
        PACER.begin(plan, start);
        PACER.setSpeed(ShanhaiReplayConst.REPLAY_SPEED_DEFAULT);
        if (anchor != null) {
            PACER.atAnchor(anchor.tick());
        }
        // 🔴 守门置真必须排在节拍器之后、第一个 tick 之前（见 running 的注释）
        running = true;
        startTick = start;
        steps = 0L;
        startWallMs = System.currentTimeMillis();
        writtenTotal = 0L;
        failedTotal = 0L;
        skippedTotal = 0L;
        alreadyAirTotal = 0L;
        blocksTotal = 0L;
        entityTotal = 0L;
        entityAppliedTotal = 0L;
        entityReasonTotal = 0L;
        progressTotal = 0L;
        beDiffTotal = 0L;
        scalarTotal = 0L;
        scalarSkippedTotal = 0L;
        motionTotal = 0L;
        motionAppliedTotal = 0L;
        motionSentTotal = 0L;
        motionPacketsTotal = 0L;
        healthTotal = 0L;
        healthAppliedTotal = 0L;
        motionAfterRebuildTotal = 0L;
        rebuildPosOkTotal = 0L;
        rebuildPosMismatchTotal = 0L;
        blockWrittenTotal = 0L;
        blockVerifiedTotal = 0L;
        blockAlreadyCorrectTotal = 0L;
        blockWriteFailedTotal = 0L;
        blockMismatchTotal = 0L;
        boundServer = srv;
        boundActor = actor;
        engine = new ShanhaiReplayEngine(srv);
        activeAnchor = anchor;
        anchorTick = anchor == null ? ShanhaiReplayAnchor.NO_ANCHOR_TICK : anchor.tick();
        anchorRestored = false;
        anchorDimMismatch = false;
        anchorRestoreDetail = "";
        // 回退要把 BE 的"上一次 baseline"当成过去态 ⇒ 工作 NBT 必须是这一轮全新的
        ShanhaiReplayBlockEntityRecorder.clearWorking();

        GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] start mode={} window={} seconds={} startTick={} newest={} oldest={}"
                        + " per_tick=1 speed={} savedLevels={} noSaveLevels={} anchor_tick={} anchor_dim={}"
                        + " anchor_pos={} anchor_restored=0 anchor_dim_mismatch=0 actor={}"
                        + " newest5={} blocks_classify[{}] entity_classify[{}] scalar_throttle[{}]"
                        + " journals[blocks={} entities={} progress={} be={} scalars={} motion={} health={}]",
                anchor == null ? "seconds" : "anchor", plan, seconds, start, newest,
                Math.max(ShanhaiReplayRecorder.journal().oldestTick(), 0),
                ShanhaiReplayConst.REPLAY_SPEED_DEFAULT, saved, noSaveCount,
                anchor == null ? -1 : anchor.tick(),
                anchor == null ? -1 : anchor.dim(),
                anchor == null ? "-" : anchor.posText(),
                actor == null ? "console" : actor.getGameProfile().getName(),
                journals.newestTick(),
                ShanhaiReplayRecorder.classifyLine(),
                ShanhaiReplayEntityRecorder.classifyLine(),
                ShanhaiReplayScalarRecorder.throttleLine(),
                journals.blocks().summary(), journals.entities().summary(), journals.progress().summary(),
                journals.blockEntities().summary(), journals.scalars().summary(),
                journals.entityMotion() == null ? "null" : journals.entityMotion().summary(),
                journals.health() == null ? "null" : journals.health().summary());
        // 🔴 五层"挂上了没有"自证行：与 AE 那边同一种手法（反射数注入方法）。
        //    没有这一行的时候，"机器没在跑"和"钩子根本没挂上"在日志上长得一模一样（真机血账）。
        logHooksArmed();
        // 🔴 2026-11-14 用户口径（"暂停和倒流不兼容"）的落点：把"从哪种模式进来、出去回到哪种模式"
        //    当场写进日志 —— 否则"结束之后时间被强行恢复流动"只能靠回忆。
        GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] mode_compose mode_before={} mode_during={} restore_to={} {}",
                modeBefore, ShanhaiWorldClock.SERVER.getMode(),
                ShanhaiReplayModeCompose.restoreAfter(modeBefore),
                ShanhaiReplayModeCompose.rowLine(modeBefore));
        // 🔴 运动记录"到底是谁"（判据：先证明猪有没有被记）——开始与结束各一次
        GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] {} {}", ShanhaiReplayEntityMotionRecorder.topLine(),
                ShanhaiReplayEntityMotionRecorder.mixLine());
        GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] health_records={} journal[{}]",
                ShanhaiReplayHealthRecorder.recorded(),
                ShanhaiReplayHealthRecorder.journal().summary());
        if (anchor == null) {
            notify(actor, "gt_shanhai.time.rewind.started", plan);
        }
        for (ServerPlayer player : srv.getPlayerList().getPlayers()) {
            ShanhaiClockNetwork.sendStateTo(player);
        }
        return true;
    }

    // ------------------------------------------------------------------ 推进

    /**
     * 每个服务端外层 tick（{@code TickEvent.ServerTickEvent} END）推进一拍。
     *
     * <p>⚠️ 这个入口<b>在暂停期间也会被调到</b> —— 那是刻意的：REPLAY 本身就是"暂停 + 播放头回退"。
     */
    public static synchronized void tick(MinecraftServer srv) {
        if (replayFailed) return;
        if (ReplaySaveGuard.hasSaveConflict()) {
            failReplay(srv != null ? srv : boundServer, "save_conflict");
            return;
        }
        if (!PACER.isActive()) {
            return;
        }
        final MinecraftServer server = srv != null ? srv : boundServer;
        if (ShanhaiWorldClock.SERVER.getMode() != ShanhaiClockMode.REPLAY) {
            if (!PACER.hasPendingSlice()) {
                stop(server, "mode_changed");
                return;
            }
            // Finish the in-flight slice before allowing the world to resume.
            pendingStopReason = "mode_changed";
            PACER.requestStop();
            ShanhaiWorldClock.SERVER.setMode(ShanhaiClockMode.REPLAY);
        }
        if (server == null) {
            return;
        }
        // 游标防御：万一还有比游标更新的记录（同一个外层 tick 里的抖动），对齐到最新
        // 🔴 对齐全额补预算：只抬游标不补窗口 ⇒ 整个倒流窗口整体上移（真机实测 28 tick，
        //    结果是"世界没退到锚点却报 anchor_restored=1"）。
        final int aligned = PACER.alignCursor(journals().newestTick());
        if (aligned > 0) {
            PACER.extendPlan(aligned);
            GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] cursor_aligned by={} cursor={} window={} left={}",
                    aligned, PACER.cursor(), PACER.window(), PACER.remaining());
        }

        final ShanhaiReplayPacer.Step step = PACER.step(journals(), targets());
        if (step.slices() > 0 || step.counts().changes() > 0 || step.counts().health() > 0) {
            writtenTotal += step.counts().written();
            failedTotal += step.counts().failed();
            skippedTotal += step.counts().skipped();
            blocksTotal += step.counts().blocks();
            entityTotal += step.counts().entityEvents();
            if (step.counts().entityApplied() > 0) {
                entityAppliedTotal += step.counts().entityApplied();
                ++entityReasonTotal;
            }
            progressTotal += step.counts().progress();
            beDiffTotal += step.counts().blockEntities();
            scalarTotal += step.counts().scalars();
            motionTotal += step.counts().motion();
            motionAppliedTotal += step.counts().motionApplied();
            motionSentTotal += step.counts().motionSent();
            motionPacketsTotal += step.counts().motionPackets();
            healthTotal += step.counts().health();
            healthAppliedTotal += step.counts().healthApplied();
            motionAfterRebuildTotal += step.counts().motionAfterRebuild();
            rebuildPosOkTotal += step.counts().rebuildPosOk();
            rebuildPosMismatchTotal += step.counts().rebuildPosMismatch();
            blockWrittenTotal += step.counts().blockWritten();
            blockVerifiedTotal += step.counts().blockVerified();
            blockAlreadyCorrectTotal += step.counts().blockAlreadyCorrect();
            blockWriteFailedTotal += step.counts().blockWriteFailed();
            blockMismatchTotal += step.counts().blockMismatch();
        }
        ++steps;
        if (engine != null) {
            alreadyAirTotal = engine.alreadyAir();
        }
        // 🔴 A 组的落点：把这一拍写过的格子【现在】就发给客户端。
        //    为什么必须在这里发：冻结路径的 getChunkSource().tick(haveTime, false) 跳过了
        //    ServerChunkCache#tickChunks()，而把 changedBlocksPerSection 变成
        //    ClientboundSectionBlocksUpdatePacket 的唯一地方就是 tickChunks:372-375
        //    ⇒ 不显式发，整段倒流客户端一个方块包都收不到（见 ShanhaiReplayClientSync 类注释 §1）。
        final String syncLine = engine == null ? "client_sync engine=null" : engine.flushClientSync();
        final double hz = pacerHz(PACER.totalSlices(), System.currentTimeMillis() - startWallMs);

        if (steps % 20L == 0L) {
            final int pct = ShanhaiReplayProgress.percent(PACER.doneTicks(), PACER.window());
            // 🔴 per_tick / steps 一眼就能看出"是逐步还是瞬移"（判据 ③ 的日志口径）
            // 🔴 entities=<桶里几条> entity_applied=<真删/真建几只> entity_reason=<为什么>
            //    —— 这三个数是为了"entities=0 到底是被跳过还是没东西可退"（真机血账）。
            // 🔴 pacer_hz：每秒【实际】推进了几个时间片（期望 20.00；被相位/节流拖慢会当场露馅）。
            // 🔴 client_sync：本步写了几格 / 真发出去几个包（同量级才算客户端能看见）。
            GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] step={} {} left={} pct={} reverted=[{},{}]"
                            + " written={} failed={} skippedPlayer={} alreadyAir={} anchor_tick={}"
                            + " entities_total={} entity_applied_total={} motion_total={} motion_sent_total={}"
                            + " health_records={} health_applied={}"
                            + " pacer_hz={} {}",
                    steps, step.toText(), PACER.remaining(), pct, PACER.cursor() + 1, startTick,
                    writtenTotal, failedTotal, skippedTotal, alreadyAirTotal, anchorTick,
                    entityTotal, entityAppliedTotal, motionTotal, motionSentTotal,
                    healthTotal, healthAppliedTotal,
                    String.format(java.util.Locale.ROOT, "%.2f", hz), syncLine);
        } else {
            GTDishanhaiMod.LOGGER.debug("[SHANHAI-REPLAY] step={} pacer_hz={} {}",
                    steps, String.format(java.util.Locale.ROOT, "%.2f", hz), syncLine);
        }
        showProgress(server);

        if (step.stop() == ShanhaiReplayPacer.Stop.FAILED || ReplaySaveGuard.hasSaveConflict()) {
            failReplay(server, step.stop() == ShanhaiReplayPacer.Stop.FAILED ? "apply_failed" : "save_conflict");
            return;
        }
        if (pendingStopReason != null && !PACER.hasPendingSlice()) {
            String requested = pendingStopReason;
            pendingStopReason = null;
            stop(server, requested);
            return;
        }
        switch (step.stop()) {
            case ANCHOR -> finishAtAnchor(server, "anchor_reached");
            case BUDGET -> {
                if (isAnchorRewind()) {
                    // 结构上不该走到这里（窗口 = start-anchorTick，与上面那条同时成立）；
                    // 留一条兜底：绝不"无声无息地停在半路"。
                    finishAtAnchor(server, "anchor_budget_zero");
                } else {
                    stop(server, "cursor_zero");
                }
            }
            default -> {
                // 继续
            }
        }
    }

    /**
     * 回退到位（游标到达锚点 tick）：判闸 → 同维度才把玩家传回锚点 → 收尾。
     *
     * <p>🔴 顺序：<b>先传送、再 stop</b>（stop 会清 ring 与锚点，传送要用 {@link #activeAnchor}）。
     */
    private static void finishAtAnchor(MinecraftServer server, String reason) {
        final ShanhaiReplayJournal.Anchor anchor = activeAnchor;
        // 🔴 2026-11-14 用户原话：「他倒流结束那一刻会拉回玩家位置，<b>不要拉回</b>」
        //    ⇒ 收尾<b>不搬人</b>（一个坐标都不许改）。锚点仍然记着位置，只用于显示/日志。
        //    旧路径 ShanhaiReplayAnchor#restore（会 teleportTo）保留成负对照的参照，生产不再调用。
        final ShanhaiReplayAnchor.RestoreResult result = ShanhaiReplayAnchor.restoreAtEnd(anchor);
        anchorRestored = result.restored();
        anchorDimMismatch = result.dimMismatch();
        anchorRestoreDetail = result.detail();
        showRestoreBar(server, result);
        stop(server, reason);
    }

    /** 到位那一刻的动作栏（判据 ④ 要求动作栏也打 {@code anchor_restored}）。 */
    private static void showRestoreBar(MinecraftServer server, ShanhaiReplayAnchor.RestoreResult result) {
        final Component line = Component.translatable(result.restored()
                ? "gt_shanhai.time.rewind.restored"
                : result.dimMismatch() ? "gt_shanhai.time.rewind.dimension_mismatch"
                : "gt_shanhai.time.rewind.restore_incomplete", result.detail());
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player != null) {
                    player.displayClientMessage(line, true);
                }
            }
        } catch (Throwable ignored) {
            // 动作栏发不出去不影响判定（日志里还有整行）
        }
    }

    /** 进度反馈：<b>动作栏</b>（{@code displayClientMessage(..., true)}）每拍刷新一次。 */
    private static void showProgress(MinecraftServer server) {
        final Component line = Component.translatable("gt_shanhai.time.rewind.progress",
                PACER.doneTicks(), PACER.window(), PACER.remaining());
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player != null) {
                    player.displayClientMessage(line, true);
                }
            }
        } catch (Throwable ignored) {
            // 动作栏发不出去不影响倒流本身
        }
    }

    private static ShanhaiReplayEngine engine() {
        if (engine == null) {
            engine = new ShanhaiReplayEngine(boundServer);
        }
        return engine;
    }

    /**
     * 每秒<b>实际</b>推进了几个时间片（判据：期望 {@value ShanhaiReplayConst#TICKS_PER_SECOND}.00）。
     *
     * <h2>为什么要单独读数（B 组的第二个判据）</h2>
     * 驱动是 {@code ShanhaiClockInit#onServerTick} 的 {@code TickEvent.ServerTickEvent END}
     * （{@code ShanhaiClockInit.java:141}，无条件、每外层 tick 一次）⇒ 理论上 20 片/秒。
     * 但"理论上"不算证据：真机日志里 `per_tick=1` 只说明<b>每拍退几片</b>，不说明<b>每秒几拍</b>。
     * 这两个数乘起来才是用户看到的"帧率"，所以必须把后者也打出来。
     * 分母用墙钟（{@code startWallMs}），分子用节拍器自己数过的片数（{@code totalSlices()}）。
     *
     * @param slices    已经退掉的时间片数
     * @param elapsedMs 距倒流开始的墙钟毫秒
     */
    private static double pacerHz(long slices, long elapsedMs) {
        if (slices <= 0L || elapsedMs <= 0L) {
            return 0.0D;
        }
        return (double) slices * 1000.0D / (double) elapsedMs;
    }

    private static void broadcastClock(MinecraftServer server) {
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                try { ShanhaiClockNetwork.sendStateTo(player); }
                catch (Throwable failure) { GTDishanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] clock broadcast failed", failure); }
            }
        } catch (Throwable failure) {
            GTDishanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] clock broadcast unavailable", failure);
        }
    }

    /** Drain the distinct vanilla chunk, entity and POI I/O workers with one deadline. */
    private static boolean drainStorage(MinecraftServer server) {
        if (server == null) return false;
        try {
            java.util.List<java.util.concurrent.CompletableFuture<?>> pending = new java.util.ArrayList<>();
            for (ServerLevel level : server.getAllLevels()) {
                var chunk = ((com.dishanhai.gt_shanhai.mixin.replay.ShanhaiChunkStorageAccessor)
                        level.getChunkSource().chunkMap).shanhai$getWorker();
                var poi = ((com.dishanhai.gt_shanhai.mixin.replay.ShanhaiSectionStorageAccessor)
                        level.getPoiManager()).shanhai$getWorker();
                var manager = ((com.dishanhai.gt_shanhai.mixin.replay.ShanhaiLevelEntityStorageAccessor)
                        level).shanhai$getEntityManager();
                var storage = ((com.dishanhai.gt_shanhai.mixin.replay.ShanhaiEntityManagerStorageAccessor)
                        manager).shanhai$getPermanentStorage();
                var entities = ((com.dishanhai.gt_shanhai.mixin.replay.ShanhaiEntityStorageAccessor)
                        storage).shanhai$getWorker();
                pending.add(chunk.synchronize(true));
                pending.add(poi.synchronize(true));
                pending.add(entities.synchronize(true));
            }
            return ReplayIoBarrier.drain(pending, 10_000L);
        } catch (Throwable failure) {
            GTDishanhaiMod.LOGGER.error("[SHANHAI-REPLAY] storage barrier unavailable", failure);
            return false;
        }
    }

    private static void failReplay(MinecraftServer server, String reason) {
        replayFailed = true;
        ReplaySaveGuard.fail();
        pendingStopReason = null;
        ShanhaiWorldClock.SERVER.setMode(ShanhaiClockMode.REPLAY);
        GTDishanhaiMod.LOGGER.error("[SHANHAI-REPLAY] failed reason={}; saving remains disabled", reason);
        if (server != null) {
            try {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    player.sendSystemMessage(Component.translatable("gt_shanhai.time.rewind.apply_failed"));
                    ShanhaiClockNetwork.sendStateTo(player);
                }
            } catch (Throwable failure) {
                GTDishanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] failed to notify players", failure);
            }
        }
    }

    // ------------------------------------------------------------------ 收尾

    /**
     * 收尾（三步，顺序见类注释 §3）。幂等：没在倒流时调用直接返回 {@code false}。
     *
     * <h2>🔴 2026-11-14 C 组：这条方法以前在"正常倒完"时<b>一次都没跑过</b></h2>
     * 旧首行是 {@code if (!PACER.isActive()) return false;}，而 {@link ShanhaiReplayPacer#step}
     * 在算出 {@code Stop.ANCHOR}/{@code Stop.BUDGET} 时<b>自己就把 active 置 false</b>
     * （{@code ShanhaiReplayPacer.java:490-499}）⇒ {@code finishAtAnchor → stop()} 一进门就 return false，
     * 旧第 543 行 {@code setMode(LIVE)} 永远跑不到。真机后果（用户原话）：
     * 「倒放结束之后时间没有正常流动，下面的锚点进度条也没消失」——世界永久冻结、
     * {@code noSave} 永久 true、锚点永不清空（{@code journal.clearAnchor()} 也在这行之后）。
     *
     * <h2>现在的结构（三条硬约束）</h2>
     * <ol>
     *   <li>守门用 {@link #running}（由 {@link #begin} 置真），<b>不再看已经自停的节拍器</b>；</li>
     *   <li><b>关账三件套放在最前面</b>（{@link ShanhaiReplayStopOrder#disarm}：停节拍器 / 关写后门 /
     *       切 LIVE），任何一件抛都不影响另外两件；快照只取"不会抛的基础类型"；</li>
     *   <li>剩下的步骤（对账行 / block_verify / 落盘 / 清时间线）全包在 try/catch 里，
     *       <b>收尾动作放 finally</b>：再兜一次 LIVE、flush 客户端同步、打 {@code stop_exit}、
     *       广播"无锚点"帧、重下发时钟状态。</li>
     * </ol>
     */
    public static synchronized boolean stop(MinecraftServer srv, String reason) {
        if (replayFailed) return false;
        if (!running && !PACER.isActive()) {
            return false;
        }
        if (PACER.hasPendingSlice() && !"server_stopping".equals(reason)) {
            PACER.requestStop();
            pendingStopReason = reason;
            return true;
        }
        final MinecraftServer releaseServer = srv != null ? srv : boundServer;
        if (releaseServer == null || !drainStorage(releaseServer) || !ReplaySaveGuard.releaseAfterSuccess()) {
            failReplay(releaseServer, "save_barrier_or_conflict");
            return false;
        }
        pendingStopReason = null;
        running = false;                    // 先脱闸 ⇒ 第二次进来一律 false（幂等，不会再跑一遍收尾）
        final MinecraftServer server = srv != null ? srv : boundServer;
        // 🔴 收尾要回到"进来之前"的模式（PAUSED → PAUSED，LIVE → LIVE），见 modeBefore 的注释
        final ShanhaiClockMode modeBeforeFinal =
                modeBefore == null ? ShanhaiClockMode.LIVE : modeBefore;
        final ShanhaiClockMode restoreTo = ShanhaiReplayModeCompose.restoreAfter(modeBeforeFinal);

        // ---- 只取"不可能抛"的基础读数：一旦关账（PACER.reset）就会被清掉，必须先抄下来
        final int leftTicks = PACER.remaining();
        final int windowFinal = PACER.window();
        final int cursorFinal = PACER.cursor();
        final long stepsFinal = steps;
        final long slicesFinal = PACER.totalSlices();
        final int anchorTickFinal = anchorTick;
        final boolean anchorModeFinal = isAnchorRewind();
        final int anchorRestoredFlag = anchorRestored ? 1 : 0;
        final int anchorDimMismatchFlag = anchorDimMismatch ? 1 : 0;
        final String anchorDetail = anchorRestoreDetail == null ? "" : anchorRestoreDetail;

        GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] stop_enter reason={} mode={} pacer_active={} left={}"
                        + " window={} cursor={} steps={} slices={}",
                reason, ShanhaiWorldClock.SERVER.getMode(), PACER.isActive() ? 1 : 0,
                leftTicks, windowFinal, Math.max(0, cursorFinal), stepsFinal, slicesFinal);

        // ---- ① 关账：停节拍器 → 关写后门 → 切 LIVE（bump epoch）。
        //     🔴 必须排在【任何可能抛异常的代码】之前：否则一次异常就把世界永久冻在 REPLAY。
        final boolean disarmed = ShanhaiReplayStopOrder.disarm(new ShanhaiReplayStopOrder.Clock() {
            @Override
            public void resetPacer() {
                PACER.reset();
            }

            @Override
            public void resetWriteGate() {
                ShanhaiReplayGate.resetReplayWrite();
            }

            @Override
            public void restoreMode() {
                ShanhaiWorldClock.SERVER.setMode(restoreTo);
            }

            @Override
            public void warn(String what, Throwable t) {
                GTDishanhaiMod.LOGGER.error("[SHANHAI-REPLAY] stop_disarm_failed what={} err={}", what, t.toString());
            }
        });

        boolean ok = false;
        String syncLine = "client_sync engine=null";
        try {
            if (!disarmed) throw new IllegalStateException("Failed to disarm replay safely");
            final long elapsedMs = System.currentTimeMillis() - startWallMs;
            final String failures = engine == null ? "engine=null" : engine.failureSummary();
            final ShanhaiReplayJournal journal = ShanhaiReplayRecorder.journal();
            final long overflow = journal.overflow();
            final long dropped = journal.droppedOldest();
            final ShanhaiReplayJournal.Anchor anchorNow = journal.anchor();
            final int anchorStaleFlag = anchorNow != null && anchorNow.stale() ? 1 : 0;
            final String progressUnsupported = ShanhaiReplayProgressRecorder.unsupportedSummary();

            // 🔴 收尾对齐（用户："收尾瞬移十几格"）：对动过的实体补发 teleport（坐标 = 最后写入坐标）。
            //    必须排在 setMode 之前：那时玩家还处于冻结态，客户端收到就当场落位。
            if (engine != null) {
                try {
                    engine.flushLagNow();
                    engine.flushFinalPositions();
                } catch (Throwable t) {
                    GTDishanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] end_align 失败 err={}", t.toString());
                }
            }

            // ---- ② 对账：一行可 grep 的判据（不允许静默）
            // 🔴 reverted=[a,b]：a = 游标+1，b = 开始时的 tick。
            // 🔴 2026-11-14 D：**同名异值**一律不许出现 ——
            //    `motion=<记录>/<应用>`、`health=<记录>/<应用>` 各只有一份；
            //    引擎内部计数改名成 `engine_*`（见 ShanhaiReplayEngine#failureSummary）。
            GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] done reason={} cursor={} reverted=[{},{}] per_tick=1 speed={}"
                            + " written={} failed={} skippedPlayer={} alreadyAir={}"
                            + " slices={} steps={} blocks={} entities={} progress={} beDiffs={} scalars={}"
                            + " motion={}/{}"
                            + " motion_sent={} motion_packets={} motion_after_rebuild={}"
                            + " entity_rebuild_pos={}:{}/{}"
                            + " health={}/{} health_death_edges={} revived={} {}"
                            + " block_written={} block_verified={} block_already_correct={}"
                            + " block_write_failed={} block_mismatch={}"
                            + " overflow={} droppedOldest={} window={} left={} ms={} {} {}"
                            + " anchor_mode={} anchor_tick={} anchor_dist={} anchor_restored={} anchor_dim_mismatch={}"
                            + " anchor_stale={} anchor_detail={}",
                    reason, Math.max(0, cursorFinal), cursorFinal + 1, startTick,
                    ShanhaiReplayConst.REPLAY_SPEED_DEFAULT,
                    writtenTotal, failedTotal, skippedTotal, alreadyAirTotal,
                    slicesFinal, stepsFinal, blocksTotal, entityTotal, progressTotal, beDiffTotal, scalarTotal,
                    motionTotal, motionAppliedTotal,
                    motionSentTotal, motionPacketsTotal, motionAfterRebuildTotal,
                    rebuildPosMismatchTotal == 0 ? "ok" : "mismatch",
                    rebuildPosOkTotal, rebuildPosMismatchTotal,
                    healthTotal, healthAppliedTotal, ShanhaiReplayHealthRecorder.deathEdges(),
                    ShanhaiReplayEntityRecorder.revived(), ShanhaiReplayRevive.line(),
                    blockWrittenTotal, blockVerifiedTotal, blockAlreadyCorrectTotal,
                    blockWriteFailedTotal, blockMismatchTotal,
                overflow, dropped, windowFinal, leftTicks, elapsedMs, failures, progressUnsupported,
                anchorModeFinal ? 1 : 0, anchorModeFinal ? anchorTickFinal : -1,
                anchorModeFinal ? Math.max(0, cursorFinal - anchorTickFinal) : -1,
                anchorRestoredFlag, anchorDimMismatchFlag, anchorStaleFlag, anchorDetail);
        // 🔴 方块"到底有没有回来"的硬判据：抽 32 个本窗口内被改过的 pos，读回比对锚点态。
        //    计数全绿但方块没回来，是 2026-11 真机实测的形态 ⇒ 必须有一行独立读数。
        if (engine != null) {
            try {
                GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] block_verify {} entity_records={} entity_applied={}"
                                + " motion_records={} motion_applied={} motion_sent={} motion_packets={}"
                                + " health_records={} health_applied={} block_records={} {}",
                        engine.verifyBlocks(), entityTotal, entityAppliedTotal, motionTotal, motionAppliedTotal,
                        motionSentTotal, motionPacketsTotal, healthTotal, healthAppliedTotal,
                        blocksTotal, engine.particleLine());
            } catch (Throwable t) {
                GTDishanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] block_verify 自查失败 err={}（这一轮没有这条读数）",
                        t.toString());
            }
        }
        // 🔴 运动记录"到底是谁"（判据：先证明猪有没有被记）——回放结束这一次必须打在 reset 之前
        GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] {} {}",
                ShanhaiReplayEntityMotionRecorder.topLine(), ShanhaiReplayEntityMotionRecorder.mixLine());
        // 🔴 取证（用户："收尾瞬移十几格，不止 0.5"）：逐实体发包 + 最后写入坐标 + 血量 0 跳过/救活
        if (engine != null) {
            try {
                GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] {} {} {} health_zero_skipped={} {} {}",
                        engine.sendTopLine(), engine.spawnLine(), engine.lastWrittenLine(),
                        engine.healthZeroSkipped(), engine.aliveAfterLine(),
                        ShanhaiReplayHealthRecorder.deathEdgeLine() + " revive_prevented_die=" + ShanhaiReplayEntityRecorder.revivePreventedDie());
                GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] {} {}",
                        engine.particleLine(), engine.leadLine());
            } catch (Throwable t) {
                GTDishanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] 取证行打印失败 err={}", t.toString());
            }
        }
        // 稳态 dayTime 不逐 tick 记 ⇒ 收尾按"实际退掉的 tick 数"把太阳拨回去
        ShanhaiReplayScalarRecorder.catchUpDayTime(server, windowFinal - leftTicks);

        // ---- ③ 落盘：恢复 noSave 并显式 save 一次（第 3 参 false 才真写）
        if (server != null) {
            int restored = 0;
            for (ServerLevel level : server.getAllLevels()) {
                if (level == null) {
                    continue;
                }
                level.noSave = false;
                ++restored;
            }
            final int saved = saveAll(server, true, false);
            if (restored == 0 || saved != restored) {
                throw new IllegalStateException("Final rewind save failed: " + saved + "/" + restored);
            }
            GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] save_restored noSave=false levels={} saved={}", restored, saved);
        }

        // ---- ④ 清空五条时间线 + 各载荷池（设计 §4.2 末 + 任务口径"结束后把 ring 清空"）
        journal.clear();
        journal.clearAnchor();
        ShanhaiReplayPayloads.clear();
        ShanhaiReplayEntityRecorder.reset();
        ShanhaiReplayEntityMotionRecorder.reset();
        ShanhaiReplayHealthRecorder.reset();
        ShanhaiReplayProgressRecorder.reset();
        ShanhaiReplayBlockEntityRecorder.reset();
        ShanhaiReplayScalarRecorder.reset();
        // 倒流改过时间/天气/规则 ⇒ 必须重新基线，否则下一拍会把我们自己的写当成新历史
        ShanhaiReplayScalarRecorder.rebaseline();
        activeAnchor = null;
        anchorTick = ShanhaiReplayAnchor.NO_ANCHOR_TICK;
        boundActor = null;
        anchorRestoreDetail = "";
        modeBefore = null;
        if (server != null) {
            try {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (player == null) {
                        continue;
                    }
                    player.displayClientMessage(Component.translatable("gt_shanhai.time.rewind.finished", reason), true);
                }
            } catch (Throwable ignored) {
                // 提示发不出去不影响收尾
            }
        }
        anchorRestored = false;
        anchorDimMismatch = false;
        ok = true;
        } catch (Throwable t) {
            replayFailed = true;
            running = true;
            ReplaySaveGuard.fail();
            GTDishanhaiMod.LOGGER.error("[SHANHAI-REPLAY] stop_body_failed reason={} err={}; save guard active",
                    reason, t.toString());
            if (server != null) {
                try {
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        player.sendSystemMessage(Component.translatable("gt_shanhai.time.rewind.apply_failed"));
                    }
                } catch (Throwable notifyFailure) {
                    GTDishanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] failed to notify players", notifyFailure);
                }
            }
        } finally {
            if (server != null) {
                for (ServerLevel level : server.getAllLevels()) {
                    try { if (level != null) level.noSave = replayFailed; }
                    catch (Throwable t) { GTDishanhaiMod.LOGGER.error("[SHANHAI-REPLAY] restore save flag failed", t); }
                }
            }
            // ---- ⑤ 兜底收尾：无论上面抛没抛，这三件事必须成立（顺序即判据顺序）
            try {
                PACER.reset();
                ShanhaiReplayGate.resetReplayWrite();
                // 🔴 回到"进来之前"的模式（不是一律 LIVE）：PAUSED 进来的，出去还是 PAUSED
                ShanhaiWorldClock.SERVER.setMode(replayFailed ? ShanhaiClockMode.REPLAY : restoreTo);
            } catch (Throwable t) {
                GTDishanhaiMod.LOGGER.error("[SHANHAI-REPLAY] stop_force_mode_failed mode={} err={}",
                        restoreTo, t.toString());
            }
            // 🔴 方案 A（解冻后强制绝对包）：窗口必须从 setMode 之后开始 —— 冻结期 sendChanges 根本不跑
            //    （tickChunks 被跳过），提前 arm 只会把 250 ms 烧在冻结里；放第一处 setMode 同理（后面还有 saveAll）。
            ShanhaiReplayForceTeleport.arm(250L);
            GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] force_abs_teleport arm=250ms");
            try {
                // 本步还压着的方块包：退回 LIVE 之前先发干净（否则它们要等下一拍 tickChunks 才走）
                syncLine = engine == null ? "client_sync engine=null" : engine.flushClientSync();
            } catch (Throwable t) {
                syncLine = "client_sync flush_failed err=" + t;
            }
            // 🔴 判据第一行：证明这个方法真的跑完了（旧版在这里之前就 return 了）
            // 🔴 mode_before / mode_after：证明"暂停进来的，出去还是暂停"（用户 2026-11-14 的诉求）
            GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] stop_exit ok={} disarmed={} mode={} mode_before={}"
                            + " mode_after={} reason={} left={} window={} steps={} {}",
                    ok, disarmed, ShanhaiWorldClock.SERVER.getMode(), modeBeforeFinal,
                    ShanhaiWorldClock.SERVER.getMode(), reason,
                    leftTicks, windowFinal, stepsFinal, syncLine);
            // 🔴 判据第二行：主动广播"无锚点"帧 ⇒ 客户端 HUD 立刻消失
            //    （以前只有 logging_out / window_tick 会给帧，而 window_tick 只在"还有锚点"时才发：
            //     锚点没清 ⇒ 条永远留在屏幕上——真机日志 12:30 的 anchor_tick=7073 就是这么来的）
            if (!replayFailed) ShanhaiAnchorBarBroadcast.onReplayEnd(server, reason);
            // 🔴 判据第三行：客户端时钟跟着回到 mode_before（PAUSED 那局：客户端打 clock_resume mode=PAUSED）
            if (server != null) {
                try {
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        if (player != null) {
                            ShanhaiClockNetwork.sendStateTo(player);
                        }
                    }
                } catch (Throwable ignored) {
                    // 时钟包发不出去不影响判定（单机下客户端还会读 SERVER 兜底，见 ShanhaiClientClock#frozen）
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 记录闸读数

    /** 上一次打 {@code record_gate} 时的模式（{@code null} = 还没打过）。 */
    private static ShanhaiClockMode gateLastMode;
    /** 上一次打 {@code record_gate} 时的"累计记录条数"与那一拍的采样拍数。 */
    private static long gateLastRecorded = -1L;
    private static long gateTicks;

    /** 记录器当前累计条数（六层之和；只给读数和自检判读用）。 */
    public static long recordedTotal() {
        return ShanhaiReplayRecorder.recordKept()
                + ShanhaiReplayEntityRecorder.joins() + ShanhaiReplayEntityRecorder.leaves()
                + ShanhaiReplayEntityMotionRecorder.recorded()
                + ShanhaiReplayHealthRecorder.recorded()
                + ShanhaiReplayProgressRecorder.observed()
                + ShanhaiReplayBlockEntityRecorder.capturedCount()
                + ShanhaiReplayScalarRecorder.scalarRecords();
    }

    /**
     * {@code record_gate} 一行（判据：<b>暂停期间记录必须继续</b>）。
     *
     * <p>用户原话：「我设置锚点之后，暂停时间，再 {@code /shanhai clock replay}，
     * 此时直接开始倒流了，而它应该<b>继续记录</b>才对」。
     * ⇒ 这一行要能<b>当场</b>读出"现在这个模式记不记、最近这段时间记了多少"：
     * <pre>
     *   record_gate mode=PAUSED recording=true recorded=42 ticks=100 per_tick=0.42 blocks=0 entities=2 …
     * </pre>
     * 模式一切换就打一行（{@code reason=mode_change}），之后每 {@value #RECORD_GATE_PERIOD} 拍一行
     * （{@code reason=periodic}）。{@code recorded=} 是这段时间的<b>增量</b>，所以"仍在增长"可直接被判读。
     */
    public static void recordGateTick(MinecraftServer server) {
        try {
            final ShanhaiClockMode mode = ShanhaiWorldClock.SERVER.getMode();
            final long total = recordedTotal();
            final boolean modeChanged = gateLastMode != mode;
            ++gateTicks;
            if (!modeChanged && gateTicks % RECORD_GATE_PERIOD != 0L) {
                return;
            }
            final long delta = gateLastRecorded < 0L ? 0L : Math.max(0L, total - gateLastRecorded);
            final long ticks = Math.max(1L, modeChanged ? 1L : RECORD_GATE_PERIOD);
            GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] record_gate mode={} recording={} recorded={} ticks={}"
                            + " per_tick={} total={} reason={} {}",
                    mode, ShanhaiReplayModeCompose.recordsUnder(mode), delta, ticks,
                    String.format(java.util.Locale.ROOT, "%.2f", (double) delta / (double) ticks),
                    total, modeChanged ? "mode_change" : "periodic",
                    ShanhaiReplayModeCompose.rowLine(mode));
            gateLastMode = mode;
            gateLastRecorded = total;
            gateTicks = 0L;
        } catch (Throwable t) {
            // 读数坏了绝不影响记录本身
        }
    }

    /** {@code record_gate} 的周期（拍）。 */
    public static final int RECORD_GATE_PERIOD = 100;

    /**
     * 流水线状态机表（启动时打一次，可 grep {@code mode_compose}）。
     *
     * <p>三行 {@code LIVE/PAUSED/REPLAY} × 每个动作的可否 —— 与自检 V 组断言的是同一份输出。
     */
    public static void logModeComposeTable() {
        try {
            for (String line : ShanhaiReplayModeCompose.tableLines()) {
                GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] mode_compose table {}", line);
            }
        } catch (Throwable t) {
            GTDishanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] mode_compose 表打印失败", t);
        }
    }

    /** 服务端启动：清空一切（新世界不继承上一个世界的记录）。 */
    public static synchronized void onServerStarted(MinecraftServer srv) {
        replayFailed = false;
        pendingStopReason = null;
        PACER.reset();
        running = false;
        engine = null;
        boundServer = srv;
        startTick = 0;
        steps = 0L;
        activeAnchor = null;
        anchorTick = ShanhaiReplayAnchor.NO_ANCHOR_TICK;
        boundActor = null;
        anchorRestored = false;
        anchorDimMismatch = false;
        anchorRestoreDetail = "";
        ShanhaiReplayGate.resetReplayWrite();
        ShanhaiReplayEngine.clearDims();
        ShanhaiReplayRecorder.resetAll();
        ShanhaiReplayEntityRecorder.reset();
        ShanhaiReplayEntityMotionRecorder.reset();
        ShanhaiReplayHealthRecorder.reset();
        ShanhaiReplayProgressRecorder.reset();
        ShanhaiReplayBlockEntityRecorder.reset();
        ShanhaiReplayScalarRecorder.reset();
        ShanhaiReplayCapture.reset();
    }

    /** 服务端停止：<b>必须</b>在停机存档之前把 {@code noSave} 恢复（否则整局进度不落盘）。 */
    public static synchronized void onServerStopping(MinecraftServer srv) {
        if (replayFailed) {
            GTDishanhaiMod.LOGGER.error("[SHANHAI-REPLAY] stopping after failed rewind; level saving stays disabled");
            return;
        }
        // Shutdown cannot leave a partly applied time slice in the save.
        if (PACER.hasPendingSlice()) PACER.requestStop();
        while (PACER.isActive() && PACER.hasPendingSlice()) {
            var step = PACER.step(journals(), targets());
            if (step.stop() == ShanhaiReplayPacer.Stop.FAILED) {
                replayFailed = true;
                ReplaySaveGuard.fail();
                GTDishanhaiMod.LOGGER.error("[SHANHAI-REPLAY] shutdown drain failed; save guard active");
                return;
            }
            if (engine != null) engine.flushClientSync();
        }
        // 🔴 判据用 running（不是 PACER）：节拍器在最后一拍会自停，而收尾还没做。
        if (running || PACER.isActive()) {
            stop(srv, "server_stopping");
        }
        running = false;
        boundServer = null;
        ShanhaiReplayGate.resetReplayWrite();
        ShanhaiReplayEngine.clearDims();
    }

    // ------------------------------------------------------------------ status

    /** {@code /shanhai clock status} 与 {@code /shanhai time status} 用的一行。 */
    public static String statusLine() {
        final ShanhaiReplayPacer.Journals journals = journals();
        final int pct = ShanhaiReplayProgress.percent(PACER.doneTicks(), PACER.window());
        return "replay=" + (running ? "on" : "off")
                + " pacer_active=" + (PACER.isActive() ? 1 : 0)
                + " window=" + PACER.window() + " left=" + PACER.remaining() + " pct=" + pct
                + " written=" + writtenTotal + " failed=" + failedTotal + " skippedPlayer=" + skippedTotal
                + " anchor_mode=" + (isAnchorRewind() ? 1 : 0)
                + " anchor_tick=" + (isAnchorRewind() ? anchorTick : -1)
                + " anchor_restored=" + (anchorRestored ? 1 : 0)
                + " anchor_dim_mismatch=" + (anchorDimMismatch ? 1 : 0)
                + " pacer[" + PACER.summary() + "]"
                + " journal[" + journals.blocks().summary() + "]"
                + " entities[" + journals.entities().summary() + "]"
                + " progress[" + journals.progress().summary() + "]"
                + " beDiff[" + journals.blockEntities().summary() + "]"
                + " scalars[" + journals.scalars().summary() + "]"
                + " motion[" + (journals.entityMotion() == null ? "null" : journals.entityMotion().summary()) + "]"
                + " health[" + (journals.health() == null ? "null" : journals.health().summary()) + "]"
                + " record=" + ShanhaiReplayModeCompose.recordsUnder(ShanhaiWorldClock.SERVER.getMode())
                + " recorded_total=" + recordedTotal()
                + " states=" + ShanhaiStateIndex.size()
                + " payloads=" + ShanhaiReplayPayloads.size();
    }

    /** 记录器概览（status 用）。 */
    public static String recorderLine() {
        return "recorder blocks(attempts=" + ShanhaiReplayRecorder.recordAttempts()
                + " kept=" + ShanhaiReplayRecorder.recordKept()
                + " noop=" + ShanhaiReplayRecorder.recordNoop()
                + " failed=" + ShanhaiReplayRecorder.recordFailed() + ")"
                + " | " + ShanhaiReplayBlockEntityRecorder.summary()
                + " | " + ShanhaiReplayProgressRecorder.summary()
                + " | " + ShanhaiReplayEntityRecorder.summary()
                + " | " + ShanhaiReplayScalarRecorder.summary()
                + " | " + ShanhaiReplayCapture.summary();
    }

    // ------------------------------------------------------------------ 五层挂载自证

    /**
     * 五层「挂上了没有」自证行（可 grep {@code hooks_armed}）。
     *
     * <p>🔴 判定手法与工程里既有的 {@code [SHANHAI-CLOCK] ae_hooks_armed …} 完全一致：
     * 反射数<b>目标类</b>里有没有我们注入的 {@code shanhai$…} 方法。
     * Mixin 变换完成后它们是真身方法，所以"有"＝这一层这一轮确实在跑；
     * "没有"＝{@code ok=false}，并且一定跟 {@code reason=}。
     *
     * <p>为什么必须在 {@code ServerStartedEvent}/倒流开始时打：太早做反射会看到<b>未混入</b>的类
     * （本工程 2026-09 血账，见 {@code ShanhaiClockInit#logAeHooksArmed} 注释）。
     */
    public static List<ShanhaiReplayArmed.Layer> armedLayers() {
        final List<ShanhaiReplayArmed.Layer> out = new java.util.ArrayList<>(6);
        out.add(probe(ShanhaiReplayRecorder.LAYER_ID, "LevelChunk#setBlockState",
                net.minecraft.world.level.chunk.LevelChunk.class,
                ShanhaiReplayRecorder.MIXIN_METHOD, "mixin_absent"));
        out.add(probe(ShanhaiReplayRecorder.LAYER_DESTROY_ID, "Level#removeBlock",
                net.minecraft.world.level.Level.class,
                ShanhaiReplayRecorder.MIXIN_DESTROY_METHOD, "mixin_absent"));
        out.add(new ShanhaiReplayArmed.Layer("entities", "EntityJoinLevelEvent",
                ShanhaiReplayEntityRecorder.listenerArmed(),
                ShanhaiReplayEntityRecorder.listenerArmed() ? "" : "listener_not_registered"));
        final boolean gt = ShanhaiReplayGtProgress.available();
        final boolean observeHook = hasInjected(net.minecraft.world.level.chunk.LevelChunk.class,
                ShanhaiReplayProgressRecorder.MIXIN_METHOD);
        out.add(new ShanhaiReplayArmed.Layer("progress", "gtceu/RecipeLogic#setProgress",
                gt && observeHook,
                gt ? (observeHook ? "" : "mixin_absent") : "gt_absent"));
        out.add(probe("be", "BlockEntity#setChanged",
                net.minecraft.world.level.block.entity.BlockEntity.class,
                ShanhaiReplayBlockEntityRecorder.MIXIN_METHOD, "mixin_absent"));
        out.add(new ShanhaiReplayArmed.Layer("scalars", "ServerTickEvent#END",
                ShanhaiReplayCapture.ticks() > 0, "tick_event_never_fired"));
        return out;
    }

    private static ShanhaiReplayArmed.Layer probe(String id, String hook, Class<?> target,
                                                  String method, String absentReason) {
        final boolean ok = hasInjected(target, method);
        return new ShanhaiReplayArmed.Layer(id, hook, ok, ok ? "" : absentReason);
    }

    /**
     * 目标类里有没有注入的这个方法 —— 🔴 <b>改名感知</b>（注入方法不公开，只能用 {@code declared}）。
     *
     * <h2>为什么必须改名感知（不能比全名）</h2>
     * Mixin 运行期会把 handler 改名成 {@code handler$<token>$<原名>} 再插进目标类 ⇒
     * {@code getName().equals("shanhai$recordForReplay")} <b>恒不成立</b>，四层全报假 {@code absent}。
     * 本工程既有口径同此：{@code ShanhaiHookSelfCheckCore} 按片段计数（{@code contains}），
     * {@code ModuleSetBlockWatch#verifyArmed} 2026-09 血账（第一版 {@code startsWith} ⇒ 世界加载后仍报「找不到」，
     * 而同一轮日志的栈里就有 {@code LevelChunk.handler$zgh000$shanhai$watchSetBlockState}）。
     * 2026-11-14 实测同一形态：{@code be} 层 {@code hook_calls>0}（{@code BlockEntity#setChanged} 的混入确实在跑），
     * 而本自证一直报 {@code ok=false reason=mixin_absent}。
     *
     * <h2>为什么是 endsWith 而不是 contains</h2>
     * 只认<b>末尾</b>：Mixin 的形态是 {@code handler$abc123$shanhai$recordForReplay}，末尾就是原名；
     * 用 {@code contains} 会让 {@code shanhai$recordForReplay} 命中同类的
     * {@code shanhai$recordRemoveForReplay} 之类兄弟方法（假阳性）。
     */
    private static boolean hasInjected(Class<?> target, String method) {
        try {
            for (java.lang.reflect.Method m : target.getDeclaredMethods()) {
                final String n = m.getName();
                if (n != null && (n.equals(method) || n.endsWith("$" + method))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 取不到就是"判不了" ⇒ 必须算 false（"读不到"不许被当成"挂上了"）
        }
        return false;
    }

    /** 打那一行（全绿 INFO / 有红项 ERROR，后者绝不静默）。 */
    public static void logHooksArmed() {
        try {
            final List<ShanhaiReplayArmed.Layer> layers = armedLayers();
            final String text = ShanhaiReplayArmed.line(layers);
            if (ShanhaiReplayArmed.allArmed(layers)) {
                GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] {}", text);
            } else {
                GTDishanhaiMod.LOGGER.error("[SHANHAI-REPLAY] {} —— 红的层={}；"
                                + "ok=false 只代表【这一层这一轮没在跑】，不代表世界里没这类事",
                        text, ShanhaiReplayArmed.redIds(layers));
            }
        } catch (Throwable t) {
            GTDishanhaiMod.LOGGER.error("[SHANHAI-REPLAY] hooks_armed 自证失败 err={} ⇒ "
                    + "本次日志里没有 hooks_armed 行【不能】当作「五层都挂上了」的证据", t.toString());
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 给所有 level 各存一次；返回成功次数（失败只记日志，绝不抛）。 */
    private static int saveAll(MinecraftServer server, boolean flush, boolean skip) {
        int ok = 0;
        for (ServerLevel level : server.getAllLevels()) {
            if (level == null) {
                continue;
            }
            try {
                // 🔴 第 3 参 = skip：false 才真写（true 是"跳过"，设计 §4.2 的坑）
                level.save(null, flush, skip);
                ++ok;
            } catch (Throwable t) {
                GTDishanhaiMod.LOGGER.error("[SHANHAI-REPLAY] save 失败 dim={} err={}",
                        level.dimension().location(), t.toString());
            }
        }
        return ok;
    }

    private static void notify(ServerPlayer actor, String key, Object... args) {
        if (actor == null) {
            return;
        }
        try {
            actor.sendSystemMessage(Component.translatable(key, args));
        } catch (Throwable ignored) {
            // 提示发不出去不影响判定
        }
    }

    private ShanhaiReplayService() {
    }
}
