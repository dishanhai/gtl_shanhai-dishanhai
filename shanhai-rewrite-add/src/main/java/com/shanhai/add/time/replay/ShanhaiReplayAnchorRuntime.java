package com.shanhai.add.time.replay;

import java.util.List;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.time.ShanhaiClockMode;
import com.shanhai.add.time.ShanhaiClockNetwork;
import com.shanhai.add.time.ShanhaiWorldClock;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

/**
 * 山海重构 · <b>锚点的运行期外壳</b>（Minecraft 侧；纯规则全在 {@link ShanhaiReplayAnchor} 里）。
 *
 * <h2>1. 这一类只干四件"必须碰游戏"的事</h2>
 * <ol>
 *   <li><b>抓快照</b>：{@link #capture} 从 {@code ServerPlayer} 上取 tick / 维度 / 坐标 / 朝向 / 日志游标；</li>
 *   <li><b>设/清/看</b>：{@link #set} 先过状态机闸（LIVE 才许设，PAUSED/REPLAY 各自给码），再写进
 *       {@link ShanhaiReplayRecorder#journal()}（只存一份）；</li>
 *   <li><b>标 stale</b>：玩家死亡 / 换维度 / 进 REPLAY ⇒ 锚点<b>保留</b>但标失效（监听器就在下面两个方法上）；</li>
 *   <li><b>回退到位后把玩家传回去</b>：{@link #restore} 走 {@link ShanhaiReplayAnchor#restore} 的闸，
 *       同维度才 {@code teleportTo}。</li>
 * </ol>
 *
 * <h2>2. 🔴 为什么用 {@code teleportTo} 而不是 {@code changeDimension}</h2>
 * 用户口径是"把玩家传回锚点位置/朝向"。{@code changeDimension} 会重建玩家实体、走传送门逻辑、
 * 可能触发 {@code PlayerChangedDimensionEvent}（那就变成"倒流顺手改了玩家维度"）。
 * 这里只调 {@code ServerPlayer#teleportTo(ServerLevel, x, y, z, yaw, pitch)}：同一个维度里
 * 挪位置 + 摆正朝向，**不**换维度、**不**重建实体。
 *
 * <h2>3. 失败方向</h2>
 * 全部 {@code try/catch(Throwable)} 自吞 + 记日志：锚点出问题<b>绝不能</b>让倒流本身崩掉
 * （宁可"世界回到了锚点但玩家没被传送"，也不能把玩家踢出世界）。
 */
public final class ShanhaiReplayAnchorRuntime {

    private ShanhaiReplayAnchorRuntime() {
    }

    // ------------------------------------------------------------------ 快照

    /** 当前世界 tick（拿不到就 0 —— 锚点相关的东西一律不许因此抛）。 */
    public static int nowTick(MinecraftServer srv) {
        try {
            final ServerLevel level = srv == null ? null : srv.overworld();
            return level == null ? 0 : (int) level.getGameTime();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 某个玩家所在世界的 tick（设锚点用：与 {@link ShanhaiReplayRecorder} 记的 tick 同源）。 */
    public static int nowTickOf(ServerPlayer player) {
        try {
            return player == null ? 0 : (int) player.serverLevel().getGameTime();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 把"这一刻的玩家"抓成锚点。
     *
     * <p>{@code journalCursor} 记的是<b>落锚那一刻日志里最新一条的 tick</b>（{@code -1} = 当时还没有
     * 任何记录）：它把锚点和"那一刻的环形缓冲"绑在一起，status/日志里能直接看出
     * "锚点是空日志时期设的"还是"日志里已经有历史了"。
     */
    public static ShanhaiReplayJournal.Anchor capture(ServerPlayer player) {
        final ServerLevel level = player.serverLevel();
        return new ShanhaiReplayJournal.Anchor(
                (int) level.getGameTime(),
                ShanhaiReplayEngine.dimIdOf(level.dimension()),
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(),
                ShanhaiReplayRecorder.journal().journalCursor(),
                false, "", 0);
    }

    // ------------------------------------------------------------------ 设 / 看 / 清

    /**
     * {@code /shanhai_add time anchor set}：在玩家当前位置设锚点（覆盖旧的）。
     *
     * @return {@link ShanhaiReplayAnchor.Refusal#NONE} = 设上了；其余 = 拒绝码（已写日志，未设）
     */
    public static ShanhaiReplayAnchor.Refusal set(MinecraftServer srv, ServerPlayer actor) {
        final ShanhaiReplayAnchor.Refusal refusal =
                ShanhaiReplayAnchor.setRefusal(ShanhaiWorldClock.SERVER.getMode());
        if (refusal.refused()) {
            ShanhaiMod.LOGGER.info("[SHANHAI-REPLAY] anchor_refused reason={} mode={} actor={}",
                    refusal.code(), ShanhaiWorldClock.SERVER.getMode(),
                    actor == null ? "console" : actor.getGameProfile().getName());
            notify(actor, "设锚点被拒绝（" + refusal.code() + "）：" + refusal.text());
            return refusal;
        }
        if (actor == null) {
            // 控制台没有"当前位置" —— 命令层会先挡；这里再挡一次，保证 capture() 永不 NPE
            notify(null, "锚点记的是「你的位置」，必须由玩家执行");
            return ShanhaiReplayAnchor.Refusal.NO_ANCHOR;
        }
        final ShanhaiReplayJournal.Anchor anchor = capture(actor);
        ShanhaiReplayRecorder.journal().setAnchor(anchor);
        ShanhaiMod.LOGGER.info("[SHANHAI-REPLAY] anchor_set {} set_count={} actor={} mode={}",
                anchor.toText(), ShanhaiReplayRecorder.journal().anchorSetCount(),
                actor.getGameProfile().getName(), ShanhaiWorldClock.SERVER.getMode());
        notify(actor, "锚点已记下：" + anchor.toText());
        return ShanhaiReplayAnchor.Refusal.NONE;
    }

    /** {@code /shanhai_add time anchor status} 那一行（另加一行 mode/window 语境）。 */
    public static String statusLine(MinecraftServer srv) {
        final ShanhaiReplayJournal journal = ShanhaiReplayRecorder.journal();
        final ShanhaiReplayJournal.Anchor anchor = journal.anchor();
        final int now = nowTick(srv);
        return ShanhaiReplayAnchor.statusLine(anchor, now, ShanhaiReplayConst.WINDOW_TICKS)
                + " set_count=" + journal.anchorSetCount()
                + " mode=" + ShanhaiWorldClock.SERVER.getMode();
    }

    /**
     * {@code /shanhai_add time anchor clear}。
     *
     * @return {@code false} = 本来就没有锚点
     */
    public static boolean clear(MinecraftServer srv, ServerPlayer actor) {
        final ShanhaiReplayJournal journal = ShanhaiReplayRecorder.journal();
        final boolean had = journal.hasAnchor();
        journal.clearAnchor();
        if (had) {
            ShanhaiMod.LOGGER.info("[SHANHAI-REPLAY] anchor_clear actor={}",
                    actor == null ? "console" : actor.getGameProfile().getName());
            notify(actor, "锚点已清除");
        }
        return had;
    }

    // ------------------------------------------------------------------ 到期 ⇒ 自动暂停（B）

    /** 已经触发过"自动暂停"的那个锚点 tick（{@code NO_ANCHOR_TICK} = 没有）。 */
    private static int autoPausedAnchorTick = ShanhaiReplayAnchor.NO_ANCHOR_TICK;

    /** 最近一次判定（status 判读用）。 */
    private static ShanhaiReplayAnchor.ExpiryDecision lastExpiry =
            new ShanhaiReplayAnchor.ExpiryDecision(ShanhaiReplayAnchor.Expiry.NONE, 0,
                    ShanhaiReplayConst.WINDOW_TICKS, "");

    /**
     * 🔴 B：锚点到期那一刻<b>自动把世界时钟切到 PAUSED</b>（而不是让锚点失效）。
     *
     * <p>用户原话：「另外，锚点到时间了自动把时间暂停，而不是使锚点自动失效」。
     * 走的是<b>现有 pause 那条路</b>（{@code ShanhaiWorldClock.SERVER.setMode(PAUSED)}），
     * 不自造状态、不动 {@code ShanhaiReplayGate.frozen()} 的语义。
     *
     * <p>三件事必须一起做（少一件玩家就会看到"时间停了但客户端还在走"）：
     * <ol>
     *   <li>{@code setMode(PAUSED)}（bump epoch）；</li>
     *   <li>{@code noteAnchorTick(now)}（诊断：恢复后从同一个 tick 继续）；</li>
     *   <li>给所有在线玩家 {@link ShanhaiClockNetwork#sendStateTo}（客户端立刻冻住）。</li>
     * </ol>
     *
     * <p>日志（可 grep）：{@code [SHANHAI-REPLAY] anchor_auto_pause tick=<now> age=1200 window=1200 ...}
     *
     * <p>⚠️ 只在 LIVE 判：PAUSED/REPLAY 期间时间本来就不前进，`age` 不可能变大。
     */
    public static void watchExpiry(MinecraftServer srv) {
        try {
            if (srv == null) {
                return;
            }
            final ShanhaiReplayJournal journal = ShanhaiReplayRecorder.journal();
            final ShanhaiReplayJournal.Anchor anchor = journal.anchor();
            if (anchor == null) {
                autoPausedAnchorTick = ShanhaiReplayAnchor.NO_ANCHOR_TICK;
                return;
            }
            if (ShanhaiWorldClock.SERVER.getMode() != ShanhaiClockMode.LIVE) {
                return;   // 已暂停/倒流中：时间不前进，不需要判
            }
            if (autoPausedAnchorTick != anchor.tick()) {
                autoPausedAnchorTick = ShanhaiReplayAnchor.NO_ANCHOR_TICK;   // 换了锚点 ⇒ 重新计
            }
            final int now = nowTick(srv);
            final boolean already = autoPausedAnchorTick == anchor.tick();
            final ShanhaiReplayAnchor.ExpiryDecision decision = ShanhaiReplayAnchor.expiry(
                    anchor, now, ShanhaiReplayConst.WINDOW_TICKS, already);
            lastExpiry = decision;
            if (decision.shouldPause()) {
                autoPausedAnchorTick = anchor.tick();
                ShanhaiWorldClock.SERVER.setMode(ShanhaiClockMode.PAUSED);
                ShanhaiWorldClock.SERVER.noteAnchorTick(now);
                ShanhaiMod.LOGGER.info("[SHANHAI-REPLAY] anchor_auto_pause tick={} age={} window={} "
                                + "anchor_tick={} anchor_pos={} mode={} reason={}",
                        now, decision.age(), decision.windowTicks(), anchor.tick(), anchor.posText(),
                        ShanhaiWorldClock.SERVER.getMode(), decision.reason());
                broadcastPause(srv, anchor, decision);
                return;
            }
            if (decision.action() == ShanhaiReplayAnchor.Expiry.EXPIRED && already) {
                // 自动暂停被解除（玩家 /shanhai_add clock resume）⇒ 保留旧语义：锚点过期，按原样拒绝
                ShanhaiMod.LOGGER.info("[SHANHAI-REPLAY] anchor_expired after_resume tick={} age={} window={} "
                                + "anchor_tick={} reason={}（自动暂停被解除后才可能到这里）",
                        now, decision.age(), decision.windowTicks(), anchor.tick(), decision.reason());
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] 锚点到期判定失败（这一拍不暂停）", t);
        }
    }

    /** 自动暂停那一刻的提示（聊天栏 + 状态广播）。 */
    private static void broadcastPause(MinecraftServer srv, ShanhaiReplayJournal.Anchor anchor,
                                       ShanhaiReplayAnchor.ExpiryDecision decision) {
        try {
            final Component line = Component.literal("[山海时钟] 锚点已到 " + decision.windowTicks()
                    + " tick 窗口边界（age=" + decision.age() + "）⇒ 时间已自动暂停，锚点仍然可用；"
                    + "随时 /shanhai_add time rewind 回到锚点");
            for (ServerPlayer player : srv.getPlayerList().getPlayers()) {
                if (player == null) {
                    continue;
                }
                player.sendSystemMessage(line);
                ShanhaiClockNetwork.sendStateTo(player);
            }
        } catch (Throwable ignored) {
            // 提示发不出去不影响"已经暂停"这件事
        }
    }

    /** 最近一次到期判定（status 用）。 */
    public static String expiryStatusLine() {
        final ShanhaiReplayJournal.Anchor anchor = ShanhaiReplayRecorder.journal().anchor();
        return "expiry[" + lastExpiry.toText() + " auto_paused_anchor="
                + (autoPausedAnchorTick == ShanhaiReplayAnchor.NO_ANCHOR_TICK ? "-" : autoPausedAnchorTick)
                + " anchor_tick=" + (anchor == null ? "-" : anchor.tick()) + "]";
    }

    // ------------------------------------------------------------------ stale（保留锚点，标失效）

    /**
     * 给锚点打失效标记（{@code reason} = {@code player_death} / {@code dim_change} / {@code replay}）。
     *
     * @return {@code false} = 没有锚点（正常：没设过锚点的人死了不该刷日志）
     */
    public static boolean markStale(String reason, int tick) {
        final ShanhaiReplayJournal journal = ShanhaiReplayRecorder.journal();
        final boolean marked = journal.markAnchorStale(reason, tick);
        if (marked) {
            final ShanhaiReplayJournal.Anchor anchor = journal.anchor();
            ShanhaiMod.LOGGER.info("[SHANHAI-REPLAY] anchor_stale reason={} tick={} anchor_tick={} stale=1",
                    reason, tick, anchor == null ? "?" : Integer.toString(anchor.tick()));
        }
        return marked;
    }

    /**
     * 玩家死亡 ⇒ 标 stale（锚点<b>保留</b>：世界要回到哪个 tick 不受死亡影响）。
     *
     * <p>⚠️ 本工程的 {@code ShanhaiServerPlayerDieGuardMixin} 会<b>拒绝</b>玩家死亡
     * （guard 那一层保活），所以这条监听在"玩家真的没死"时本来就不会触发 ——
     * 它管的是"guard 没盖住的那些死法"（例如别的 mod 直接调 {@code kill()}）。
     * 挂上它是为了让"死亡"这个口径永远有一条真的判据，而不是靠假设。
     */
    public static void onLivingDeath(LivingDeathEvent event) {
        try {
            if (event == null || !(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            markStale("player_death", nowTickOf(player));
        } catch (Throwable ignored) {
            // 死亡回调里抛异常会把整条事件链打断（比"没标 stale"严重得多）
        }
    }

    /** 玩家换维度 ⇒ 标 stale（锚点位置从此可能跨维度，回退只回世界、不传人）。 */
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        try {
            if (event == null || !(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            markStale("dim_change", nowTickOf(player));
        } catch (Throwable ignored) {
            // 同上：监听器绝不向外抛
        }
    }

    // ------------------------------------------------------------------ 回退到位后把玩家送回锚点

    /**
     * 回退到锚点 tick 之后：判闸 → 同维度才把玩家传回锚点位置/朝向。
     *
     * <p>🔴 跨维度时<b>一次都不调用</b> {@code teleportTo}（判据 ④ 的"零次"由
     * {@link ShanhaiReplayAnchor#restore} 保证，这里只提供 sink）。
     *
     * @param preferred 发起倒流的那个玩家（可为 {@code null} = 控制台发起）
     */
    /**
     * 回退到锚点 tick 之后：<b>一个坐标都不许搬人</b>。
     *
     * <p>🔴 2026-11-14 用户原话：「他倒流结束那一刻会拉回玩家位置，<b>不要拉回</b>」
     * ⇒ 本方法自本版起<b>只返回"没传送"</b>（{@code reason=no_teleport_by_design}），
     * 运行期不存在任何把玩家挪到锚点的路径。锚点仍然记着位置/朝向（锚点条、日志、status 用）。
     *
     * <p>⚠️ 旧的 {@code teleportTo} 实现保留在 {@link #teleport} 里但<b>不再被调用</b>
     * （它只作为负对照的参照，见自检 Y 组：把传送放回来 ⇒ 判据必须红）。
     *
     * @param preferred 发起倒流的那个玩家（可为 {@code null} = 控制台发起；本版不再使用）
     */
    public static ShanhaiReplayAnchor.RestoreResult restore(MinecraftServer srv, ServerPlayer preferred,
                                                             ShanhaiReplayJournal.Anchor anchor) {
        final ShanhaiReplayAnchor.RestoreResult result = ShanhaiReplayAnchor.restoreAtEnd(anchor);
        try {
            ShanhaiMod.LOGGER.info("[SHANHAI-REPLAY] anchor_restore {} anchor_tick={} anchor_dim={}"
                            + " player={} reason=no_teleport_by_design",
                    result.toText(), anchor == null ? -1 : anchor.tick(), anchor == null ? -1 : anchor.dim(),
                    preferred == null ? "console" : preferred.getGameProfile().getName());
        } catch (Throwable ignored) {
            // 日志打不出来不影响"不搬人"这件事
        }
        return result;
    }

    /**
     * 把玩家挪到锚点（同维度）。
     *
     * <p>主路径 {@code teleportTo(level, x, y, z, yaw, pitch)}（会发 {@code ClientboundPlayerPositionPacket}，
     * 客户端跟着落地）；失败才退到 {@code moveTo}（只改服务端实体，客户端可能"瞬移但视角不跟"）。
     */
    private static boolean teleport(ServerPlayer player, MinecraftServer srv, int dim,
                                    double x, double y, double z, float yaw, float pitch) {
        ServerLevel level = null;
        try {
            final ResourceKey<Level> key = ShanhaiReplayEngine.dimKeyOf(dim);
            level = key == null ? player.serverLevel() : srv.getLevel(key);
        } catch (Throwable ignored) {
            // 维度表被清过（换世界）⇒ 退回玩家当前世界；判闸已经保证同维度才会走到这里
        }
        if (level == null) {
            level = player.serverLevel();
        }
        try {
            player.teleportTo(level, x, y, z, yaw, pitch);
            return true;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] anchor_teleport 失败 pos={} err={} ⇒ 退到 moveTo",
                    String.format(java.util.Locale.ROOT, "%.2f/%.2f/%.2f", x, y, z), t.toString());
        }
        try {
            player.moveTo(x, y, z, yaw, pitch);
            player.setYRot(yaw);
            player.setXRot(pitch);
            return true;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("[SHANHAI-REPLAY] anchor_teleport 彻底失败 err={}", t.toString());
            return false;
        }
    }

    /** 只有一个玩家在线时，控制台发起的倒流也能把他送回锚点（多人时不猜，宁可不传）。 */
    private static ServerPlayer solePlayer(MinecraftServer srv) {
        try {
            final List<ServerPlayer> players = srv.getPlayerList().getPlayers();
            if (players.size() == 1) {
                final ServerPlayer only = players.get(0);
                return only == null || only.isRemoved() ? null : only;
            }
        } catch (Throwable ignored) {
            // 玩家列表取不到 ⇒ 当作"没有可传的人"
        }
        return null;
    }

    private static void notify(ServerPlayer actor, String message) {
        if (actor == null) {
            return;
        }
        try {
            actor.sendSystemMessage(Component.literal("[山海时钟] " + message));
        } catch (Throwable ignored) {
            // 提示发不出去不影响判定
        }
    }
}
