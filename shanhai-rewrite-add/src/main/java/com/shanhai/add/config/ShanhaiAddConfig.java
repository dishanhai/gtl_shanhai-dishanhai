package com.shanhai.add.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 山海扩展（{@code shanhai_add}）的 Forge 原生配置（{@code ModConfig.Type.COMMON}）。
 *
 * <h2>为什么这里是 COMMON，而不是 CLIENT / SERVER</h2>
 * 与本体 {@code com.shanhai.config.ShanhaiConfig} 同一条口径（那份类注释里写得很细）：
 * <ol>
 *   <li>Forge 的 COMMON 配置<b>不跨端同步</b> ⇒ 单机里客户端与集成服务器读的是同一个
 *       {@code config/shanhai_add-common.toml}，天然一致；</li>
 *   <li>专用服务端读服务端自己那份，客户端那份只作本地回退（真正的值由
 *       {@code com.shanhai.add.gravity.ShanhaiGravityNetwork} 的 S2C 包下发 ⇒ 不会"服务端 0.5 / 客户端 1"
 *       那种橡皮筋）；</li>
 *   <li>不用 {@code Type.SERVER}：那份在单机里要等世界登录才加载，而重力在
 *       {@code ServerStartedEvent} 就要读（与本体 {@code ShanhaiConfig:8-12} 记的取舍逐字同源）。</li>
 * </ol>
 *
 * <h2>文件名与节名（🔴 与拆分前逐字对齐）</h2>
 * Forge 的配置文件名 = {@code <modid>-<type>.toml} ⇒ <b>{@code config/shanhai_add-common.toml}</b>
 * （本体那份是 {@code config/shanhai-common.toml}）。节名仍是 {@code [gravity]}、
 * 键名仍是 {@code multiplier} —— 只是从本体那份搬到了这一份（拆分设计 §4）。
 *
 * <h2>玩家已有值的迁移</h2>
 * 旧的 {@code config/shanhai-common.toml} 里如果 {@code [gravity] multiplier} 不是 1.0，
 * 由 {@link AddGravityConfigMigration} 在启动时搬一次（设计 §11 待确认 6 选"迁移一次"）。
 */
public final class ShanhaiAddConfig {

    /** 本配置在 {@code config/} 下的文件名（只用于日志/文档；真值由 Forge 按 modid 生成）。 */
    public static final String FILE_NAME = "shanhai_add-common.toml";

    /** 重力倍率的默认值（= 原版手感）。迁移判定与"没加载时的兜底"共用这一个常量。 */
    public static final double DEFAULT_GRAVITY_MULTIPLIER = 1.0D;

    /** 已构建的配置规格（扩展的 {@code @Mod} 构造器里注册到 Forge）。 */
    public static final ForgeConfigSpec COMMON_SPEC;

    /** 已绑定的配置值容器。 */
    public static final ConfigValues COMMON = new ConfigValues();

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        COMMON.init(builder);
        COMMON_SPEC = builder.build();
    }

    private ShanhaiAddConfig() {}

    /**
     * 配置是否已加载。
     *
     * <p>🔴 兜底不可省：{@code ConfigValue#get()} 在 spec 未加载时直接抛 {@code IllegalStateException}。
     * 迁移代码在 {@code FMLCommonSetupEvent} 里跑，那一刻通常已加载，但"通常"不算判据。
     */
    public static boolean isLoaded() {
        return COMMON_SPEC.isLoaded();
    }

    /**
     * 读当前重力倍率，读不到给兜底值（<b>不抛异常</b>）。
     *
     * @param fallback 配置未加载/取值失败时用的值（调用方一般传 1.0 = 原版）
     * @return 当前倍率，或 {@code fallback}
     */
    public static double gravityOr(double fallback) {
        if (!isLoaded()) {
            return fallback;
        }
        try {
            return COMMON.gravityMultiplier.get();
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 配置值容器。 */
    public static class ConfigValues {

        /**
         * 全量重力倍率 G。
         *
         * <p>🔴 口径与拆分前逐字一致：0 = 关掉重力 / 1 = 原版 / 负数 = 反重力 / 小数 = 半重力；
         * 作用范围 = 全部实体（生物 + 非生物），各实体用自己的原版基准值 × G。
         */
        public ForgeConfigSpec.DoubleValue gravityMultiplier;

        void init(ForgeConfigSpec.Builder builder) {
            builder.push("gravity");
            gravityMultiplier = builder
                    .comment("全量重力倍率 G：0 = 关掉重力 / 1 = 原版 / 负数 = 反重力 / 小数 = 半重力",
                            "作用范围 = 全部实体：生物（玩家/怪/动物/坐骑/盔甲架）＋ 非生物（物品/经验球/下落方块/",
                            "  TNT/箭/投掷物/钓鱼钩/羊驼口水/潜影贝子弹/矿车/船/鱿鱼/鱼/海豚/守卫者/海龟），共 17 个施加点",
                            "口径 = 各实体自己的原版基准值 × G（不是共用一个 0.08），所以 G=1 时与上游逐字节一致",
                            "游戏内改法：/shanhai_add gravity <倍率>（写回本文件）· /shanhai_add gravity reset（回 1）",
                            "范围 -100 ~ 100：生物那条走 forge:entity_gravity（值域 -8~8，基准 0.08）⇒ ±100 正好顶到边界",
                            "🔴 玩家不豁免（用户口径：所有实体都被调）；G≤0 时服务端会放行「悬空」反作弊判定，不会被踢",
                            "⚠️ 本项 2026-10-18 从本体的 config/shanhai-common.toml 搬来（节名/键名/默认值都没变）；",
                            "   旧文件里若还是非 1.0 的值，扩展启动时会一次性搬到这里（只搬一次，之后不再读旧文件）")
                    .defineInRange("multiplier", DEFAULT_GRAVITY_MULTIPLIER, -100.0D, 100.0D);
            builder.pop();
        }

        ConfigValues() {}
    }
}
