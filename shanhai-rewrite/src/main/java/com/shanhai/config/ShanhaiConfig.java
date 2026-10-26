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

        // ═══════════ 🆕 2026-10-17「创始现实修改模块」的三个功能开关（COMMON） ═══════════
        //
        // 为什么进 COMMON（= config/shanhai-common.toml）而不是 CLIENT：
        //   AI 层与攻击端**跑在服务端**（LivingChangeTargetEvent / LivingAttackEvent / ServerTickEvent），
        //   专用服务端也要读到同一个值 ⇒ 只能进两侧都加载的 COMMON。
        //   （这与既有 recipe_editor.ui 那一项的存法同源：同一个 spec、同一个 save()，全息设置面板
        //     本来也是靠 COMMON 读写它 ⇒ 本组三项沿用同一套存储，不另起一套。）
        //
        // 🔴 默认值就是用户口径：AI 层**关**（新功能，没开时一个字节都不改世界）、
        //   攻击端**开**、死而无损**开**。

        /** 🆕 AI 层开关（默认<b>关</b>）。 */
        public ForgeConfigSpec.BooleanValue guardAiLayer;

        /** 🆕 攻击端开关（默认<b>开</b>）。 */
        public ForgeConfigSpec.BooleanValue guardAttackSide;

        /** 🆕 死而无损开关（默认<b>开</b>）。 */
        public ForgeConfigSpec.BooleanValue guardDeathLossless;

        // ═══════════ 🆕 2026-10-17「反击退 ＋ 反挤压」两个开关（COMMON） ═══════════
        //
        // 🔴 用户口径（原话）：「我还需要反击退（包括碰撞箱挤压我），这个猫把我扔来扔去的」
        //                「我不想做拉回，会误伤很多别的 mod 的东西」
        //    ⇒ 只改速度/标志，一个坐标都不搬；只在"最近 20 tick 内我们确实拦过东西"的窗口里刹车。
        //
        // 为什么进 COMMON：判据跑在服务端每 tick（ServerTickEvent END），专用服务端也要读到同一个值
        //   ⇒ 与上面那三项同源（同一个 spec、同一个 save()）。

        /** 🆕 反击退主开关（默认<b>开</b>）。 */
        public ForgeConfigSpec.BooleanValue guardKnockback;

        /** 🆕 反击退左下角播报（默认<b>关</b> —— 用户口径「默认不播，避免刷屏」）。 */
        public ForgeConfigSpec.BooleanValue guardKnockbackBroadcast;

        // 🔴 2026-10-18 mod 拆分：原「[gravity] multiplier」整节已【搬进扩展 shanhai_add】。
        //    现址 = 扩展（shanhai_add）的 ShanhaiAddConfig 的 [gravity] multiplier
        //        （文件 config/shanhai_add-common.toml；[gravity] 节名与 multiplier 键名都没变）。
        //    为什么搬：重力调制按拆分口径整块归扩展（设计 §1.1 / §4）；本体只装本体时不该有这一节。
        //    ⚠️ 旧的 config/shanhai-common.toml 里若还有 [gravity]，扩展启动时会做一次性迁移
        //    （值不是 1.0 时搬到新文件；本体这份不再读它）。

        // ═══════════ 🆕 2026-10-18「只读诊断探针」的总开关（COMMON） ═══════════
        //
        // 用户报：伪神之锻炉（GTAdditions）与我们自己的模块都会「配方进度清零、从头再跑」。
        // 上游有三个【完全静默】的清零入口，日志上分不开是谁干的：
        //   ① RecipeLogic#interruptRecipe()  ⇒ status=IDLE, progress=0, duration=0
        //   ② RecipeLogic#doDamping()       ⇒ 进度回退（缺电 / 无线电网输入失败）
        //   ③ 「先扣料、后判产出」的空窗     ⇒ 料没了、lastRecipe=null、机器空转
        // ⇒ 加一组【只打日志、不改任何行为】的探针 [SHANHAI-RLPROBE]（P1..P5）把它们分开。
        //
        // 🔴 默认 true：这一轮就是要抓现场；关掉它 = 那组探针一行都不打（连 armed 自证行也没有）。
        // 为什么进 COMMON：清零发生在【服务端】的 tick 里（RecipeLogic.serverTick / 上游的闸门），
        //   专用服务端也要读同一个值 ⇒ 与上面那几组同源（同一个 spec、同一个 save()）。

        /** 🆕 配方逻辑只读探针总开关（默认<b>开</b>）。 */
        public ForgeConfigSpec.BooleanValue recipeProbe;

        /**
         * 🆕 归墟视界枢核的<b>成型检查合并</b>开关（默认<b>开</b>）。
         *
         * <p>开：同一 tick 内同一台主机结构的多次方块变化，压成该 tick 末尾的<b>一次</b>
         * {@code MultiblockState#onBlockStateChanged} ⇒ 每 tick 最多一次全量图案检查
         * （归墟视界枢核的图案是 148×135×131 ≈ 261.7 万格，一次检查几十到上千毫秒）。
         * 关：逐字节回到上游行为（每次方块变化都立刻全量检查一次）。
         *
         * <p>🔴 只作用于 {@code com.shanhai.machine.host.GuixuVisionCoreMachine}；
         * 其它 mod 与 gtceu/gtlcore 的多方块一律不受影响。
         * <p>🔴 代价：结构失效/重新成型最多晚 1 tick（≈50 ms）才发现 —— 这是合并的必然代价。
         */
        public ForgeConfigSpec.BooleanValue patternCheckCoalesce;

        /** 星律样板总成：普通样板区每页行数（默认 6 = 既有行为；列数固定 9）。 */
        public ForgeConfigSpec.IntValue stellarPatternRowsPerPage;

        /** 星律样板总成：普通样板区最多页数（默认 20 = 既有行为；可见槽数上限 1080）。 */
        public ForgeConfigSpec.IntValue stellarPatternPages;

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

            // ============================================================ 🆕 2026-10-17
            //   [guard_module] —— 「创始现实修改模块」三件功能的开关。
            //   ⚠️ 整节【追加在末尾】（没有把任何既有项挪位置）；默认值 = 用户拍板的那三个。
            //   游戏内改法：/shanhai guard ai|attack|death|knockback on|off（写回本文件）。
            builder.push("guard_module");
            guardAiLayer = builder
                    .comment("AI 层开关（默认 false = 关）",
                            "true  = ①谁也不能把目标设成受保护玩家（LivingChangeTargetEvent 直接取消）",
                            "        ②每 10 tick 扫一次受保护玩家周围 64 格的生物：目标是他的 ⇒ 目标清空",
                            "        ③上面命中的生物 setNoAi(true)，并在它的持久数据打标记 shanhai_frozen=true",
                            "⚠️ 只处理「目标是你」或「被这场开关冻过（带标记）」的生物；没标记的一只都不碰",
                            "⚠️ 恢复口径：只有把本开关【关掉】时，才扫描带标记的生物把 noAi 还原为 false 并清标记",
                            "   （不做按时间/距离的自动恢复 —— 用户原话：「noAi 是你关掉这个功能了 ai 才会恢复」）")
                    .define("ai_layer", false);
            guardAttackSide = builder
                    .comment("攻击端开关（默认 true = 开）",
                            "触发 A：受保护玩家受到攻击（LivingAttackEvent）⇒ 取消伤害后反手抹除攻击者本人",
                            "         （召唤物/投射物一律不处理；玩家一律不处理）",
                            "触发 B：/shanhai purge（或全息菜单第 1 块板「清除实体」点一次）⇒ 抹除游戏内所有实体",
                            "         🔴 安全默认：跳过所有玩家（含自己）与 EntityType.PLAYER，跳过的数量记日志",
                            "移除阶梯：L1 discard → L2 核实 → L3 setRemoved → L4 发包 → L5 摘实体账 → L6 隔离",
                            "日志用 purge_channel=L1|L3|L4|L5|L6 分辨是哪一级解决掉的")
                    .define("attack_side", true);
            guardDeathLossless = builder
                    .comment("死而无损开关（默认 true = 开）",
                            "现有的 die() 拒绝已经让死亡不发生；本项是「万一真的走到死亡」时的兜底：",
                            "还原 背包+副手+盔甲 / 经验等级与进度 / 位置与朝向 / 饥饿与饱和度 / 药水效果",
                            "只在开关打开时生效；每还原一次记一行「死而无损：已还原 <哪几项>」")
                    .define("death_lossless", true);
            // ============================================================ 🆕 2026-10-17
            //   反击退 ＋ 反挤压（用户口径：「我还需要反击退（包括碰撞箱挤压我），这个猫把我扔来扔去的」；
            //   「我不想做拉回，会误伤很多别的 mod 的东西」）⇒ **只改速度/标志，一个坐标都不搬**。
            guardKnockback = builder
                    .comment("反击退 / 反挤压开关（默认 true = 开；关掉后本功能完全不动玩家）",
                            "只管【受保护玩家】（身上带「创始现实修改模块」，或曾经带过）",
                            "① Entity#hurtMarked == true ⇒ 置 false（原版 ServerEntity#sendChanges 会把它当击退速度发给客户端）",
                            "② 只有在【我们最近 20 tick 内确实拦过东西】（拦伤害/拒死/补血/拦移除 任一）的窗口里，",
                            "   且水平速度 hypot(dx,dz) > 0.6 格/tick ⇒ 把 deltaMovement 的 x/z 清零、y 原样保留",
                            "③ 🔴 不搬人：没有位置快照、没有拉回、没有卡墙回退（用户明确否掉了拉回）",
                            "豁免（代码里写死的四条）：鞘翅飞行 / 在载具里 / 旁观者 / 刚传送（换维度或复活后 2 tick）",
                            "为什么阈值是 0.6：走路约 0.2、冲刺约 0.3、跳跃水平约 0.5 格/tick，都低于它 ⇒ 正常移动原样通过",
                            "为什么窗口只认'我们拦过'：别的 mod 的弹射、跳板、技能推人没有攻击事件 ⇒ 窗口不打开 ⇒ 不会被误伤",
                            "游戏内改法：/shanhai guard knockback on|off（写回本文件）")
                    .define("knockback_guard", true);
            guardKnockbackBroadcast = builder
                    .comment("反击退是否在左下角（action bar）播报，默认 false = 不播",
                            "🔴 默认不播：被推的时候每一下都播会刷屏",
                            "打开后仍然是「每秒最多一行」（沿用守卫那套同类合并口径），且只发给受保护玩家本人",
                            "⚠️ 本项【不会】把消息发到聊天栏（聊天栏那一路刻意不扩：聊天栏那条路只在拦到事情时才说话）")
                    .define("knockback_broadcast", false);
            builder.pop();

            // ============================================================ 🆕 2026-10-18
            //   [gravity] —— 已整节搬进扩展（见 ConfigValues 里那条说明），本体不再定义。

            // ============================================================ 🆕 2026-10-18
            //   [diag] —— 只读诊断探针的总开关。
            //   ⚠️ 整节【追加在末尾】（没有把任何既有项挪位置）；默认 true = 本轮就是要抓现场。
            builder.push("diag");
            recipeProbe = builder
                    .comment("配方逻辑只读探针 [SHANHAI-RLPROBE] 的总开关（默认 true = 开）",
                            "它只打日志、不改任何行为：全部注入点都是 @Inject(...HEAD/RETURN)，require = 0，",
                            "  非 cancellable、非 Redirect、非 ModifyVariable ⇒ 关掉/开开，机器的行为逐字节一致",
                            "作用 = 把「配方进度被清零」的三个静默入口分开：",
                            "  ① interruptRecipe()（status=IDLE, progress=0, duration=0）",
                            "  ② doDamping()（进度回退：缺电 / 无线电网输入失败）",
                            "  ③ 「先扣料、后判产出」的空窗（料没了、lastRecipe=null、机器空转）",
                            "启动时会在日志里打两行自证：",
                            "  [SHANHAI-RLPROBE] config …   = 本项当前值（开没开可判）",
                            "  [SHANHAI-RLPROBE] armed P1=true … = 混入到底挂上了没有（没数据 vs 没挂上可分开）",
                            "限流：同一台机器同一类事件 2 秒最多 1 行（行尾 folded=N），每 60 秒一行 summary",
                            "⚠️ 关掉后那四类事件行一行都不打，但启动时【仍然】打 config/armed 两行自证",
                            "   （刻意如此：那两行是「这一轮到底开没开、探针到底挂没挂」的唯一机器证据）",
                            "改完需要重启客户端/服务端才生效")
                    .define("recipe_probe", true);
            patternCheckCoalesce = builder
                    .comment("归墟视界枢核的成型检查【合并】开关（默认 true = 开）",
                            "开 = 同一 tick 内同一台主机的多次方块变化，压成该 tick 末尾的一次 onBlockStateChanged",
                            "     ⇒ 每 tick 最多一次全量图案检查（本机图案 148×135×131 ≈ 261.7 万格）",
                            "     ⇒ 一键搭建铺两万多格时，上游原本会做两万多次全量检查（卡服几十秒）",
                            "关 = 逐字节回到上游行为（每次方块变化都立刻全量检查一次）",
                            "🔴 只作用于 GuixuVisionCoreMachine（我们那台主机）；其它 mod / gtceu / gtlcore 的多方块不受影响",
                            "🔴 代价：结构失效 / 重新成型最多晚 1 tick（≈50ms）才发现 —— 拆方块后多等半秒内掉成型，属预期",
                            "🔴 拿不到服务器线程 / 拿不到 controller 时一律【不合并】，宁可慢不可错；异步检查那条路完全不动",
                            "自证：启动后第一次 ServerTickEvent.End 打一行 [SHANHAI-PATCOALESCE] armed=… threshold=200000",
                            "合并的实际账目在 [SHANHAI-PATPROBE] summary 的 deferred= / applied= / applied_max_ms= 里",
                            "改完需要重启客户端/服务端才生效")
                    .define("pattern_check_coalesce", true);
            builder.pop();

            // ============================================================ 阶段八 ③：星律样板总成的版面
            //   [stellar] —— 普通样板槽的「行数 / 页数」（列数固定 9，见下）。
            //   🔴 默认值 = 加这两项之前的行为：9 列 × 6 行 × 20 页 = 1080 槽
            //      ⇒ 不改配置就与以前逐字节一致（这两项只影响「界面分页」与「可见槽数上界」）。
            //   ⚠️ 为什么列数不做成配置：gtlcore 的 PaginationUIManager 把 9 列的几何写死在字节码里
            //      （x = 8 + (i % patternsPerRow) * 18，面板宽 18*cols+16），列数可配就得自己重写分页器
            //      ⇒ 风险明显不划算（父代理 2026-10 的口径：列数固定 9）。
            //   ⚠️ 为什么页数变小不会弄丢样板：底层 storage 仍然是 1080 格（构造参数不变），
            //      这两项只决定「界面翻得到几页」；超出可见页的样板【数据仍在存档里】，
            //      只是暂时看不到 —— 日志会打一条 WARN 说明有多少格因此不可见。
            builder.push("stellar");
            stellarPatternRowsPerPage = builder
                    .comment("星律样板总成：普通样板区每页几行（默认 6 = 既有行为）",
                            "列数固定 9（上游分页器的几何写死 9 列，不开放配置）",
                            "本项 × 9 = 每页格数；与下一项相乘 = 界面可见槽数上界（上限 1080）",
                            "改完需要重新打开界面才看到变化；已放置的样板不会因为调小而消失")
                    .defineInRange("patternRowsPerPage", 6, 1, 8);
            stellarPatternPages = builder
                    .comment("星律样板总成：普通样板区最多几页（默认 20 = 既有行为）",
                            "可见槽数 = 9 × 行数 × 页数，超过 1080 的部分会被夹到 1080（上限）",
                            "⚠️ 调小后超出的样板不会丢，只是翻不到；日志会打一条 WARN 指出有多少格不可见")
                    .defineInRange("patternPages", 20, 1, 120);
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
