package com.shanhai.add.guard.state;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.config.ShanhaiAddConfig;

/**
 * 山海 · <b>「玩家状态独占权威」三个开关</b>（<b>唯一一份读取口径</b>）。
 *
 * <h2>与本体那一套的关系（照抄口径，不照抄类）</h2>
 * 本体 {@code com.shanhai.common.guard.ShanhaiGuardSwitches} 管 {@code [guard_module] ai_layer/attack_side/…}；
 * 本类管<b>同一族</b>的三个新键，但住在<b>扩展自己那份</b> {@code config/shanhai_add-common.toml}
 * （与 {@code [gravity] multiplier} 同一个文件、同一套 {@code ForgeConfigSpec}）。
 * 为什么不去改本体那份：本轮写入范围只到扩展模块；本体那份 {@code [guard_module]} 一个字节都没动。
 *
 * <h2>三个键与两个默认值口径</h2>
 * <pre>
 *   momentum_guard  默认 <b>开</b>（与本体「攻击端默认开」一致）
 *   pos_guard       默认 <b>开</b>（只在窗口内生效，正常走路一个字节都不碰）
 *   inv_guard       默认 <b>开</b>（只认"一次少掉一大片"）
 * </pre>
 *
 * <h2>两条读法纪律（与本体同一套）</h2>
 * <ol>
 *   <li><b>配置没加载 ⇒ 退回默认值</b>（{@code ConfigValue#get()} 未绑定时会抛 {@code IllegalStateException}）；</li>
 *   <li><b>一次动作只读一次</b>：调用点先取进局部变量再判。</li>
 * </ol>
 */
public final class ShanhaiStateSwitches {

    /** 与守卫同一套前缀（纯 JDK 常量，避免本类依赖 MC）。 */
    public static final String PREFIX = ShanhaiStateLog.PREFIX;

    /** 配置键（与 {@code ShanhaiAddConfig} 里 {@code .define(...)} 的那个串一致）。 */
    public static final String KEY_MOMENTUM = "guard_module.momentum_guard";
    /** 见 {@link #KEY_MOMENTUM}。 */
    public static final String KEY_POS = "guard_module.pos_guard";
    /** 见 {@link #KEY_MOMENTUM}。 */
    public static final String KEY_INV = "guard_module.inv_guard";

    /** 三个默认值只在配置类里写一次。 */
    public static final boolean DEFAULT_MOMENTUM = ShanhaiAddConfig.DEFAULT_MOMENTUM_GUARD;
    /** 见 {@link #DEFAULT_MOMENTUM}。 */
    public static final boolean DEFAULT_POS = ShanhaiAddConfig.DEFAULT_POS_GUARD;
    /** 见 {@link #DEFAULT_MOMENTUM}。 */
    public static final boolean DEFAULT_INV = ShanhaiAddConfig.DEFAULT_INV_GUARD;

    private ShanhaiStateSwitches() {}

    /** 动量那一项现在开着吗。 */
    public static boolean momentum() {
        return read(KEY_MOMENTUM, DEFAULT_MOMENTUM, 0);
    }

    /** 位置那一项现在开着吗。 */
    public static boolean pos() {
        return read(KEY_POS, DEFAULT_POS, 1);
    }

    /** 物品栏那一项现在开着吗。 */
    public static boolean inv() {
        return read(KEY_INV, DEFAULT_INV, 2);
    }

    private static boolean read(String key, boolean fallback, int which) {
        if (!ShanhaiAddConfig.isLoaded()) {
            return fallback;
        }
        try {
            switch (which) {
                case 0:
                    return ShanhaiAddConfig.COMMON.momentumGuard.get();
                case 1:
                    return ShanhaiAddConfig.COMMON.posGuard.get();
                default:
                    return ShanhaiAddConfig.COMMON.invGuard.get();
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} state_switch_read_failed key={} err={}（退回默认值）",
                    PREFIX, key, t.toString());
            return fallback;
        }
    }

    /** 三个开关的机器名（命令参数用）。 */
    public enum Switch {

        /** 动量。 */
        MOMENTUM("momentum", "动量", DEFAULT_MOMENTUM),
        /** 位置。 */
        POS("pos", "位置", DEFAULT_POS),
        /** 物品栏。 */
        INV("inv", "物品栏", DEFAULT_INV);

        /** 命令里那个词。 */
        public final String arg;
        /** 人话名。 */
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
                case MOMENTUM:
                    return momentum();
                case POS:
                    return pos();
                default:
                    return inv();
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
     * <p>日志口径（用户点名要的那一行）：
     * <pre>
     *   动量： [SHANHAI-GUARD] momentum_switch enabled=false was=true saved=true key=guard_module.momentum_guard
     *   其它： [SHANHAI-GUARD] state_switch field=pos enabled=false was=true saved=true key=guard_module.pos_guard
     * </pre>
     *
     * @return true = 真的写进去了；false = 配置没加载 / 写失败（<b>不假报成功</b>）
     */
    public static boolean set(Switch which, boolean on) {
        if (which == null) {
            return false;
        }
        if (!ShanhaiAddConfig.isLoaded()) {
            ShanhaiMod.LOGGER.warn("{} state_switch_write_skipped reason=config_not_loaded field={} want={}",
                    PREFIX, which.arg, on);
            return false;
        }
        final boolean before = which.now();
        boolean saved = false;
        try {
            switch (which) {
                case MOMENTUM:
                    ShanhaiAddConfig.COMMON.momentumGuard.set(on);
                    break;
                case POS:
                    ShanhaiAddConfig.COMMON.posGuard.set(on);
                    break;
                default:
                    ShanhaiAddConfig.COMMON.invGuard.set(on);
                    break;
            }
            ShanhaiAddConfig.COMMON_SPEC.save();
            saved = true;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} state_switch_write_failed field={} want={} err={}",
                    PREFIX, which.arg, on, t.toString());
            return false;
        }
        final boolean after = which.now();
        if (which == Switch.MOMENTUM) {
            // 🔴 用户点名的那一行：关掉时必须有 `momentum_switch enabled=false`
            ShanhaiMod.LOGGER.info("{} momentum_switch enabled={} was={} saved={} key={}",
                    PREFIX, after, before, saved, KEY_MOMENTUM);
        } else {
            ShanhaiMod.LOGGER.info("{} state_switch field={} zh={} enabled={} was={} saved={} key={}",
                    PREFIX, which.arg, which.zh, after, before, saved,
                    which == Switch.POS ? KEY_POS : KEY_INV);
        }
        return after == on;
    }

    /** 一行总表（命令回显 / 启动自证 / 报告都用它）。 */
    public static String statusLine() {
        return "state_authority loaded=" + ShanhaiAddConfig.isLoaded()
                + " momentum_guard=" + momentum() + "（默认 " + DEFAULT_MOMENTUM + "）"
                + " pos_guard=" + pos() + "（默认 " + DEFAULT_POS + "）"
                + " inv_guard=" + inv() + "（默认 " + DEFAULT_INV + "）";
    }
}
