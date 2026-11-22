package com.shanhai.add.client.judge;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.ShanhaiAdd;
import com.shanhai.add.guard.state.ShanhaiJudgeAuthority;
import com.shanhai.common.guard.ShanhaiGuardCore;
import com.shanhai.common.guard.ShanhaiGuardEvents;
import com.shanhai.common.guard.ShanhaiGuardTriage;
import com.shanhai.common.guard.ShanhaiGuardValueLayer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.world.entity.player.Player;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 山海重构 · <b>客户端的判据层消费点（真逻辑）</b>（2026-11-21）。
 *
 * <p>对应三个 mixin（全部只打原版类）：
 * <pre>
 *   ShanhaiSetScreenDeadJudgeMixin  → {@link #reportDeadOrDying(LocalPlayer, String)}   （Minecraft#m_91152_ / #m_91398_）
 *   ShanhaiCombatKillGuardMixin     → {@link #suppressCombatKill(Object)}                （ClientPacketListener#m_142747_）
 *   （观测）                        → {@link #onScreenOpening(ScreenEvent.Opening)}       （判据层自愈的消费侧读数）
 * </pre>
 *
 * <h2>为什么逻辑在本类、而不在 mixin 里</h2>
 * 本工程铁律：mixin 类体内不许引 MC 成员（refmap 里没有条目 ⇒ 生产环境会拿开发名去 SRG 类上找）。
 * 本类是普通类，会被 reobf ⇒ {@code isGuarded} / {@code getHealth} / {@code getPlayerId} 运行期对得上。
 *
 * <h2>为什么这两个消费点"不受对手 HEAD-cancel 的影响"</h2>
 * <pre>
 *   对手在 {@code isDeadOrDying()} / {@code getHealth()} 上的注入是 {@code @At("HEAD") + cancellable}，
 *   它能让<b>被调方法</b>返回假值；但消费点劫持的是<b>这一次调用</b>（{@code @Redirect}）或
 *   整条处理链（{@code @Inject(HEAD)}），与那个方法的注入彼此独立。
 *   ⇒ 这是本条线唯一"结构上赢"的位置。
 * </pre>
 *
 * <h2>负向路径（逐条写死）</h2>
 * <pre>
 *   不是 {@link Player} / 不是受保护玩家     ⇒ 🔴 原版读数（{@code player.isDeadOrDying()}），逐位放行
 *   {@code player == null}                  ⇒ {@link #reportDeadOrDying} 返回 false（原版那里会 NPE；我们不给 NPE）
 *   死亡包不是自己的                         ⇒ 不取消（{@code getPlayerId() != 自己} ⇒ 原样）
 *   任何 Throwable                          ⇒ 放行原版（消费点抛异常绝不许改变原版行为）
 * </pre>
 *
 * <h2>只在客户端</h2>
 * {@code @OnlyIn(Dist.CLIENT)} ＋ {@code @Mod.EventBusSubscriber(value = Dist.CLIENT)}：
 * 专用服务端上 Forge 不会把本类注册进事件总线，常量池里的 {@code net.minecraft.client.*} 永远不解析
 * ⇒ 服务端不可能 {@code NoClassDefFoundError}（与 {@code ShanhaiAnchorBarClient} 同款）。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiClientJudge {

    /** 日志前缀（与守卫同一套，历史 grep 口径不失效）。 */
    public static final String PREFIX = ShanhaiGuardCore.LOG_PREFIX;

    /** {@code setScreen} 那处消费点压掉"判死"的次数。 */
    private static final AtomicLong SET_SCREEN_SUPPRESSED = new AtomicLong();

    /** {@code runTick} 那处消费点压掉"判死"的次数（现场 20/s 的驱动源就是它）。 */
    private static final AtomicLong RUN_TICK_SUPPRESSED = new AtomicLong();

    /** 死亡包被拒的次数。 */
    private static final AtomicLong COMBAT_KILL_CANCELED = new AtomicLong();

    /** "死亡界面被提议"被观察到的次数（含被别人取消的）。 */
    private static final AtomicLong DEATH_SCREEN_SEEN = new AtomicLong();

    /** "第一次真的生效"只打一行（证明消费点挂上了）。 */
    private static final AtomicBoolean ENGAGED_LOGGED = new AtomicBoolean(false);

    /** 三类日志各 1 秒最多一行。 */
    private static final ShanhaiGuardTriage.Throttle LOG_1S = new ShanhaiGuardTriage.Throttle(1_000L);

    /** 客户端自己的 tick 计数（判据自愈的"时钟"；规格：客户端按 tick 计数）。 */
    private static int clientTicks;

    private ShanhaiClientJudge() {}

    // ═══════════════ 消费点 A：Minecraft#m_91152_(setScreen) / #m_91398_(runTick) 的那次读取 ═══════════════

    /**
     * 🔴 <b>这一次 {@code LocalPlayer#m_21224_()} 读取要用的返回值</b>。
     *
     * @param player 被读取的玩家（{@code this.player}，可能是 null）
     * @param site   哪一处消费点（只进日志：{@code setScreen} / {@code runTick}）
     * @return 受保护玩家恒为 {@code false}；其余=原版读数（逐位放行）
     */
    public static boolean reportDeadOrDying(LocalPlayer player, String site) {
        try {
            if (player == null) {
                return false;                       // 原版那里会 NPE；我们不给 NPE，也不造假死亡界面
            }
            if (!ShanhaiGuardEvents.isGuarded(player)) {
                return player.isDeadOrDying();      // 🔴 不是受保护玩家 ⇒ 原版读数（逐位放行）
            }
            final long total = "runTick".equals(site)
                    ? RUN_TICK_SUPPRESSED.incrementAndGet()
                    : SET_SCREEN_SUPPRESSED.incrementAndGet();
            announceOnce(site, player, total);
            return false;                           // 🔴 受保护玩家：这一次读取一律"没死"
        } catch (Throwable t) {
            try {
                return player != null && player.isDeadOrDying();
            } catch (Throwable ignored) {
                return false;                       // 连原版读数都读不到 ⇒ 不造假死亡界面
            }
        }
    }

    // ═══════════════ 消费点 B：ClientPacketListener#m_142747_（战斗死亡包） ═══════════════

    /**
     * 🔴 <b>这个死亡包要不要整个丢掉</b>。
     *
     * <p>判据只有三条：包本身、包里的 playerId 是自己、自己是受保护玩家。
     * （包是只读数据类 ⇒ 丢掉它不落任何状态；死亡界面与自动重生都在被取消的那段里。）
     *
     * @param packet {@code ClientboundPlayerCombatKillPacket}（mixin 传 {@code (Object)}，本类负责转型）
     * @return true ⇒ 调用方 {@code ci.cancel()}
     */
    public static boolean suppressCombatKill(Object packet) {
        try {
            if (!(packet instanceof ClientboundPlayerCombatKillPacket kill)) {
                return false;                       // 不是这个包 ⇒ 原样
            }
            final Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return false;
            }
            final LocalPlayer self = mc.player;
            if (self == null) {
                return false;                       // 还没进世界 ⇒ 原样（那时也没有界面可言）
            }
            if (!ShanhaiGuardEvents.isGuarded(self)) {
                return false;                       // 🔴 不受保护 ⇒ 原样放行
            }
            if (kill.getPlayerId() != self.getId()) {
                return false;                       // 不是自己 ⇒ 原样（别吞别人的死亡包）
            }
            final long total = COMBAT_KILL_CANCELED.incrementAndGet();
            if (LOG_1S.admit("combat_kill", System.currentTimeMillis())) {
                ShanhaiMod.LOGGER.warn(PREFIX + "guard_combat_kill_cancel"
                        + " player=" + name(self) + " packet_player_id=" + kill.getPlayerId()
                        + " total=" + total
                        + " note=death_screen_and_respawn_both_skipped");
            }
            return true;
        } catch (Throwable t) {
            return false;                           // 我们坏了 ⇒ 原版行为（死亡包照常处理）
        }
    }

    // ═══════════════ 消费侧观察：还有谁在给受保护玩家提议"死亡界面" ═══════════════

    /**
     * 🔴 <b>判据层自愈的消费侧读数</b>：{@code ScreenEvent.Opening} 里出现 {@code DeathScreen}
     * 且本地玩家受保护 ⇒ 记一次。
     *
     * <pre>
     *   receiveCanceled = true   ← 被别人取消的那些也要看见（我们要的是"有没有被提议过死"）
     *   priority = LOWEST        ← 等所有人都处理完再看，拿到的是这一拍最终的样子
     * </pre>
     *
     * <p>本方法<b>不取消、不改界面</b>（拦截是别的类的事）：它只计数 + 一条 1/s 的日志，
     * 供 {@link ShanhaiJudgeAuthority} 在 200 tick 探针里判 {@code guard_judge_lost cause=death_screen}。
     */
    @SubscribeEvent(receiveCanceled = true, priority = EventPriority.LOWEST)
    public static void onScreenOpening(ScreenEvent.Opening event) {
        try {
            if (event == null) {
                return;
            }
            final Screen next = event.getNewScreen();
            if (!(next instanceof DeathScreen)) {
                return;                             // 不是死亡界面 ⇒ 不管
            }
            final Minecraft mc = Minecraft.getInstance();
            final LocalPlayer self = mc == null ? null : mc.player;
            if (self == null) {
                return;
            }
            if (!ShanhaiGuardEvents.isGuarded(self)) {
                return;                             // 不受保护 ⇒ 正常死亡界面，不是异常
            }
            final long total = DEATH_SCREEN_SEEN.incrementAndGet();
            ShanhaiJudgeAuthority.noteDeathScreenOpening("client");
            if (LOG_1S.admit("death_screen", System.currentTimeMillis())) {
                ShanhaiMod.LOGGER.warn(PREFIX + "guard_death_screen_proposed"
                        + " canceled=" + event.isCanceled()
                        + " field=" + ShanhaiGuardValueLayer.readHealthField(self)
                        + " read=" + safeHealth(self)
                        + " total=" + total
                        + " player=" + name(self)
                        + " note=consumer_side_lost（这条会被 200t 探针判成 guard_judge_lost）");
            }
        } catch (Throwable ignored) {
            // 观察者坏了不许影响任何界面行为
        }
    }

    // ═══════════════ 客户端 200 tick 探针（判据自愈） ═══════════════

    /**
     * 客户端每 {@value ShanhaiJudgeAuthority#PROBE_EVERY_TICKS} tick 探一次本地玩家。
     *
     * <p>用 {@code TickEvent.ClientTickEvent.END} 自己数 tick（规格："客户端按 tick 计数"）。
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event == null || event.phase != TickEvent.Phase.END) {
            return;
        }
        clientTicks++;
        if (clientTicks % ShanhaiJudgeAuthority.PROBE_EVERY_TICKS != 0) {
            return;
        }
        try {
            final Minecraft mc = Minecraft.getInstance();
            final LocalPlayer self = mc == null ? null : mc.player;
            if (self == null) {
                return;
            }
            if (!ShanhaiGuardEvents.isGuarded(self)) {
                return;
            }
            ShanhaiJudgeAuthority.probe("client", self, clientTicks);
        } catch (Throwable ignored) {
            // 探针坏了不许影响游戏
        }
    }

    // ═══════════════ 读数 ═══════════════

    /** 一行读数（命令 / 报告用）。 */
    public static String statusLine() {
        return "client.judge set_screen_suppressed=" + SET_SCREEN_SUPPRESSED.get()
                + " run_tick_suppressed=" + RUN_TICK_SUPPRESSED.get()
                + " combat_kill_canceled=" + COMBAT_KILL_CANCELED.get()
                + " death_screen_seen=" + DEATH_SCREEN_SEEN.get()
                + " client_ticks=" + clientTicks;
    }

    // ═══════════════ 小工具（全部防抛） ═══════════════

    /** 第一次真的生效时打一行（证明消费点挂上了；之后不再打）。 */
    private static void announceOnce(String site, LocalPlayer player, long total) {
        if (!ENGAGED_LOGGED.compareAndSet(false, true)) {
            return;
        }
        try {
            ShanhaiMod.LOGGER.info(PREFIX + "guard_client_judge_engaged"
                    + " at=first_suppression site=" + site
                    + " field=" + ShanhaiGuardValueLayer.readHealthField(player)
                    + " read=" + safeHealth(player)
                    + " total=" + total
                    + " note=consumer_side");
        } catch (Throwable ignored) {
            // 日志失败不许影响界面
        }
    }

    /** {@code getHealth()} 的只读快照（只进日志；读不到 ⇒ NaN）。 */
    private static float safeHealth(LocalPlayer player) {
        try {
            return player == null ? Float.NaN : player.getHealth();
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    /** 玩家名（只进日志；拿不到 ⇒ {@code ?}）。 */
    private static String name(Player player) {
        try {
            return player.getName().getString();
        } catch (Throwable t) {
            return "?";
        }
    }
}
