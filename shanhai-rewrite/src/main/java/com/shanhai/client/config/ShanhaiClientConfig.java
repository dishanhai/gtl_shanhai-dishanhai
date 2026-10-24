package com.shanhai.client.config;

import com.shanhai.ShanhaiMod;
import com.shanhai.client.holo.ShanhaiHoloPersist;
import com.shanhai.client.holo.ShanhaiHoloSurroundTuning;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 山海的<b>客户端配置</b>（{@code config/shanhai-client.toml}）。
 *
 * <h2>为什么必须有这一份</h2>
 * 终末之环的最终位姿只有「进游戏看一眼」才能判定 —— 而光环落在哪、多大、歪不歪，
 * 受用户装的 YSM 模型、模型缩放、模型自身骨骼影响。把 8 个旋钮交给用户，
 * 比让他等我们重编一次 jar 快得多（这正是他上一个同类 mod 的做法，他已熟悉）。
 *
 * <h2>🔴 两条硬约定（都是用户上一个 mod 定过的口径）</h2>
 * <ol>
 *   <li><b>变换顺序 = {@code translate → rotate → scale}（缩放放最后）</b>。
 *       这样位移量的单位含义不受缩放影响（1 格 = 16 像素），用户调过的数不用重调。
 *       实现见 {@link com.shanhai.client.render.halo.HaloPose#applyTo}。</li>
 *   <li><b>注册必须在 {@code @Mod} 构造器里</b>（{@code ModLoadingContext.get().registerConfig(...)}）。
 *       放进 {@code FMLClientSetupEvent} 的话，游戏内 Mods 列表认不出 Config 按钮 ——
 *       用户就没法在游戏里点齿轮改数。见 {@code ShanhaiMod} 构造器。</li>
 * </ol>
 *
 * <h2>默认值的来源（不是拍的）</h2>
 * <ul>
 *   <li>{@code [halo_end.model_pose]} 全 0 / 1.0 —— <b>照抄启示录原版</b>
 *       （{@code OdamaneHaloLayer.render} 在 Curios 路径下除模型的 head/Halo 部件自身位移外
 *       <b>不施加任何额外变换</b>，所以"默认 = 什么都不加"就是原版行为；改了反而错）。</li>
 *   <li>{@code [halo_end.ysm_pose]} = {@code 0 / 20 / -5 px}、{@code 0/0/180 度}、{@code scale 1.0} ——
 *       位姿（位置 / 旋转）<b>逐值抄自用户 2026-09-21 实机验收通过的</b>
 *       {@code test/config/ysm_render_compat-client.toml} 的 {@code [halo_of_the_end]} 一节；
 *       只有 {@code scale} 于 2026-10-03 按用户实测要求改成 {@code 1.0}
 *       （用户原话：「大小不要是1.5，要是1，1是正常大小」）。
 *       ⚠️ {@code scale} 是<b>线性倍率、在渲染路径上只施加一次</b>
 *       （取证：{@code temp\halo-scale\verify\ScaleCountProbe} 的实测几何比 = 1.500 而不是 2.250）。</li>
 * </ul>
 * ⚠️ <b>改完要重启游戏才生效</b>（本类在渲染每帧读 {@code .get()}，
 * 但 Forge 配置文件本身的重新加载只在游戏启动时发生；游戏内 Mods→Config 界面改动
 * 会触发 {@code ModConfigEvent}，但为稳妥起见，改数后重启一次最保险）。
 *
 * <h2>🆕 2026-10-07：{@code [holo_menu]} —— 悬浮全息菜单的显示设置（20 项）</h2>
 *
 * <h3>为什么 20 项全进 <b>CLIENT</b>（= {@code config/shanhai-client.toml}），一项都不进 COMMON</h3>
 * 判据只有一条：<b>谁会读这个值</b>。逐项查过（grep 全工程）：
 * <ul>
 *   <li>菜单状态 4 项（开关 / 朝向 / 排布 / 距离）—— 读它的是
 *       {@code ShanhaiHoloMenuState}（{@code @OnlyIn(Dist.CLIENT)}）、渲染器、输入层、以及
 *       {@code /shanhai menu} 这条<b>客户端</b>命令（注册在 {@code RegisterClientCommandsEvent}，
 *       客户端就地执行、<b>不发包</b>，见 {@code ShanhaiHoloMenuClient} 的字节码取证）。
 *       <b>服务端一处都不读</b>：全息是纯客户端画的，服务端连"菜单开着"都不知道。</li>
 *   <li>环绕档位 1 项 + 环绕取值 15 项 —— 读它的是 {@code ShanhaiHoloSurround} /
 *       {@code ShanhaiHoloSurroundRenderer}（每帧的绘制遍），同样<b>只在客户端</b>。</li>
 * </ul>
 * ⇒ 没有"服务端也要读"的那一类（原假设的"菜单开合影响命令树下发"<b>不成立</b>：
 * 开合只影响客户端渲染，命令树的下发是服务端按权限发的，与本开关无关）
 * ⇒ <b>20 项全部 CLIENT</b>。放进 COMMON 反而会让专用服务端也生成一份它永远不读的显示设置。
 *
 * <h3>🔴 单一真相源：{@code [holo_menu]} 是唯一持久副本（load-once + 改值即落盘）</h3>
 * <ul>
 *   <li>进世界那一刻读一次进运行副本（{@code ShanhaiHoloPersist.ensureLoaded()}）；</li>
 *   <li>之后命令 / 面板里<b>每改一格</b>都立刻写回本文件并 {@code CLIENT_SPEC.save()}；</li>
 *   <li>⇒ 本文件与游戏里看到的永远一致；{@code ShanhaiHoloPersist.auditLine()} 逐项比对两者。</li>
 *   <li>⚠️ 所以<b>手改本文件的值会在下次进世界时被读进去</b>（那之前游戏里的改动会覆盖掉手改）
 *       —— 这正是"配置是唯一持久副本"的代价，也是用户点单「全存」要的行为。</li>
 * </ul>
 *
 * <h3>默认值</h3>
 * 逐项 = 加持久化之前那些常量（{@code ShanhaiHoloPersist.DEFAULT_*} / {@code defaultOf(i)}）。
 * 配置缺失 / 读失败 / 值越界 / 枚举名不认识 ⇒ 一律回这些默认值 + 一行 WARN
 * ⇒ 行为与加持久化之前<b>逐项相等</b>。
 */
public final class ShanhaiClientConfig {

    /**
     * 走哪条姿势路径。
     *
     * <ul>
     *   <li>{@link #AUTO}（默认）：按<b>渲染器类型</b>自动判定 ——
     *       渲染器是原版 {@code PlayerRenderer} ⇒ 走 {@code [model_pose]}（CuriosLayer 已经在画了）；
     *       不是 ⇒ 走 {@code [ysm_pose]}（YSM 自算姿势）。</li>
     *   <li>{@link #SELF_POSE}：<b>强制自算姿势</b>（不看渲染器类型，且主动让 Curios 路径闭嘴）。
     *       万一自动判定在用户机器上选错了，他一键切到这里即可。</li>
     *   <li>{@link #MODEL_POSE}：<b>强制原版路径</b>（YSM 兜底路径完全不画）。</li>
     * </ul>
     */
    public enum PoseMode {
        AUTO,
        SELF_POSE,
        MODEL_POSE
    }

    public static final ForgeConfigSpec CLIENT_SPEC;

    /**
     * 🆕 键名 ⇒ {@code [holo_menu]} 那一节的 {@code ConfigValue}（键名 = {@link ShanhaiHoloPersist}
     * 里的路径常量，逐字相同）。
     *
     * <p>用一张表而不是 20 个字段：落盘层是"按键名读写"的（见 {@link ShanhaiHoloPersist.Store}），
     * 表结构让它一处就能改，也不会出现"字段名对了、键名写错"的不一致。
     *
     * <p>🔴 <b>它必须声明在下面的 {@code static {}} 之前</b> —— Java 的静态初始化按<b>书写顺序</b>执行：
     * 写在后面的话，{@code static {}} 里的 {@code reg(...)} 会往一个还没被 new 出来的 map 里 put，
     * 类初始化直接抛 NPE（实测：{@code Cannot invoke "java.util.Map.put(Object,Object)" because
     * "ShanhaiClientConfig.HOLO" is null}）。那会让整个 mod 加载失败。
     */
    private static final java.util.Map<String, ForgeConfigSpec.ConfigValue<?>> HOLO =
            new java.util.LinkedHashMap<>();

    private static final ForgeConfigSpec.EnumValue<PoseMode> POSE_MODE;

    private static final ForgeConfigSpec.BooleanValue HALO_ENABLED;

    // ---- [halo_end.model_pose] 原版权威路径（CuriosLayer 内部）----
    private static final ForgeConfigSpec.DoubleValue MODEL_OFFSET_X;
    private static final ForgeConfigSpec.DoubleValue MODEL_OFFSET_Y;
    private static final ForgeConfigSpec.DoubleValue MODEL_OFFSET_Z;
    private static final ForgeConfigSpec.DoubleValue MODEL_ROT_X;
    private static final ForgeConfigSpec.DoubleValue MODEL_ROT_Y;
    private static final ForgeConfigSpec.DoubleValue MODEL_ROT_Z;
    private static final ForgeConfigSpec.DoubleValue MODEL_SCALE;

    // ---- [halo_end.ysm_pose] YSM 自算姿势路径（RenderLivingEvent.Post）----
    private static final ForgeConfigSpec.DoubleValue YSM_OFFSET_X;
    private static final ForgeConfigSpec.DoubleValue YSM_OFFSET_Y;
    private static final ForgeConfigSpec.DoubleValue YSM_OFFSET_Z;
    private static final ForgeConfigSpec.DoubleValue YSM_ROT_X;
    private static final ForgeConfigSpec.DoubleValue YSM_ROT_Y;
    private static final ForgeConfigSpec.DoubleValue YSM_ROT_Z;
    private static final ForgeConfigSpec.DoubleValue YSM_SCALE;

    // ---- 🆕 [debug] 黑洞渲染件（com.shanhai.client.render.blackhole）----
    //   4 个键 = 4 个【默认值】：总开关 / 调试中心 / 视界倍率 / 偏折强度。
    //   🔴 运行期以 `/shanhai bh …` 命令为准（命令不落盘，重启后回这里的默认值）。
    //   读它们的是客户端渲染，服务端一处不读 ⇒ CLIENT。
    private static final ForgeConfigSpec.BooleanValue BLACKHOLE_ENABLED;
    private static final ForgeConfigSpec.ConfigValue<String> BLACKHOLE_CENTER;
    private static final ForgeConfigSpec.DoubleValue BLACKHOLE_SCALE;
    private static final ForgeConfigSpec.DoubleValue BLACKHOLE_STRENGTH;
    /** 🔴 2026-10-25：光影下黑洞兜底贴图（默认 true；false ⇒ 回到"宁可不画"）。 */
    private static final ForgeConfigSpec.BooleanValue BLACKHOLE_FALLBACK_TEXTURE;
    // 🔴 2026-10-24：归墟视界枢核「动态特效」的两个键（**追加在末尾**，老键一个没动、默认值一个没改）
    //    ⛔ `maxDistance`（距离裁剪）已在同一天删除 —— 用户原话「走到 65 格以外 特效全停、
    //       真方块回来这个功能我不要」。删它还有一层原因：控制室天然离环心约 73 格，
    //       旧判定让特效从站体里永远看不见（这正是"特效一点没看见"的根因）。
    private static final ForgeConfigSpec.BooleanValue HOSTFX_ENABLED;
    private static final ForgeConfigSpec.BooleanValue HOSTFX_HIDE_REAL_BLOCKS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("终末之环（shanhai:halo_end）的头顶渲染参数。",
                        "位置单位 = 像素（1 格 = 16 像素）：X = 左右，Y = 上下（正 = 上），Z = 前后。",
                        "旋转单位 = 度：X = 俯仰，Y = 偏航，Z = 翻滚。",
                        "缩放 = 倍数：1.0 = 原大小。",
                        "变换顺序恒为 位移 → 旋转 → 缩放（缩放放最后，所以位移量不受缩放影响）。",
                        "⚠️ 改完请重启游戏。")
                .push("halo_end");

        HALO_ENABLED = b.comment("是否渲染终末之环的头顶光环。")
                .define("enabled", true);

        POSE_MODE = b.comment("走哪条姿势路径：AUTO = 按渲染器类型自动判定（默认）；",
                        "SELF_POSE = 强制自算姿势（自动判定不对时切这个）；",
                        "MODEL_POSE = 强制原版路径（本 mod 不补画）。")
                .defineEnum("poseMode", PoseMode.AUTO);

        // 两组位姿必须各占【自己的小节】。少了这两个 push，"scale / position / rotation"
        // 会在同一个路径上被定义两遍 ⇒ 后者覆盖前者 ⇒ model_pose 的默认值会静默变成
        // YSM 那组（Curios 路径就会被多施加一次位移）。实测判据见 verify\ConfigSpecProbe。
        b.comment("【A 原版/Curios 路径】的额外位姿。默认全 0 / 1.0 = 与启示录逐值等价（不改就对）。")
                .push("model_pose");
        MODEL_SCALE = b.comment("缩放倍数。原版 = 1.0。")
                .defineInRange("scale", 1.0D, 0.05D, 20.0D);
        b.comment("位移（像素）。原版 = 全 0（启示录在 Curios 路径下不施加额外位移）。").push("position");
        MODEL_OFFSET_X = b.defineInRange("offsetX", 0.0D, -512.0D, 512.0D);
        MODEL_OFFSET_Y = b.defineInRange("offsetY", 0.0D, -512.0D, 512.0D);
        MODEL_OFFSET_Z = b.defineInRange("offsetZ", 0.0D, -512.0D, 512.0D);
        b.pop();
        b.comment("旋转（度）。原版 = 全 0。").push("rotation");
        MODEL_ROT_X = b.defineInRange("rotX", 0.0D, -3600.0D, 3600.0D);
        MODEL_ROT_Y = b.defineInRange("rotY", 0.0D, -3600.0D, 3600.0D);
        MODEL_ROT_Z = b.defineInRange("rotZ", 0.0D, -3600.0D, 3600.0D);
        b.pop();
        b.pop();

        b.comment("【B 自算姿势/YSM 路径】的位姿。默认值 = 用户 2026-09-21 在 YSM 下实机验收值。")
                .push("ysm_pose");
        YSM_SCALE = b.comment("缩放倍数。默认 1.0 = 原大小（线性倍率，只施加一次）。")
                .defineInRange("scale", 1.0D, 0.05D, 20.0D);
        b.comment("位移（像素）。默认 0 / 20 / -5 = 用户实机验收值（YSM 下光环浮在头顶上方）。")
                .push("position");
        YSM_OFFSET_X = b.defineInRange("offsetX", 0.0D, -512.0D, 512.0D);
        YSM_OFFSET_Y = b.defineInRange("offsetY", 20.0D, -512.0D, 512.0D);
        YSM_OFFSET_Z = b.defineInRange("offsetZ", -5.0D, -512.0D, 512.0D);
        b.pop();
        b.comment("旋转（度）。默认 0 / 0 / 180 = 用户实机验收值。")
                .push("rotation");
        YSM_ROT_X = b.defineInRange("rotX", 0.0D, -3600.0D, 3600.0D);
        YSM_ROT_Y = b.defineInRange("rotY", 0.0D, -3600.0D, 3600.0D);
        YSM_ROT_Z = b.defineInRange("rotZ", 180.0D, -3600.0D, 3600.0D);
        b.pop();
        b.pop();

        b.pop();

        // ================================================================ 🆕 2026-10-07
        //   [holo_menu] —— 悬浮全息菜单的显示设置（20 项）。
        //   为什么全进 CLIENT（一项都不进 COMMON）与"唯一持久副本"的口径：见类注释那两节。
        //   🔴 键名一律取 ShanhaiHoloPersist 里的路径常量（不是在这里另抄一遍字符串）
        //      ⇒ 落盘层与配置层用的是同一个键，不存在"对不上"。
        b.comment("悬浮全息菜单的显示设置：投影开关 / 朝向档 / 排布档 / 距离 / 环绕特效档位 + 15 项取值。",
                        "⚠️ 这些值会被游戏内的改动【立刻覆盖】：/shanhai menu … 与全息设置面板里每改一格，",
                        "   本文件当场重写一次。手改的值在【下次进世界】时生效（在那之前游戏里的改动会盖掉手改）。",
                        "值不合法（越界 / 枚举名不认识 / 读不出来）⇒ 回默认值并打一行 WARN：",
                        "  enabled=false  facing=FIXED  layout=RING  distance=2.8  preset=MID  15 项 = 「中」档那组数。")
                .push("holo_menu");
        reg(b.comment("投影开关的默认值（=/shanhai menu on|off 与面板上的那个开关）。",
                        "false = 加持久化之前的行为：进游戏不自启，右键模块才开。")
                .define("enabled", ShanhaiHoloPersist.DEFAULT_ENABLED), ShanhaiHoloPersist.K_ENABLED);
        reg(b.comment("朝向档：FOLLOW = 平滑转向玩家；SNAP = 立即转向玩家；",
                        "FIXED = 世界固定（默认；2026-10-06 实测选出的那一档，见 ShanhaiHoloMenuState.Facing）。",
                        "⚠️ 环绕档（默认排布）下这三档都不生效。")
                .defineEnum("facing", ShanhaiHoloPersist.DEFAULT_FACING), ShanhaiHoloPersist.K_FACING);
        reg(b.comment("排布档：RING = 五块板沿水平面绕玩家一圈（默认）；COLUMN = 上下叠成柱；ROW = 左右排成横排。")
                .defineEnum("layout", ShanhaiHoloPersist.DEFAULT_LAYOUT), ShanhaiHoloPersist.K_LAYOUT);
        reg(b.comment("全息浮在玩家前方多远 / 环绕档下这一圈离玩家多远（格）。",
                        "区间与 /shanhai menu distance 的参数区间、面板 ± 的夹取范围是同一个来源。")
                .defineInRange("distance", (double) ShanhaiHoloPersist.DEFAULT_DISTANCE,
                        (double) ShanhaiHoloPersist.DISTANCE_MIN, (double) ShanhaiHoloPersist.DISTANCE_MAX),
                ShanhaiHoloPersist.K_DISTANCE);

        b.comment("环绕特效（那一圈数据环 + 星尘点场）。").push("surround");
        reg(b.comment("档位：OFF = 不画（15 项取值原样保留，切回来现场还在）；",
                        "WEAK / MID / STRONG = 整组替换下面那 15 项。MID = 出厂默认（= 加持久化之前那组数）。")
                .defineEnum("preset", ShanhaiHoloPersist.Preset.MID), ShanhaiHoloPersist.K_PRESET);

        b.comment("环绕特效的 15 项取值（顺序与面板「高级调参」4 页逐项一致；键名见各项注释）。")
                .push("params");
        for (int i = 0; i < ShanhaiHoloSurroundTuning.PARAM_COUNT; i++) {
            final String key = ShanhaiHoloPersist.paramKey(i);
            final String name = ShanhaiHoloPersist.PARAM_KEYS[i];
            final String unit = ShanhaiHoloSurroundTuning.PARAM_UNIT[i];
            final float def = ShanhaiHoloPersist.defaultOf(i);
            final float lo = ShanhaiHoloPersist.paramMin(i);
            final float hi = ShanhaiHoloPersist.paramMax(i);
            final String c = ShanhaiHoloSurroundTuning.PARAM_NAME[i]
                    + (unit.isEmpty() ? "" : "（单位 " + unit + "）")
                    + "。默认 " + ShanhaiHoloSurroundTuning.fmt(def, ShanhaiHoloSurroundTuning.PARAM_DECIMALS[i])
                    // 🔴 2026-10-10 修注释（**只改注释文本**：键名 fieldCover ／ 默认值 ／ 键结构一个字节都没动）。
                    //    第 16 项「覆盖范围」的 0/1/2 是【档号】不是数 ⇒ 原来那句"覆盖范围。默认 1"
                    //    与事实不符（读的人会当成"1 = 某个数"）：那一档叫【半球】，= 出厂档。
                    //    档名从核里那一份取（ShanhaiHoloSurroundTuning#coverText），配置层不另抄一遍。
                    + (i == ShanhaiHoloSurroundTuning.P_FIELD_COVER
                            ? " = " + ShanhaiHoloSurroundTuning.coverText(Math.round(def))
                                    + "（三档：0 环带 / 1 半球 / 2 全球；半球 = 出厂档：星尘从地平线一直铺到天顶）"
                            : "");
            if (ShanhaiHoloPersist.paramIsInt(i)) {
                reg(b.comment(c).defineInRange(name, Math.round(def), (int) lo, (int) hi), key);
            } else {
                reg(b.comment(c).defineInRange(name, (double) def, (double) lo, (double) hi), key);
            }
        }
        b.pop();   // params
        b.pop();   // surround
        b.pop();   // holo_menu
        // ⚠️ 这里【不许】再 pop 一次：halo_end 那一层已经在上面第 190 行 pop 掉了
        //    （实测踩过：多一次 pop ⇒ 类初始化抛
        //     IllegalArgumentException: Attempted to pop 1 elements when we only had: []）。
        // ⚠️ 层级账（push/pop 必须配平）：halo_end 已在 190 行关掉 ⇒ 本节的三个 push 各自配一个 pop。

        // ================================================================ 🆕 2026-10-19
        //   [debug] —— 只给"主机中心那颗黑洞"的渲染件用的两个调试开关。
        //   为什么必须有：主机本体还没做出来，而黑洞效果只能在游戏里判 ⇒ 用一行坐标就能先看效果。
        //   为什么在 CLIENT：读它的是客户端渲染（BlackHoleLensRenderer），服务端一处不读。
        //   为什么默认空 = 不画：装上本 mod 的人不该平白无故在世界上看见一颗黑盘。
        b.comment("调试用。[debug] 段只给黑洞渲染件（主机中心那颗）用，不影响任何正式玩法。",
                        "blackhole_center 填 \"x y z\"（三个数字、空白分隔、世界坐标）⇒ 那个位置画一颗黑洞；",
                        "空 = 什么都不画（默认）。单位 = 方块，一般填主机中心的方块坐标。",
                        "blackhole_enabled = 总开关；false ⇒ 连调试坐标也不看。",
                        "⚠️ 改完要重启游戏（Forge 配置文件只在启动时加载）。")
                .push("debug");

        BLACKHOLE_ENABLED = b.comment("黑洞渲染件的总开关（默认 true；只有填了 blackhole_center 才看得见东西）。")
                .define("blackhole_enabled", true);

        BLACKHOLE_CENTER = b.comment("黑洞中心的世界坐标，格式 \"x y z\"（例如 \"120.5 64 -33\"）。",
                        "空串 = 不画（默认）。格式不对 ⇒ 打一行 WARN（[SHANHAI-BH] center_malformed）并当作没填。",
                        "整数维会自动按方块中心补 +0.5（120 ⇒ 120.5），并打一行 center_snapped 说明补了多少。",
                        "运行期可用 /shanhai bh center <x> <y> <z>（支持 ~）当场改，不落盘。")
                .define("blackhole_center", "");

        BLACKHOLE_SCALE = b.comment("【默认值】视界半径倍率（运行期用 /shanhai bh scale 覆盖）。",
                        "史瓦西半径 = 1.0 格 × 本倍率；黑盘半径 = 2.598 × 史瓦西半径。",
                        "范围 [0.05, 8]。")
                .defineInRange("blackhole_scale", 1.0D, 0.05D, 8.0D);

        BLACKHOLE_STRENGTH = b.comment("【默认值】引力偏折强度倍率（运行期用 /shanhai bh strength 覆盖）。",
                        "0 = 背景不弯（吸积盘/光子环照旧）、1 = 物理弱场偏折、2 = 夸张效果。",
                        "范围 [0, 2]。")
                .defineInRange("blackhole_strength", 1.0D, 0.0D, 2.0D);

        // 🔴 2026-10-25 追加（父代理批准第四节那两条）：光影下也要画黑洞的【安全兜底】
        BLACKHOLE_FALLBACK_TEXTURE = b.comment(
                        "光影包生效（或一级透镜画不出来）时，用一张【程序化生成的常规贴图】给黑洞兜底（默认 true）。",
                        "true  ⇒ 光影下也能看见黑洞；贴图走 NativeImage + TextureManager（MC 自己的上传路径），",
                        "        这条路上【没有】任何 _genTexture/_texImage2D 裸调用 —— 2026-10-24 驱动崩溃那条路径不会被走回来。",
                        "false ⇒ 回到 2026-10-24 的「宁可不画」：光影下一律不画黑洞（[SHANHAI-BH] fallback=3 reason=fallback_off_by_config）。",
                        "⚠️ 改完要重启游戏（Forge 配置文件只在启动时加载）。")
                .define("blackhole_fallback_texture", true);

        b.pop();   // debug

        // ═════════════════════════════ 归墟视界枢核 · 动态特效（2026-10-24 追加） ═════════════════════════════
        //   这些键都是【纯客户端】：读它们的是 client/render/hostfx/** 的 TESR，服务端一处不读。
        b.comment("归墟视界枢核（主机）的【动态特效】：内环绕中心轴转动、上下 8 艘磁悬浮飞船、",
                        "内外环之间的动态光束、黑洞两极喷流，以及把黑洞挂到控制室那台机器的中心。",
                        "⚠️ 只是渲染，方块一个都不变。⚠️ 改完要重启游戏（Forge 配置文件只在启动时加载）。")
                .push("hostfx");

        HOSTFX_ENABLED = b.comment("总开关。false ⇒ 这些特效一律不画（真方块也不藏，就是普通结构）。")
                .define("enabled", true);

        // ⛔ 2026-10-24 删除 `maxDistance`（距离裁剪整条移除）：用户原话「走到 65 格以外 特效全停、
        //    真方块回来这个功能我不要」。另一层原因：控制室天然离环心约 73 格 ⇒ 旧判定让特效
        //    从站体里永远看不见（这就是"特效一点没看见"的根因）。老 toml 里残留这个键不会报错（Forge 忽略未知键）。

        HOSTFX_HIDE_REAL_BLOCKS = b.comment("是否把内环带与 8 艘飞船的【真方块】在客户端临时藏起来（默认 true）。",
                        "true ⇒ 看到的是「转动的环 + 飞行的飞船」（造型里那些静止真块被藏起来，未成型时按台账写回）；",
                        "false ⇒ 真块留着，动效叠在上面（两层的观感，但没有客户端写方块这一层风险）。",
                        "⚠️ 只有在特效真的出问题时才建议关掉它。")
                .define("hideRealBlocks", true);

        b.pop();   // hostfx

        CLIENT_SPEC = b.build();

        // 🔴 把落盘出口装上：ShanhaiHoloPersist 是纯逻辑（不 import Forge），
        //    真正读写 ForgeConfigSpec 的是下面这个 HoloStore —— "谁是唯一持久副本"因此只有一处。
        ShanhaiHoloPersist.useStore(new HoloStore());
    }

    private ShanhaiClientConfig() {}

    // 读配置时全部兜底：配置还没加载时 .get() 会抛，宁可退回"原版默认值"也不让渲染线程炸。

    // ==================================================================== 🆕 [holo_menu] 那 20 个键

    /** 登记一个 holo 键（返回原值，便于写成一行）。 */
    private static <T extends ForgeConfigSpec.ConfigValue<?>> T reg(T value, String key) {
        HOLO.put(key, value);
        return value;
    }

    /** 配置是否已加载（与 {@code ShanhaiConfig.isLoaded()} 同款兜底）。 */
    public static boolean isLoaded() {
        return CLIENT_SPEC.isLoaded();
    }

    /** 看一眼某个 holo 键现在写到 toml 路径是什么（自检 / 报告用）。 */
    public static java.util.Set<String> holoKeys() {
        return java.util.Collections.unmodifiableSet(HOLO.keySet());
    }

    /**
     * 🔴 {@code [holo_menu]} 的落盘出口（{@link ShanhaiHoloPersist.Store} 的唯一生产实现）。
     *
     * <p>三条纪律：
     * <ol>
     *   <li>读：任何异常 / 键不存在 ⇒ 返回调用方给的 {@code def}（<b>绝不抛</b>，
     *       配置没加载时 {@code .get()} 会抛 {@code IllegalStateException}）；</li>
     *   <li>写：只写值，<b>不 save</b>（save 由 {@link #save()} 统一做，一次改动只落盘一次）；</li>
     *   <li>{@link #save()} = {@code CLIENT_SPEC.save()} —— 这就是"立刻写进 toml"那一步。</li>
     * </ol>
     */
    private static final class HoloStore implements ShanhaiHoloPersist.Store {

        @Override
        public boolean loaded() {
            return CLIENT_SPEC.isLoaded();
        }

        @Override
        public boolean getBool(String key, boolean def) {
            final Object o = raw(key, def);
            return o instanceof Boolean b ? b : def;
        }

        @Override
        public double getDouble(String key, double def) {
            final Object o = raw(key, def);
            return o instanceof Number n ? n.doubleValue() : def;
        }

        @Override
        public int getInt(String key, int def) {
            final Object o = raw(key, def);
            return o instanceof Number n ? n.intValue() : def;
        }

        @Override
        public String getEnumName(String key) {
            final Object o = raw(key, null);
            if (o instanceof Enum<?> e) {
                return e.name();
            }
            return o instanceof String s ? s : null;
        }

        @Override
        public void setBool(String key, boolean value) {
            if (HOLO.get(key) instanceof ForgeConfigSpec.BooleanValue v) {
                v.set(value);
            }
        }

        @Override
        public void setDouble(String key, double value) {
            if (HOLO.get(key) instanceof ForgeConfigSpec.DoubleValue v) {
                v.set(value);
            }
        }

        @Override
        public void setInt(String key, int value) {
            if (HOLO.get(key) instanceof ForgeConfigSpec.IntValue v) {
                v.set(value);
            }
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void setEnumName(String key, String value) {
            final ForgeConfigSpec.ConfigValue<?> raw = HOLO.get(key);
            if (!(raw instanceof ForgeConfigSpec.EnumValue v)) {
                ShanhaiMod.LOGGER.warn("{} holo_enum_not_found key={} got={}",
                        ShanhaiHoloPersist.PREFIX, key, raw == null ? "null" : raw.getClass().getName());
                return;
            }
            if (value == null) {
                return;
            }
            try {
                Enum target = null;
                try {
                    final Object now = v.get();
                    if (now instanceof Enum<?> e) {
                        target = Enum.valueOf((Class) e.getDeclaringClass(), value.trim().toUpperCase(java.util.Locale.ROOT));
                    }
                } catch (Throwable t) {
                    ShanhaiMod.LOGGER.warn("{} holo_enum_read_failed key={} err={}",
                            ShanhaiHoloPersist.PREFIX, key, t.toString());
                }
                if (target == null) {
                    final Object def = v.getDefault();
                    target = Enum.valueOf((Class) def.getClass(), value.trim().toUpperCase(java.util.Locale.ROOT));
                }
                v.set(target);
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn("{} holo_enum_setting_failed key={} value={} err={}",
                        ShanhaiHoloPersist.PREFIX, key, value, t.toString());
            }
        }

        @Override
        public boolean save() {
            try {
                CLIENT_SPEC.save();
                return true;
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn("{} holo_config_save_failed err={}", ShanhaiHoloPersist.PREFIX, t.toString());
                return false;
            }
        }

        private static Object raw(String key, Object def) {
            final ForgeConfigSpec.ConfigValue<?> v = HOLO.get(key);
            if (v == null) {
                ShanhaiMod.LOGGER.warn("{} holo_key_missing key={}", ShanhaiHoloPersist.PREFIX, key);
                return def;
            }
            try {
                final Object o = v.get();
                if (o == null) {
                    ShanhaiMod.LOGGER.warn("{} holo_value_null key={} path={}",
                            ShanhaiHoloPersist.PREFIX, key, v.getPath());
                    return def;
                }
                return o;
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn("{} holo_value_read_failed key={} path={} err={}",
                        ShanhaiHoloPersist.PREFIX, key, v.getPath(), t.toString());
                return def;
            }
        }
    }


    public static boolean haloEnabled() {
        try {
            return HALO_ENABLED.get();
        } catch (Throwable t) {
            return true;
        }
    }

    // ==================================================================== 🆕 [debug] 黑洞渲染件

    /**
     * {@code [debug] blackhole_enabled}。
     *
     * <p>读不到（配置还没加载 / 键不存在）⇒ <b>false</b>：宁可这一帧不动画面，也不要在
     * 配置半加载状态下去建 GL 资源。
     */
    public static boolean blackholeEnabled() {
        try {
            return BLACKHOLE_ENABLED.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * {@code [debug] blackhole_center} 的<b>原始字符串</b>（不在这里解析：解析器是
     * {@code com.shanhai.client.render.blackhole.BlackHoleDebugSpec}，它可以离线跑值表）。
     *
     * <p>读不到 ⇒ 空串（= 不画）。
     */
    public static String blackholeCenter() {
        try {
            return BLACKHOLE_CENTER.get();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * {@code [debug] blackhole_scale} 的<b>默认值</b>（运行期以 {@code /shanhai bh scale} 为准）。
     *
     * <p>读不到 ⇒ 1.0（= 不做任何缩放，即本件原来的行为）。
     */
    public static double blackholeScale() {
        return dv(BLACKHOLE_SCALE, 1.0D);
    }

    /**
     * {@code [debug] blackhole_strength} 的<b>默认值</b>（运行期以 {@code /shanhai bh strength} 为准）。
     *
     * <p>读不到 ⇒ 1.0（= 物理弱场偏折 {@code α = 2Rs/b}）。
     */
    public static double blackholeStrength() {
        return dv(BLACKHOLE_STRENGTH, 1.0D);
    }

    /**
     * {@code [debug] blackhole_fallback_texture} —— 光影下黑洞<b>兜底贴图</b>开关。
     *
     * <p>读不到 ⇒ {@code true}（<b>默认开</b>：用户就是要看见黑洞）。
     * <p>关掉它 = 回到 2026-10-24 的「宁可不画」：光影下一律不画黑洞。
     * 打开时走的是 {@code NativeImage + TextureManager}（MC 自己的上传路径），
     * <b>不经过任何裸 GL 纹理调用</b>。
     */
    public static boolean blackholeFallbackTexture() {
        try {
            return BLACKHOLE_FALLBACK_TEXTURE.get();
        } catch (Throwable t) {
            return true;
        }
    }

    // ═════════════════════ 归墟视界枢核 · 动态特效（2026-10-24 追加，读不到一律回到"默认行为"） ═════════════════════

    /** 动态特效总开关；读不到 ⇒ {@code true}（默认开）。 */
    public static boolean hostFxEnabled() {
        try {
            return HOSTFX_ENABLED.get();
        } catch (Throwable t) {
            return true;
        }
    }

    // ⛔ 2026-10-24 删除 hostFxMaxDistance()：距离裁剪整条移除（用户明确不要；且它是"一点没看见"的根因）。

    /** 是否在客户端把内环带与 8 艘飞船的真方块临时藏起来；读不到 ⇒ {@code true}（默认藏）。 */
    public static boolean hostFxHideRealBlocks() {
        try {
            return HOSTFX_HIDE_REAL_BLOCKS.get();
        } catch (Throwable t) {
            return true;
        }
    }

    public static PoseMode poseMode() {
        try {
            return POSE_MODE.get();
        } catch (Throwable t) {
            return PoseMode.AUTO;
        }
    }

    public static double[] modelPose() {
        return new double[]{
                dv(MODEL_OFFSET_X, 0.0D), dv(MODEL_OFFSET_Y, 0.0D), dv(MODEL_OFFSET_Z, 0.0D),
                dv(MODEL_ROT_X, 0.0D), dv(MODEL_ROT_Y, 0.0D), dv(MODEL_ROT_Z, 0.0D),
                dv(MODEL_SCALE, 1.0D)
        };
    }

    public static double[] ysmPose() {
        return new double[]{
                dv(YSM_OFFSET_X, 0.0D), dv(YSM_OFFSET_Y, 20.0D), dv(YSM_OFFSET_Z, -5.0D),
                dv(YSM_ROT_X, 0.0D), dv(YSM_ROT_Y, 0.0D), dv(YSM_ROT_Z, 180.0D),
                dv(YSM_SCALE, 1.0D)
        };
    }

    private static double dv(ForgeConfigSpec.DoubleValue v, double fallback) {
        try {
            return v.get();
        } catch (Throwable t) {
            return fallback;
        }
    }
}
