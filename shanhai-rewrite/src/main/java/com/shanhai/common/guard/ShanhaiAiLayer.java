package com.shanhai.common.guard;

import com.shanhai.ShanhaiMod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 山海 · <b>② AI 层（开关，默认关）</b>：谁也不能把目标设成「受保护玩家」。
 *
 * <h2>三件事（需求逐条对着写）</h2>
 * <pre>
 *   ① {@code LivingChangeTargetEvent}：新目标是受保护玩家 ⇒ {@code setCanceled(true)}
 *      （javap 实证：该事件带 {@code @Cancelable}，而 {@code Mob#setTarget} 里
 *       {@code if (!event.isCanceled()) this.target = event.getNewTarget();}
 *       ⇒ 取消就是"谁也设不上"）；
 *   ② 每 10 tick：扫受保护玩家周围 <b>64 格</b>内的生物，目标是受保护玩家 ⇒ {@code setTarget(null)}；
 *   ③ 对 ①② 命中的生物 {@code setNoAi(true)}，并在它的<b>持久数据</b>打标记
 *      {@code shanhai_frozen=true}（记录"我们冻过谁"；<b>fail-closed：没标记的一个都不碰</b>）。
 * </pre>
 *
 * <h2>🔴 恢复口径（用户原话："noAi 是你关掉这个功能了 ai 才会恢复"）</h2>
 * 只有把开关从【开】切到【关】的那一拍，才扫描世界里带 {@code shanhai_frozen} 标记的生物，
 * 把 {@code noAi} 还原为 false 并清标记。<b>不做按时间/距离的自动恢复</b>
 * （那会让"冻住"变成"过一会儿自己解开"，与用户口径相反）。
 *
 * <h2>范围 = 所有生物（含中立/被动），但只碰两类</h2>
 * 「目标是你」的 ＋ 「被这场开关冻过（带标记）」的。除此以外一只都不碰 ——
 * 尤其绝不做"遍历世界把生物 AI 全关掉"那种批量操作。
 *
 * <h2>默认关</h2>
 * 这是新功能，用户没打开的时候本类<b>一个字节都不改变世界</b>：
 * ① 在事件里第一行就返回；② 在 tick 里第一行就返回；③ 的恢复扫描也只在"开→关"那一拍发生。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID)
public final class ShanhaiAiLayer {

    private ShanhaiAiLayer() {}

    /** 日志前缀（与守卫同一套）。 */
    public static final String PREFIX = ShanhaiGuardCore.LOG_PREFIX;

    /** 🔴 "我们冻过谁"的标记键（持久数据里；fail-closed 的唯一凭据）。 */
    public static final String FROZEN_KEY = "shanhai_frozen";

    /** 扫描半径（格）——需求点名 64 格。 */
    public static final double SCAN_RADIUS = 64.0D;

    /** 扫描周期（tick）——需求点名每 10 tick。 */
    public static final int SCAN_PERIOD_TICKS = 10;

    private static final ShanhaiGuardCore.LogGate LOG = new ShanhaiGuardCore.LogGate();

    /** 日志类别：① 事件层拦下"设目标"。 */
    public static final String KIND_TARGET = "AI目标";
    /** 日志类别：③ 冻住（noAi）。 */
    public static final String KIND_FROZEN = "AI冻结";
    /** 日志类别：关掉开关后恢复。 */
    public static final String KIND_THAWED = "AI恢复";

    /** 本进程里被我们冻过的实体（只用于读数；<b>判据永远是持久数据里那个标记</b>）。 */
    private static final Set<UUID> FROZEN_BY_US = new HashSet<>();

    /** 上一 tick 看到的开关值（用来抓"开 → 关"那一拍）。 */
    private static boolean lastSwitchOn = false;

    /** tick 计数（每 10 tick 扫一次）。 */
    private static int tickCounter = 0;

    // ═══════════════════════════════ ① LivingChangeTargetEvent ═══════════════════════════════

    /**
     * 有人要设目标 —— 新目标是受保护玩家就取消。
     *
     * <p>⚠️ 这里<b>必须</b>判在被取消之后也要能进来？不需要：本方法自己就是那个"取消"的人；
     * 传入事件是 {@code @Cancelable} 的，我们在 HEAD 之后不会有人再改它。
     */
    @SubscribeEvent
    public static void onLivingChangeTarget(LivingChangeTargetEvent event) {
        if (!ShanhaiGuardSwitches.aiLayer()) {
            return;   // 🔴 默认关：一个字节都不改世界
        }
        final LivingEntity newTarget = event.getNewTarget();
        if (!(newTarget instanceof ServerPlayer player)) {
            return;   // 目标不是玩家 / 是别的实体：不动
        }
        try {
            if (!ShanhaiGuardEvents.isGuarded(player)) {
                return;   // 不是受保护玩家：不动（别人的战斗行为逐字不变）
            }
            event.setCanceled(true);   // 谁也设不上
            final LivingEntity self = event.getEntity();
            if (self instanceof Mob mob) {
                freeze(mob, "LivingChangeTargetEvent");
            }
            final String line = LOG.admit(KIND_TARGET, System.currentTimeMillis());
            if (line != null) {
                ShanhaiMod.LOGGER.info(line + " ／ 目标=" + player.getName().getString()
                        + " ／ 生物=" + describe(self)
                        + " ／ 机制=LivingChangeTargetEvent.setCanceled(true)");
            }
        } catch (Throwable t) {
            // 第二道保险：我们坏了也绝不许影响原版的"设目标"
            ShanhaiMod.LOGGER.warn(PREFIX + "ai_layer 事件判据抛异常（原样放行）：" + t);
        }
    }

    // ═══════════════════════════════ ② / ③ 每 10 tick ＋ 恢复 ═══════════════════════════════

    /**
     * 开机一次：如果<b>服务器起来时开关就已经是关的</b>，而世界上还留着上次冻住的生物
     * （持久数据里带 {@link #FROZEN_KEY}），那么把它们恢复 —— 否则"关掉功能"这件事会
     * 因为一次重启而永远生效不了（生物永久 noAi）。
     *
     * <p>⚠️ 仍然只碰<b>带标记</b>的生物（fail-closed）；开关开着时这里什么都不做。
     */
    @SubscribeEvent
    public static void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        final boolean on = ShanhaiGuardSwitches.aiLayer();
        lastSwitchOn = on;
        if (on) {
            return;
        }
        restoreAll(event.getServer(), "服务器启动时 AI 层开关就是关的（清理上次留下的 noAi）");
    }

    /**
     * 服务端 tick 末：
     * <pre>
     *   ① 抓"开 → 关"那一拍 ⇒ 扫描带标记的生物恢复 noAi 并清标记（唯一一处自动恢复，且只在关闭时）
     *   ② 开关关着 ⇒ 立刻返回（每 10 tick 的扫描只在开着时跑）
     *   ③ 每 10 tick 扫一次 64 格
     * </pre>
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        final MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        final boolean on = ShanhaiGuardSwitches.aiLayer();
        if (lastSwitchOn && !on) {
            // 🔴 用户原话："noAi 是你关掉这个功能了 ai 才会恢复"
            restoreAll(server, "开关由开变关");
        }
        lastSwitchOn = on;
        if (!on) {
            return;
        }
        tickCounter++;
        if (tickCounter % SCAN_PERIOD_TICKS != 0) {
            return;
        }
        try {
            scan(server);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn(PREFIX + "ai_layer 十tick扫描抛异常（跳过这一轮）：" + t);
        }
    }

    /** 扫所有受保护玩家周围 64 格：目标是受保护玩家 ⇒ 目标清空 ＋ 冻住。 */
    private static void scan(MinecraftServer server) {
        int cleared = 0;
        int frozen = 0;
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == null || player.hasDisconnected() || !ShanhaiGuardEvents.isGuarded(player)) {
                continue;
            }
            final ServerLevel level = player.serverLevel();
            if (level == null) {
                continue;
            }
            final AABB box = player.getBoundingBox().inflate(SCAN_RADIUS);
            final List<Mob> mobs = level.getEntitiesOfClass(Mob.class, box);
            for (final Mob mob : mobs) {
                if (mob == null || mob.isRemoved()) {
                    continue;
                }
                final LivingEntity target = mob.getTarget();
                if (target == player) {
                    mob.setTarget(null);   // ② 目标清空（与"目标是你"同一条判据）
                    cleared++;
                    freeze(mob, "每10tick扫描");
                    frozen++;
                } else if (target == null && mob.getPersistentData().getBoolean(FROZEN_KEY) && mob.isNoAi()) {
                    // 已被我们冻过、目标也空了 ⇒ 这就是稳定态，什么都不用做
                    FROZEN_BY_US.add(mob.getUUID());
                }
            }
        }
        if (cleared > 0 || frozen > 0) {
            final String line = LOG.admit(KIND_FROZEN, System.currentTimeMillis());
            if (line != null) {
                ShanhaiMod.LOGGER.info(line + " ／ 目标清空=" + cleared + " 只 ／ 冻住(noAi)=" + frozen
                        + " 只 ／ 半径=" + SCAN_RADIUS + " 格 ／ 周期=" + SCAN_PERIOD_TICKS + " tick"
                        + " ／ 标记=" + FROZEN_KEY + " ／ 本进程累计冻过=" + FROZEN_BY_US.size() + " 只");
            }
        }
    }

    /** ③ 冻住一只：{@code setNoAi(true)} ＋ 持久数据打标记（fail-closed 的凭据）。 */
    private static void freeze(Mob mob, String why) {
        if (mob == null) {
            return;
        }
        try {
            mob.setNoAi(true);
            mob.getPersistentData().putBoolean(FROZEN_KEY, true);
            FROZEN_BY_US.add(mob.getUUID());
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn(PREFIX + "ai_layer 冻结失败（跳过这一只）mob=" + describe(mob)
                    + " why=" + why + " err=" + t);
        }
    }

    /**
     * 关掉开关时的<b>唯一</b>恢复通道：扫全服，凡是持久数据里带 {@link #FROZEN_KEY} 的生物，
     * 把 {@code noAi} 还原为 false 并<b>清标记</b>。
     *
     * <p>🔴 fail-closed：<b>没标记的一只都不碰</b>（别人自己用命令关掉的 AI 不会被我们打开）。
     */
    public static int restoreAll(MinecraftServer server, String why) {
        if (server == null) {
            return 0;
        }
        int restored = 0;
        final List<Mob> candidates = new ArrayList<>();
        for (final ServerLevel level : server.getAllLevels()) {
            if (level == null) {
                continue;
            }
            // 先取快照再动手：直接边遍历边改实体的持久数据会在某些实现上踩 CME
            for (final Entity entity : level.getAllEntities()) {
                if (entity instanceof Mob mob && !mob.isRemoved()
                        && mob.getPersistentData().getBoolean(FROZEN_KEY)) {
                    candidates.add(mob);
                }
            }
        }
        for (final Mob mob : candidates) {
            try {
                mob.setNoAi(false);
                mob.getPersistentData().remove(FROZEN_KEY);
                FROZEN_BY_US.remove(mob.getUUID());
                restored++;
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn(PREFIX + "ai_layer 恢复失败（保留标记，下次再试）mob="
                        + describe(mob) + " err=" + t);
            }
        }
        final String line = LOG.admit(KIND_THAWED, System.currentTimeMillis());
        if (line != null) {
            ShanhaiMod.LOGGER.info(line + " ／ 原因=" + why + " ／ 已恢复 noAi=false 的生物 " + restored
                    + " 只（只碰带 " + FROZEN_KEY + " 标记的；没标记的一只都不碰）");
        }
        return restored;
    }

    // ═══════════════════════════════ 读数 ═══════════════════════════════

    /** 一行状态（命令回显 / 报告）。 */
    public static String statusLine() {
        return "ai_layer=" + ShanhaiGuardSwitches.aiLayer()
                + " 半径=" + SCAN_RADIUS + "格 周期=" + SCAN_PERIOD_TICKS + "tick"
                + " 标记键=" + FROZEN_KEY
                + " 本进程冻过=" + FROZEN_BY_US.size() + "只";
    }

    /** 实体的人话名（日志用；不依赖客户端语言）。 */
    private static String describe(Entity entity) {
        if (entity == null) {
            return "(null)";
        }
        final net.minecraft.resources.ResourceLocation id =
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return (id == null ? "?" : id.toString()) + "#" + entity.getId();
    }
}
