package com.shanhai.add.guard.selfcheck;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.ShanhaiAdd;
import com.shanhai.common.guard.ShanhaiGuardCore;
import com.shanhai.common.guard.ShanhaiGuardRemoval;
import com.shanhai.common.guard.ShanhaiGuardValueLayer;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 🔴 <b>「我们的注入还在不在」自检 ＋ 自动降级</b>（{@code /shanhai_add hooks}）。
 *
 * <h2>用户口径（为什么要做这个）</h2>
 * <pre>
 *   用户原话：「我当时说设计上要保护原版的一些类不被修改，我看你也没有保护住 setHealth 啊」
 *   主代理答复（用户未反对）：真正的风险是【我们的注入被对手剥掉】而我们还蒙着跑。
 *   ⇒ 本类只回答两件事：
 *      ① 我们的 handler 方法还在不在原版类里（存在性）；
 *      ② 值层那两条判据还灵不灵（行为探针）。
 *      任一不成立 ⇒ 置降级标志 data_only（补血强制直写同步血量字段，跳过 setHealth 那条腿）。
 * </pre>
 *
 * <h2>三个入口</h2>
 * <pre>
 *   ServerTickEvent(END)   每 {@value #CHECK_EVERY_TICKS} tick（20 tps ⇒ 30 秒）跑一轮；结论只在【状态变化】时落一行
 *   /shanhai_add hooks     立刻跑一轮并把 {@code [SHANHAI-GUARD] hooks status=…} 那行回显给来源（控制台也算成功）
 *   dataOnly()             给血路读的降级标志（真正的存储在 {@link ShanhaiGuardValueLayer#dataOnly()}）
 * </pre>
 *
 * <h2>🔴 只读 ＋ 绝不碰真玩家</h2>
 * <ul>
 *   <li>存在性：只 {@code Class.forName(…, false, loader)} ＋ {@code getDeclaredMethods()} ＋ 字符串比对
 *       —— <b>不初始化目标类</b>（初始化会提前触发原版静态块）；</li>
 *   <li>探针：<b>零实体</b>。血路探针调的是值层<b>判据</b>（{@link ShanhaiGuardCore#rewriteHealthDecision}
 *       ＋ {@link ShanhaiGuardCore#healTarget}）与一次"非玩家目标进值层原样返回"的冒烟
 *       （{@link ShanhaiGuardValueLayer#guardSetHealth(Object, float)} 传一个 {@code new Object()} 假目标）；
 *       移除路探针调的是 {@code ShanhaiGuardEntityGuard#refuseSetRemoved} 背后那条判据
 *       （{@link ShanhaiGuardRemoval#refuseRemoval}）。
 *       ⇒ <b>没有任何真人玩家/真人实体被构造、被写、被改</b>。</li>
 *   <li>降级：只切一个 {@code volatile boolean}；<b>已验收的拦截行为（setHealth 入参改写 / die 拒绝 /
 *       setRemoved 拒绝）一个字节都不改</b>。</li>
 * </ul>
 *
 * <h2>日志（机器可判）</h2>
 * <pre>
 *   [SHANHAI-GUARD] hooks_missing missing=&lt;名单&gt; probe=health|remove|ok degraded=data_only reason=… found=[实际名字]
 *   [SHANHAI-GUARD] hooks_restored missing=(none) probe=ok found=4 data_only=false
 *   [SHANHAI-GUARD] hooks status=ok|missing data_only=&lt;bool&gt; found=&lt;0..4&gt; missing=&lt;名单&gt; probe=ok|health|remove
 *   [SHANHAI-GUARD] hooks_found Entity(3)=[…],LivingEntity(5)=[…],ServerPlayer(2)=[…]     ← 命令附带的对账行（控制台）
 * </pre>
 * 🔴 <b>限流</b>：状态不变<b>一行都不打</b>；连续失败/抖动时失败行<b>每 5 分钟最多一条</b>；
 * 恢复（{@code hooks_restored}）不受限流（恢复必须立刻可见）。
 *
 * <h2>判据与离线自证</h2>
 * 判据全在 {@link ShanhaiHookSelfCheckCore}（纯 JDK，无 MC/Forge import）：
 * 正/负对照（一个有 {@code shanhai$x} 的假类、一个没有的假类 ＋ Mixin {@code handler$} 前缀的假样本）
 * 见 {@code temp/hookscheck/selfcheck/HookSelfCheckSelfProof.java}，输出 {@code pass=/fail=/exit=}。
 *
 * <h2>⚠️ 本类<b>不许</b>被本体 import</h2>
 * 依赖方向是 add → 本体（见 {@code ShanhaiAdd} 类注释"依赖方向不许反过来"）。所以降级标志真正住在
 * {@link ShanhaiGuardValueLayer}（本体，血路在那儿），本类只读写它、并对外暴露 {@link #dataOnly()}。
 */
@Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiHookSelfCheck {

    /** 日志前缀（与守卫同一套，历史 grep 口径不失效）。 */
    public static final String PREFIX = ShanhaiGuardCore.LOG_PREFIX;

    /** 自检周期：20 tps ⇒ 600 tick = 30 秒（规格点名"每 30 秒一次，服务端 tick 计数"）。 */
    public static final int CHECK_EVERY_TICKS = 600;

    /** 连续失败/抖动时，失败行最多每 5 分钟一条。 */
    public static final long RETRY_LOG_INTERVAL_MS = 5L * 60L * 1000L;

    /** 服务端 tick 计数（只在 ServerTickEvent(END) 上加）。 */
    private static int ticks;

    /** 上一轮的结论：{@code true} = 上一轮判失效（用来判"状态变化"）。 */
    private static boolean degraded;

    /** 上一轮失败行的全文（用来判"失败细节变了没有"）。 */
    private static String lastFailureLine = "";

    /** 上一条失败行的落盘时刻（0 = 从没打过）。 */
    private static long lastFailureLogAt;

    /** tick 里出异常时的告警闸门（同类 5 分钟最多一行）。 */
    private static long lastErrorLogAt;

    private ShanhaiHookSelfCheck() {}

    // ═══════════════════════════════ 对外：降级标志 ═══════════════════════════════

    /**
     * 🔴 <b>降级标志</b>（{@code data_only}）：true ⇒ 血路只走"读 {@code DATA_HEALTH_ID} ＋ 直写字段"，
     * 跳过 {@code setHealth} 那条腿（因为自检说它那条腿可能已经没人守了）。
     *
     * <p>权威存储在 {@link ShanhaiGuardValueLayer#dataOnly()}（本体，血路在那儿；add 可以 import 本体，
     * 反过来不行）—— 本方法只是给外部/命令用的同一口径。
     */
    public static boolean dataOnly() {
        try {
            return ShanhaiGuardValueLayer.dataOnly();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 置/清降级标志（<b>只有本类</b>会调；状态由自检结论决定，命令不提供手动开关）。
     *
     * <p>⚠️ 这里<b>刻意不打日志</b>：失效那一行由 {@link #runOnce()} 统一打，
     * 保证"失效时恰好一行"。
     */
    private static void applyDataOnly(boolean on) {
        try {
            ShanhaiGuardValueLayer.setDataOnly(on);
        } catch (Throwable ignored) {
            // 置标志失败也不许影响任何拦截行为（下一轮自检还会再试）
        }
    }

    // ═══════════════════════════════ 入口①：每 30 秒一轮 ═══════════════════════════════

    /**
     * 服务端 tick 末：只数 tick，每 {@value #CHECK_EVERY_TICKS} tick（30 秒）真跑一轮。
     *
     * <p>🔴 自检自己坏了<b>绝不许</b>影响任何拦截：整段包 try/catch，告警 5 分钟最多一行。
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event == null || event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++ticks < CHECK_EVERY_TICKS) {
            return;
        }
        ticks = 0;
        try {
            runOnce();
        } catch (Throwable t) {
            final long now = System.currentTimeMillis();
            if (lastErrorLogAt == 0L || now - lastErrorLogAt >= RETRY_LOG_INTERVAL_MS) {
                lastErrorLogAt = now;
                ShanhaiMod.LOGGER.warn(PREFIX + "hooks_check_failed err=" + t
                        + "（自检这一轮跳过；拦截行为不受影响）");
            }
        }
    }

    /**
     * 命令入口：<b>立刻</b>跑一轮（同一套判据、同一套状态机），返回要回显的那一行。
     *
     * <p>⚠️ 命令这一轮<b>照常</b>参与状态机（若状态真的变了，控制台会多一行
     * {@code hooks_missing} / {@code hooks_restored}）—— 恢复必须立刻可见，不能等下一轮 tick。
     */
    public static Result runForCommand() {
        final ShanhaiHookSelfCheckCore.Verdict verdict = runOnce();
        return new Result(verdict, verdict.commandLine(PREFIX, dataOnly()));
    }

    /** 命令回显用的两段：那一行 ＋ 对账用的"实际找到的名字"。 */
    public static final class Result {

        /** 这一轮的完整结论。 */
        public final ShanhaiHookSelfCheckCore.Verdict verdict;

        /** 规格那行：{@code [SHANHAI-GUARD] hooks status=… data_only=… found=… missing=… probe=…}。 */
        public final String line;

        Result(ShanhaiHookSelfCheckCore.Verdict verdict, String line) {
            this.verdict = verdict;
            this.line = line;
        }

        /** 实际跑在类里的方法名（{@code handler$<token>$} 前缀一眼可见）。 */
        public String foundDetail() {
            return verdict.foundDetail();
        }
    }

    // ═══════════════════════════════ 一轮自检 ═══════════════════════════════

    /**
     * 跑一轮：存在性 → （通过才）两条行为探针 → 合成结论 → 置/清降级标志 → 状态变化才落一行。
     */
    private static ShanhaiHookSelfCheckCore.Verdict runOnce() {
        final List<ShanhaiHookSelfCheckCore.ClassCheck> checks = new ArrayList<>();
        boolean present = true;
        for (ShanhaiHookSelfCheckCore.Target target : ShanhaiHookSelfCheckCore.TARGETS) {
            final ShanhaiHookSelfCheckCore.ClassCheck check =
                    ShanhaiHookSelfCheckCore.inspect(target, resolve(target.className));
            checks.add(check);
            if (!check.ok()) {
                present = false;
            }
        }

        // 🔴 规格：探针只在存在性通过时做（存在性没过时把两条都当"没失败"，probe 字段会是 ok）
        final ProbeReading health = present ? probeHealth() : ProbeReading.skipped();
        final ProbeReading remove = present ? probeRemove() : ProbeReading.skipped();
        final ShanhaiHookSelfCheckCore.Verdict verdict = ShanhaiHookSelfCheckCore.compose(
                checks, health.ok, remove.ok, health.note, remove.note);

        final boolean failed = !verdict.healthy;
        applyDataOnly(failed);
        noteStateChange(failed, verdict);
        degraded = failed;
        return verdict;
    }

    /**
     * 状态机（规格第 5 条）：
     * <pre>
     *   ok → fail     打 hooks_missing（但 5 分钟内已经打过失败行 ⇒ 压掉，防抖动刷屏）
     *   fail → fail   细节没变 ⇒ 一行不打；细节变了 ⇒ 也要过 5 分钟闸门才打
     *   fail → ok     立刻打 hooks_restored（恢复不受限流）
     *   ok → ok       一行不打
     * </pre>
     */
    private static void noteStateChange(boolean failed, ShanhaiHookSelfCheckCore.Verdict verdict) {
        if (!failed) {
            if (degraded) {
                lastFailureLine = "";
                ShanhaiMod.LOGGER.info(verdict.restoredLine(PREFIX));
            }
            return;
        }
        final long now = System.currentTimeMillis();
        final String line = verdict.failureLine(PREFIX);
        final boolean detailChanged = !line.equals(lastFailureLine);
        final boolean throttleOk = lastFailureLogAt == 0L
                || now - lastFailureLogAt >= RETRY_LOG_INTERVAL_MS;
        if (detailChanged && throttleOk) {
            lastFailureLine = line;
            lastFailureLogAt = now;
            ShanhaiMod.LOGGER.warn(line);
        }
    }

    // ═══════════════════════════════ 存在性：目标类解析 ═══════════════════════════════

    /**
     * 解析目标类（<b>不初始化</b>）。
     *
     * <p>先按规格用 {@code Class.forName(官方类名, false, loader)}；解析不到才退回编译期的同一类型
     * class 常量（两者在运行期是同一个类，只是给"名字映射差异"留一条退路，<b>不改变判据</b>）。
     *
     * @return 目标类；两条路都失败 ⇒ {@code null}（= 明确"没找到"，不抛）
     */
    private static Class<?> resolve(String className) {
        try {
            return Class.forName(className, false, ShanhaiHookSelfCheck.class.getClassLoader());
        } catch (Throwable ignored) {
            // 退路：编译期 class 常量（reobf 会把它换成运行期真名）
        }
        try {
            if ("net.minecraft.world.entity.LivingEntity".equals(className)) {
                return LivingEntity.class;
            }
            if ("net.minecraft.world.entity.Entity".equals(className)) {
                return Entity.class;
            }
            if ("net.minecraft.server.level.ServerPlayer".equals(className)) {
                return ServerPlayer.class;
            }
        } catch (Throwable ignored) {
            // 连 class 常量都解析不到 ⇒ 当作没找到（报红，不静默）
        }
        return null;
    }

    // ═══════════════════════════════ 行为探针（零实体，绝不碰真玩家） ═══════════════════════════════

    /** 一条探针的结论：过没过 ＋ 失败时那几条子读数（进 {@code reason=(…)}）。 */
    private static final class ProbeReading {

        /** 过了没有。 */
        final boolean ok;

        /** 子读数摘要（ASCII，逗号分隔；通过时为空串）。 */
        final String note;

        private ProbeReading(boolean ok, String note) {
            this.ok = ok;
            this.note = note == null ? "" : note;
        }

        /** 存在性没过 ⇒ 按规矩不跑（当成"没失败"，probe 字段给 ok）。 */
        static ProbeReading skipped() {
            return new ProbeReading(true, "");
        }
    }

    /**
     * 🔴 <b>血路探针</b>：值层"传 0 会被改写成 &gt;0"这条性质还成不成立。
     *
     * <p>为什么<b>不</b>直接拿一个假 {@code ServerPlayer} 去调 {@link ShanhaiGuardValueLayer#guardSetHealth}：
     * 那条入口里有 {@code instanceof ServerPlayer}、{@code isGuarded(player)}（要读背包/UUID）、
     * {@code getMaxHealth()}（要属性表）—— 人工造一个能走通的 {@code ServerPlayer} 等于伪造半个玩家实体，
     * 风险远大于收益，而且规格点名"绝不许作用在真玩家身上"。
     * ⇒ 用<b>同一份判据</b>（值层 {@code guardSetHealth} 内部调的就是 {@link ShanhaiGuardCore#rewriteHealthDecision}）
     * 做等价探针，另外加一条"非玩家目标进值层必须原样返回"的冒烟（{@code new Object()} 假目标，零副作用）。
     *
     * <pre>
     *   ① rewriteHealthDecision(0,   isServerPlayer=true,  guarded=true, enabled=true).yes == true   ← 传 0 ⇒ 要改写
     *   ② rewriteHealthDecision(1,   isServerPlayer=true,  guarded=true, enabled=true).yes == false  ← 正值 ⇒ 一个字节都不改
     *   ③ rewriteHealthDecision(0,   isServerPlayer=false, guarded=true, enabled=true).yes == false  ← 非玩家 ⇒ 原样
     *   ④ healTarget(20) &gt; 0                                                                     ← 改写目标必须是 >0
     *   ⑤ guardSetHealth(new Object(), 0f) == 0f                                                   ← 值层入口可调、非玩家原样
     * </pre>
     */
    private static ProbeReading probeHealth() {
        try {
            final boolean rewritesZero = ShanhaiGuardCore.rewriteHealthDecision(
                    0.0f, true, true, true).yes;
            final boolean keepsPositive = !ShanhaiGuardCore.rewriteHealthDecision(
                    1.0f, true, true, true).yes;
            final boolean keepsNonPlayer = !ShanhaiGuardCore.rewriteHealthDecision(
                    0.0f, false, true, true).yes;
            final boolean targetPositive = ShanhaiGuardCore.healTarget(20.0f) > 0.0f;
            final boolean valueLayerSmoke = ShanhaiGuardValueLayer.guardSetHealth(new Object(), 0.0f) == 0.0f;
            final boolean ok = rewritesZero && keepsPositive && keepsNonPlayer && targetPositive && valueLayerSmoke;
            return new ProbeReading(ok, ok ? "" : "rewrites_zero=" + rewritesZero
                    + ",keeps_positive=" + keepsPositive
                    + ",keeps_non_player=" + keepsNonPlayer
                    + ",target_positive=" + targetPositive
                    + ",value_layer_smoke=" + valueLayerSmoke);
        } catch (Throwable t) {
            return new ProbeReading(false, "err=" + t.getClass().getSimpleName());
        }
    }

    /**
     * 🔴 <b>移除路探针</b>：{@code refuseSetRemoved} 背后那条判据对"受保护玩家 ＋ KILLED"还返不返拒绝。
     *
     * <pre>
     *   ① refuseRemoval(isServerPlayer=true, guarded=true, KILLED, disconnected=false, stopping=false).yes == true
     *   ② refuseRemoval(true, true, CHANGED_DIMENSION, false, false).yes == false   ← 白名单还得放行（否则换维度卡死）
     *   ③ refuseRemoval(false, true, KILLED, false, false).yes == false              ← 非服务端玩家一个字节都不改
     * </pre>
     *
     * <p>⚠️ 探针只用判据函数，<b>不构造实体、不调 setRemoved</b>。
     */
    private static ProbeReading probeRemove() {
        try {
            final boolean refusesKill = ShanhaiGuardRemoval.refuseRemoval(
                    true, true, ShanhaiGuardRemoval.R_KILLED, false, false, false).yes;
            final boolean allowsWhitelist = !ShanhaiGuardRemoval.refuseRemoval(
                    true, true, ShanhaiGuardRemoval.R_CHANGED_DIMENSION, false, false, false).yes;
            final boolean ignoresNonPlayer = !ShanhaiGuardRemoval.refuseRemoval(
                    false, true, ShanhaiGuardRemoval.R_KILLED, false, false, false).yes;
            final boolean ok = refusesKill && allowsWhitelist && ignoresNonPlayer;
            return new ProbeReading(ok, ok ? "" : "refuses_kill=" + refusesKill
                    + ",allows_whitelist=" + allowsWhitelist
                    + ",ignores_non_player=" + ignoresNonPlayer);
        } catch (Throwable t) {
            return new ProbeReading(false, "err=" + t.getClass().getSimpleName());
        }
    }
}
