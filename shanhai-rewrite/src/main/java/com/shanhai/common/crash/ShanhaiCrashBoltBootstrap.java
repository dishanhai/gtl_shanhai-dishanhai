package com.shanhai.common.crash;

import com.shanhai.ShanhaiMod;
import com.shanhai.registry.ShanhaiEntities;
import net.minecraftforge.common.MinecraftForge;

/**
 * 阶段 3（16 件异常投掷物）的<b>唯一装配点</b>：mod 构造期调一次 {@link #install()}。
 *
 * <h2>装的是哪四件事</h2>
 * <ol>
 *   <li>触达 {@link ShanhaiEntities}（注册 1 个 {@code EntityType}）；</li>
 *   <li>占住 OOM 用的「保命缓冲」（{@link ShanhaiCrashBoltReserve#allocate()}）；</li>
 *   <li>把 {@link ShanhaiCrashBoltScheduler#onServerTick} 挂到 FORGE 事件总线
 *       （崩端必须在 <b>world tick</b> 上推进，见调度器类注释 §二）；</li>
 *   <li>打两行机器可判的启动自证（见 {@link #PREFIX}），这样"到底装上了没有"在日志上可判
 *       —— 工程既有口径：自证行不能是"成功了什么都不打"（{@code ModuleSetBlockWatch} 的教训）。</li>
 * </ol>
 *
 * <h2>为什么用显式 {@code MinecraftForge.EVENT_BUS.addListener} 而不是 {@code @Mod.EventBusSubscriber}</h2>
 * 与工程既有三条探针同一个理由（{@code ShanhaiMod} 构造器里的注释逐字）：
 * 自动订阅<b>成功时一行日志都没有</b>，「探针到底挂上了没有」在日志上不可判；
 * 显式注册 + 紧跟一行日志 ⇒ 可判。
 * ⚠️ 不用 {@code Dist.DEDICATED_SERVER} 过滤：用户玩单机，单机的服务端逻辑跑在集成服务器上、
 * Dist 仍是 CLIENT ⇒ 按 DedicatedServer 注册会在单机里静默失效（同一条注释）。
 */
public final class ShanhaiCrashBoltBootstrap {

    /** 本功能所有机器可判行的统一前缀。 */
    public static final String PREFIX = ShanhaiCrashBoltScheduler.PREFIX;

    private static boolean installed;

    /** mod 构造期调用（幂等）。 */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;

        // ① 实体类型
        ShanhaiEntities.init();

        // ② OOM 保命缓冲（需求：启动时预分配几十 MB）
        int reserveBytes = ShanhaiCrashBoltReserve.allocate();
        if (reserveBytes <= 0) {
            ShanhaiMod.LOGGER.warn("{} reserve_bytes=0（堆太小，保命缓冲没占到 ⇒ OOM 那一件的崩溃报告可能写不出来；"
                    + "可用 -D{}=<MB> 调整）", PREFIX, ShanhaiCrashBoltReserve.PROP_RESERVE_MB);
        }

        // ③ world tick 推进器（崩端唯一入口）
        MinecraftForge.EVENT_BUS.addListener(ShanhaiCrashBoltScheduler::onServerTick);

        // ④ 自证行：一行说装配读数，一行把 16 条的对应关系全列出来（可 grep、可比对）
        ShanhaiMod.LOGGER.info("{} installed reserve_bytes={} fuse_ticks={} types={} hoard_chunk_bytes={} table={}",
                PREFIX, reserveBytes, ShanhaiCrashBoltScheduler.FUSE_TICKS, ShanhaiCrashBoltTable.COUNT,
                ShanhaiCrashBoltReserve.HEAP_CHUNK_BYTES, "ShanhaiCrashBoltTable.ROWS");
        StringBuilder rowLine = new StringBuilder();
        for (int i = 0; i < ShanhaiCrashBoltTable.COUNT; i++) {
            if (i > 0) {
                rowLine.append(" | ");
            }
            rowLine.append(i).append(':').append(ShanhaiCrashBoltTable.path(i))
                    .append('=').append(ShanhaiCrashBoltTable.exceptionClass(i));
        }
        ShanhaiMod.LOGGER.info("{} table {}", PREFIX, rowLine);
    }

    private ShanhaiCrashBoltBootstrap() {}
}
