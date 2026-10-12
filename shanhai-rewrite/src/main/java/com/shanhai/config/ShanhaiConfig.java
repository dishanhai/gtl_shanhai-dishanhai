package com.shanhai.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 本 mod 的 Forge 原生配置（<b>不引 cloth config、不改 {@code mods.toml}</b>）。
 *
 * <h2>为什么是 COMMON 而不是 CLIENT</h2>
 * 这一项是<b>纯客户端显示偏好</b>（"我自己的画面想看到哪种球体"），但放进 COMMON 是刻意的：
 * Forge 的 COMMON 配置<b>不跨端同步</b>（两端各读各自 {@code config/} 目录下的同名 toml），
 * 多人环境里每个玩家改的就是自己那份，语义上正好等于「只影响我自己」。
 * 这一点与上游 {@code DShanhaiConfig} 的处理逐字一致。
 *
 * <h2>注册</h2>
 * 由 {@code com.shanhai.ShanhaiMod} 构造器调用
 * {@code ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ShanhaiConfig.COMMON_SPEC)}。
 */
public final class ShanhaiConfig {

    /**
     * restore 去抖阈值的默认值（tick）= 100 tick = 5 秒。
     *
     * <p>放在这里而不是只写死在 {@code defineInRange} 里：渲染路径上的读配置代码需要一个
     * "配置未加载时"的兜底值（见 {@code GtlAddClientCompat#restoreDebounceTicks}），
     * 两处必须是同一个数 —— 所以在同一个常量上取。
     *
     * <p>选 100 的依据：本轮实测「结构未成型」的瞬时窗口最长 <b>3.012 秒</b>（60.24 tick），
     * 100 tick 留了 1.66 倍余量；同时 5 秒对人类操作（拆机器/移动）仍算是"立刻"。
     */
    public static final int DEFAULT_RING_RESTORE_DEBOUNCE_TICKS = 100;

    /** 已构建的配置规格（构造期注册到 Forge）。 */
    public static final ForgeConfigSpec COMMON_SPEC;

    /** 已绑定的配置值容器。 */
    public static final ConfigValues COMMON = new ConfigValues();

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        COMMON.init(builder);
        COMMON_SPEC = builder.build();
    }

    private ShanhaiConfig() {}

    /** 配置值容器（只放"原始终焉引擎"相关项；后续模块的配置另开 push）。 */
    public static class ConfigValues {

        /**
         * 球体渲染风格的客户端显示覆盖。
         *
         * <p>🔴 <b>刻意不复用 {@link com.shanhai.common.machine.PrimordialSphereStyle}</b>：
         * 后者的 {@code ordinal} 是机器 NBT 持久化 / {@code @DescSynced} 值，其 javadoc 明写禁止插入中间项；
         * 且本枚举多一个 {@code FOLLOW_MACHINE} 语义 —— 它<b>不是机器状态，是观看者偏好</b>。
         * 两者职责正好分开：机器上那个值是「建造者选的世界状态」，本项是「我自己想看到什么」。
         * <b>本项不改写机器的持久化状态</b>，切回 {@code FOLLOW_MACHINE} 即恢复各机器原设定。
         */
        public enum SphereStyleOverride {
            /** 跟随机器 GUI 侧栏的切换按钮（默认）。 */
            FOLLOW_MACHINE,
            /** 强制渲染成鸿蒙微型宇宙。 */
            UNIVERSE,
            /** 强制渲染成中子星。 */
            NEUTRON_STAR
        }

        /** 见 {@link SphereStyleOverride}。 */
        public ForgeConfigSpec.EnumValue<SphereStyleOverride> primordialSphereStyle;

        /**
         * 环方块 restore 的去抖阈值（tick）。
         *
         * <p>「结构未成型」在真实世界里是<b>抖动</b>的：结构部件所在区块卸载 ⇒ GTCEu
         * {@code onPartUnload} → {@code UNLOAD_ERROR} → 每 4 tick 异步重查 ⇒ {@code isFormed} 反复翻转
         * （本轮实测 12 次成型 / 6 次 restore，瞬时窗口最长 3.012 秒）。
         * 环的 restore 会真的写回一整片方块，所以必须要求"连续未成型 ≥ 本值"才执行。
         *
         * <p>这是<b>客户端渲染侧</b>的参数，但放在 COMMON：与 {@link #primordialSphereStyle} 同理，
         * COMMON 不跨端同步 ⇒ 每个玩家读自己那份，语义正好是"只影响我自己的画面"。
         * 调小 = 拆掉结构后更快看到方块回来（但更容易被区块加载抖动误触发）；
         * 调大到 1200（60 秒）≈ 只在真正长时间失型时才恢复。
         */
        public ForgeConfigSpec.IntValue ringRestoreDebounceTicks;

        /**
         * 🆕 2026-10-07（配方编辑器「两套界面」第 1 步）<b>配方编辑器用哪一套界面</b>。
         *
         * <p>两套界面<b>共存</b>，本项决定打开哪一套：
         * <ul>
         *   <li>{@link Ui#OURS} = 我们自己那套（三段式、可编辑）—— <b>默认</b>；
         *       ⚠️ 默认值就是"加本项之前的行为"，所以加这一项本身<b>不改任何既有行为</b>；</li>
         *   <li>{@link Ui#HIS} = 上游那套界面里的<b>只读浏览页</b>（类型列表 / 配方卡片 / 翻页）。</li>
         * </ul>
         *
         * <p>放 COMMON 的理由：界面的 ModularUI 是 {@code UIFactory.createUITemplate}
         * <b>两侧各建一次</b>，两侧必须选出同一套界面，否则控件表对不上；
         * COMMON 在单机里两侧读的是同一个 {@code config/shanhai-common.toml} ⇒ 天然一致。
         * （这条与 {@link #primordialSphereStyle} 那条"COMMON 不跨端同步 = 只影响我自己"的既有口径同源。）
         *
         * <p>游戏内也可以改：{@code /shanhai edit ui ours|his}（写回本文件）
         * 或全息设置面板里那一格。
         */
        public enum Ui {
            /** 我们自己那套（默认）。 */
            OURS,
            /** 上游那套界面的只读浏览页。 */
            HIS
        }

        /** 见 {@link Ui}。 */
        public ForgeConfigSpec.EnumValue<Ui> editorUi;

        void init(ForgeConfigSpec.Builder builder) {
            builder.push("primordial_omega_engine");
            primordialSphereStyle = builder
                    .comment("原始终焉引擎中心球体的渲染风格覆盖（纯客户端显示偏好）",
                            "COMMON 配置不同步：多人环境各玩家读自己本地的 shanhai-common.toml，只影响自己的画面",
                            "FOLLOW_MACHINE = 跟随每台机器 GUI 侧栏的切换按钮（默认）",
                            "UNIVERSE = 强制全部渲染成鸿蒙微型宇宙，忽略机器上的设定",
                            "NEUTRON_STAR = 强制全部渲染成中子星，忽略机器上的设定",
                            "本项不改写机器的持久化状态，切回 FOLLOW_MACHINE 即恢复各机器原设定")
                    .defineEnum("sphereStyle", SphereStyleOverride.FOLLOW_MACHINE);
            ringRestoreDebounceTicks = builder
                    .comment("环方块 restore（把被隐藏的环写回原方块）的去抖阈值，单位 tick（20 tick = 1 秒）",
                            "语义：结构必须【连续未成型】达到本值，才真的写回环方块；中途一旦重新成型，计时清零",
                            "为什么需要：结构部件所在区块卸载会让 isFormed 反复翻转（GTCEu onPartUnload →",
                            "UNLOAD_ERROR → 每 4 tick 异步重查），不去抖就会一帧写回、下一帧又隐藏（方块闪烁）",
                            "默认 100 = 5 秒（本轮实测瞬时未成型窗口最长 3.012 秒，留 1.66 倍余量）",
                            "纯客户端参数：本项只影响你自己画面里的环，不改机器的持久化状态",
                            "区间 0..1200；0 = 不去抖（回到旧行为，不推荐）")
                    .defineInRange("ringRestoreDebounceTicks", DEFAULT_RING_RESTORE_DEBOUNCE_TICKS, 0, 1200);
            builder.pop();
            // 🆕 2026-10-07（第 1 步）：配方编辑器「两套界面」开关。
            //    ⚠️ 整节【追加在末尾】（没有把任何既有项挪位置），默认值 = OURS = 加这一节之前的行为。
            builder.push("recipe_editor");
            editorUi = builder
                    .comment("配方编辑器用哪一套界面（两套共存，这一项决定打开哪一套）",
                            "OURS = 我们自己那套（三段式、可编辑）—— 默认，与加本项之前的行为完全一致",
                            "HIS  = 上游那套界面里的【只读浏览页】：类型列表 / 配方卡片 / 翻页，点编辑动作不响应",
                            "COMMON 不跨端同步：单机两侧读的是同一个 config/shanhai-common.toml ⇒ 必然一致",
                            "游戏内改法：/shanhai edit ui ours|his，或全息设置面板里那一格（都会写回本文件）")
                    .defineEnum("ui", Ui.OURS);
            builder.pop();
        }

        ConfigValues() {}
    }

    /**
     * 配置是否已加载。
     *
     * <p>🔴 这个兜底<b>不可省</b>：配置尚未加载时 {@code ConfigValue.get()} 会直接抛
     * {@code IllegalStateException}（Forge 的 {@code ConfigValue} 在 {@code ConfigSpec} 未加载时报错）。
     * TESR 与 tooltip 每帧都可能走到这里，开发环境/资源重载窗口期尤其容易撞上。
     */
    public static boolean isLoaded() {
        return COMMON_SPEC.isLoaded();
    }
}
