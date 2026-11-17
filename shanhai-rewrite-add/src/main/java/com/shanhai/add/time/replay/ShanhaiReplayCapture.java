package com.shanhai.add.time.replay;

import com.shanhai.ShanhaiMod;

import net.minecraft.server.MinecraftServer;

/**
 * 山海重构 · <b>每 tick 的采集编排（"记录侧"的唯一入口）</b>。
 *
 * <h2>1. 谁调它</h2>
 * {@code ShanhaiClockInit#onServerTick}（{@code TickEvent.ServerTickEvent} END，暂停期间照样会来）
 * 在推进倒流<b>之前</b>调 {@link #endOfTick(MinecraftServer)}。
 *
 * <h2>2. 顺序</h2>
 * <ol>
 *   <li><b>方块实体差分</b>（{@code setChanged} 标记集 → 只差被标记的 BE）：先把这一拍被标记的 BE 结算掉；</li>
 *   <li><b>机器进度</b>（候选机器逐个读 int）：必须在 BE 差分之后 —— 同一 tick 里 BE 差分先落、
 *       进度瘦日志后落，回退时按"进度最后写"的顺序才对得上；</li>
 *   <li><b>实体已移除扫描</b>（死亡/反注册不一定发 leave 事件）；</li>
 *   <li><b>世界标量 + 整服快照</b>（时间/天气/游戏规则/计分板）。</li>
 * </ol>
 *
 * <h2>3. 失败方向</h2>
 * 每一段各自 {@code try/catch(Throwable)}：任何一段坏了都不许拖垮服务端 tick，
 * 也不许让别的层跟着不记（"一层坏全层停"是最难查的形态）。
 */
public final class ShanhaiReplayCapture {

    private static long ticks;
    private static long failedSteps;

    /** 每个外层 tick 末调一次（只在 LIVE 真正干活；记录器自己判模式）。 */
    public static void endOfTick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        ++ticks;
        try {
            ShanhaiReplayBlockEntityRecorder.flush(server);
        } catch (Throwable t) {
            ++failedSteps;
            warn("BE 差分采集失败", t);
        }
        try {
            ShanhaiReplayProgressRecorder.scan(server);
        } catch (Throwable t) {
            ++failedSteps;
            warn("机器进度采集失败", t);
        }
        try {
            ShanhaiReplayEntityRecorder.sweep(server);
        } catch (Throwable t) {
            ++failedSteps;
            warn("实体已移除扫描失败", t);
        }
        try {
            // 🔴 "猪倒着走"这一层的数据源：每 tick 采一次玩家 128 格内实体的位置（只记变化）。
            //    血量层复用同一批枚举结果（见 ShanhaiReplayHealthRecorder#beginTick/sampleLiving/endTick）。
            ShanhaiReplayEntityMotionRecorder.scan(server);
        } catch (Throwable t) {
            ++failedSteps;
            warn("实体运动/血量采样失败", t);
        }
        try {
            ShanhaiReplayScalarRecorder.endOfTick(server);
        } catch (Throwable t) {
            ++failedSteps;
            warn("世界标量采集失败", t);
        }
    }

    public static long ticks() {
        return ticks;
    }

    public static long failedSteps() {
        return failedSteps;
    }

    /** 换世界/停机：把计数器也清掉。 */
    public static void reset() {
        ticks = 0L;
        failedSteps = 0L;
    }

    /** 一行摘要（status 用）。 */
    public static String summary() {
        return "capture_ticks=" + ticks + " capture_failed=" + failedSteps;
    }

    private static void warn(String message, Throwable t) {
        ShanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] {}（这一拍这一层不记，其余层照常）", message, t);
    }

    private ShanhaiReplayCapture() {
    }
}
