package com.shanhai.add.time;

import java.util.function.Supplier;

import com.shanhai.ShanhaiMod;

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

        ShanhaiMod.LOGGER.info("[SHANHAI-CLOCK] 世界时钟已装配：指令=/shanhai_add clock pause|resume|status|rate，"
                + "拦截类别=[挖/放/使用/攻击/丢/捡/容器/爆炸/指令/硬网]，网络通道={}",
                ShanhaiClockNetwork.class.getSimpleName());
    }

    // ------------------------------------------------------------------ 监听

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        ShanhaiClockCommands.register(event.getDispatcher());
        ShanhaiMod.LOGGER.info("[SHANHAI-CLOCK] 指令已注册：/shanhai_add clock pause|resume|status|rate|replay");
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
        clock.advanceWorld(delta);

        if (++tickCounter % 20 == 0 && event.getServer() != null) {
            ShanhaiClockNetwork.broadcastIfChanged(event.getServer().getPlayerList().getPlayers());
        }
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            ShanhaiClockNetwork.onPlayerReady(player);
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
        ShanhaiWorldClock.SERVER.setMode(ShanhaiClockMode.LIVE);
        lastOuterTickNanos = -1L;
        ShanhaiMod.LOGGER.info("[SHANHAI-CLOCK] 服务端已启动，时钟重置为 LIVE（{}）",
                ShanhaiWorldClock.SERVER.snapshot().toLine());
        logAeHooksArmed();
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

    private static void onServerStopping(ServerStoppingEvent event) {
        ShanhaiWorldClock.SERVER.setMode(ShanhaiClockMode.LIVE);
        ShanhaiMod.LOGGER.info("[SHANHAI-CLOCK] 服务端停止，时钟回到 LIVE（避免下次启动继承暂停态）");
    }

    /** 供别处（如自检/调试）读的只读包装，避免直接暴露可变字段。 */
    public static Supplier<String> statusSupplier() {
        return () -> ShanhaiWorldClock.SERVER.snapshot().toLine() + " " + ShanhaiClockGuard.summary();
    }

    private ShanhaiClockInit() {
    }
}
