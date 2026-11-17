package com.shanhai.common.crash;

import java.util.ArrayList;
import java.util.List;

/**
 * OOM 那一件专用的两块内存：<b>「保命缓冲」</b>（启动时占好、崩时归还）与<b>「吃满堆的那一批」</b>。
 *
 * <h2>一、为什么需要保命缓冲</h2>
 * {@code OutOfMemoryError} 是"想分配的时候分配不到"才发生的。真吃满堆之后再让崩溃报告去分配
 * （{@code CrashReport} + {@code SystemReport} + 字符串拼接，几十到几百 KB），
 * 很可能当场再 OOM 一次 —— 那时报告<b>一个字都写不出来</b>，玩家只看到进程没了。
 * ⇒ 启动时先占一块几十 MB 的空数组（不 touch，不进 RSS 太深，但确实占着堆），
 * 捕获 OOM 之后第一件事就是把它置空（{@link #releaseForCrashReport()}），
 * 让"写报告"这件事有确定的余量。
 *
 * <h2>二、为什么还要把吃满的那一批也还回去</h2>
 * 只还保命缓冲的话，报告写得出来，但<b>紧接着的存档写不出来</b>：
 * 崩端路径是 {@code runServer} 的 catch（写报告）→ {@code finally stopServer()}（存世界），
 * 而 {@code stopServer()} 里 {@code saveAllChunks} 要序列化几十个区块，
 * 在堆只剩几十 MB 的情况下会二次 OOM，正好可能把 region 文件写到一半
 * ⇒ 那才是真正的坏结果（存档残缺）。
 * ⇒ 捕获 OOM 后<b>两块都还</b>：先 {@link #releaseForCrashReport()}（保命缓冲），
 * 再 {@link #releaseHoard()}（吃满的那批）。
 * ⚠️ 仍然必须先还保命缓冲：它是"报告能不能写出来"的**保底**，
 * 而吃满的那批是"报告写完之后存档能不能写"的**余量** —— 两者用途不同，所以顺序是刻意的。
 *
 * <h2>三、真吃满是怎么做的</h2>
 * {@link #consumeAllHeap()} 在一个死循环里 {@code new byte[8 MiB]} 并全部塞进 {@link #HOARD} 持有
 * —— 直到 JVM 自己抛 {@code OutOfMemoryError}。{@link #HOARD} 是静态字段，
 * 所以那块内存<b>真的被持有</b>、不会被优化掉（这一点是"真 OOM"与"new 一个假的 OOM"的分界）。
 *
 * <h2>四、大小可调</h2>
 * 默认 32 MB；{@code -Dshanhai.crashbolt.reserveMB=64} 可改（需求只说"几十 MB"）。
 * 分配失败（极小堆）不炸启动，只把保命缓冲置空并让 {@link #reserveBytes()} 返回 0 ——
 * 那一支由 {@code ShanhaiCrashBoltBootstrap} 打一条 WARN。
 */
public final class ShanhaiCrashBoltReserve {

    /** 系统属性：保命缓冲的 MB 数。 */
    public static final String PROP_RESERVE_MB = "shanhai.crashbolt.reserveMB";

    /** 默认保命缓冲（MB）。需求：几十 MB。 */
    public static final int DEFAULT_RESERVE_MB = 32;

    /** 每次分配的块大小（8 MiB）。注意：G1 下超过 region 一半的数组是 humongous 对象，
     *  用 8 MiB 是为了既快到 OOM、又不至于一开始就卡在巨型数组的连续内存搜索上。 */
    public static final int HEAP_CHUNK_BYTES = 8 << 20;

    /** 保命缓冲本体。<b>只</b>在两个地方被写：{@link #allocate()} 与 {@link #releaseForCrashReport()}。 */
    private static byte[] reserve;

    /** 吃满堆的那一批（真持有 ⇒ JVM 才算"分配不到"）。 */
    private static final List<byte[]> HOARD = new ArrayList<>();

    /**
     * 启动时占住保命缓冲（由 {@code ShanhaiCrashBoltBootstrap} 在 mod 构造期调用一次）。
     *
     * @return 实际占到的字节数（0 = 没占到，堆太小或已经分配失败）
     */
    public static int allocate() {
        if (reserve != null) {
            return reserve.length;
        }
        int mb = DEFAULT_RESERVE_MB;
        try {
            String raw = System.getProperty(PROP_RESERVE_MB);
            if (raw != null && !raw.isBlank()) {
                mb = Math.max(1, Math.min(256, Integer.parseInt(raw.trim())));
            }
        } catch (RuntimeException ignored) {
            // 属性写坏就用默认值（启动期不许因为一个属性炸掉）
        }
        try {
            reserve = new byte[mb << 20];
        } catch (OutOfMemoryError tooSmallHeap) {
            reserve = null;
            return 0;
        }
        return reserve.length;
    }

    /** 当前保命缓冲大小（字节）；没占到 = 0。 */
    public static int reserveBytes() {
        byte[] r = reserve;
        return r == null ? 0 : r.length;
    }

    /**
     * 🔴 归还保命缓冲 —— <b>必须在</b> {@code new CrashReport(...)} <b>之前</b>调用。
     *
     * <p>幂等：重复调用只是多置一次 null。
     */
    public static void releaseForCrashReport() {
        reserve = null;
    }

    /** 归还吃满的那一批（见类注释 §二：不还的话，随后的存档写盘会二次 OOM）。 */
    public static void releaseHoard() {
        HOARD.clear();
    }

    /** 当前持有多少字节（诊断用；OOM 之后应当是 0）。 */
    public static long hoardBytes() {
        long total = 0L;
        for (byte[] chunk : HOARD) {
            total += chunk.length;
        }
        return total;
    }

    /** 当前持有几块（诊断用）。 */
    public static int hoardChunks() {
        return HOARD.size();
    }

    /**
     * 🔴 真·吃满堆：死循环分配大块并全部持有，直到 JVM 抛 {@code OutOfMemoryError}。
     *
     * <p>不 catch —— 让那个 OOM 原样往上传（{@link ShanhaiCrashBoltTriggers#produce(int)} 接住它）。
     */
    public static void consumeAllHeap() {
        while (true) {
            HOARD.add(new byte[HEAP_CHUNK_BYTES]);
        }
    }

    private ShanhaiCrashBoltReserve() {}
}
