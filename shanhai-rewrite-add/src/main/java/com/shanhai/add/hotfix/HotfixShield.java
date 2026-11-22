package com.shanhai.add.hotfix;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>「废掉那一行」的运行时落点</b>：普通攻击（{@code attack=line}）把目标方法里
 * "写受保护状态的那一条指令"换成对本类的静态调用，本类决定<b>这一行到底执行不执行</b>。
 *
 * <h2>为什么要有这个类（而不是直接把指令删掉）</h2>
 * 直接把 {@code setHealth} 那条 {@code INVOKEVIRTUAL} 换成 {@code NOP} 需要把栈上的
 * 接收者与参数弹掉（{@code POP}/{@code POP2}），指令数变了 ⇒ 所有跳转偏移与
 * {@code StackMapTable} 都要重算。<b>换成"同形参个数的一静态调用"则栈效应完全相同</b>
 * （{@code INVOKEVIRTUAL (F)V} 与 {@code INVOKESTATIC (Object;F)V} 都是"吃掉 2 个槽、不返回值"），
 * 于是：
 * <ul>
 *   <li>指令数不变、偏移不变、{@code StackMapTable} 一个字节都不用动；</li>
 *   <li>{@code ClassWriter(0)} 原样复制 maxStack，不必 {@code COMPUTE_FRAMES}（那会加载类）；</li>
 *   <li>形参用 {@code Object} 接接收者 ⇒ <b>任何</b>接收者类型都通过校验（不需要知道它是不是
 *       {@code LivingEntity}）。</li>
 * </ul>
 *
 * <h2>判据（一个 mod 名都没有）</h2>
 * <pre>
 *   ① 值本身是无害的（{@code > 0} 且有限）      ⇒ <b>原样转发</b>（正常加血/回血照跑，一个字节不碰）
 *   ② 值会读成"死的"（{@code <=0 / NaN / ±∞}）
 *        ＋ 归属是受保护玩家（由装配侧注入的 {@link Judge} 判）⇒ <b>这一行无效</b>（不转发）
 *        其余                                                    ⇒ 原样转发（生物照常会死）
 * </pre>
 * ⇒ 对手换类名/包名/写法都不影响：判的是"这一行要写什么"，不是"谁写的"。
 *
 * <h2>为什么"转发"用反射</h2>
 * 本类必须能被<b>脱离游戏</b>的离线自检加载（{@code temp\hotfix\lineattack\}），所以它
 * <b>不 import 任何 MC 类</b>。转发只能走 {@link Method#invoke}：
 * <ul>
 *   <li>{@code setHealth}：按<b>名字集合</b>找（{@code setHealth} 与 SRG 的 {@code m_21153_}），
 *       匹配"public void (float)"；</li>
 *   <li>{@code SynchedEntityData#set}：按<b>形状</b>找（参数 2/3 个、第 0 个的类型名是
 *       {@code net.minecraft.network.syncher.EntityDataAccessor}、第 1 个是 {@code Object}），
 *       所以<b>不需要知道它的 SRG 方法名</b>。</li>
 * </ul>
 * 解析不到 ⇒ 记 {@code forward_unavailable} 并<b>不写</b>（fail-closed：宁可这一次加血没加上，
 * 也不许把一次"写死"放过去）。
 *
 * <p>所有计数都是 {@link AtomicLong}，日志 1 行/秒限流 —— 这一行会在对手 20/s 的循环里被调，
 * 不许刷屏、不许抛。
 */
public final class HotfixShield {

    /** 本类的内部名（{@link HotfixLinePatch} 生成 {@code INVOKESTATIC} 时用）。 */
    public static final String SELF_INTERNAL = "com/shanhai/add/hotfix/HotfixShield";

    /** 本类的源级名（{@link #resolvableFrom(ClassLoader)} 用）。 */
    public static final String SELF_NAME = "com.shanhai.add.hotfix.HotfixShield";

    // ---- 三个调用点（名字是字节码常量，别改：HotfixLinePatch 与自检都按它生成/断言）
    /** {@code setHealth(float)} 那条。 */
    public static final String CALL_SET_HEALTH = "dropSetHealth";
    /** {@code SynchedEntityData#set(Accessor,Object)} 那条（2 参）。 */
    public static final String CALL_DATA2 = "dropHealthData";
    /** {@code SynchedEntityData#set(Accessor,Object,boolean)} 那条（3 参）。 */
    public static final String CALL_DATA3 = "dropHealthDataForce";
    /** 调用点 2 参的描述符。 */
    public static final String DESC_SET_HEALTH = "(Ljava/lang/Object;F)V";
    /**
     * 调用点（2 参 {@code set}）的描述符。
     *
     * <p>🔴 为什么是 3 个 {@code Object}：原指令是
     * {@code INVOKEVIRTUAL SynchedEntityData.set(EntityDataAccessor, Object)V} ——
     * 栈上是「接收者 ＋ 两个实参」共 <b>3</b> 个槽。{@code INVOKESTATIC} 要吃掉同样多的槽，
     * 所以形参必须是 3 个（接收者用 {@code Object} 接，这样任何接收者类型都过校验）。
     */
    public static final String DESC_DATA2 =
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V";
    /** 调用点（3 参 {@code set}）的描述符：接收者 ＋ 两个 Object ＋ 一个 boolean。 */
    public static final String DESC_DATA3 =
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Z)V";

    /** 站点名（进日志的 {@code site=}）。 */
    public static final String SITE_SET_HEALTH = "setHealth";
    public static final String SITE_DATA2 = "data_health";
    public static final String SITE_DATA3 = "data_health3";

    /**
     * 装配侧注入的判据（"这一行该不该失效"）。
     *
     * <p>{@code null}（离线／还没装配）⇒ 用 {@link #DEFAULT} 的保守策略：
     * 只对"会读成死的"值失效，其余原样转发。
     */
    public interface Judge {

        /**
         * @param owner 接收者（实体／{@code SynchedEntityData}，可能为 {@code null}）
         * @param value 这一行要写进去的值（血量）
         * @param site  {@link #SITE_SET_HEALTH}／{@link #SITE_DATA2}／{@link #SITE_DATA3}
         * @return {@code true} = 这一行无效（不转发）
         */
        boolean drop(Object owner, float value, String site);
    }

    /** 默认判据：值会读成死的 ⇒ 失效；其余转发（一个 mod 名都没有）。 */
    public static final Judge DEFAULT = new Judge() {
        @Override
        public boolean drop(Object owner, float value, String site) {
            return needsDrop(value);
        }
    };

    /** 值会不会让"活着的实体"读成死的（{@code <=0 ／ NaN ／ ±∞}）。 */
    public static boolean needsDrop(float value) {
        return !(value > 0.0F) || Float.isInfinite(value);
    }

    private static volatile Judge judge;

    // ---- 读数（进 statusLine）
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong FORWARDED = new AtomicLong();
    private static final AtomicLong FORWARD_FAILED = new AtomicLong();
    private static final AtomicLong OVERRIDES = new AtomicLong();
    private static final AtomicLong SUPPRESSED = new AtomicLong();

    /** {@code site|target} ⇒ 上次打日志的时刻（1 行/秒）。 */
    private static final Map<String, Long> LOG_AT = new LinkedHashMap<>();

    /** 解析过的转发目标（{@code 类名#形状} ⇒ Method；拿不到也缓存一次，避免反复反射）。 */
    private static final Map<String, Method> FORWARDERS = new LinkedHashMap<>();

    private static volatile long lastLogMillis;

    private HotfixShield() {
        // 纯静态
    }

    /** 装判据（装配侧调；{@code null} = 回到 {@link #DEFAULT}）。 */
    public static void bindJudge(Judge j) {
        judge = j;
    }

    /** 装的是哪个判据（日志判读用）。 */
    public static String judgeToken() {
        return judge == null ? "default" : "guarded";
    }

    // ═══════════════════════════════ 三个调用点（被目标方法直接调） ═══════════════════════════════

    /**
     * {@code setHealth(float)} 那一条的替换目标。
     *
     * <p>⚠️ 它跑在<b>别人家的方法体</b>里 ⇒ 绝不许抛；任何异常都只记一行并<b>不写</b>。
     *
     * @param entity 接收者（{@code Object} ⇒ 任何实体类型都通过校验）
     * @param value  这一行要写的血量
     */
    public static void dropSetHealth(Object entity, float value) {
        try {
            if (!decide(entity, value, SITE_SET_HEALTH)) {
                forwardSetHealth(entity, value);
                return;
            }
            DROPPED.incrementAndGet();
            log(SITE_SET_HEALTH, entity, value);
        } catch (Throwable t) {
            FORWARD_FAILED.incrementAndGet();
        }
    }

    /**
     * {@code SynchedEntityData#set(Accessor, Object)} 那一条的替换目标（2 参）。
     *
     * @param synchedData {@code (Object) 那个 SynchedEntityData}
     * @param value       要写进同步字段的值
     */
    public static void dropHealthData(Object synchedData, Object key, Object value) {
        dropData(synchedData, key, value, false, false, SITE_DATA2);
    }

    /**
     * {@code SynchedEntityData#set(Accessor, Object, boolean)} 那一条的替换目标（3 参）。
     *
     * @param synchedData {@code (Object) 那个 SynchedEntityData}
     * @param value       要写进同步字段的值
     * @param force       原第三参
     */
    public static void dropHealthDataForce(Object synchedData, Object key, Object value, boolean force) {
        dropData(synchedData, key, value, force, true, SITE_DATA3);
    }

    private static void dropData(Object synchedData, Object key, Object value, boolean force,
                                 boolean threeArg, String site) {
        try {
            final float asFloat = value instanceof Float ? ((Float) value).floatValue() : Float.NaN;
            final Object owner = ownerOfSynchedData(synchedData);
            if (!decide(owner, asFloat, site)) {
                forwardDataWrite(synchedData, key, value, force, threeArg);
                return;
            }
            DROPPED.incrementAndGet();
            log(site, owner, asFloat);
        } catch (Throwable t) {
            FORWARD_FAILED.incrementAndGet();
        }
    }

    // ═══════════════════════════════ 判据 ═══════════════════════════════

    /** {@code true} = 这一行无效。 */
    private static boolean decide(Object owner, float value, String site) {
        if (!needsDrop(value)) {
            return false;      // 🔴 快路：无害值一个字节都不碰（正常回血照跑）
        }
        final Judge j = judge;
        if (j == null) {
            return DEFAULT.drop(owner, value, site);
        }
        try {
            final boolean drop = j.drop(owner, value, site);
            if (!drop) {
                OVERRIDES.incrementAndGet();
            }
            return drop;
        } catch (Throwable t) {
            // 判据坏了 ⇒ 落到保守策略（宁可不写，也不许把一次"写死"放过去）
            return DEFAULT.drop(owner, value, site);
        }
    }

    // ═══════════════════════════════ 转发（反射；不 import 任何 MC 类） ═══════════════════════════════

    /** 名字集合：开发名 ＋ SRG 名（两个都是原版的字面量，不是任何 mod 的名字）。 */
    private static final String[] SET_HEALTH_NAMES = {"setHealth", "m_21153_"};

    private static void forwardSetHealth(Object entity, float value) {
        if (entity == null) {
            return;
        }
        final Method m = forwarderSetHealth(entity.getClass());
        if (m == null) {
            FORWARD_FAILED.incrementAndGet();
            log("forward_unavailable", entity, value);
            return;
        }
        try {
            m.invoke(entity, Float.valueOf(value));
            FORWARDED.incrementAndGet();
        } catch (Throwable t) {
            FORWARD_FAILED.incrementAndGet();
            log("forward_failed", entity, value);
        }
    }

    private static void forwardDataWrite(Object synchedData, Object key, Object value, boolean force,
                                         boolean threeArg) {
        if (synchedData == null) {
            return;
        }
        final Method m = forwarderDataWrite(synchedData.getClass(), threeArg);
        if (m == null) {
            FORWARD_FAILED.incrementAndGet();
            log("forward_unavailable", null, Float.NaN);
            return;
        }
        try {
            if (threeArg) {
                m.invoke(synchedData, key, value, Boolean.valueOf(force));
            } else {
                m.invoke(synchedData, key, value);
            }
            FORWARDED.incrementAndGet();
        } catch (Throwable t) {
            FORWARD_FAILED.incrementAndGet();
            log("forward_failed", null, Float.NaN);
        }
    }

    /** {@code setHealth(float)} 的转发目标（按 {@code 类#setHealth} 缓存）。 */
    private static Method forwarderSetHealth(Class<?> cls) {
        final String key = cls.getName() + "#sh";
        synchronized (FORWARDERS) {
            if (FORWARDERS.containsKey(key)) {
                return FORWARDERS.get(key);
            }
            Method found = null;
            for (String name : SET_HEALTH_NAMES) {
                try {
                    final Method m = cls.getMethod(name, float.class);
                    if (m.getReturnType() == void.class) {
                        found = m;
                        break;
                    }
                } catch (Throwable ignored) {
                    // 试下一个名字
                }
            }
            cache(key, found);
            return found;
        }
    }

    /**
     * {@code SynchedEntityData#set} 的转发目标（按<b>形状</b>找，所以不依赖它的 SRG 方法名）。
     *
     * @param threeArg {@code true} = 3 参那个 {@code (Accessor, Object, boolean)}
     */
    private static Method forwarderDataWrite(Class<?> cls, boolean threeArg) {
        final String key = cls.getName() + (threeArg ? "#d3" : "#d2");
        synchronized (FORWARDERS) {
            if (FORWARDERS.containsKey(key)) {
                return FORWARDERS.get(key);
            }
            Method found = null;
            try {
                for (Method m : cls.getMethods()) {
                    if (m.getReturnType() != void.class) {
                        continue;
                    }
                    final Class<?>[] p = m.getParameterTypes();
                    if (p.length != (threeArg ? 3 : 2)) {
                        continue;
                    }
                    if (!"net.minecraft.network.syncher.EntityDataAccessor".equals(p[0].getName())) {
                        continue;
                    }
                    if (p[1] != Object.class) {
                        continue;
                    }
                    if (threeArg && p[2] != boolean.class) {
                        continue;
                    }
                    found = m;
                    break;
                }
            } catch (Throwable ignored) {
                found = null;
            }
            cache(key, found);
            return found;
        }
    }

    private static void cache(String key, Method m) {
        if (FORWARDERS.size() > 64) {
            FORWARDERS.clear();   // 极端情况下重置；绝不无限涨
        }
        FORWARDERS.put(key, m);
    }

    /** 这条同步数据属于谁（{@code SynchedEntityData.entity}，{@code private final}）。 */
    private static volatile java.lang.reflect.Field SYNCED_DATA_ENTITY;
    private static volatile boolean SYNCED_DATA_ENTITY_TRIED;

    private static Object ownerOfSynchedData(Object synchedData) {
        if (synchedData == null) {
            return null;
        }
        try {
            if (!SYNCED_DATA_ENTITY_TRIED) {
                synchronized (HotfixShield.class) {
                    if (!SYNCED_DATA_ENTITY_TRIED) {
                        SYNCED_DATA_ENTITY_TRIED = true;
                        Class<?> c = synchedData.getClass();
                        while (c != null && SYNCED_DATA_ENTITY == null) {
                            final String[] names = {"entity", "f_135344_"};
                            for (int i = 0; i < names.length; i++) {
                                try {
                                    final java.lang.reflect.Field f = c.getDeclaredField(names[i]);
                                    f.setAccessible(true);
                                    SYNCED_DATA_ENTITY = f;
                                    break;
                                } catch (Throwable ignored) {
                                    // 试下一个名字
                                }
                            }
                            if (SYNCED_DATA_ENTITY == null) {
                                c = c.getSuperclass();
                            }
                        }
                    }
                }
            }
            final java.lang.reflect.Field f = SYNCED_DATA_ENTITY;
            return f == null ? null : f.get(synchedData);
        } catch (Throwable t) {
            return null;
        }
    }

    // ═══════════════════════════════ 可解析性（插桩前必判） ═══════════════════════════════

    /**
     * 目标类的加载器能不能解析到本类（与 {@link HotfixProbeCounter#resolvableFrom} 同一套判据）。
     *
     * <p>解析不到就<b>不许插</b>：插进去的那条 {@code INVOKESTATIC} 会在运行期抛
     * {@code NoClassDefFoundError}，而且是在别人家的方法体里抛。
     *
     * @param loader 目标类的加载器（{@code null} = bootstrap ⇒ 判不可解析）
     */
    public static boolean resolvableFrom(ClassLoader loader) {
        if (loader == null) {
            return false;
        }
        try {
            return Class.forName(SELF_NAME, false, loader) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // ═══════════════════════════════ 读数与日志 ═══════════════════════════════

    /** 一行读数（进 {@code /shanhai_add hotfix status}）。 */
    public static String statusLine() {
        return "line_dropped=" + DROPPED.get()
                + " line_forwarded=" + FORWARDED.get()
                + " line_forward_failed=" + FORWARD_FAILED.get()
                + " line_judge_overrides=" + OVERRIDES.get()
                + " line_log_suppressed=" + SUPPRESSED.get()
                + " line_judge=" + judgeToken();
    }

    /** 这一行真的失效过几次（离线自检断言用）。 */
    public static long dropped() {
        return DROPPED.get();
    }

    /** 原样转发过几次。 */
    public static long forwarded() {
        return FORWARDED.get();
    }

    /** 转发失败过几次。 */
    public static long forwardFailed() {
        return FORWARD_FAILED.get();
    }

    /** 清零（离线自检每段之前调；生产不调）。 */
    public static void resetCounters() {
        DROPPED.set(0L);
        FORWARDED.set(0L);
        FORWARD_FAILED.set(0L);
        OVERRIDES.set(0L);
        SUPPRESSED.set(0L);
        synchronized (FORWARDERS) {
            FORWARDERS.clear();
        }
    }

    /**
     * 1 行/秒限流（对手 20/s 的循环里不许刷屏）。被压掉的次数进下一行的 {@code suppressed=}。
     */
    private static void log(String site, Object owner, float value) {
        final long now = System.currentTimeMillis();
        if (now - lastLogMillis < 1000L) {
            SUPPRESSED.incrementAndGet();
            return;
        }
        lastLogMillis = now;
        final long suppressed = SUPPRESSED.getAndSet(0L);
        final StringBuilder sb = new StringBuilder(128);
        sb.append("line_write site=").append(site)
                .append(" value=").append(fmt(value))
                .append(" owner=").append(owner == null ? "?" : HotfixAutoCounter.token(owner.getClass().getName()))
                .append(" dropped=").append(DROPPED.get())
                .append(" forwarded=").append(FORWARDED.get())
                .append(" forward_failed=").append(FORWARD_FAILED.get());
        if (suppressed > 0L) {
            sb.append(" suppressed=").append(suppressed);
        }
        HotfixAutoCounter.logShield(sb.toString());
    }

    private static String fmt(float v) {
        if (Float.isNaN(v)) {
            return "NaN";
        }
        if (Float.isInfinite(v)) {
            return v > 0 ? "Infinity" : "-Infinity";
        }
        return String.format(java.util.Locale.ROOT, "%.3f", Float.valueOf(v));
    }
}
