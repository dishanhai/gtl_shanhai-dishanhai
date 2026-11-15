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

    /**
     * 🆕 2026-11-08 「玩家状态由我们独占权威」· 动量那一项的默认值：<b>开</b>。
     *
     * <p>口径与本体 {@code [guard_module] attack_side} 一致（攻击侧的保护类功能默认开）。
     * 默认值只此一处；{@code ShanhaiStateSwitches.DEFAULT_MOMENTUM} 转引本常量。
     */
    public static final boolean DEFAULT_MOMENTUM_GUARD = true;

    /** 🆕 「位置」默认值：<b>开</b>（只在窗口内生效，正常走路一个字节都不碰）。 */
    public static final boolean DEFAULT_POS_GUARD = true;

    /** 🆕 「物品栏」默认值：<b>开</b>（只认"一次少掉一大片"；不做"缺了就补"以免复制物品）。 */
    public static final boolean DEFAULT_INV_GUARD = true;

    /**
     * 🆕 常开栈雷达默认值：<b>开</b>。
     *
     * <p>用户口径（原话）：「所以你要一直开这个窗口」—— 雷达是<b>常驻</b>的，
     * 不做"可疑窗口"（那正是最早被否掉的思路：判"可疑"本身就给了对方绕开的空间）。
     * 默认值只此一处；{@code ShanhaiStackRadar.onServerStarted} 转引本常量。
     */
    public static final boolean DEFAULT_STACK_RADAR = true;

    /** 🆕 采样间隔默认值（毫秒）：4 ⇒ 约 250 次/秒（≈2 秒窗口装进 512 格环形缓冲）。 */
    public static final int DEFAULT_STACK_RADAR_INTERVAL_MS = 4;

    /** 采样间隔下限（夹取用）：再低没有意义，`0` 会把采样线程变成忙转。 */
    public static final int STACK_RADAR_INTERVAL_MIN_MS = 1;

    /** 采样间隔上限（夹取用）：再高就采不到"某一拍"了（50 ms ≈ 一 tick 一次）。 */
    public static final int STACK_RADAR_INTERVAL_MAX_MS = 50;

    /** 🆕 每次采样只看栈顶前几帧（越深越贵，且远处的帧离"谁在 tick"已经很远）。 */
    public static final int DEFAULT_STACK_RADAR_DEPTH = 32;

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

    /**
     * 🆕 常开栈雷达开没开（读不到给兜底值，<b>不抛异常</b>）。
     *
     * <p>只在 {@code ServerStartedEvent} 读一次 —— 采样线程在等世界起来那几秒里没有意义。
     *
     * @param fallback 配置未加载/取值失败时用的值（生产调用传 {@link #DEFAULT_STACK_RADAR}）
     */
    public static boolean stackRadarOr(boolean fallback) {
        if (!isLoaded()) {
            return fallback;
        }
        try {
            return COMMON.stackRadar.get();
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * 🆕 栈雷达的采样间隔（毫秒），读不到给兜底值，并且<b>夹取到 1~50</b>。
     *
     * <p>为什么代码里还要再夹一次：{@code defineInRange} 只在<b>配置文件加载</b>时校验，
     * 手改出一个 {@code 0} 或 {@code 999} 之后，非法值仍可能从"内存里已被别的代码改过"的路径流进来；
     * 而 {@code 0} 会让采样线程变成忙转（打满单核），这是唯一会造成事故的取值。
     *
     * @param fallback 配置未加载/取值失败时用的值
     */
    public static int stackRadarIntervalMsOr(int fallback) {
        final int raw = isLoaded() ? readStackRadarInterval(fallback) : fallback;
        if (raw < STACK_RADAR_INTERVAL_MIN_MS) {
            return STACK_RADAR_INTERVAL_MIN_MS;
        }
        return Math.min(raw, STACK_RADAR_INTERVAL_MAX_MS);
    }

    private static int readStackRadarInterval(int fallback) {
        try {
            return COMMON.stackRadarIntervalMs.get();
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * 🆕 栈雷达每次只看栈顶前几帧，读不到给兜底值，并且夹到 {@code >=1}。
     *
     * @param fallback 配置未加载/取值失败时用的值
     */
    public static int stackRadarDepthOr(int fallback) {
        final int raw = isLoaded() ? readStackRadarDepth(fallback) : fallback;
        return raw <= 0 ? Math.max(1, fallback) : raw;
    }

    private static int readStackRadarDepth(int fallback) {
        try {
            return COMMON.stackRadarDepth.get();
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

        /**
         * 🆕 动量那一项：<b>玩家状态由我们独占权威</b>
         * （判据 = <b>这次写入是不是玩家自己的操作</b>；tick 边界只当采样时机）。
         *
         * <p>默认 {@link #DEFAULT_MOMENTUM_GUARD} = <b>开</b>。关掉之后一个字节都不碰。
         * 🔴 判据不认任何 mod / 类名，<b>也没有速度阈值</b>（见 {@code ShanhaiStateRules}）。
         * 归属标记见 {@code ShanhaiSelfInput}（{@code self_input} / {@code self_guard}）。
         */
        public ForgeConfigSpec.BooleanValue momentumGuard;

        /**
         * 🆕 「位置」那一项（只在"我们刚拦过东西"的窗口内纠正；锚点跟着玩家自己的移动包走）。
         *
         * <p>默认 {@link #DEFAULT_POS_GUARD} = <b>开</b>。判据 = <b>归属</b>
         * （{@code self_input}/{@code self_guard} 一律采纳）＋ 距锚点的位移超出人腿预算
         * （{@code 0.6 格/tick × 经过 tick}）。锚点 = 玩家自己那次移动包写完之后的位置。
         */
        public ForgeConfigSpec.BooleanValue posGuard;

        /**
         * 🆕 「物品栏」那一项（只认"一个 tick 里一次少掉 ≥30% 非空槽"这一种形态，且没开容器、且在窗口内）。
         *
         * <p>默认 {@link #DEFAULT_INV_GUARD} = <b>开</b>。🔴 刻意不做"缺了就补"：那会造出复制物品。
         */
        public ForgeConfigSpec.BooleanValue invGuard;

        /**
         * 🆕 常开栈雷达（默认 {@link #DEFAULT_STACK_RADAR} = <b>开</b>）。
         *
         * <p>🔴 这是"一直开"的那个开关，不是"可疑窗口"：开了之后服务端在跑就一直按
         * {@link #stackRadarIntervalMs} 采样主线程，只有命中/违规时才 dump 一行。
         * 关掉之后连线程都不起（日志留一行 {@code disabled}）。
         */
        public ForgeConfigSpec.BooleanValue stackRadar;

        /** 🆕 采样间隔（毫秒，1~50）：4 ⇒ 约 250 次/秒，512 格环形缓冲覆盖 ≈2 秒。 */
        public ForgeConfigSpec.IntValue stackRadarIntervalMs;

        /** 🆕 每次采样只看栈顶前几帧（默认 32）：越深越贵，且远处的帧离"谁在 tick"已经很远。 */
        public ForgeConfigSpec.IntValue stackRadarDepth;

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

            // ============================================================ 🆕 2026-11-08
            //   [guard_module] —— 与本体 config/shanhai-common.toml 的 [guard_module] 同一族（同一套命名/同一套
            //   读法：配置没加载 ⇒ 退回默认值）。⚠️ 整节【追加在末尾】，没有把任何既有项挪位置。
            //   游戏内改法：/shanhai_add momentum on|off（写回本文件）。
            builder.push("guard_module");
            momentumGuard = builder
                    .comment("「动量」那一项（默认 true = 开）：玩家状态由我们独占权威 —— 只对 ServerPlayer 生效",
                            "拦的是什么：玩家自己的操作【之外】的东西改了玩家的 deltaMovement（不认 mod/类名）",
                            "判据（🔴 没有速度阈值，也【不再用 tick 边界判断归属】）：",
                            "  ① self_input = 原版处理【玩家自己的移动包】那一段（handleMovePlayer 的 HEAD→每条 return）",
                            "  ② self_guard = 我们自己的写入（复活 / 复位 / 清击退 / 还原后重锚）",
                            "  这两类一律采纳（并记成新的认可值）；两边都不是、值又变了 ⇒ 外部写入 ⇒ 写回认可值",
                            "  依据：玩家自己的起跳 0.42 是 handleMovePlayer 里 jumpFromGround() 产生的（javap 已证）",
                            "写回时机 = 下一拍 START（原版把速度积进位置之前）⇒ 服务端这一拍一次都不会用那个动量移动玩家",
                            "白名单口径（用户原话「就只给自己的动作加白名单」）= 我们不写白名单：",
                            "  Entity#setDeltaMovement 本身一个字节都不碰，我们自己的复活/复位照旧生效",
                            "两条结构例外（写死在 ShanhaiStateRules 里）：",
                            "  ① 豁免态（旁观/载具/鞘翅/换维度/死亡/刚加入）以现状为准，不纠正",
                            "  ② 归零不算攻击（只会让人停下，不会把人扔走；本体传送拉回时也会归零）",
                            "关掉之后：一个字节都不碰（日志仍会打一行 momentum_switch enabled=false）",
                            "游戏内改法：/shanhai_add momentum on|off（写回本文件）",
                            "⚠️ 本键住在扩展这份 config/shanhai_add-common.toml（本体那份 [guard_module] 不动）")
                    .define("momentum_guard", DEFAULT_MOMENTUM_GUARD);
            posGuard = builder
                    .comment("「位置」那一项（默认 true = 开；只在窗口内纠正）",
                            "触发窗口：我们刚拦过东西（拦到动量写入 / 玩家被攻击）之后的 20 tick",
                            "判据：归属（self_input/self_guard ⇒ 采纳）＋ 距【锚点】的位移",
                            "  锚点 = 玩家自己那次移动包写完之后的位置（不是窗口打开那一刻）",
                            "  ⇒ 走路/冲刺/跳跃/下落都不会被拉回；只有【标记之外】的大位移才拉回",
                            "  预算：0.6 格/tick × 距锚点经过的 tick ＋ 认可动量贡献（本模块唯一用数值预算的一项）",
                            "豁免：旁观 / 载具里 / 鞘翅飞行 / 正换维度 / 死亡中 / 刚加入 40 tick",
                            "⚠️ 与本体既有的「传送拉回」（只在 |x|/|z|>3e7、|y|>4096、NaN 时拉回）不冲突：",
                            "   本体那条管离谱坐标，本项管窗口内的小幅传送；两边都是幂等的写位")
                    .define("pos_guard", DEFAULT_POS_GUARD);
            invGuard = builder
                    .comment("「物品栏」那一项（默认 true = 开）",
                            "只认一种形态：同一个 tick 里一次少掉 ≥ max(2, 30%) 个非空槽",
                            "并且同时满足：没开别的容器 / 手上没捏着拖拽中的物品 / 处在上面那个窗口里",
                            "🔴 刻意【不】做「缺了就补」：丢东西、吃东西、合成、往容器里放全都长得像被抹掉，",
                            "   补回去等于制造复制物品（比原 bug 更糟）⇒ 只补「一次少掉一大片」这一种",
                            "补的是快照里那些「原来有、现在空了」的槽（物品种类与 NBT 原样拷贝）")
                    .define("inv_guard", DEFAULT_INV_GUARD);
            // ============================================================ 🆕 常开栈雷达（3 键）
            //   🔴 为什么是"常开"而不是"可疑窗口"：用户原话「那你怎么判断可疑窗口，要是敌方绕开了怎么办，
            //      所以你要一直开这个窗口」—— 判"可疑"这件事本身就把绕开的空间让给了对方。
            //   ⇒ 服务端在跑就一直按低密度采样主线程，只有命中/违规时才归因 dump。
            //   键名与默认值：stack_radar=true / stack_radar_interval_ms=4 / stack_radar_depth=32
            //   改完要重启世界（这几项在 ServerStartedEvent 读一次）。
            stackRadar = builder
                    .comment("常开栈雷达（默认 true = 开）：服务端在跑就一直按低密度采样【主线程】，",
                            "只有命中/违规时才把最近 200 ms 的样本归因成 top-5 打一行 [SHANHAI-RADAR] suspect",
                            "🔴 不是「可疑窗口」：用户口径是「所以你要一直开这个窗口」——判可疑等于给对面绕开的余地",
                            "它解决什么：敌方不写玩家字段、也不调 setHealth/setRemoved/die 时，",
                            "  单纯枚举写点的方案抓不到；只有「违规那一拍主线程在跑谁的代码」能归因",
                            "白名单（不算命中）：java.* jdk.* sun.* net.minecraft.* net.minecraftforge.* cpw.*",
                            "  org.spongepowered.* com.gtladd* com.gtlcore.* com.shanhai.*",
                            "⚠️ 白名单只划「宿主 + 我们」⇒ GTCEu/KubeJS 等正常 mod 帧平时也会被算成 hits，",
                            "   所以 hits>0 本身不是「敌方」的证据，它回答的是「那一刻主线程卡在哪串帧上」",
                            "关掉之后：连采样线程都不起（日志留一行 [SHANHAI-RADAR] disabled stack_radar=false）")
                    .define("stack_radar", DEFAULT_STACK_RADAR);
            stackRadarIntervalMs = builder
                    .comment("栈雷达采样间隔（毫秒，1~50 夹取；默认 4 ⇒ 约 250 次/秒）",
                            "单次抓栈 ≈ 10~50 µs ⇒ 4 ms 一档的 CPU 约 0.75% 单核（每 60 秒一行 stats 给实测读数）",
                            "🔴 绝不在主线程上抓栈：抓栈线程是常驻 daemon「shanhai-stack-radar」，",
                            "   它抓的是 ServerStartedEvent 里记下的那个主线程引用（自己在主线程抓只会抓到自己）",
                            "环形缓冲 512 格 ≈ 覆盖 2 秒；dump 只看最近 200 ms",
                            "漏采（有样本但一条非白名单帧都没有）时会自动升档：1 ms / 30 秒；",
                            "  连续 3 次漏采进 burst（0 ms + 1 ms 让出 / 10 秒，单次抓栈 >2 ms 立刻退回）",
                            "本档位就是「默认档」：升档到期后回落到这里")
                    .defineInRange("stack_radar_interval_ms", DEFAULT_STACK_RADAR_INTERVAL_MS,
                            STACK_RADAR_INTERVAL_MIN_MS, STACK_RADAR_INTERVAL_MAX_MS);
            stackRadarDepth = builder
                    .comment("栈雷达每次只看栈顶前几帧（默认 32）",
                            "为什么要截：区块保存/红石这类深栈能到几百帧，而离栈顶 32 帧以外的部分",
                            "  离「谁在 tick」已经太远，看了也只是把 String[] 撑大",
                            "过白名单后每个样本最多留 8 帧（写死在 ShanhaiStackRadarSampler.MAX_FRAMES）")
                    .defineInRange("stack_radar_depth", DEFAULT_STACK_RADAR_DEPTH, 1, 256);
            builder.pop();
        }

        ConfigValues() {}
    }
}
