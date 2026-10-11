package com.shanhai.client.holo;

import com.shanhai.ShanhaiMod;

import java.util.Locale;

/**
 * 山海重构 · 悬浮全息菜单的<b>显示设置持久化</b>（2026-10-07 用户点单：「全存」）。
 *
 * <h2>1. 存什么（21 项 = 4 + 1 + 16）</h2>
 * <pre>
 *   菜单状态 4 项：开合开关 / 朝向档 / 排布档 / 距离
 *   环绕档位 1 项：关 / 弱 / 中 / 强（四档，整组替换 16 项取值）
 *   环绕取值 16 项：面板「高级调参」那 4 页（4 / 4 / 4 / 4）
 * </pre>
 * ⚠️ 任务书里写的是「高级页 14 项」——实际是 15 项（第 15 项「跟随延迟」后加），
 * 🆕 2026-10-07 又加了第 16 项「覆盖范围」
 * （{@link ShanhaiHoloSurroundTuning#P_FIELD_COVER}）⇒ 现在是
 * {@link ShanhaiHoloSurroundTuning#PARAM_COUNT} = <b>16</b>，本类按 <b>16</b> 项存。
 * <p>落盘项数（{@code auditLine()} 里那个 {@code checked}）= 4 + 1 + 16 = <b>21</b>
 * （它是 {@code 5 + PARAM_COUNT} 算出来的，不是手抄的 ⇒ 加项时自动跟上）。
 *
 * <h2>2. 🔴 单一真相源：本类选的是【load-once + 改值即落盘】（write-through）</h2>
 * <pre>
 *   持久副本：配置文件 config/shanhai-client.toml 的 [holo_menu] 一节 —— 全工程唯一一份。
 *   运行副本：ShanhaiHoloMenuState 的 4 个字段 + ShanhaiHoloSurroundTuning 的 LIVE[15]/preset
 *            （就是面板与渲染器每帧读的那几个字段，本轮【一个都没有搬家】）。
 *   同步口径：
 *     · 进世界那一刻 loadOnce()   ：配置 ⇒ 运行副本（只此一次，见 ensureLoaded）
 *     · 任何一个 setter 改完值     ：运行副本 ⇒ 配置 + CLIENT_SPEC.save()（立刻落盘）
 *   ⇒ 两边任何时刻都相等，不存在"两处各存一份、各自漂移"。
 * </pre>
 *
 * <h2>3. 🔴 默认值 = 加持久化之前的那些常量，逐项（回归红线）</h2>
 * 本类的 {@code DEFAULT_*} / {@link #defaultOf(int)} <b>不是新拍的数</b>，而是把既有常量接过来：
 * <pre>
 *   开关默认  = false（{@code ShanhaiHoloMenuState.enabled} 原来的字段默认值）
 *   朝向默认  = {@link ShanhaiHoloMenuState.Facing#FIXED}（2026-10-06 改过的那个默认档）
 *              ⚠️ 任务书举例写的是「朝向默认 follow」——与今天的代码不符：今天是 FIXED。
 *                 本类按【代码】取 FIXED（红线④ = 与今天的现状逐项相等）。
 *   排布默认  = {@link ShanhaiHoloMenuState.Layout#RING}
 *   距离默认  = {@link ShanhaiHoloMenuTuning#DISTANCE_BLOCKS} = 2.8
 *   环绕默认档 = {@link ShanhaiHoloSurroundTuning#PRESET_DEFAULT} = 中
 *   15 项默认 = {@link ShanhaiHoloSurroundTuning#defaultOf(int)}（= 「中」档那一行）
 * </pre>
 * <b>配置缺失 / 读失败 / 值越界 / 枚举名不认识 ⇒ 一律回上面这些默认值</b>
 * （外加一行 WARN）⇒ 行为与加持久化之前<b>逐项相等</b>。
 *
 * <h2>4. 为什么把落盘做成接口（{@link Store}）</h2>
 * 本类<b>不 import 任何 Forge / Minecraft 类</b>，Forge 那一侧（读 {@code ForgeConfigSpec}、
 * 调 {@code save()}）由 {@code ShanhaiClientConfig} 实现 {@link Store} 后 {@link #useStore} 注入。
 * 好处是这一整套"默认值 / 范围 / 回退 / 往返"的判据可以<b>离线跑</b>
 * （探针 {@code temp/holo-persist/probe/PersistProbe.java}，用内存假 Store 驱动同一批生产函数）。
 *
 * <h2>5. ⚠️ 一处【刻意】不落盘的入口</h2>
 * 「模块从玩家身上消失 ⇒ 自动关投影」（{@code ShanhaiHoloMenuInput} 每 tick 那条）走的是
 * {@link ShanhaiHoloMenuState#setEnabledTransient(boolean)}：它是<b>瞬时条件</b>不是用户意图，
 * 写盘会把用户"我开着"的偏好悄悄抹掉。其余所有入口一律落盘。
 */
public final class ShanhaiHoloPersist {

    /** 本类所有日志行的前缀（便于 grep）。 */
    public static final String PREFIX = "[SHANHAI-HOLO-PERSIST]";

    // ==================================================================== 一、键名（= toml 里的路径，逐字）

    /** {@code [holo_menu] enabled}。 */
    public static final String K_ENABLED = "holo_menu.enabled";
    /** {@code [holo_menu] facing}。 */
    public static final String K_FACING = "holo_menu.facing";
    /** {@code [holo_menu] layout}。 */
    public static final String K_LAYOUT = "holo_menu.layout";
    /** {@code [holo_menu] distance}。 */
    public static final String K_DISTANCE = "holo_menu.distance";
    /** {@code [holo_menu.surround] preset}。 */
    public static final String K_PRESET = "holo_menu.surround.preset";

    /** 16 项取值的键名前缀（后接 {@link #PARAM_KEYS}）。 */
    public static final String PARAM_PREFIX = "holo_menu.surround.";

    /**
     * 16 项取值的键名（顺序 = {@link ShanhaiHoloSurroundTuning} 的下标顺序，不许换位）。
     *
     * <p>🔴 长度<b>必须</b>等于 {@code ShanhaiHoloSurroundTuning.PARAM_COUNT}：
     * {@code paramKey(idx)} / {@code describe()} / {@code ShanhaiClientConfig} 那个
     * {@code for (i < PARAM_COUNT)} 的定义循环全都按下标取它 ⇒ 少一格就是
     * {@code ArrayIndexOutOfBoundsException}（进世界那一刻）。
     * 联机线格式那一份（{@code ShanhaiHoloRemoteCodec.PARAM_COUNT}）也断言等于本数组长度，
     * 判据在 {@code ShanhaiHoloRemoteView#selfCheck}。
     */
    public static final String[] PARAM_KEYS = {
            "ringCount", "ringScale", "ringBright", "ringTickDensity", "ringFlowScale",
            "ringPrecessScale", "fieldPointCount", "fieldInnerRadius", "fieldOuterRadius",
            "fieldLinkThreshold", "fieldLinkMaxPerPoint", "fieldLinkPeriod", "fieldBright",
            "globalBright", "fieldFollowTau",
            // 🆕 2026-10-07：第 16 项「覆盖范围」（0 环带 / 1 半球 / 2 全球）
            "fieldCover"
    };

    // ==================================================================== 二、默认值（不是新拍的数，见类注释 §3）

    /** 开关默认 = false（= 加持久化之前 {@code enabled} 字段的默认值）。 */
    public static final boolean DEFAULT_ENABLED = false;

    /** 朝向档默认 = 世界固定（= 今天的代码；⚠️ 不是任务书举例里的 follow）。 */
    public static final ShanhaiHoloMenuState.Facing DEFAULT_FACING = ShanhaiHoloMenuState.Facing.FIXED;

    /** 排布档默认 = 环绕。 */
    public static final ShanhaiHoloMenuState.Layout DEFAULT_LAYOUT = ShanhaiHoloMenuState.Layout.RING;

    /** 距离默认（格）= {@link ShanhaiHoloMenuTuning#DISTANCE_BLOCKS}。 */
    public static final float DEFAULT_DISTANCE = ShanhaiHoloMenuTuning.DISTANCE_BLOCKS;

    /**
     * 距离的可调下限 / 上限（格）—— <b>唯一一份</b>。
     * 三处读它：{@code ShanhaiHoloMenuState.setDistance} 的夹取、{@code /shanhai menu distance} 的
     * 参数区间、以及 {@code shanhai-client.toml} 里这一项的 {@code defineInRange}。
     */
    public static final float DISTANCE_MIN = 0.8f;
    /** 见 {@link #DISTANCE_MIN}。 */
    public static final float DISTANCE_MAX = 8.0f;

    /** 环绕档位的枚举名（toml 里存的是名字，不是序号 —— 改序号不会悄悄串档）。 */
    public static final String[] PRESET_NAMES = presetNames();

    /**
     * 环绕档位的枚举（<b>toml 里存名字</b>）。
     *
     * <p>🔴 它的 {@code ordinal} <b>必须</b>与 {@link ShanhaiHoloSurroundTuning} 的
     * {@code PRESET_OFF/WEAK/MID/STRONG}（= 0/1/2/3）逐项对应 —— 这是"名字 ↔ 档位号"的唯一桥，
     * 离线探针里有一条判据专门盯它。
     */
    public enum Preset {
        OFF,
        WEAK,
        MID,
        STRONG
    }

    private static String[] presetNames() {
        final Preset[] v = Preset.values();
        final String[] out = new String[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = v[i].name();
        }
        return out;
    }

    /** 第 idx 项的键名；越界返回 {@code ""}。 */
    public static String paramKey(int idx) {
        return idx < 0 || idx >= ShanhaiHoloSurroundTuning.PARAM_COUNT ? "" : PARAM_PREFIX + PARAM_KEYS[idx];
    }

    /** 第 idx 项的出厂默认值（= 「中」档那一行的值）。 */
    public static float defaultOf(int idx) {
        return ShanhaiHoloSurroundTuning.defaultOf(idx);
    }

    /** 第 idx 项在 toml 里是整数键还是小数键（照面板显示的小数位：0 位 ⇒ 整数键）。 */
    public static boolean paramIsInt(int idx) {
        return idx >= 0 && idx < ShanhaiHoloSurroundTuning.PARAM_COUNT
                && ShanhaiHoloSurroundTuning.PARAM_DECIMALS[idx] == 0;
    }

    /** 第 idx 项的安全下限（= 面板 ± 的夹取下限，同一份表）。 */
    public static float paramMin(int idx) {
        return idx < 0 || idx >= ShanhaiHoloSurroundTuning.PARAM_COUNT
                ? 0.0f : ShanhaiHoloSurroundTuning.PARAM_MIN[idx];
    }

    /** 第 idx 项的安全上限（= 面板 ± 的夹取上限，同一份表）。 */
    public static float paramMax(int idx) {
        return idx < 0 || idx >= ShanhaiHoloSurroundTuning.PARAM_COUNT
                ? 0.0f : ShanhaiHoloSurroundTuning.PARAM_MAX[idx];
    }

    // ==================================================================== 三、落盘出口（Forge 侧实现）

    /**
     * 配置读写的<b>唯一出口</b>。生产实现 = {@code ShanhaiClientConfig.HoloStore}
     * （读 {@code ForgeConfigSpec} 的 20 个键，写完之后调 {@code CLIENT_SPEC.save()}）。
     *
     * <p>每一个 {@code getXxx} 都必须带回退值语义：<b>读不到 / 没加载 ⇒ 返回传入的 {@code def}</b>。
     * 越界与否由本类判断（见 {@link #loadOnce}），这一层不许自作主张改数。
     */
    public interface Store {

        /** {@code ForgeConfigSpec.isLoaded()}：配置没加载时一律不读也不写。 */
        boolean loaded();

        boolean getBool(String key, boolean def);

        double getDouble(String key, double def);

        int getInt(String key, int def);

        /** 枚举按<b>名字</b>读（{@code null} = 键不在 / 不是字符串）。 */
        String getEnumName(String key);

        void setBool(String key, boolean value);

        void setDouble(String key, double value);

        void setInt(String key, int value);

        void setEnumName(String key, String value);

        /** {@code ForgeConfigSpec.save()} —— 真的把内存里的值写进 toml。 */
        boolean save();
    }

    private static Store store;
    private static boolean loadedOnce;
    private static int warnBudget = 8;

    private ShanhaiHoloPersist() {}

    /** 注入落盘出口（由 {@code ShanhaiClientConfig} 的静态块调一次）。 */
    public static void useStore(Store impl) {
        store = impl;
        loadedOnce = false;
        warnBudget = 8;
    }

    /** 有没有接上落盘出口（离线探针里是 true，游戏里装完配置也是 true）。 */
    public static boolean hasStore() {
        return store != null;
    }

    /** 这一次能不能落盘：出口在 + 配置已加载。 */
    public static boolean ready() {
        final Store s = store;
        if (s == null) {
            return false;
        }
        try {
            return s.loaded();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 一行 WARN（限次，避免每帧刷屏）。 */
    private static void warnOnce(String what, Object detail) {
        if (warnBudget <= 0) {
            return;
        }
        warnBudget--;
        ShanhaiMod.LOGGER.warn("{} {} detail={} remaining_warns={}", PREFIX, what, detail, warnBudget);
    }

    // ==================================================================== 四、读：配置 ⇒ 运行副本（只此一次）

    /**
     * 进世界那一刻读一次配置（幂等：同一个进程只真读一次）。
     *
     * <p>⚠️ 配置没加载 / 没接出口 ⇒ <b>什么都不做</b>：运行副本保持它自己的字段初值
     * = {@link #DEFAULT_ENABLED} / {@link #DEFAULT_FACING} / {@link #DEFAULT_LAYOUT} /
     * {@link #DEFAULT_DISTANCE} / 中档 15 项 ⇒ 与加持久化之前逐项相同。
     */
    public static void ensureLoaded() {
        if (loadedOnce) {
            return;
        }
        if (!ready()) {
            warnOnce("load_skipped", "config_not_loaded (运行副本保持出厂默认)");
            return;
        }
        loadedOnce = true;
        loadOnce();
    }

    /** 离线探针用：把"已读一次"的标志清掉，好让同一个 JVM 里反复跑读路径。 */
    public static void resetLoadedFlagForTest() {
        loadedOnce = false;
    }

    /**
     * 真的读一遍并写进运行副本。
     *
     * <p>每一项的判据都是<b>同一条</b>：值合法 ⇒ 用它；不合法（越界 / NaN / 枚举名不认识）⇒
     * 用默认值 + 一行 WARN。
     */
    static void loadOnce() {
        final Store s = store;
        if (s == null) {
            return;
        }
        try {
            // ---- 菜单 4 项 ----
            final boolean enabled = boolOf(s, K_ENABLED, DEFAULT_ENABLED);
            final ShanhaiHoloMenuState.Facing facing =
                    facingOf(s.getEnumName(K_FACING), DEFAULT_FACING);
            final ShanhaiHoloMenuState.Layout layout =
                    layoutOf(s.getEnumName(K_LAYOUT), DEFAULT_LAYOUT);
            final float distance = (float) ranged(s, K_DISTANCE, DEFAULT_DISTANCE, DISTANCE_MIN, DISTANCE_MAX);
            ShanhaiHoloMenuState.applyPersisted(enabled, facing, layout, distance);

            // ---- 环绕档位 ----
            final int preset = presetOf(s.getEnumName(K_PRESET));

            // ---- 环绕 15 项 ----
            final float[] vals = new float[ShanhaiHoloSurroundTuning.PARAM_COUNT];
            for (int i = 0; i < vals.length; i++) {
                final String key = paramKey(i);
                final float def = defaultOf(i);
                final double raw = paramIsInt(i) ? s.getInt(key, Math.round(def)) : s.getDouble(key, def);
                final float v = (float) raw;
                if (!Float.isFinite(v) || v < paramMin(i) || v > paramMax(i)) {
                    warnOnce("param_out_of_range", key + "=" + raw + " -> default " + def);
                    vals[i] = def;
                } else {
                    vals[i] = v;
                }
            }
            ShanhaiHoloSurroundTuning.applyPersisted(preset, vals);
            ShanhaiMod.LOGGER.info("{} loaded {} | {}", PREFIX, describe(), auditLine());
        } catch (Throwable t) {
            // 读挂了也【绝不】把运行副本改成别的值：它还是出厂默认 ⇒ 行为与加持久化之前一样
            loadedOnce = false;
            ShanhaiMod.LOGGER.error("{} load_failed err={} (运行副本保持出厂默认)", PREFIX, t.toString(), t);
        }
    }

    // ---- 取值 + 合法性判据（读路径的全部算术都在这几个函数里，离线探针直接驱动它们）----

    private static boolean boolOf(Store s, String key, boolean def) {
        try {
            return s.getBool(key, def);
        } catch (Throwable t) {
            warnOnce("bool_read_failed", key);
            return def;
        }
    }

    /** 带范围的读数：非有限 / 越界 ⇒ 默认 + WARN（任务书 ⑤：非法值 ⇒ 回默认）。 */
    static double ranged(Store s, String key, double def, double min, double max) {
        double v;
        try {
            v = s.getDouble(key, def);
        } catch (Throwable t) {
            warnOnce("double_read_failed", key);
            return def;
        }
        if (Double.isNaN(v) || Double.isInfinite(v) || v < min || v > max) {
            warnOnce("out_of_range", key + "=" + v + " -> default " + def);
            return def;
        }
        return v;
    }

    /** 枚举名 ⇒ 朝向档；不认识 ⇒ 默认 + WARN。 */
    static ShanhaiHoloMenuState.Facing facingOf(String name, ShanhaiHoloMenuState.Facing def) {
        if (name != null) {
            for (ShanhaiHoloMenuState.Facing f : ShanhaiHoloMenuState.Facing.values()) {
                if (f.name().equalsIgnoreCase(name.trim())) {
                    return f;
                }
            }
            warnOnce("facing_unknown", name + " -> default " + def);
        }
        return def;
    }

    /** 枚举名 ⇒ 排布档；不认识 ⇒ 默认 + WARN。 */
    static ShanhaiHoloMenuState.Layout layoutOf(String name, ShanhaiHoloMenuState.Layout def) {
        if (name != null) {
            for (ShanhaiHoloMenuState.Layout l : ShanhaiHoloMenuState.Layout.values()) {
                if (l.name().equalsIgnoreCase(name.trim())) {
                    return l;
                }
            }
            warnOnce("layout_unknown", name + " -> default " + def);
        }
        return def;
    }

    /** 枚举名 ⇒ 环绕档位号；不认识 / 越界 ⇒ {@link ShanhaiHoloSurroundTuning#PRESET_DEFAULT} + WARN。 */
    static int presetOf(String name) {
        final int i = presetIndexOf(name);
        if (i >= 0) {
            return i;
        }
        if (name != null) {
            warnOnce("preset_unknown", name + " -> default " + PRESET_NAMES[ShanhaiHoloSurroundTuning.PRESET_DEFAULT]);
        }
        return ShanhaiHoloSurroundTuning.PRESET_DEFAULT;
    }

    /** 枚举名 ⇒ 档位号（0..3）；不认识返回 {@code -1}。大小写不敏感。 */
    public static int presetIndexOf(String name) {
        if (name == null) {
            return -1;
        }
        final String t = name.trim();
        for (int i = 0; i < PRESET_NAMES.length; i++) {
            if (PRESET_NAMES[i].equalsIgnoreCase(t)) {
                return i;
            }
        }
        return -1;
    }

    // ==================================================================== 五、写：运行副本 ⇒ 配置（每个入口改完值都走这里）

    /**
     * 把菜单那 4 项写进配置并落盘。任何 setter 改完值都调它。
     *
     * @return true = 真的写进文件了；false = 没接出口 / 配置没加载（调用方不必报错，运行副本照旧生效）
     */
    public static boolean saveMenu() {
        return write(true, false);
    }

    /** 把环绕档位 + 15 项写进配置并落盘。 */
    public static boolean saveSurround() {
        return write(false, true);
    }

    /** 菜单 + 环绕一起写。 */
    public static boolean saveAll() {
        return write(true, true);
    }

    private static boolean write(boolean menu, boolean surround) {
        final Store s = store;
        if (s == null || !ready()) {
            warnOnce("save_skipped", "config_not_loaded menu=" + menu + " surround=" + surround);
            return false;
        }
        try {
            if (menu) {
                s.setBool(K_ENABLED, ShanhaiHoloMenuState.enabledRaw());
                s.setEnumName(K_FACING, ShanhaiHoloMenuState.facingRaw().name());
                s.setEnumName(K_LAYOUT, ShanhaiHoloMenuState.layoutRaw().name());
                s.setDouble(K_DISTANCE, ShanhaiHoloMenuState.distanceRaw());
            }
            if (surround) {
                s.setEnumName(K_PRESET, PRESET_NAMES[ShanhaiHoloSurroundTuning.preset()]);
                for (int i = 0; i < ShanhaiHoloSurroundTuning.PARAM_COUNT; i++) {
                    final float v = ShanhaiHoloSurroundTuning.param(i);
                    if (paramIsInt(i)) {
                        s.setInt(paramKey(i), Math.round(v));
                    } else {
                        s.setDouble(paramKey(i), v);
                    }
                }
            }
            final boolean ok = s.save();
            if (!ok) {
                ShanhaiMod.LOGGER.warn("{} save_returned_false menu={} surround={}", PREFIX, menu, surround);
            }
            return ok;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} save_failed menu={} surround={} err={}", PREFIX, menu, surround,
                    t.toString(), t);
            return false;
        }
    }

    // ==================================================================== 六、读数 / 自检（机器可判）

    /** 一行读数：运行副本里现在是什么（与 {@code /shanhai menu dump} 同一套字段）。 */
    public static String describe() {
        final StringBuilder sb = new StringBuilder(160);
        sb.append("enabled=").append(ShanhaiHoloMenuState.enabledRaw())
                .append(" facing=").append(ShanhaiHoloMenuState.facingRaw().name().toLowerCase(Locale.ROOT))
                .append(" layout=").append(ShanhaiHoloMenuState.layoutRaw().name().toLowerCase(Locale.ROOT))
                .append(" distance=").append(ShanhaiHoloMenuState.distanceRaw())
                .append(" preset=").append(PRESET_NAMES[ShanhaiHoloSurroundTuning.preset()]);
        for (int i = 0; i < ShanhaiHoloSurroundTuning.PARAM_COUNT; i++) {
            sb.append(' ').append(PARAM_KEYS[i]).append('=').append(ShanhaiHoloSurroundTuning.param(i));
        }
        return sb.toString();
    }

    /**
     * 🔴 <b>单一真相源的机器判据</b>：逐项比对"运行副本"与"配置里现在写着的值"。
     *
     * <p>两边任何一项不等 ⇒ 说明有人绕过了 setter 直接改字段（或者配置被外部改了），
     * 本行会点名是哪一项。返回 {@code "audit_ok=true"} 或 {@code "audit_ok=false bad=..."}。
     */
    public static String auditLine() {
        final Store s = store;
        if (s == null) {
            return "audit_ok=unknown reason=no_store";
        }
        if (!ready()) {
            return "audit_ok=unknown reason=config_not_loaded";
        }
        final StringBuilder bad = new StringBuilder();
        int checked = 0;
        try {
            checked++;
            cmp(bad, K_ENABLED, ShanhaiHoloMenuState.enabledRaw(), s.getBool(K_ENABLED, DEFAULT_ENABLED));
            checked++;
            cmp(bad, K_FACING, ShanhaiHoloMenuState.facingRaw().name(), s.getEnumName(K_FACING));
            checked++;
            cmp(bad, K_LAYOUT, ShanhaiHoloMenuState.layoutRaw().name(), s.getEnumName(K_LAYOUT));
            checked++;
            cmp(bad, K_DISTANCE, ShanhaiHoloMenuState.distanceRaw(), s.getDouble(K_DISTANCE, DEFAULT_DISTANCE));
            checked++;
            cmp(bad, K_PRESET, PRESET_NAMES[ShanhaiHoloSurroundTuning.preset()], s.getEnumName(K_PRESET));
            for (int i = 0; i < ShanhaiHoloSurroundTuning.PARAM_COUNT; i++) {
                checked++;
                final double cfg = paramIsInt(i)
                        ? s.getInt(paramKey(i), Math.round(defaultOf(i)))
                        : s.getDouble(paramKey(i), defaultOf(i));
                cmp(bad, paramKey(i), ShanhaiHoloSurroundTuning.param(i), cfg);
            }
        } catch (Throwable t) {
            return "audit_ok=unknown err=" + t;
        }
        return bad.length() == 0
                ? "audit_ok=true checked=" + checked
                : "audit_ok=false checked=" + checked + " bad=[" + bad + "]";
    }

    private static void cmp(StringBuilder bad, String key, Object runtime, Object config) {
        final boolean eq;
        if (runtime instanceof Number rn && config instanceof Number cn) {
            // 🔴 必须按 float 比，不能按 double 比 —— 实测踩过：
            //    运行副本里是 float 1.35f（= 1.3499999642372131），toml 里是 double 1.35
            //    ⇒ Double.compare 判它们不等 ⇒ audit 报假红（"检查器自己的假设错了"那一类）。
            //    运行副本里所有数都是 float，所以把两边都压成 float 才是同一个口径。
            eq = Float.compare(rn.floatValue(), cn.floatValue()) == 0;
        } else {
            eq = String.valueOf(runtime).equals(String.valueOf(config));
        }
        if (!eq) {
            if (bad.length() > 0) {
                bad.append(',');
            }
            bad.append(key).append(": runtime=").append(runtime).append(" config=").append(config);
        }
    }

    /**
     * 自检那几行（进世界时由 {@code ShanhaiHoloMenuClient} 打进日志）：
     * <b>不看运行副本，只看"默认值表 + 落盘出口"本身</b>，所以离线也能跑同一份。
     */
    public static String selfCheckLine() {
        final StringBuilder sb = new StringBuilder(256);
        sb.append("persist_selfcheck store=").append(hasStore())
                .append(" loaded=").append(ready())
                .append(" default{enabled=").append(DEFAULT_ENABLED)
                .append(" facing=").append(DEFAULT_FACING.name())
                .append(" layout=").append(DEFAULT_LAYOUT.name())
                .append(" distance=").append(DEFAULT_DISTANCE)
                .append(" preset=").append(PRESET_NAMES[ShanhaiHoloSurroundTuning.PRESET_DEFAULT])
                .append(" params=").append(ShanhaiHoloSurroundTuning.PARAM_COUNT).append('}');
        return sb.toString();
    }
}
