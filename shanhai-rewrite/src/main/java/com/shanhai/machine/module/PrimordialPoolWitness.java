package com.shanhai.machine.module;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * 🔴 <b>「谁动了无线网池子」的只读取证器（2026-11-02，用户点单「查它是不是被别的机器用掉了」）。</b>
 *
 * <h2>读的是哪张表（javap 实证，不是猜的）</h2>
 * gtladditions 的 mixin {@code com.gtladd.gtladditions.mixin.gtmtings.WirelessEnergyManagerMixin}
 * 把 gtmthings 原始的 {@code addEUToGlobalEnergyMap(UUID, BigInteger, MetaMachine)}
 * <b>{@code @Overwrite}</b> 掉了，并在里面多写了一张<b>按机器记账</b>的表：
 * <pre>
 *   WirelessEnergyManagerData.BIG_MACHINE_DATA :
 *       Cache&lt;MetaMachine, Pair&lt;UUID, BigInteger&gt;&gt;      ← weakKeys + expireAfterWrite(5s) + maximumSize(10000)
 *   每次写入（正=入池，负=取电）都 put 一条：(这台机器, (主人uuid, 本笔金额))
 * </pre>
 * ⇒ 这是本包<b>唯一</b>「按机器看得见谁在取电」的数据源（gtmthings 自带的
 * {@code WirelessEnergyManager.MachineData} 因为那条方法被整段 {@code @Overwrite} 了，
 * <b>在本包永远不会被写</b>——它只在原始方法体里 {@code put}）。
 *
 * <h2>能读到什么 / 读不到什么（诚实边界，别读一半）</h2>
 * <ul>
 *   <li>✅ <b>每台机器的"最后一次单笔"</b>（带符号）＋它的坐标。这正是游戏里「无线网能量监控器」
 *       那个统计数据列表的来源，语义已经被上游用在界面上。</li>
 *   <li>✅ <b>本队伍（team uuid 相同）里正在取电的机器清单</b>（负值那些）⇒ 可以把"差额"归因到具体机器/坐标。</li>
 *   <li>❌ <b>不是窗口累计</b>：一张表只留"最后那一笔"，同一台机器在 20 tick 里写了 20 次也只看得见最后一次
 *       ⇒ 想拿它<b>精确</b>对上「20 tick 的净取电」是做不到的（本类也<b>不</b>假装能对）。</li>
 *   <li>❌ <b>空闲 5 秒就过期</b>（{@code expireAfterWrite(5s)}）；{@code weakKeys} 意味着机器被卸载后条目会被回收
 *       ⇒ 「明细为空」有两种可能：真的没人取电，或者取电的机器 5 秒内没再写过。</li>
 *   <li>❌ <b>读不到时一律报 {@code unreadable}</b>（连原因一起打）：caffeine 在 gtladditions 里是
 *       {@code META-INF/jars/caffeine-3.1.8.jar} 的<b>运行期 jar-in-jar</b>，javac 看不见它，
 *       {@code it.unimi.dsi.fastutil.Pair} 也同理 ⇒ 本类只能走反射。反射任何一环失败 = 读不到，
 *       <b>不许猜、不许填 0</b>（本项目纪律：宁可缺，不可假）。</li>
 * </ul>
 *
 * <h2>为什么也<b>不</b>用 gtlcore 那张 {@code WirelessEnergyMonitorSnapshot.RECENT_ENTRIES}</h2>
 * 它的读取入口是 {@code drain()}（<b>取走并清空</b>）—— 那是给能量监控器自己用的，
 * 我们在旁边调一次就会把别人要显示的数据偷走 ⇒ <b>结构上不适合旁观者</b>。
 */
public final class PrimordialPoolWitness {

    private PrimordialPoolWitness() {}

    /** gtladditions 那张按机器记账的表（Kotlin {@code object} 的 {@code @JvmField} 静态字段）。 */
    public static final String DATA_CLASS = "com.gtladd.gtladditions.utils.WirelessEnergyManagerData";
    /** 字段名。 */
    public static final String DATA_FIELD = "BIG_MACHINE_DATA";
    /** 缓存接口（只有它 {@code asMap()} 是公开 API）。 */
    public static final String CACHE_INTERFACE = "com.github.benmanes.caffeine.cache.Cache";
    /** 值类型 {@code Pair<UUID, BigInteger>}。 */
    public static final String PAIR_INTERFACE = "it.unimi.dsi.fastutil.Pair";
    /** 我们入池用的类（只用于反射列出重载签名，给日志当证据）。 */
    public static final String MANAGER_CLASS = "com.hepdd.gtmthings.api.misc.WirelessEnergyManager";
    /** 入池方法名。 */
    public static final String ADD_METHOD = "addEUToGlobalEnergyMap";

    // ═════════════════════════════ 1. 数据结构 ═════════════════════════════

    /** 一条「某台机器最后一次写了多少」（正 = 入池，负 = 取电）。 */
    public static final class Entry {

        /** 这台机器（只读，用来取坐标/名字；不持有强引用以外的东西）。 */
        public final MetaMachine machine;
        /** 它记账用的主人 uuid（上游存的是原始 uuid，不是 team uuid）。 */
        public final UUID userId;
        /** 本笔金额：正 = 入池，负 = 取电。 */
        public final BigInteger amount;
        /** 是不是我们自己（发电这台）。 */
        public final boolean self;

        Entry(MetaMachine machine, UUID userId, BigInteger amount, boolean self) {
            this.machine = machine;
            this.userId = userId;
            this.amount = amount;
            this.self = self;
        }

        /** 机器 id（{@code MachineDefinition#getId()}；取不到退回方块显示名）。 */
        public String machineId() {
            final MachineDefinition def = machine.getDefinition();
            if (def != null && def.getId() != null) {
                return def.getId().toString();
            }
            try {
                return machine.getBlockState().getBlock().getName().getString();
            } catch (Throwable t) {
                return machine.getClass().getSimpleName();
            }
        }

        /** 坐标（形如 {@code (0,108,25)}；与日志里 {@code pos=BlockPos{...}} 同源）。 */
        public String posText() {
            final BlockPos p = machine.getPos();
            return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
        }

        @Override
        public String toString() {
            return machineId() + "@" + posText() + ":" + (amount.signum() > 0 ? "+" : "") + amount;
        }
    }

    /** 一次取证的读数（{@link #readable} 为 false 时只有 {@link #why} 有意义）。 */
    public static final class Reading {

        /** 表读到了没有（false ⇒ 明细一律 unreadable，原因见 {@link #why}）。 */
        public final boolean readable;
        /** 读不到的原因（可读时为 {@code ""}）。 */
        public final String why;
        /** 表里一共几条（{@code tracked}；读不到时 = −1）。 */
        public final int tracked;
        /** 属于本队伍（且不是我们）的条数。 */
        public final int teamEntries;
        /** 本队伍里<b>取电</b>的那些"最后一次单笔"之和（负值；读不到时 = 0 且 {@link #readable} 为 false）。 */
        public final BigInteger teamNegativeSum;
        /** Top N（按 |金额| 降序，已剔除自己）。 */
        public final List<Entry> top;

        Reading(boolean readable, String why, int tracked, int teamEntries, BigInteger teamNegativeSum, List<Entry> top) {
            this.readable = readable;
            this.why = why;
            this.tracked = tracked;
            this.teamEntries = teamEntries;
            this.teamNegativeSum = teamNegativeSum;
            this.top = top;
        }

        /** 日志里「明细=…」那一段：能读到就列 Top3，读不到就 {@code unreadable(原因)}。 */
        @NotNull
        public String detailText() {
            if (!readable) {
                return "unreadable(" + why + ")";
            }
            if (top.isEmpty()) {
                return "无别人的写入（表内 " + tracked + " 条，本队伍其它机器 0 条）";
            }
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < top.size(); i++) {
                if (i > 0) {
                    sb.append(" ／ ");
                }
                sb.append(i + 1).append('.').append(top.get(i));
            }
            return sb + "（表内 " + tracked + " 条，含本队伍 " + teamEntries + " 条；"
                    + "⚠️ 每台只有【最后一次单笔】，不是窗口累计；空闲 5 秒即过期）";
        }

        /** 本队伍取电机器的「最后一次单笔」之和（日志用；读不到 ⇒ {@code unreadable}）。 */
        @NotNull
        public String negativeSumText() {
            return readable ? teamNegativeSum.toString() : "unreadable";
        }
    }

    private static final Reading UNREADABLE_INIT = new Reading(false, "尚未初始化", -1, 0, BigInteger.ZERO, List.of());
    // ═════════════════════════════ 2. 反射句柄（失败一次就永久 unreadable） ═════════════════════════════

    private static boolean sInitTried;
    private static boolean sInitOk;
    private static String sInitWhy = "";

    private static Method sAsMap;
    private static Method sPairLeft;
    private static Method sPairRight;
    private static Class<?> sDataClass;

    private static synchronized void init() {
        if (sInitTried) {
            return;
        }
        sInitTried = true;
        try {
            final Class<?> cache = Class.forName(CACHE_INTERFACE);
            sAsMap = cache.getMethod("asMap");
            final Class<?> pair = Class.forName(PAIR_INTERFACE);
            sPairLeft = pair.getMethod("left");
            sPairRight = pair.getMethod("right");
            // 🔴 这里<u>不</u>初始化那个类（initialize=false）：加载期自检就跑一次，
            //    而它的静态初始化会去 new 一个 caffeine 缓存 —— 没必要在注册期触发。
            //    真正的初始化发生在第一次 read()（那时一定要读，绕不开）。
            sDataClass = Class.forName(DATA_CLASS, false, PrimordialPoolWitness.class.getClassLoader());
            final Field f = sDataClass.getField(DATA_FIELD);
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                throw new IllegalStateException(DATA_FIELD + " 不是静态字段");
            }
            sInitOk = true;
            sInitWhy = "";
        } catch (Throwable t) {
            sInitOk = false;
            sInitWhy = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** 反射句柄准备好了没有（读不到时用它给原因）。 */
    public static boolean available() {
        init();
        return sInitOk;
    }

    /** 读不到的原因（可读时为空串）。 */
    @NotNull
    public static String unavailableReason() {
        init();
        return sInitOk ? "" : sInitWhy;
    }

    // ═════════════════════════════ 3. 只读取证 ═════════════════════════════

    /**
     * 读一次「谁在动池子」。
     *
     * @param self   我们自己（发电这台；明细里会被剔除）。允许 null
     * @param ourTeam 本队伍键（{@code TeamUtil.getTeamUUID(池键)}）。允许 null ⇒ 不按队伍过滤
     * @param teamOf  {@code uuid → team uuid} 的解析器（上游同一个 {@code TeamUtil.getTeamUUID}）。
     *                允许 null ⇒ 不按队伍过滤
     * @param topN   明细最多几条（按 |金额| 降序）
     * @return 读数；<b>永不抛异常</b>（读不到就 {@code readable=false}）
     */
    @NotNull
    public static Reading read(@Nullable MetaMachine self, @Nullable UUID ourTeam,
                               @Nullable UnaryOperator<UUID> teamOf, int topN) {
        init();
        if (!sInitOk) {
            return new Reading(false, unavailableReason(), -1, 0, BigInteger.ZERO, List.of());
        }
        Map<?, ?> map;
        try {
            final Field f = sDataClass.getField(DATA_FIELD);
            final Object cache = f.get(null);
            if (cache == null) {
                return new Reading(false, DATA_FIELD + " == null", -1, 0, BigInteger.ZERO, List.of());
            }
            final Object raw = sAsMap.invoke(cache);
            if (!(raw instanceof Map<?, ?> m)) {
                return new Reading(false, "asMap() 返回的不是 java.util.Map", -1, 0, BigInteger.ZERO, List.of());
            }
            map = m;
        } catch (Throwable t) {
            return new Reading(false, t.getClass().getSimpleName() + ": " + t.getMessage(),
                    -1, 0, BigInteger.ZERO, List.of());
        }

        final List<Entry> all = new ArrayList<>();
        BigInteger negativeSum = BigInteger.ZERO;
        int teamEntries = 0;
        try {
            for (final Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof MetaMachine machine)) {
                    continue;
                }
                final Object pair = e.getValue();
                if (pair == null) {
                    continue;
                }
                final Object left = sPairLeft.invoke(pair);
                final Object right = sPairRight.invoke(pair);
                if (!(left instanceof UUID owner) || !(right instanceof BigInteger amount)) {
                    continue;
                }
                final boolean isSelf = self != null && machine == self;
                if (!isSelf) {
                    if (ourTeam != null && teamOf != null && !ourTeam.equals(teamOf.apply(owner))) {
                        continue; // 别的队伍：与我们的池子是两本账，不混进明细
                    }
                    teamEntries++;
                    if (amount.signum() < 0) {
                        negativeSum = negativeSum.add(amount);
                    }
                }
                all.add(new Entry(machine, owner, amount, isSelf));
            }
        } catch (Throwable t) {
            return new Reading(false, "遍历 BIG_MACHINE_DATA 失败: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage(), map.size(), 0, BigInteger.ZERO, List.of());
        }

        final List<Entry> others = new ArrayList<>();
        for (final Entry e : all) {
            if (!e.self) {
                others.add(e);
            }
        }
        others.sort(Comparator.comparing((Entry e) -> e.amount.abs()).reversed());
        final int n = Math.max(0, Math.min(topN, others.size()));
        return new Reading(true, "", map.size(), teamEntries, negativeSum,
                n == 0 ? List.of() : List.copyOf(others.subList(0, n)));
    }

    // ═════════════════════════════ 4. 重载签名（日志证据） ═════════════════════════════

    /**
     * 反射列出 {@code WirelessEnergyManager.addEUToGlobalEnergyMap} 的<b>全部重载真签名</b>。
     *
     * <p>用途：把「我们到底有几条可选、其中哪条是真池子」变成一行可 grep 的日志证据
     * （不再依赖"我记得"）。读不到 ⇒ 返回原因串，<b>不抛</b>。
     */
    @NotNull
    public static String overloadSignatures() {
        try {
            final Class<?> c = Class.forName(MANAGER_CLASS);
            final StringBuilder sb = new StringBuilder();
            int n = 0;
            for (final Method m : c.getDeclaredMethods()) {
                if (!ADD_METHOD.equals(m.getName())) {
                    continue;
                }
                if (n++ > 0) {
                    sb.append(" ｜ ");
                }
                sb.append("(");
                final Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(ps[i].getSimpleName());
                }
                sb.append(") → ").append(m.getReturnType().getSimpleName());
            }
            return n == 0 ? "unreadable(找不到 " + ADD_METHOD + ")" : n + " 条：" + sb;
        } catch (Throwable t) {
            return "unreadable(" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
        }
    }

    /** 初始化失败原因（自检/日志用；未失败为空串）。 */
    @NotNull
    public static String selfCheck() {
        init();
        return sInitOk
                ? "BIG_MACHINE_DATA 反射句柄就绪（" + DATA_CLASS + "#" + DATA_FIELD + "）"
                : "BIG_MACHINE_DATA 读不到：" + sInitWhy + " ⇒ 明细一律 unreadable（不猜、不填 0）";
    }

    /** 供 {@code main} 之外的调试用：未初始化时的空读数（避免调用方判 null）。 */
    @NotNull
    public static Reading empty() {
        return UNREADABLE_INIT;
    }

    /** 调用方自己出错时的兜底读数（原因会原样进日志的 {@code unreadable(...)}，不吞）。 */
    @NotNull
    public static Reading unreadable(@NotNull String why) {
        return new Reading(false, why, -1, 0, BigInteger.ZERO, List.of());
    }
}
