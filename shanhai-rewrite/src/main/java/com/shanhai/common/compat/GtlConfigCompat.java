package com.shanhai.common.compat;

import com.shanhai.ShanhaiMod;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 读 <b>gtlcore</b> 的 {@code org.gtlcore.gtlcore.config.ConfigHolder} 取值 —— 同一份 jar
 * <b>同时兼容【扁平字段】与【分组字段】两种布局</b>。
 *
 * <h2>为什么必须兼容两种布局（2026-11-07 实测，不是推测）</h2>
 * <pre>
 *   gtlcore ≤ 1.2.3.2-fix4：字段全在 ConfigHolder 顶层（扁平）
 *        ConfigHolder.INSTANCE.MEPatternOutputMin / batchProcessingTimeLimitTicks / durationMultiplier …
 *   gtlcore ≥ 1.2.3.2-fix5：同样的字段搬进 8 个内部类，顶层只剩组对象（分组）
 *        ConfigHolder.INSTANCE.machines.MEPatternOutputMin
 *        ConfigHolder.INSTANCE.machines.batchProcessingTimeLimitTicks
 *        ConfigHolder.INSTANCE.content.durationMultiplier
 * </pre>
 * 两种布局的 {@code javap -p -s} 证据在 {@code temp\prefix-audit\javap\fix5\} 与 {@code \cfg-new\}。
 * 只写死其中一种的话：<b>写死分组 ⇒ 旧核心编译失败；写死扁平 ⇒ 新核心编译失败</b>，
 * 而"该用哪一代核心"由 gtladditions 的字段布局决定（add 3.3.1 仍在读扁平字段），
 * 这个选择不在我们手里 ⇒ <b>只能两边都兼容</b>。
 *
 * <h2>读取顺序（固定的三段）</h2>
 * <ol>
 *   <li>{@code ConfigHolder.INSTANCE.<group>.<field>}（分组布局，如 {@code machines.MEPatternOutputMin}）；</li>
 *   <li>{@code ConfigHolder.INSTANCE.<field>}（扁平布局，如 {@code MEPatternOutputMin}）；</li>
 *   <li>两条都不通 ⇒ 打<b>一行 WARN</b>（带 {@code tried=[…]} 清单）并返回调用方给的默认值。
 *       <b>不许静默</b>：静默回默认值会让行为悄悄变，而日志里看不出发生过什么。</li>
 * </ol>
 *
 * <p>解析结果（含失败）按 {@code group.field} 缓存 —— 调用点里有每 tick / 每条配方的热路径，
 * 不允许反复反射。
 */
public final class GtlConfigCompat {

    private GtlConfigCompat() {
    }

    private static final String HOLDER = "org.gtlcore.gtlcore.config.ConfigHolder";

    /** 解析成功 ⇒ 真值；解析失败 ⇒ {@link #FAILED}。 */
    private static final Map<String, Object> CACHE = new ConcurrentHashMap<>();

    private static final Object FAILED = new Object();

    // ─────────────────────────── 对外 ───────────────────────────

    /**
     * 读 {@code machines} 组里的 int（分组布局）或顶层同名字段（扁平布局）。
     *
     * @param field 字段名，如 {@code "MEPatternOutputMin"}
     * @param def   两条布局都读不到时的默认值（会连同 WARN 一起打进日志）
     */
    public static int machineInt(String field, int def) {
        return asInt(resolve("machines", field, def), def);
    }

    /** 读 {@code content} 组里的 int（分组）或顶层同名字段（扁平）。 */
    public static int contentInt(String field, int def) {
        return asInt(resolve("content", field, def), def);
    }

    /** 读 {@code content} 组里的 double（分组）或顶层同名字段（扁平）。 */
    public static double contentDouble(String field, double def) {
        return asDouble(resolve("content", field, def), def);
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private static int asInt(Object v, int def) {
        return (v instanceof Number n) ? n.intValue() : def;
    }

    private static double asDouble(Object v, double def) {
        return (v instanceof Number n) ? n.doubleValue() : def;
    }

    private static Object resolve(String group, String field, Object defForLog) {
        final String key = (group == null ? "" : group + ".") + field;
        final Object cached = CACHE.get(key);
        if (cached != null) {
            return cached == FAILED ? null : cached;
        }
        final List<String> tried = new ArrayList<>();
        Object out = null;
        try {
            final Class<?> holder = Class.forName(HOLDER);
            final Object inst = holder.getField("INSTANCE").get(null);

            // ① 分组布局（gtlcore ≥ 1.2.3.2-fix5）
            if (group != null) {
                try {
                    final Object g = readField(holder, inst, group);
                    if (g != null) {
                        out = readField(g.getClass(), g, field);
                    }
                } catch (NoSuchFieldException nsf) {
                    tried.add("INSTANCE." + group + "." + field + "(grouped)");
                }
            }

            // ② 扁平布局（gtlcore ≤ 1.2.3.2-fix4）
            if (out == null) {
                try {
                    out = readField(holder, inst, field);
                } catch (NoSuchFieldException nsf) {
                    tried.add("INSTANCE." + field + "(flat)");
                }
            }
        } catch (Throwable t) {
            tried.add(HOLDER + "/INSTANCE ⇒ " + t);
        }

        if (out == null) {
            ShanhaiMod.LOGGER.warn("[SHANHAI-CFGCOMPAT] 读不到 gtlcore 配置项 {} ⇒ 用默认值 {}（tried={}）。"
                            + "布局口径：gtlcore ≤1.2.3.2-fix4 是顶层扁平字段、≥1.2.3.2-fix5 是分组字段"
                            + "（machines/content/…）；若上游再搬字段，改 com.shanhai.common.compat.GtlConfigCompat 即可。",
                    key, defForLog, tried);
            CACHE.put(key, FAILED);
            return null;
        }
        CACHE.put(key, out);
        return out;
    }

    /** 先 {@code getField}（public），失败再 {@code getDeclaredField}+{@code setAccessible}。 */
    private static Object readField(Class<?> owner, Object target, String name)
            throws NoSuchFieldException, IllegalAccessException {
        Field f;
        try {
            f = owner.getField(name);
        } catch (NoSuchFieldException nsf) {
            f = owner.getDeclaredField(name);
            f.setAccessible(true);
        }
        return f.get(target);
    }
}
