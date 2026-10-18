package com.shanhai.common.guard;

import com.shanhai.ShanhaiMod;
import com.shanhai.config.ShanhaiConfig;

/**
 * 山海 · 创始现实修改模块「三件功能」的<b>三个开关</b>（<b>唯一一份读取口径</b>）。
 *
 * <h2>存储 = 现有设置那一套（不另起一套）</h2>
 * 三个键住在 {@code config/shanhai-common.toml} 的 {@code [guard_module]} 一节里
 * （{@link ShanhaiConfig.ConfigValues#guardAiLayer} 等），与全息设置面板里那一格
 * {@code recipe_editor.ui} <b>同一个 spec、同一个 save()</b>。
 *
 * <p>为什么必须是 COMMON 而不是 CLIENT：AI 层与攻击端<b>跑在服务端</b>
 * （{@code LivingChangeTargetEvent} / {@code LivingAttackEvent} / {@code ServerTickEvent}），
 * 专用服务端也要读到同一个值 ⇒ 只能放两侧都加载的 COMMON 配置。
 *
 * <h2>🔴 三个默认值都是用户拍板的</h2>
 * <pre>
 *   AI 层     默认 <b>关</b>（新功能；没开的时候一个字节都不改变世界）
 *   攻击端    默认 <b>开</b>
 *   死而无损  默认 <b>开</b>
 *   🆕 反击退 默认 <b>开</b>（2026-10-17 加：用户原话「我还需要反击退（包括碰撞箱挤压我），这个猫把我扔来扔去的」）
 * </pre>
 *
 * <h2>读法的两条纪律</h2>
 * <ol>
 *   <li><b>配置没加载 ⇒ 退回默认值</b>（{@code ConfigValue#get()} 在未绑定时会抛
 *       {@code IllegalStateException}；世界加载早期的 tick 与开发环境重载窗口都可能撞上）；</li>
 *   <li><b>一次动作只读一次</b>：调用点先把值取进局部变量再判，不在循环里反复 {@code get()}
 *       （读一个 volatile 布尔本身很便宜，但"读到一半变了"会让日志出现自相矛盾的读数）。</li>
 * </ol>
 */
public final class ShanhaiGuardSwitches {

    private ShanhaiGuardSwitches() {}

    /** 本类所有日志行的前缀（与守卫同一套）。 */
    public static final String PREFIX = ShanhaiGuardCore.LOG_PREFIX;

    /** AI 层默认值：<b>关</b>（用户口径）。 */
    public static final boolean DEFAULT_AI_LAYER = false;

    /** 攻击端默认值：<b>开</b>（用户口径）。 */
    public static final boolean DEFAULT_ATTACK_SIDE = true;

    /** 死而无损默认值：<b>开</b>（用户口径）。 */
    public static final boolean DEFAULT_DEATH_LOSSLESS = true;

    /**
     * 🆕 反击退默认值：<b>开</b>（用户口径：「我还需要反击退（包括碰撞箱挤压我），这个猫把我扔来扔去的」）。
     */
    public static final boolean DEFAULT_KNOCKBACK = ShanhaiGuardKnockback.DEFAULT_MASTER;

    /** 🆕 反击退左下角播报默认值：<b>关</b>（用户口径「默认不播，避免刷屏」）。 */
    public static final boolean DEFAULT_KNOCKBACK_BROADCAST = ShanhaiGuardKnockback.DEFAULT_BROADCAST;

    /** 配置里的键名（与 {@code ShanhaiConfig} 里 {@code .define("ai_layer", …)} 的那个串一致）。 */
    public static final String KEY_AI_LAYER = "guard_module.ai_layer";
    /** 见 {@link #KEY_AI_LAYER}。 */
    public static final String KEY_ATTACK_SIDE = "guard_module.attack_side";
    /** 见 {@link #KEY_AI_LAYER}。 */
    public static final String KEY_DEATH_LOSSLESS = "guard_module.death_lossless";
    /** 🆕 见 {@link #KEY_AI_LAYER}。 */
    public static final String KEY_KNOCKBACK = ShanhaiGuardKnockback.KEY_MASTER;
    /** 🆕 见 {@link #KEY_AI_LAYER}。 */
    public static final String KEY_KNOCKBACK_BROADCAST = ShanhaiGuardKnockback.KEY_BROADCAST;

    // ═══════════════════════════════ 读 ═══════════════════════════════

    /** AI 层开关现在是不是开着（配置没加载 ⇒ {@link #DEFAULT_AI_LAYER}）。 */
    public static boolean aiLayer() {
        if (!ShanhaiConfig.isLoaded()) {
            return DEFAULT_AI_LAYER;
        }
        try {
            return ShanhaiConfig.COMMON.guardAiLayer.get();
        } catch (Throwable t) {
            warnRead(KEY_AI_LAYER, t);
            return DEFAULT_AI_LAYER;
        }
    }

    /** 攻击端开关现在是不是开着（配置没加载 ⇒ {@link #DEFAULT_ATTACK_SIDE}）。 */
    public static boolean attackSide() {
        if (!ShanhaiConfig.isLoaded()) {
            return DEFAULT_ATTACK_SIDE;
        }
        try {
            return ShanhaiConfig.COMMON.guardAttackSide.get();
        } catch (Throwable t) {
            warnRead(KEY_ATTACK_SIDE, t);
            return DEFAULT_ATTACK_SIDE;
        }
    }

    /** 死而无损开关现在是不是开着（配置没加载 ⇒ {@link #DEFAULT_DEATH_LOSSLESS}）。 */
    public static boolean deathLossless() {
        if (!ShanhaiConfig.isLoaded()) {
            return DEFAULT_DEATH_LOSSLESS;
        }
        try {
            return ShanhaiConfig.COMMON.guardDeathLossless.get();
        } catch (Throwable t) {
            warnRead(KEY_DEATH_LOSSLESS, t);
            return DEFAULT_DEATH_LOSSLESS;
        }
    }

    /**
     * 🆕 反击退开关现在是不是开着（配置没加载 ⇒ {@link #DEFAULT_KNOCKBACK} = <b>开</b>）。
     *
     * <p>关掉之后：{@code hurtMarked} 一个字节都不碰、{@code deltaMovement} 一个字节都不碰
     * （判据在 {@link ShanhaiGuardKnockback#hurtMarkedDecision} 与
     * {@link ShanhaiGuardKnockback#brakeDecision} 的第一条）。
     */
    public static boolean knockbackGuard() {
        if (!ShanhaiConfig.isLoaded()) {
            return DEFAULT_KNOCKBACK;
        }
        try {
            return ShanhaiConfig.COMMON.guardKnockback.get();
        } catch (Throwable t) {
            warnRead(KEY_KNOCKBACK, t);
            return DEFAULT_KNOCKBACK;
        }
    }

    /**
     * 🆕 反击退的左下角播报开着吗（配置没加载 ⇒ {@link #DEFAULT_KNOCKBACK_BROADCAST} = <b>关</b>）。
     *
     * <p>⚠️ 这一项<b>不是</b>功能开关：它只决定"要不要在左下角说一句"，主开关是 {@link #knockbackGuard()}。
     */
    public static boolean knockbackBroadcast() {
        if (!ShanhaiConfig.isLoaded()) {
            return DEFAULT_KNOCKBACK_BROADCAST;
        }
        try {
            return ShanhaiConfig.COMMON.guardKnockbackBroadcast.get();
        } catch (Throwable t) {
            warnRead(KEY_KNOCKBACK_BROADCAST, t);
            return DEFAULT_KNOCKBACK_BROADCAST;
        }
    }

    // ═══════════════════════════════ 写 ═══════════════════════════════

    /** 四个开关的机器名（命令参数用；{@code /shanhai guard ai|attack|death|knockback on|off}）。 */
    public enum Switch {

        /** AI 层。 */
        AI("ai", "AI 层", DEFAULT_AI_LAYER),
        /** 攻击端。 */
        ATTACK("attack", "攻击端", DEFAULT_ATTACK_SIDE),
        /** 死而无损。 */
        DEATH("death", "死而无损", DEFAULT_DEATH_LOSSLESS),
        /** 🆕 反击退 / 反挤压（{@code /shanhai guard knockback on|off}）。 */
        KNOCKBACK("knockback", "反击退", DEFAULT_KNOCKBACK);

        /** 命令里那个词。 */
        public final String arg;
        /** 人话名（日志/聊天栏）。 */
        public final String zh;
        /** 出厂默认值。 */
        public final boolean def;

        Switch(String arg, String zh, boolean def) {
            this.arg = arg;
            this.zh = zh;
            this.def = def;
        }

        /** 现在开着吗。 */
        public boolean now() {
            switch (this) {
                case AI:
                    return aiLayer();
                case ATTACK:
                    return attackSide();
                case DEATH:
                    return deathLossless();
                default:
                    return knockbackGuard();
            }
        }

        /** 命令参数 ⇒ 枚举；认不出来 ⇒ {@code null}（调用方自己报错，不猜）。 */
        public static Switch byArg(String arg) {
            if (arg == null) {
                return null;
            }
            final String a = arg.trim().toLowerCase(java.util.Locale.ROOT);
            for (Switch s : values()) {
                if (s.arg.equals(a)) {
                    return s;
                }
            }
            return null;
        }
    }

    /**
     * 改一个开关（<b>写配置 ＋ 立刻落盘 ＋ 一行日志</b>）。
     *
     * @return true = 真的写进去了；false = 配置没加载 / 写失败（<b>不假报成功</b>）
     */
    public static boolean set(Switch which, boolean on) {
        if (which == null) {
            return false;
        }
        if (!ShanhaiConfig.isLoaded()) {
            ShanhaiMod.LOGGER.warn(PREFIX + "guard_switch_write_skipped reason=config_not_loaded switch={} want={}",
                    which.arg, on);
            return false;
        }
        final boolean before = which.now();
        try {
            switch (which) {
                case AI:
                    ShanhaiConfig.COMMON.guardAiLayer.set(on);
                    break;
                case ATTACK:
                    ShanhaiConfig.COMMON.guardAttackSide.set(on);
                    break;
                case DEATH:
                    ShanhaiConfig.COMMON.guardDeathLossless.set(on);
                    break;
                default:
                    ShanhaiConfig.COMMON.guardKnockback.set(on);
                    break;
            }
            ShanhaiConfig.COMMON_SPEC.save();
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn(PREFIX + "guard_switch_write_failed switch={} want={} err={}",
                    which.arg, on, t.toString(), t);
            return false;
        }
        final boolean after = which.now();
        ShanhaiMod.LOGGER.info(PREFIX + "guard_switch switch={} zh={} was={} now={} saved=true",
                which.arg, which.zh, before, after);
        return after == on;
    }

    // ═══════════════════════════════ 读数 ═══════════════════════════════

    /** 一行总表（命令回显 / 开机日志 / 报告都用它）。 */
    public static String statusLine() {
        return "guard_switches loaded=" + ShanhaiConfig.isLoaded()
                + " ai_layer=" + aiLayer() + "（默认 " + DEFAULT_AI_LAYER + "）"
                + " attack_side=" + attackSide() + "（默认 " + DEFAULT_ATTACK_SIDE + "）"
                + " death_lossless=" + deathLossless() + "（默认 " + DEFAULT_DEATH_LOSSLESS + "）"
                + " knockback_guard=" + knockbackGuard() + "（默认 " + DEFAULT_KNOCKBACK + "）"
                + " knockback_broadcast=" + knockbackBroadcast() + "（默认 " + DEFAULT_KNOCKBACK_BROADCAST + "）";
    }

    private static void warnRead(String key, Throwable t) {
        ShanhaiMod.LOGGER.warn(PREFIX + "guard_switch_read_failed key={} err={}（退回默认值）",
                key, t.toString());
    }
}
