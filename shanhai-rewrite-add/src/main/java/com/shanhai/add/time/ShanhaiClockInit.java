package com.shanhai.add.time;

import java.util.function.Supplier;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.time.anchor.ShanhaiAnchorBarBroadcast;
import com.shanhai.add.time.replay.ShanhaiReplayAnchorRuntime;
import com.shanhai.add.time.replay.ShanhaiReplayBudget;
import com.shanhai.add.time.replay.ShanhaiReplayCapture;
import com.shanhai.add.time.replay.ShanhaiReplayCoverage;
import com.shanhai.add.time.replay.ShanhaiReplayEntityRecorder;
import com.shanhai.add.time.replay.ShanhaiReplaySavedDataAudit;
import com.shanhai.add.time.replay.ShanhaiReplayService;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;

/**
 * 山海重构 · 世界时钟的<b>唯一装配点</b>。
 *
 * <p>把 {@code MinecraftForge.EVENT_BUS} 上的监听、指令树、网络通道<b>在一个地方</b>挂完，
 * 这样"到底挂没挂上"是一次可复查的动作（本工程血账：探针挂没挂上在日志上必须可判
 * ⇒ 下面每挂一批都打一行 {@code [SHANHAI-CLOCK]} 日志）。
 *
 * <p>由扩展自己的 {@code com.shanhai.add.ShanhaiAdd}（{@code @Mod}）构造器调用一次（服务端与客户端都会走到）。
 * 拆分前这一步在本体 {@code ShanhaiMod} 的构造器里（2026-10-18 阶段 3a 整块搬进来）。
 */
public final class ShanhaiClockInit {

    private static boolean registered;

    /** 上一次 {@link ShanhaiWorldClock#advanceWorld} 用的墙钟（ns）；用来喂累加器。 */
    private static long lastOuterTickNanos = -1L;

    private static int tickCounter;

    /** 上一次外层 tick 算出来的"该跑几次世界 tick"（{@code LIVE=1}、{@code PAUSED/REPLAY=0}）。 */
    private static int lastWantedTicks;

    public static void init() {
        if (registered) {
            return;
        }
        registered = true;

        // ---- 网络（必须早于任何玩家登录）
        ShanhaiClockNetwork.register();

        // ---- 指令树
        MinecraftForge.EVENT_BUS.addListener(ShanhaiClockInit::onRegisterCommands);

        // ---- 「冻结期间禁止改世界」的事件层（能给出玩家提示的那一层）
        MinecraftForge.EVENT_BUS.addListener(ShanhaiClockGuard::onBreak);
        MinecraftForge.EVENT_BUS.addListener(ShanhaiClockGuard::onPlace);
        MinecraftForge.EVENT_BUS.addListener(ShanhaiClockGuard::onRightClickBlock);
        MinecraftForge.EVENT_BUS.addListener(ShanhaiClockGuard::onRightClickItem);
        MinecraftForge.EVENT_BUS.addListener(ShanhaiClockGuard::onAttack);
        MinecraftForge.EVENT_BUS.addListener(ShanhaiClockGuard::onHurt);
        MinecraftForge.EVENT_BUS.addListener(ShanhaiClockGuard::onToss);
        MinecraftForge.EVENT_BUS.addListener(ShanhaiClockGuard::onPickup);
        MinecraftForge.EVENT_BUS.addListener(ShanhaiClockGuard::onExplosionStart);
        // CommandEvent 用 LOWEST：让别的 mod 先在正常优先级上看到未取消的事件
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, ShanhaiClockGuard::onCommand);

        // ---- 世界时钟自身的节拍（累加器 / 状态广播）
        MinecraftForge.EVENT_BUS.addListener(ShanhaiClockInit::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(ShanhaiClockInit::onPlayerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(ShanhaiClockInit::onPlayerLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(ShanhaiClockInit::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(ShanhaiClockInit::onServerStopping);

        // ---- 锚点的「保留但标 stale」两条口径（玩家死亡 / 换维度；"进 REPLAY"那条在服务里）
        MinecraftForge.EVENT_BUS.addListener(ShanhaiReplayAnchorRuntime::onLivingDeath);
        MinecraftForge.EVENT_BUS.addListener(ShanhaiReplayAnchorRuntime::onChangedDimension);
        // 锚点条：换维度时把这一局那一条先清掉（按维度广播 ⇒ 走出去的人再也收不到帧，会永久卡住）
        MinecraftForge.EVENT_BUS.addListener(ShanhaiAnchorBarBroadcast::onPlayerChangedDimension);

        // ---- 倒流第 1 层：实体"出现 / 消失"（玩家在判据里豁免；从存档加载的不算出现）
        MinecraftForge.EVENT_BUS.addListener(ShanhaiReplayEntityRecorder::onJoin);
        MinecraftForge.EVENT_BUS.addListener(ShanhaiReplayEntityRecorder::onLeave);
        // 监听器注册完才置位：hooks_armed 那一行的 entities 层判据就是它
        ShanhaiReplayEntityRecorder.markListenerArmed();

        ShanhaiMod.LOGGER.info("[SHANHAI-CLOCK] 世界时钟已装配：指令=/shanhai_add clock pause|resume|status|rate"
                        + "、/shanhai_add time rewind [秒数|stop]|anchor set|status|clear|status|coverage，"
                        + "拦截类别=[挖/放/使用/攻击/丢/捡/容器/爆炸/指令/硬网]，"
                        + "倒流=[五层差分(方块/BE最小差/机器进度/实体/世界标量)+逐tick反向播放+锚点回退+到期自动暂停]，"
                        + "网络通道={}",
                ShanhaiClockNetwork.class.getSimpleName());
    }

    // ------------------------------------------------------------------ 监听

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        ShanhaiClockCommands.register(event.getDispatcher());
        ShanhaiMod.LOGGER.info("[SHANHAI-CLOCK] 指令已注册：/shanhai_add clock pause|resume|status|rate|replay"
                + "、/shanhai_add time rewind|anchor|status");
    }

    /**
     * 每个服务端外层 tick：维护累加器 + 把状态变化广播出去。
     *
     * <p>⚠️ 它跑在 {@code MinecraftServer.tickChildren} <b>之外</b>的那一层（Forge 的 ServerTickEvent），
     * 所以<b>暂停期间它照样在跑</b> —— 这正是我们想要的：keep-alive、玩家输入、指令都活着。
     */
    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        long now = System.nanoTime();
        long delta = lastOuterTickNanos < 0L ? 0L : now - lastOuterTickNanos;
        lastOuterTickNanos = now;

        ShanhaiWorldClock clock = ShanhaiWorldClock.SERVER;
        // 🔴 2026-11：这个返回值以前被丢掉（"半截口"之一）。它是"本外层 tick 该跑几次世界 tick"
        //    的唯一来源（LIVE=1，PAUSED/REPLAY=0），现在接住并暴露给 status 判读
        //    —— 不再有"看着像暂停其实是 0"这种只能靠猜的地方。
        lastWantedTicks = clock.advanceWorld(delta);

        // ---- 倒流第 1 层采集（方块实体差分 / 机器进度 / 实体已移除扫描 / 世界标量）----
        // 🔴 必须在"推进倒流"之前：这一拍采集到的是这一拍世界的样子；推进之后游标就已经退了。
        ShanhaiReplayCapture.endOfTick(event.getServer());

        // ---- B：锚点到期 ⇒ 自动把世界时钟切到 PAUSED（取代"锚点失效"）
        ShanhaiReplayAnchorRuntime.watchExpiry(event.getServer());

        // ---- 记录闸读数：暂停期间"记录还在不在长"必须能当场读出来（用户 2026-11-14 的诉求）
        ShanhaiReplayService.recordGateTick(event.getServer());

        // 倒流推进：**必须在暂停/倒流期间也跑**（ServerTickEvent END 是唯一这样的节拍）
        ShanhaiReplayService.tick(event.getServer());

        // 锚点进度条的同步：自己判"倒流开始/结束"的边沿，倒流中每 5 tick 发一条、窗口态每 20 tick 滴一条。
        // 🔴 必须排在 ShanhaiReplayService.tick 之后：这一拍已经推进过的 cursor/remaining 才是要发的读数。
        ShanhaiAnchorBarBroadcast.tick(event.getServer());

        if (++tickCounter % 20 == 0 && event.getServer() != null) {
            ShanhaiClockNetwork.broadcastIfChanged(event.getServer().getPlayerList().getPlayers());
        }
    }

    /** 上一次外层 tick 算出来的"该跑几次世界 tick"（{@code LIVE=1}、{@code PAUSED/REPLAY=0}）。 */
    public static int lastWantedTicks() {
        return lastWantedTicks;
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            ShanhaiClockNetwork.onPlayerReady(player);
            // 锚点条：登录补发一次（否则重连之后屏幕上没有条，要等下一个事件才回来）
            ShanhaiAnchorBarBroadcast.onLogin(player);
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            ShanhaiClockNetwork.onPlayerGone(player);
        }
    }

    /** 世界（重）启动：清计数，回到 LIVE，并打一条「AE 拦截到底挂上了没有」的自证行。 */
    private static void onServerStarted(ServerStartedEvent event) {
        ShanhaiClockGuard.resetCounters();
        ShanhaiClockHooks.resetAeCounters();
        // 倒流：新世界不许继承上一个世界的环形缓冲/状态索引/NBT 池
        ShanhaiReplayService.onServerStarted(event.getServer());
        // 锚点条的节拍/边沿记忆同样清零（新世界不许继承"上一个世界正在倒流"）
        ShanhaiAnchorBarBroadcast.reset();
        ShanhaiWorldClock.SERVER.setMode(ShanhaiClockMode.LIVE);
        lastOuterTickNanos = -1L;
        lastWantedTicks = 0;
        ShanhaiMod.LOGGER.info("[SHANHAI-CLOCK] 服务端已启动，时钟重置为 LIVE（{}）",
                ShanhaiWorldClock.SERVER.snapshot().toLine());
        logAeHooksArmed();
        logReplayCoverage(event.getServer());
        // 🔴 倒流六层"挂上了没有"：与 AE 那边同一种手法（反射数注入方法）。
        //    这一行缺失/红项就是"下一次只能靠猜"的直接来源（真机 progress/be 两个空通道的血账）。
        ShanhaiReplayService.logHooksArmed();
        // 🔴 2026-11-14："暂停与倒流可组合"的状态机表（三行，与自检 V 组断言同一份输出）
        ShanhaiReplayService.logModeComposeTable();
    }

    /**
     * 倒流覆盖矩阵自证（可 grep {@code [SHANHAI-REPLAY] coverage}）。
     *
     * <p>为什么在启动时打一次全表：用户口径是"覆盖率这件事走一步看一步"——
     * 那么每次开服的日志里就必须有一份<b>当时生效的</b>矩阵（含已知漏点），
     * 而不是去翻代码或翻聊天记录。矩阵本体在 {@code ShanhaiReplayCoverage}（纯逻辑核，自检逐行核对）。
     */
    private static void logReplayCoverage(net.minecraft.server.MinecraftServer server) {
        try {
            for (String line : ShanhaiReplayCoverage.lines()) {
                ShanhaiMod.LOGGER.info("[SHANHAI-REPLAY] {}", line);
            }
            for (String line : ShanhaiReplayBudget.lines()) {
                ShanhaiMod.LOGGER.info("[SHANHAI-REPLAY] {}", line);
            }
            for (String line : ShanhaiReplaySavedDataAudit.lines(server)) {
                ShanhaiMod.LOGGER.info("[SHANHAI-REPLAY] {}", line);
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] coverage 自证失败（这份日志里没有矩阵行）", t);
        }
    }

    /**
     * 🔴 AE 三个混入的<b>挂载自证</b>（可 grep {@code ae_hooks_armed}）。
     *
     * <h2>为什么必须有这一行</h2>
     * AE2 是<b>第三方 mod</b> ⇒ 三个注入点全部 {@code require = 0} + mixin 配置 {@code "required": false}
     * ⇒ 上游改名/改签名时"少一层拦截"而不是"整合包起不来"。
     * 代价是<b>注入失败完全静默</b>：「AE 那边没东西可取」与「我们的钩子没挂上」在日志上长得一模一样。
     * 这一行用反射数目标类里有没有我们注入的 {@code shanhai$…} 方法，把两者分开。
     *
     * <h2>为什么在 {@code ServerStartedEvent} 而不是 mod 构造器</h2>
     * 本工程 2026-09 实测血账（见 {@code ModuleSetBlockWatch#verifyArmed} 注释）：
     * 太早做反射自证会看到<b>未混入</b>的类 ⇒ 打出一条假阴性的「没挂上」。
     * 世界加载完成那一刻，AE2 的类早已被 mixin 变换过，读到的才是真身。
     *
     * <p>⚠️ 本行只为判读，<b>不参与任何业务判定</b>；它自己抛异常也只记一条 ERROR
     * （"没有这一行"不允许被读成"挂上了"）。
     */
    private static void logAeHooksArmed() {
        try {
            final String armed = ShanhaiAeGate.armedLine();
            if (ShanhaiAeGate.armedAll()) {
                ShanhaiMod.LOGGER.info("[SHANHAI-CLOCK] {}", armed);
            } else {
                ShanhaiMod.LOGGER.error("[SHANHAI-CLOCK] {} —— 期望全是 true。"
                                + "任何一项 =false 都【不代表现场没有该事件】，"
                                + "只代表那一层 AE 拦截这一轮没在跑（CLASSNOTFOUND = AE2 不在/类改名）",
                        armed);
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("[SHANHAI-CLOCK] ae_hooks_armed 自证失败 err={} ⇒ "
                            + "本次日志里没有 ae_hooks_armed 行【不能】当作「挂上了」的证据",
                    t.toString(), t);
        }
    }

    /**
     * 服务端停止：<b>先</b>给倒流收尾，再把时钟打回 LIVE。
     *
     * <p>🔴 顺序不能换：倒流期间 {@code ServerLevel#noSave = true}（屏蔽存档）。
     * 停机流程随后就要做一次强制存档 —— 如果在这里不先把 {@code noSave} 恢复，
     * 那一次存档会被<b>静默跳过</b>⇒ 整局进度丢失（比"倒流没做完"严重得多）。
     */
    private static void onServerStopping(ServerStoppingEvent event) {
        ShanhaiReplayService.onServerStopping(event.getServer());
        ShanhaiWorldClock.SERVER.setMode(ShanhaiClockMode.LIVE);
        ShanhaiMod.LOGGER.info("[SHANHAI-CLOCK] 服务端停止，时钟回到 LIVE（避免下次启动继承暂停态）"
                + "；倒流已收尾并恢复写盘（noSave=false）");
    }

    /** 供别处（如自检/调试）读的只读包装，避免直接暴露可变字段。 */
    public static Supplier<String> statusSupplier() {
        return () -> ShanhaiWorldClock.SERVER.snapshot().toLine() + " " + ShanhaiClockGuard.summary();
    }

    private ShanhaiClockInit() {
    }
}
