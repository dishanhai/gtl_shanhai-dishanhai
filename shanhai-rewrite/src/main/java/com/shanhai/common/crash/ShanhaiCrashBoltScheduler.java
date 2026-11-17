package com.shanhai.common.crash;

import com.shanhai.ShanhaiMod;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 「落地 3 秒后必崩」的<b>调度器</b>：倒计时挂在 <b>world tick</b> 上，与实体彻底解耦。
 *
 * <h2>一、为什么倒计时不能挂在实体上</h2>
 * 投掷物实体有三种方式"消失"，任何一种都不该救玩家：
 * <table border="1">
 *   <caption>逃逸方式与取证</caption>
 *   <tr><th>逃逸</th><th>原版机制</th><th>本类怎么挡</th></tr>
 *   <tr><td>走过去捡起来</td>
 *       <td>{@code AbstractArrow#playerTouch} → {@code tryPickup}（pickup=ALLOWED 时直接收走）</td>
 *       <td>落地那刻把 {@code pickup} 改成 {@code DISALLOWED}（见实体 {@code land()}）；
 *           而且计时根本不看实体在不在</td></tr>
 *   <tr><td>把插着的方块挖掉</td>
 *       <td>{@code AbstractArrow#tick} 里 {@code shouldFall()} 为真就 {@code startFalling()}，
 *           实体掉下去；原版的 {@code inGroundTime} 也在这里归零</td>
 *       <td>计时在本类的静态表里推进（键 = UUID + 维度 + 落点），
 *           实体掉下去/被移除都不影响</td></tr>
 *   <tr><td>退出再进 / 区块卸载</td>
 *       <td>实体被写进 region 文件，重进时原样加载</td>
 *       <td>计时键仍然是 UUID：{@link ShanhaiCrashBoltSavedData} 记住"已触发"，
 *           没触发过的读档回来会重新挂表（见实体 {@code firstTickOnServer()}）</td></tr>
 * </table>
 *
 * <h2>二、为什么在 {@code ServerTickEvent(START)} 里抛</h2>
 * 取证（逐行字节码级，见 {@code temp/reality-items/报告.md} §4.3）：
 * {@code MinecraftServer#tickServer} 的第一行就是
 * {@code ForgeEventFactory.onPreServerTick(...)}（{@code MinecraftServer.java:812}），
 * 它<b>不在任何 try 里</b> ⇒ 我们在这里抛出的 {@link ReportedException} 会直冲
 * {@code runServer} 的外层 catch，被 {@code constructOrExtractCrashReport} <b>原样提取</b>。
 * 反过来：如果抛在 {@code serverlevel.tick()} 内部（{@code MinecraftServer.java:892} 之后），
 * 会被 {@code new CrashReport("Exception ticking world", t)} 重新包一层，我们的 Description 就没了。
 *
 * <h2>三、抛之前必须做的两件事</h2>
 * <ol>
 *   <li><b>先 {@code entity.discard()}</b>：崩完 {@code runServer} 的 finally 会
 *       {@code stopServer()} 存世界，实体还在就会被写盘 ⇒ 下次进世界又崩（报告 §6.6）；</li>
 *   <li><b>先写 {@link ShanhaiCrashBoltSavedData}</b>：「这一发已触发」，
 *       兜住"实体在崩溃前就已经被写进 region"的那一份。</li>
 * </ol>
 * 两件事都发生在 {@code throw} <b>之前</b>，顺序在 {@link #fire} 里是固定的（自查器按行号核）。
 */
public final class ShanhaiCrashBoltScheduler {

    /** 日志前缀（一条 grep 就能把本功能的所有机器可判行捞出来）。 */
    public static final String PREFIX = "[SHANHAI-CRASH-BOLT]";

    /** 引信长度：60 tick = 3 秒（20 tps）。 */
    public static final int FUSE_TICKS = 60;

    /**
     * 正在倒计时的投掷物。
     *
     * <p>{@link LinkedHashMap} 只为日志顺序稳定。键 = 实体 UUID ⇒ 同一个投掷物怎么折腾都只有一条。
     * <b>没有任何 {@code cancel}/{@code disarm} 方法</b>：这是刻意的 ——
     * 需求要求"捡走 / 挖掉方块都不取消"，那就不给它取消的入口。
     */
    private static final Map<UUID, Arm> ARMED = new LinkedHashMap<>();

    /** 一条倒计时。全部字段 final 除了 {@code ticksLeft}（每 tick 减一）。 */
    private static final class Arm {
        final UUID id;
        final ResourceKey<Level> dimension;
        final BlockPos pos;
        final int index;
        final long armedAtGameTime;
        int ticksLeft;

        Arm(UUID id, ResourceKey<Level> dimension, BlockPos pos, int index, long armedAtGameTime, int ticksLeft) {
            this.id = id;
            this.dimension = dimension;
            this.pos = pos;
            this.index = index;
            this.armedAtGameTime = armedAtGameTime;
            this.ticksLeft = ticksLeft;
        }
    }

    // ------------------------------------------------------------------ 挂表

    /**
     * 投掷物落地：挂上 world tick 的倒计时。
     *
     * <p>⚠️ <b>幂等</b>：同一个 UUID 已经在表里就直接返回（计时<b>绝不</b>重置）。
     * 这一点很重要 —— 挖掉支撑方块后箭头可能再落一次地（{@code onHitBlock} 会再响一次），
     * 若每次落地都重置计时，玩家就能靠"反复挖/放方块"把倒计时永远按住。
     */
    public static void arm(ServerLevel level, UUID id, BlockPos pos, int index) {
        if (id == null || level == null || !ShanhaiCrashBoltTable.valid(index)) {
            return;
        }
        final Arm arm;
        synchronized (ARMED) {
            if (ARMED.containsKey(id)) {
                return;
            }
            arm = new Arm(id, level.dimension(), pos.immutable(), index, level.getGameTime(), FUSE_TICKS);
            ARMED.put(id, arm);
        }
        ShanhaiMod.LOGGER.info("{} armed index={} item={} uuid={} dim={} pos={} fuse={}t game_time={}",
                PREFIX, index, ShanhaiCrashBoltTable.fullId(index), id, arm.dimension.location(),
                arm.pos, FUSE_TICKS, arm.armedAtGameTime);
    }

    /** 当前倒计时条数（诊断 / 自查用）。 */
    public static int pendingCount() {
        synchronized (ARMED) {
            return ARMED.size();
        }
    }

    // ------------------------------------------------------------------ 推进 + 崩

    /**
     * 服务端 tick（START）：推进所有倒计时，到点的那一条就把游戏真崩掉。
     *
     * <p>⚠️ 这里<b>不</b>吞异常：{@link #fire} 抛出的 {@link ReportedException} 必须一路
     * 冲到 {@code MinecraftServer#runServer} 的 catch（见类注释 §二）。
     */
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        final Arm due;
        synchronized (ARMED) {
            if (ARMED.isEmpty()) {
                return;
            }
            Arm found = null;
            for (Arm arm : ARMED.values()) {
                arm.ticksLeft--;
                if (arm.ticksLeft <= 0 && found == null) {
                    found = arm;
                }
            }
            if (found == null) {
                return;
            }
            ARMED.remove(found.id);
            due = found;
        }
        // 同一 tick 只崩一发：崩完进程就没了，剩下的倒计时没有意义
        fire(event.getServer(), due);
    }

    /**
     * 真崩：丢弃实体 → 记 SavedData → 真触发那一件对应的异常 → 抛 {@link ReportedException}。
     *
     * <p>顺序（自查器逐条核，改顺序会让"下次进世界又崩"重新出现）：
     * <ol>
     *   <li>{@code discarding the entity}；</li>
     *   <li>{@code markFired}（SavedData）；</li>
     *   <li>{@code produce}（真触发；OOM 时它内部已归还保命缓冲）；</li>
     *   <li>{@code new CrashReport(描述, 真异常)}；</li>
     *   <li>{@code throw new ReportedException(报告)}。</li>
     * </ol>
     */
    private static void fire(MinecraftServer server, Arm arm) {
        // ---- ① 崩前必 discard：否则 stopServer() 会把它存进存档，下次进世界又崩（报告 §6.6）
        ServerLevel level = server.getLevel(arm.dimension);
        Entity bolt = level == null ? null : level.getEntity(arm.id);
        boolean discarded = false;
        if (bolt != null) {
            bolt.discard();
            discarded = true;
        }

        // ---- ② 崩前必记 SavedData：兜住"实体在崩溃前已被写进 region"的那一份
        ShanhaiCrashBoltSavedData saved = ShanhaiCrashBoltSavedData.get(server);
        saved.markFired(arm.id);

        final String description = ShanhaiCrashBoltTable.description(arm.index);
        final String fullId = ShanhaiCrashBoltTable.fullId(arm.index);
        ShanhaiMod.LOGGER.error("{} fire index={} item={} uuid={} pos={} discarded={} saved_fired={} pending={} desc={}",
                PREFIX, arm.index, fullId, arm.id, arm.pos, discarded, saved.firedCount(), pendingCount(), description);

        // ---- ③ 真触发那一件对应的异常（这一行之后，异常对象就是 JDK/JVM/原版 API 抛出来的了）
        Throwable cause = ShanhaiCrashBoltTriggers.produce(arm.index);

        // ---- ④ 组装报告：Description 用表里的逐字文案（唯一能保住它的一行就是下面的 ReportedException）
        CrashReport report = new CrashReport(description, cause);
        try {
            report.addCategory("Shanhai crash bolt")
                    .setDetail("Item", fullId)
                    .setDetail("Exception (real trigger)", ShanhaiCrashBoltTable.exceptionClass(arm.index))
                    .setDetail("How it really happened", ShanhaiCrashBoltTable.trigger(arm.index))
                    .setDetail("Fuse", FUSE_TICKS + " ticks = 3 s, counted on the world tick, decoupled from the entity")
                    .setDetail("Landed at", arm.pos.toShortString() + " in " + arm.dimension.location())
                    .setDetail("Entity discarded before crash", String.valueOf(discarded))
                    .setDetail("Recorded in SavedData", "yes (" + saved.firedCount() + " total)");
        } catch (Throwable ignored) {
            // 加不上分类也必须崩：Description 已经在构造函数里了
        }

        // ---- ⑤ 🔴 唯一能保住 Description 的写法（报告 §4.2：裸抛会被改写成 "Exception in server tick loop"）
        throw new ReportedException(report);
    }

    private ShanhaiCrashBoltScheduler() {}
}
