package com.shanhai.machine.stellar;

import com.mojang.logging.LogUtils;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 星律样板总成的<b>统一日志闸</b>（前缀 {@value #PREFIX}）。
 *
 * <h2>一、为什么要单独一个类</h2>
 * 判据要求「日志里能<b>断言</b>」：注册自证一行、类型读取一行。
 * 如果这些行散落在机器类里各写各的，就没法用一条 grep 判定「到底有没有生效」。
 * 本类把三件事收在一处：
 * <ol>
 *   <li><b>前缀</b>：{@value #PREFIX}（与阶段一的注册自证同一前缀，日志里 {@code grep '[SHANHAI-STELLAR]'} 一行看全）；</li>
 *   <li><b>类型读取的开关</b>：默认<b>关</b>（{@link #isTypeLogEnabled()} = false），
 *       打开方式见 §二；关着时一条都不打（1080 槽逐槽打会把日志刷爆）；</li>
 *   <li><b>稳定的单行格式</b>：见 {@link #logSlotType(String, int, ItemStack, ShanhaiPatternTypeReader.Resolution)}。</li>
 * </ol>
 *
 * <h2>二、开关怎么打开（两种，都不需要改代码）</h2>
 * <pre>
 *   -Dshanhai.stellar.typeLog=true      // JVM 参数（服务端/客户端启动脚本加一句即可）
 * </pre>
 * 或运行期调 {@link #setTypeLogEnabled(boolean)}（阶段三会把它挂到一条命令上；
 * <b>本轮故意不挂</b>：命令注册要动注册入口，任务书要求「只新增文件」）。
 *
 * <h2>三、为什么用 System property 而不是 Forge 配置</h2>
 * Forge 配置要新增一个 config 类 + 一个注册点（还要改 mods.toml 之外的现有文件），
 * 而本轮的验收标准只是「能断言」；system property 零侵入、无副作用、关着时连一条 DEBUG 都不产生。
 * 若用户要「游戏里能开关」，阶段三把它接进现有命令系统即可，本类的 API 不用改。
 *
 * <h2>四、纪律</h2>
 * <ul>
 *   <li>关着时<b>不拼接字符串</b>：所有拼接都在 {@code if (typeLogEnabled())} 之后（1080 槽 × 每次刷新的代价不可接受）；</li>
 *   <li>永远不抛：日志类抛异常会把调用它的 tick / UI 路径带崩；</li>
 *   <li>{@link #warn} 之类<b>不受开关影响</b>（真异常必须看得见）。</li>
 * </ul>
 */
public final class ShanhaiStellarLog {

    /** 统一日志前缀（注册自证与类型读取共用）。 */
    public static final String PREFIX = "[SHANHAI-STELLAR]";

    /** 打开类型读取日志的 JVM 参数名。 */
    public static final String TYPE_LOG_PROPERTY = "shanhai.stellar.typeLog";

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 默认 = JVM 参数；运行期可用 {@link #setTypeLogEnabled(boolean)} 改。 */
    private static volatile boolean typeLogEnabled = readProperty();

    private ShanhaiStellarLog() {}

    private static boolean readProperty() {
        try {
            return Boolean.getBoolean(TYPE_LOG_PROPERTY);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isTypeLogEnabled() {
        return typeLogEnabled;
    }

    public static void setTypeLogEnabled(boolean enabled) {
        typeLogEnabled = enabled;
        // 开关本身也要可断言（且只在这一处打，不会刷屏）。
        LOGGER.info("{} 类型日志开关 => {}", PREFIX, enabled ? "ON" : "OFF");
    }

    /** 无条件（带前缀）—— 用于注册自证这类「必须有且只有一行」的日志。 */
    public static void info(@NotNull String message, Object... args) {
        LOGGER.info(PREFIX + " " + message, args);
    }

    /** 无条件（带前缀）的告警。 */
    public static void warn(@NotNull String message, Object... args) {
        LOGGER.warn(PREFIX + " " + message, args);
    }

    /** 受开关控制的诊断行（默认关）。 */
    public static void type(@NotNull String message, Object... args) {
        if (typeLogEnabled) {
            LOGGER.info(PREFIX + " TYPE " + message, args);
        }
    }

    /**
     * 逐槽打印一次读取结果（受开关控制）。
     *
     * <p>固定格式（便于 grep / 断言）：
     * <pre>
     *   [SHANHAI-STELLAR] TYPE slot=7 item=gtceu:programmed_circuit verdict=UNIQUE ids=[gtceu:electric_furnace] raw={...}
     * </pre>
     *
     * @param where   调用点标签（如 {@code onLoad} / {@code ui}），出问题时要能定位是谁读的
     * @param apiSlot 对外槽号（0..1079 普通，1080..1129 通配符）
     * @param stack   那块样板（可为空栈 —— 空栈不打印，避免 1080 行空跑）
     */
    public static void logSlotType(@NotNull String where, int apiSlot, @Nullable ItemStack stack,
                                   @Nullable ShanhaiPatternTypeReader.Resolution resolution) {
        if (!typeLogEnabled || resolution == null || stack == null || stack.isEmpty()) {
            return;
        }
        LOGGER.info("{} TYPE where={} slot={} item={} {} authoritativeFlag={}",
                PREFIX, where, apiSlot, String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())),
                resolution.describe(),
                resolution.hasAnyRaw() && ShanhaiPatternTypeReader.hasAuthoritativeFlag(stack.getTag()));
    }

    /**
     * 一次汇总（受开关控制）：把「普通槽 / 通配符槽」的判定分布打一行。
     *
     * <p>用途：开一次日志就能看出「1080 槽里有多少块样板读出了唯一类型、多少块歧义」，
     * 而不需要 1080 行。
     */
    public static void logSummary(@NotNull String where, int unique, int ambiguous, int none, int total) {
        if (!typeLogEnabled) {
            return;
        }
        LOGGER.info("{} TYPE SUMMARY where={} total={} unique={} ambiguous={} none={}",
                PREFIX, where, total, unique, ambiguous, none);
    }
}
