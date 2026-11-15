package com.shanhai.client.holo;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import com.shanhai.common.holo.ShanhaiHoloMenuBoards;
import com.shanhai.common.holo.ShanhaiHoloMenuNetwork;
import com.shanhai.common.holo.ShanhaiHoloRemoteCodec;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

/**
 * 山海重构 · 悬浮全息菜单的<b>世界空间渲染器</b>。
 *
 * <h2>0. 与三条既有先例的关系（本轮挑的路，以及为什么）</h2>
 * <table border="1">
 *   <tr><th>先例</th><th>它怎么挂</th><th>本轮为什么不照抄</th></tr>
 *   <tr><td>{@code AbstractRingRenderer} /
 *       {@code PrimordialOmegaEngineRingBuffer}</td>
 *       <td>GTCEu 的机器渲染器（{@code hasTESR=true}），挂在一个<b>方块实体</b>上</td>
 *       <td>用户要的是「<b>悬浮在世界里、跟随玩家移动</b>，不是放置在地面上的一个东西」
 *           ⇒ 没有方块实体可挂 ⇒ 必须换入口</td></tr>
 *   <tr><td>{@code HaloEndRenderer}</td>
 *       <td>实体渲染事件（{@code RenderLivingEvent}），挂在<b>某个实体</b>上</td>
 *       <td>同上：没有实体可挂</td></tr>
 *   <tr><td>{@code ShanhaiFontStyleMixin}</td>
 *       <td>{@code Font.drawInBatch} 的 mixin</td>
 *       <td>本类<b>只复用</b>它的文字绘制通道（见 §4），不新加任何 mixin</td></tr>
 * </table>
 * ⇒ <b>本轮采用的入口 = Forge 的 {@code RenderLevelStageEvent}</b>（工程里此前没有用过它，
 * javap 已核实其存在与方法表，见 §3）。这是"在世界空间、与任何方块/实体无关地画一个东西"的
 * 标准入口，也是"跟随玩家"这一条唯一不别扭的实现方式。
 *
 * <h2>1. 🔴 为什么它<b>不可能</b>被做成 HUD（用户硬要求④的落地）</h2>
 * <ol>
 *   <li><b>坐标是世界坐标</b>：顶点位置 = {@code 玩家眼睛 + 前方 DISTANCE 格 + 世界偏移}，
 *       再减去相机位置 ⇒ 它在世界里有一个真实位置。</li>
 *   <li><b>它是世界几何、有真实位置</b>（不是 HUD 图层）：顶点位置 = 世界坐标 − 相机位置，
 *       而且默认<b>吃深度测试</b>（{@code lequal}）⇒ 中间隔着的方块会挡住它。
 *       HUD 永远不会被方块挡住 —— 这一条就是"不是 HUD"的可判定证据。
 *       <p>🆕 2026-11-08：为了让"看不见板"这件事能<b>被实验判死</b>，新增了一个运行时开关
 *       {@code /shanhai_add holo ontop}（默认 <b>on</b>，见 {@link ShanhaiHoloOntop}）：
 *       on 时我们这几块板的深度测试被关掉、不写深度、文字走原版 {@code SEE_THROUGH}
 *       ⇒ 板浮到世界之上；画完立即把深度状态还原（{@link #restoreGlDepthState()}）。
 *       <b>位姿/尺寸/朝向/数量一个数都没动</b> —— 这条开关只碰 GL 状态。</li>
 *   <li><b>朝向是插值的</b>⇒ 快速扭头时它慢半拍，<b>那半拍里你看到的是它的侧面</b>，
 *       近大远小成立（见 {@link ShanhaiHoloMenuState} §3）。</li>
 * </ol>
 *
 * <h2>2. 🔴 图层顺序与两种混合模式（一个静态 VBO 都没有，逐帧重建）</h2>
 * <p>⚠️ 下表是 <b>ontop = off</b> 那一档（= 改动前逐位不变）；<b>ontop = on</b>（默认）时：
 * 第 1 遍换 {@code shanhai_holo_plate_ontop}（深度测试关、<b>不写深度</b>）、
 * 第 2 遍换 {@code shanhai_holo_glow_ontop}（同上）、第 3 遍换原版 {@code RenderType.textSeeThrough}。
 * 混合模式/层序/几何在两种档位下相同。
 * <pre>
 *   第 1 遍 {@link #plateType()} —— 普通 alpha 混合、<b>写深度</b>
 *        底板 / 顶边高光 / 左侧流光条 / 外描边 / 四角括号
 *   第 2 遍 {@link #glowType()}  —— <b>加法</b>混合（SRC_ALPHA, ONE）、<b>不写深度</b>
 *        内发光 / 描边外发光 / 四角外发光 / 描边流光 / 扫描线 / 色散干扰线
 *   第 3 遍 原版 {@code Font} 的 {@code RenderType.text(...)}
 *        序号 / 中文标签（含 ±1bu 的红青色散三遍）/ 英文副标题
 * </pre>
 * 层与层之间靠 <b>沿板面法线的 z 偏移</b> 分开（{@code LAYER_*_BU}，相邻至少 2 bu ≈ 0.0082 格）。
 * 这个间距是原版投影在 2.8 格处深度分辨率（约 1e-5 格）的 800 倍 ⇒ 不会 z-fighting；
 * 在屏幕上又只有 0.16 度视差 ⇒ 肉眼看不出层。
 *
 * <p>🔴 <b>不碰贴图图集</b>：所有几何都是 {@code POSITION_COLOR}（无 UV）。
 * 本工程那条「图集绝对 UV 存进静态 VBO ⇒ 资源重载后串贴图」的坑（见
 * {@code PrimordialOmegaEngineRingBuffer} 的类注释）在本类<b>不适用</b>：
 * 顶点每帧现算、没有任何 VBO、也没有任何 UV。副作用是 F3+T / 换资源包<b>不需要任何自愈逻辑</b>。
 *
 * <h2>3. 🔴 它挂在哪个 stage，以及为什么（javap 取证的坐标约定）</h2>
 * 选 {@code AFTER_PARTICLES}：它在「半透明地形 → 实体 → 方块实体 → 粒子」之后、
 * 云层/透明后处理之前 ⇒ 全息<b>叠在世界几何之上，但依然受深度测试约束</b>
 * （被挡住时会被挡住，这是我们要的）。
 *
 * <p><b>顶点怎么变换（这一条必须讲清楚，否则整块会画错位置）</b>：
 * {@code javap -c} 取证链：
 * <pre>
 *   GameRenderer.render : 233  new PoseStack()          ← 【全新、单位矩阵】的 PoseStack
 *                       : 240  renderLevel(f, j, that)
 *   LevelRenderer.renderLevel : 对上面那个 PoseStack 施加相机旋转与 −camPos 平移
 *                             : 2743 dispatchRenderStage(AFTER_PARTICLES, …, aload_1 = 该 PoseStack, …)
 *   RenderSystem.getModelViewMatrix() 在整段关卡渲染里是【单位矩阵】
 *   （反证：{@code DebugRenderer.render(PoseStack, …, camX, camY, camZ)} 与
 *     {@code LevelRenderer.renderWorldBorder} 都是"自己把 −camPos 烘进顶点"的，
 *     若 ModelView 还要再乘一遍，整个原版的调试线与世界边界框都会画歪。）
 *   ⇒ <b>把 {@code event.getPoseStack()} 乘出来的矩阵直接烘进顶点即可</b>，
 *     这正是 {@code LevelRenderer.renderLineBox(PoseStack, VertexConsumer, …)} 的用法。
 * </pre>
 *
 * <h2>4. 文字用哪条通道</h2>
 * {@code Font.drawInBatch(String, …)}（<b>11 参那个</b>）—— 它是"裸字符串"文字渲染的收口，
 * 本工程 {@code ShanhaiFontStyleMixin} 正是在这里接管 {@code &$…-} 样式前缀的。
 * 我们发的都是<b>不带前缀</b>的普通文本 ⇒ 那条 mixin 会走它自己的便宜退出口
 * （{@code text.indexOf('&') < 0} 立刻返回）⇒ 原版渲染。<b>没有新增任何 mixin 注入点</b>。
 *
 * <p>⚠️ {@code Font.drawInBatch} 的坐标系 <b>+Y 朝下</b>（GUI 系），而本类的板内坐标系 <b>+Y 朝上</b>
 * ⇒ 文字矩阵必须带一个 <b>{@code scale(k, −k, 1)}</b> 把 Y 翻过来，否则字是倒的。
 */
@OnlyIn(Dist.CLIENT)
public final class ShanhaiHoloMenuRenderer {

    private ShanhaiHoloMenuRenderer() {}

    /** 全亮的光照值（{@code LightTexture.pack(15,15)} = 0xF000F0）。全息是自发光的，不吃世界光照。 */
    private static final int LIGHT_FULL_BRIGHT = 0xF000F0;

    /** 描边流光沿板周长采样成多少段（段越多越平滑，20 段已经看不出折线）。 */
    private static final int FLOW_SEGMENTS = 20;

    // ---- 🆕 2026-10-06：点了「配方修改」之后那 0.x 秒的「按下 + 正在加载」----
    //
    //   用户原话：「我发现点击之后会卡个 0.几秒再弹出面版，这并不是什么问题，毕竟需要加载时间，
    //             但是我希望可以有一个按钮按下的效果，然后可以告诉玩家正在加载，直到弹出面版」
    //
    //   🔴 这两个量【每帧算一次】存在静态字段里，供本类三个绘制遍共用；
    //      渲染线程单线程，不存在并发（与 plateType / glowType 那两个缓存同款做法）。
    //   状态机本身在 {@link ShanhaiHoloMenuPending}（纯算术、离线可验），本类只负责画。
    /** 这一帧"正在被按下"的那一格（0 基）；{@code -1} = 没有。 */
    private static int pressBoard = -1;

    /** 这一帧的按压量（0..1）与"正在加载"的亮度呼吸（0..1）。 */
    private static float pressAmount;
    private static float pressBreath;

    // ---- 🆕 2026-10-06：展开/收回动画的整体淡入淡出 ----
    //
    //   🔴 不走 {@code RenderSystem.setShaderColor}（那要依赖 position_color 着色器里
    //      有没有 ColorModulator，本轮没法在本机取证）⇒ 把透明度<b>自己乘进顶点与文字</b>：
    //        · 所有几何最后都过 {@link #vert(VertexConsumer, Matrix4f, float, float, float, int, float)}
    //          这一个漏斗 ⇒ 在那里乘一次就覆盖全部底板/发光/进度条；
    //        · 文字走 {@link #alphaScaled(int, float)}（只压 alpha，不压 RGB ——
    //          压 RGB 会变"黑"，那不是淡出）。
    //   完全展开时它是 1.0 ⇒ {@code dimmed} 与这里的乘法都是恒等，画面与动画加进来之前逐位一致。
    private static float globalAlpha = 1.0f;

    // ---- 🆕 2026-10-06：二级面板（设置 / 命令）----
    //
    //   用户点单：「点开配置设置的也是全息的面版」「全息输入框，可以执行cmd的命令」。
    //   🔴 面板【不是】另一个渲染器：它就是"同一块板 × 5 行"（见 ShanhaiHoloMenuPanel §2），
    //      所以本类的三个绘制遍一行都没改，只是把"板从哪来"换成 panelRows()、
    //      把"文字从哪来"换成 ShanhaiHoloMenuPanel.cellText()。
    //   这两个量每帧由 onRenderLevelStage 填一次，供三个绘制遍共用（渲染线程单线程）。
    /** 本帧画的面板模式（{@code 0} = 五块菜单板）。 */
    private static int panelMode = ShanhaiHoloMenuPanel.MODE_NONE;

    /** 本帧射线指到的那一行 / 那一格（{@code -1} = 没有）—— 面板模式下用它做高亮。 */
    private static int panelHoverRow = -1;
    private static int panelHoverCell = -1;

    /** 面板正文的顶端 y（bu）：字号 1.5~1.6 倍 ⇒ 行高 ≈ 14 bu，居中就是 ±7。 */
    private static final float PANEL_TEXT_TOP_Y_BU = 7.5f;

    /** 面板格子里文字距格子左边的内缩（bu）。 */
    private static final float PANEL_CELL_PAD_BU = 12.0f;

    /** 面板标签的字号（比选项大一点，"哪一行"一眼能看见）。 */
    private static final float PANEL_LABEL_SCALE = 1.6f;

    /** 面板选项的字号。 */
    private static final float PANEL_OPTION_SCALE = 1.5f;

    /**
     * 🆕 <b>命令面板输入框里的字号</b>（用户第 6 条点单：「输入的地方大一点」）。
     * <p>1.6 → 2.0：字高从 14.4 bu 变成 18 bu（板高 62 bu ⇒ 仍留得下上下各 22 bu 的余量）。
     * 宽度方向的"大"靠<b>跨格</b>（文字从板左边缘起画，横跨全部 4 格 = 560 bu）。
     * <p>⚠️ 2026-10-06（第 ② 条点单）之后输入框<b>只占一行</b>：原来"跨两块行板"那一半
     * （{@code COMMAND_INPUT_ROW_2}）已经删掉 —— 它在屏幕上看起来就是"两个框叠着"。
     */
    private static final float PANEL_INPUT_SCALE = 2.0f;

    /** 输入区外框的线宽（bu）。 */
    private static final float PANEL_FIELD_BORDER_BU = 2.0f;

    /**
     * 每帧那个 {@code BufferBuilder} 的<b>容量下限</b>（顶点数）。
     *
     * <p>⚠️ 传进去的数<b>不是字节数</b>：{@code BufferBuilder(int capacity)} 内部做的是
     * {@code MemoryTracker.create(capacity * 6)}（源码原文）⇒ 1&lt;&lt;14 = 16384 对应
     * <b>98,304 字节（96 KiB）</b>。
     * <p>对照：原来写的是 {@code 1 << 18} ⇒ 1,572,864 字节（1.5 MB）/ 帧。
     * <p>🔴 2026-10-07（联机 P3）：<b>实际容量不再用这个常量</b>，而是
     * {@link ShanhaiHoloPose#bufferCapacityFor}{@code (1 + 远程人数)} ——
     * 它把本常量当下限（独狼时逐位不变），人多了才往上长。
     * 唯一一份定义搬到了 {@link ShanhaiHoloPose#BUFFER_CAPACITY_FLOOR}（那边能离线验）。
     */
    private static final int PER_FRAME_BUFFER_CAPACITY = ShanhaiHoloPose.BUFFER_CAPACITY_FLOOR;

    /**
     * {@link #drawSpaced} 复用的那把矩阵（每帧 ~100 次 {@code new Matrix4f(board)} ⇒ 全砍掉）。
     * <p>安全性：{@code Font.drawInBatch(...)} <b>不会留着</b>这个矩阵（它当场把顶点算完），
     * 而 {@link #drawSpaced} 只在渲染线程上顺序调用 ⇒ 复用不可能撞车。
     * <p>⚠️ 每次都要 {@code set(board)} 先复位：那把矩阵是"板内坐标系"的基，
     * 漏了复位就会把上一次的字距/缩放累乘进去（观感会是字越画越小）。
     */
    private static final Matrix4f SCRATCH_MATRIX = new Matrix4f();

    /**
     * 🆕 2026-10-06：{@link ShanhaiHoloSurroundRenderer} 要的那份"相机视图矩阵"（= 事件给的 poseStack
     * 在<b>被 translate/mulPose 之前</b>的那一份）。同样是复用，不每帧 new。
     * <p>安全性：{@code RenderLevelStageEvent} 的 post 里本类顺序执行，环绕层当场把它读成 16 个 float。
     */
    private static final Matrix4f VIEW_MATRIX = new Matrix4f();

    /**
     * 0..127 的单字符 String 缓存 —— {@link #drawSpaced} 逐字符画，
     * 原来是每字符一次 {@code String.valueOf(char)}（每帧 ~100 次小对象）。
     * <p>只缓存 ASCII（我们画的英文/数字/符号全在里面）；中文走原来的路（走缓存表反而更慢）。
     */
    private static final String[] ASCII_CHARS = new String[128];

    static {
        for (int i = 0; i < ASCII_CHARS.length; i++) {
            ASCII_CHARS[i] = String.valueOf((char) i);
        }
    }

    /** 单字符 String（ASCII 走缓存，其余现造）。 */
    private static String charString(char c) {
        return c < 128 ? ASCII_CHARS[c] : String.valueOf(c);
    }

    // ------------------------------------------------------------------ 两个自定义 RenderType

    /** {@code POSITION_COLOR} 的着色器（javap 已核实 {@code GameRenderer.getPositionColorShader()} 是 public static）。 */
    private static final RenderStateShard.ShaderStateShard POSITION_COLOR_SHADER =
            new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader);

    /** 普通 alpha 混合（与甲案 CSS 的默认 alpha 合成一致）。 */
    private static final RenderStateShard.TransparencyStateShard ALPHA_BLEND =
            new RenderStateShard.TransparencyStateShard(
                    "shanhai_holo_alpha",
                    () -> {
                        RenderSystem.enableBlend();
                        RenderSystem.blendFuncSeparate(
                                GlStateManager.SourceFactor.SRC_ALPHA,
                                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                                GlStateManager.SourceFactor.ONE,
                                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
                    },
                    () -> {
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                    });

    /** <b>加法</b>混合 —— 全息发光层用它（CSS 里的 {@code mix-blend-mode:screen} 同一个意图）。 */
    private static final RenderStateShard.TransparencyStateShard ADDITIVE_BLEND =
            new RenderStateShard.TransparencyStateShard(
                    "shanhai_holo_additive",
                    () -> {
                        RenderSystem.enableBlend();
                        RenderSystem.blendFunc(
                                GlStateManager.SourceFactor.SRC_ALPHA,
                                GlStateManager.DestFactor.ONE);
                    },
                    () -> {
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                    });

    private static RenderType plateType;
    private static RenderType glowType;

    // ------------------------------------------------------------------ 🆕 2026-11-08：「画到最上层」（ontop）
    //
    //   实机读数（本轮新构建）已经把"看不见板"的候选排掉两条：
    //     drawn_boards=5 alpha=1.000 scale=1.000   ⇒ 不是没画
    //     occluder=none                             ⇒ 不是被特效盖
    //   剩下唯一没排除的是【世界深度遮挡】：板画在 AFTER_PARTICLES（那时地形深度已经在缓冲里），
    //   而板子离玩家只有 2.8 格 ⇒ 贴墙/贴方块时会被地形深度挡掉。
    //   ⇒ 本实验：把"我们这几块板"的深度测试临时关掉（画完恢复），其余一个字都不动。
    //
    //   🔴 为什么不能只在外面写一句 `RenderSystem.disableDepthTest()`：
    //      RenderType 的 `setupRenderState()` 是在【flush 那一刻】才跑的（`BufferSource.endBatch`），
    //      而 plateType 自己的 DepthTestStateShard("lequal",515) 会把深度测试重新打开
    //      ⇒ 外面那一句会被覆盖，实验会得到假阴性（"ontop 打开了却还是被挡"）。
    //      唯一能改的落点是 RenderType 的深度状态块本身。
    //
    //   🔴 为什么只能【继承 DepthTestStateShard】：
    //      `RenderType.CompositeState.CompositeStateBuilder.setDepthTestState(...)` 的形参类型
    //      就是 `RenderStateShard$DepthTestStateShard`（javap 原文）⇒ 传别的类型编译期就红。
    //      而原版那个 ctor 的 setup 是 `if (func != 519) { enableDepthTest(); depthFunc(func); }`
    //      （javap `DepthTestStateShard.lambda$new$0`）—— 它关不掉深度测试，只能换 func
    //      ⇒ 必须覆盖 `setupRenderState()/clearRenderState()`（两者在 RenderStateShard 里是
    //      `public void`、非 final，javap 已核）。
    //
    //   ⚠️ 顺带一条取证：原版的 `NO_DEPTH_TEST` 本身就是 `DepthTestStateShard("always", 519)`
    //      （javap `RenderStateShard` <clinit>：ldc "always" / sipush 519）—— 也就是说
    //      "关深度测试"在这套 API 里的家族做法就是"永远通过 + 不写深度"。本轮更进一步：
    //      真的调 `RenderSystem.disableDepthTest()`，于是日志里可以诚实地写 `-> 0`。

    /** 运行时开关（<b>不落配置</b>；默认值只有一处定义 = {@link ShanhaiHoloOntop#DEFAULT_ON}）。 */
    private static boolean ontopOn = ShanhaiHoloOntop.initial();

    /** 本帧是否<b>真的</b>按 ontop 画（= {@link #ontopOn}；early-return 那一支一块板都不画 ⇒ 不碰 GL）。 */
    private static boolean ontopFrame;

    /** 本帧开头采样到的原始深度状态（画完按它恢复，别人的绘制看到的就是这个值）。 */
    private static boolean origDepthTest = true;
    private static int origDepthFunc = 515;

    /** 文字那一遍的显示模式：ontop 时用原版 {@code SEE_THROUGH}（它的 RenderType 是 NO_DEPTH_TEST + 只写颜色）。 */
    private static Font.DisplayMode textDisplayMode = Font.DisplayMode.NORMAL;

    /**
     * ontop 专用深度状态块：setup 里真的关掉深度测试，clear 里还原成原版 depth shard 的收尾状态
     * （{@code disableDepthTest()} + {@code depthFunc(515)}，javap {@code lambda$new$1} 同款）。
     *
     * <p>为什么 clear 之后是"关"而不是"开"：原版每个 DepthTestStateShard 的 clear 就是关掉，
     * 于是"不设深度状态"的 RenderType（例如 {@code text_see_through} 用的 {@code NO_DEPTH_TEST}，
     * 它的 setup 是空操作）会继承上家留下的"关" ⇒ 文字也跟着在最上层，不需要第二套机制。
     * 整帧画完之后由 {@link #restoreGlDepthState()} 按本帧开头的原值恢复。
     */
    private static final class OntopDepthShard extends RenderStateShard.DepthTestStateShard {
        OntopDepthShard(String name) {
            super(name, 519);                       // 519 = GL_ALWAYS（原版 NO_DEPTH_TEST 的值）
        }

        @Override
        public void setupRenderState() {
            RenderSystem.disableDepthTest();
        }

        @Override
        public void clearRenderState() {
            RenderSystem.disableDepthTest();
            RenderSystem.depthFunc(515);            // 与原版 DepthTestStateShard 的 clear 同款
        }
    }

    /** ontop 的深度状态块（只需一个实例，状态块本身无状态）。 */
    private static final RenderStateShard.DepthTestStateShard ONTOP_DEPTH =
            new OntopDepthShard("shanhai_holo_ontop");

    /** 改动前就在用的那个深度状态块（{@code lequal}/{@code 515}），抽出来只为了给四个 RenderType 共用同一份。 */
    private static final RenderStateShard.DepthTestStateShard LEQUAL_DEPTH =
            new RenderStateShard.DepthTestStateShard("lequal", 515);

    /**
     * 四个 {@code POSITION_COLOR} 批次的<b>唯一</b>构造点（改动前后的差别只有 name/混合/深度/写深度 四项）。
     *
     * <p>⚠️ 参数取值与改动前逐项相同（{@code 1<<16} 顶点、{@code affectsCrumbling=false}、
     * {@code sortOnUpload=true}、{@code cull=false}、{@code createCompositeState(false)}）——
     * 这条是"ontop=off 时与已验收的渲染状态逐位一致"的机械保证。
     *
     * @param writeDepth 普通底板 = {@code true}（五块板的前后关系靠深度）；发光层与 ontop 两档 = {@code false}
     */
    private static RenderType holoType(String name,
                                       RenderStateShard.TransparencyStateShard transparency,
                                       RenderStateShard.DepthTestStateShard depth,
                                       boolean writeDepth) {
        return RenderType.create(
                name,
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS,
                1 << 16,
                false,
                true,
                RenderType.CompositeState.builder()
                        .setShaderState(POSITION_COLOR_SHADER)
                        .setTransparencyState(transparency)
                        .setDepthTestState(depth)
                        .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, writeDepth))
                        .setCullState(new RenderStateShard.CullStateShard(false))
                        .createCompositeState(false));
    }

    /** ontop 的两档（懒建，只在 {@link #ontopFrame} 为 true 时被取到）。 */
    private static RenderType plateOntopType;
    private static RenderType glowOntopType;

    /**
     * 底板层：普通 alpha、<b>写深度</b>、不剔除、深度测试 {@code lequal}。
     * <p>写深度是刻意的：五块板隔着 0.08~0.32 格的前后关系<b>由深度决定</b>，
     * 否则半透明板会按提交顺序互相糊在一起。
     * <p>🆕 ontop 档：深度测试关、<b>不写深度</b>（不写是因为写进去会把我们自己的"最上层"卖给后面的
     * 云层/黑洞透镜 —— 板会变成"挡得住别人"的东西，那不是本实验要的）。
     */
    private static RenderType plateType() {
        if (ontopFrame) {
            if (plateOntopType == null) {
                plateOntopType = holoType("shanhai_holo_plate_ontop", ALPHA_BLEND, ONTOP_DEPTH, false);
            }
            return plateOntopType;
        }
        if (plateType == null) {
            plateType = holoType("shanhai_holo_plate", ALPHA_BLEND, LEQUAL_DEPTH, true);
        }
        return plateType;
    }

    /** 发光层：加法混合、<b>不写深度</b>（它只叠加亮度，不该挡住后面的东西）。 */
    private static RenderType glowType() {
        if (ontopFrame) {
            if (glowOntopType == null) {
                glowOntopType = holoType("shanhai_holo_glow_ontop", ADDITIVE_BLEND, ONTOP_DEPTH, false);
            }
            return glowOntopType;
        }
        if (glowType == null) {
            glowType = holoType("shanhai_holo_glow", ADDITIVE_BLEND, LEQUAL_DEPTH, false);
        }
        return glowType;
    }

    // ------------------------------------------------------------------ 入口

    /**
     * {@code RenderLevelStageEvent} 处理器（由 {@link ShanhaiHoloMenuClient} 转调，便于离线判读调用点）。
     *
     * <p>第一行就是 stage 边判 + 开关边判 ⇒ <b>关着的时候本方法的稳态开销 = 两次比较</b>。
     */
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        // ---- 🆕 2026-10-10（A）：渲染帧间隔诊断（零行为改变，只记时间）----
        //   判据：holo_frame_gap ms=… since=render_frame tick_gap_ms=…
        //   为什么需要它：上一轮那个"板上不来"的唯一指纹就是"某一帧 ≥ 1.53 s"，
        //   而旧代码里那种长帧是【看不见】的（它只表现为动画少推进一次）。
        final long frameNanos = System.nanoTime();
        // 🆕 2026-11-09：主帧缓冲深度可读性的【最早一次】采样（纯只读，1 秒 1 次）。
        //   位置刻意在【我们改任何 GL 状态之前】：这一行之上只打过一行日志，
        //   一个 RenderSystem/GL 写调用都没有 ⇒ 这里读到的是"上家留给我们的"帧缓冲。
        //   为什么要它：旧读数的 55 帧 na 与 18 帧 0.000000 分不开"读失败"和"真的被挡"
        //   （详见 ShanhaiHoloDebugBox 类注释 §4）。
        sampleMainDepthEarlyIfDue(frameNanos);
        logFrameGap(frameNanos);

        // ---- 🆕 2026-11-09：遮挡探针（🔴 纯只读采样，500 ms 一次）----
        //   ① 本帧的两个计数清零（板数 / 顶点数；
        //      **不是** 上面那两个 drawnBoards/drawnPeers —— 那两个是累计量，语义不同，别混）。
        //   ② GL 状态必须在【我们自己设任何 GL 状态之前】采：到这一行为止，本方法只打过一行日志，
        //      一个 RenderSystem/GL 调用都没有 ⇒ 这里读到的就是"上家留给我们的"，不是我们自己的。
        //      ⚠️ 晚了就等于读自己的 —— 那这条读数一文不值。
        probeFrameBegin();
        sampleGlIfDue(frameNanos);

        // ---- 🔴 2026-10-10（B）：动画推进点已从【渲染帧】搬到【客户端 tick】----
        //   旧写法（就在这一行）：
        //       ShanhaiHoloMenuAnim.advanceFrame(ShanhaiHoloMenuState.enabled(), frameNanos);
        //   🔴 为什么那是错的（用户实测的真因，不是推测）：AFTER_PARTICLES 在一帧的【末尾】，
        //      而"开 → 关"两次右键可以落在同一个没走完的帧里（实测间隔 1.53 s）⇒
        //      那一帧的 AFTER_PARTICLES 永远没走到 ⇒ advanceFrame 一次没被调用 ⇒ progress 停在 0
        //      ⇒ 下面 localVisible = false ⇒ 在画板之前就 return（用户看到的就是"5 块板没了"）。
        //   新落点 = {@link #onClientTickEnd()}（ClientTickEvent.Phase.END）。
        //   顺序证据（javap）：Minecraft.tick() 里 offset 17 = onPreClientTick、offset 828 = onPostClientTick；
        //      右键事件由 handleKeybinds → startUseItem 在【同一个 tick 更早】发出，之后才轮到 offset 828，
        //      再之后才是这一帧的渲染 ⇒ "这一帧点了开关"必然先于"这一帧推进"，
        //      不可能再出现"开了却一帧都没画"。
        //   ⚠️ 推进必须在"开关边判"【之前】这条老规矩仍然成立 —— 只是现在它由 tick 保证：
        //      收起来的那 0.28 秒里逻辑开关已经是 false 了，而 tick 侧照样在推进 ⇒ 画面继续画到 0。
        resyncIfTicksStalled(frameNanos);
        // 🔴 2026-10-07（联机 P3）：**"本地收起"不再等于"整帧什么都不画"**。
        //    原因是一条很实在的场景：投影的默认值是【关】（{@code DEFAULT_ENABLED=false}）
        //    ⇒ 照原来的写法，"我自己没开投影"就永远看不见别人的板子，
        //      而那恰恰是用户拍板④要的"显示别人的板子：默认开"。
        //    ⇒ 拆成两个条件：本地那一份画不画（localVisible）、别人那一份画不画（remoteCount > 0）。
        //    ⚠️ 本地可见时（hidden()==false）下面的代码与改动前【逐行相同】——
        //      只是多了这一层 `if (localVisible)` 的包装，读数是逐位不变的。
        final boolean localVisible = !ShanhaiHoloMenuAnim.hidden();
        final Minecraft minecraft = Minecraft.getInstance();
        final net.minecraft.client.player.LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        // 🆕 这一帧要画【几个别人】——只此一处判据（距离 / 他自己开着没 / 他的 DEF 到了没 / 在不在屏幕里）。
        //   🔴 必须在建 BufferBuilder **之前**算出来：顶点容量要跟着可见人数走（见 ShanhaiHoloPose.bufferCapacityFor）。
        final int remoteCount = collectRemotePeers(minecraft, player, event);
        if (!localVisible && remoteCount == 0) {
            // 🆕 2026-11-08：即使【一块板都没画】，也每 500 ms 留一行 visibility 读数。
            //   否则"根本没开（progress=0）"这一种病因在日志里完全没有指纹 ——
            //   旧代码在这一行 return 之后什么都不打，于是"看不到"与"没开"在日志上长得一模一样。
            //   ⚠️ 这一支不改任何行为：只读 state、只打日志，画板那一整段照旧不跑。
            visCenterCount = 0;
            // 🆕 遮挡探针：这一拍【一块板都没画】（本方法在这里就 return 了）
            //   ⇒ 显式发布 0 板 0 顶点，不把上一帧的读数留成"这一帧的现场"。
            probePublishNothing();
            logVisibilityIfDue(frameNanos, minecraft, event, event.getPoseStack().last().pose(),
                    false, 0.0, 0.0, 0.0, 0.0f, 0.0f, 1.0f, player);
            return;                             // 本地完全收起、且没有别人 ⇒ 与"从没开过"一样便宜
        }

        // 推进跟随插值（渲染线程，每帧一次；理由见 ShanhaiHoloMenuState §5）
        //   ⚠️ 面板开着的时候【也要】推 —— 否则关掉面板回到五块板时，锚点的上一帧时刻是陈旧的，
        //      那一下会看到整块"飞"过去（dt 被夹到 0.25s，但插值会真的走一大步）。
        //   ⚠️ 本地收起时也推：别人还在画，本地的锚点不能停在陈旧的时刻上（下次开投影会"飞"）。
        ShanhaiHoloMenuState.advanceHolo(player, event.getPartialTick());

        // 🆕 面板模式：这一帧画设置/命令面板，还是五块菜单板
        panelMode = ShanhaiHoloMenuState.panelMode();
        panelHoverRow = ShanhaiHoloMenuState.panelHoverRow();
        panelHoverCell = ShanhaiHoloMenuState.panelHoverCell();

        final float tick = event.getRenderTick() + event.getPartialTick();
        final float tSec = tick / 20.0f;

        final PoseStack poseStack = event.getPoseStack();
        final Vec3 camera = event.getCamera().getPosition();
        // 锚点与框偏航：面板与菜单是【两套】落地位姿（面板贴在面前、菜单环绕玩家），
        //   两者都由几何核给出，本行不自己推导任何一个数。
        final Vec3 anchor;
        final float frameYawDeg;
        if (panelMode == ShanhaiHoloMenuPanel.MODE_NONE) {
            anchor = ShanhaiHoloMenuState.position();
            frameYawDeg = ShanhaiHoloMenuState.frameYaw();
        } else {
            // 🆕 2026-10-06（用户实测第 ③ 条）：面板的位姿【全部来自状态层】——
            //   它与菜单共用同一套朝向档：默认「世界固定」= 打开那一刻钉住，
            //   于是玩家扭头时准星会横着扫过整块面板（每一格都能指到）。
            //   🔴 这里【不许】再自己写一遍 lp.getYRot()：输入层的指着/点击读的是同一个函数，
            //      两处各算一遍就会漂（修复前面板"完全没法选"的根因之一）。
            final Vec3 eye = player.getEyePosition(event.getPartialTick());
            final double[] c = ShanhaiHoloMenuState.panelCenter(eye);
            anchor = new Vec3(c[0], c[1], c[2]);
            frameYawDeg = ShanhaiHoloMenuState.panelFrameYaw();
        }

        // 🆕 展开动画的四个量（完全展开时逐位等于 1/1/0/1 ⇒ 与动画加进来之前【一模一样】）
        final float ease = ShanhaiHoloMenuAnim.originLerp();
        final float animScale = ShanhaiHoloMenuAnim.scale01();
        final float animSpin = ShanhaiHoloMenuAnim.spinDeg();
        globalAlpha = ShanhaiHoloMenuAnim.alpha01();
        // 🆕 遮挡探针：把这一帧真正要乘进顶点/文字的两个量留一份（只读采样，画面一个字节都不变）
        probeAlpha = globalAlpha;
        probeScale = animScale;
        // 原点：从【玩家脚底】插值到锚点。展开完毕时 (ox,oy,oz) == anchor（逐位），
        // 所以"动画加进来之前那套观感"不会被这一行改动。
        final double ox = player.getX() + (anchor.x - player.getX()) * ease;
        final double oy = player.getY() + (anchor.y - player.getY()) * ease;
        final double oz = player.getZ() + (anchor.z - player.getZ()) * ease;

        // 🆕 2026-10-06：环绕层（数据环 + 星尘网格）要的是【原始视图矩阵】（世界 → 相机）。
        //     事件给的 poseStack 在被 translate / mulPose 之前就是它，此后就会被改 —— 所以先拷一份。
        //     复用一把静态矩阵（就是本文件里 SCRATCH_MATRIX 那个套路），不进每帧的对象账。
        //     🔴 它【只有相机旋转、没有 −camPos】：环绕层自己补平移（ShanhaiHoloSurroundView）。
        //     ⚠️ 放在 applyHoloFrame 之前读的是同一份值：pushPose 只【复制】当前矩阵，不改它的值。
        final Matrix4f viewMatrix = VIEW_MATRIX.set(poseStack.last().pose());
        // 🔴 2026-10-07（联机 P1）：L384–401 那一段【整段抽出】成 applyHoloFrame（见该方法的注释）。
        //    抽出的证明（逐位一致）：抽出前 R、t 由这里的上下文算好、就地乘进去；
        //    抽出后 R、t 是同一批数由参数传进去 ⇒ 算式一个字没变，矩阵乘的顺序也没变。
        applyHoloFrame(poseStack, camera, ox, oy, oz, frameYawDeg, animSpin, animScale, tSec);

        // 🔴 板位姿来自几何核的同一个函数（环境档 = 绕玩家一圈；旧两档 = 甲案桶形弧面）
        // 🆕 面板模式 = ShanhaiHoloMenuLayout.panelRows()（5 块行板竖着叠）。
        final ShanhaiHoloMenuLayout.Board[] boards =
                panelMode == ShanhaiHoloMenuPanel.MODE_NONE
                        ? ShanhaiHoloMenuLayout.boardsOf(
                                ShanhaiHoloMenuState.layoutName(), ShanhaiHoloMenuState.distance())
                        : ShanhaiHoloMenuPanel.rows();

        // 🆕 2026-11-08：可见性读数（每 500 ms 最多一行）。
        //   🔴 位置刻意选在【位姿都算好、一块板都还没提交】这一刻：
        //      · 用的是这一帧真正要用的 ox/oy/oz + 框偏航 + 动画自转/缩放（同一个数，不另算一套）；
        //      · 只读 state/anim，不写任何字段（除了本类自己的诊断快照）⇒ 不改任何已验收行为；
        //      · 板心只在"这一帧真要打日志"时才算（每帧算 5 次三角函数没有意义）。
        //   板心世界坐标 = 渲染器同一个变换（{@code applyHoloFrame → poseBoard}），只省掉 0.004 格抖动。
        if (localVisible && visLogDue(frameNanos)) {
            visCenterCount = 0;
            for (int i = 0; i < ShanhaiHoloMenuTuning.BOARD_COUNT && i < boards.length; i++) {
                if (!rowDrawn(i)) {
                    continue;                   // 没画出来的行不算"画了它"
                }
                final ShanhaiHoloMenuLayout.Board b = boards[i];
                ShanhaiHoloViewMath.boardCenterWorld(VIS_SCRATCH, ox, oy, oz, frameYawDeg + animSpin,
                        animScale, b.x(), b.y(), b.z());
                VIS_CENTERS[visCenterCount * 3] = VIS_SCRATCH[0];
                VIS_CENTERS[visCenterCount * 3 + 1] = VIS_SCRATCH[1];
                VIS_CENTERS[visCenterCount * 3 + 2] = VIS_SCRATCH[2];
                visCenterCount++;
            }
        } else {
            visCenterCount = 0;
        }
        logVisibilityIfDue(frameNanos, minecraft, event, VIEW_MATRIX, localVisible,
                ox, oy, oz, frameYawDeg, animSpin, animScale, player);
        // 🆕 2026-11-08：开面板自检（只在"关→开、展开完毕"的那一帧打一行；纯观测）
        //   ⚠️ 位置在 visibility 之后、SurroundRenderer 之前：两者读同一个 MVP，
        //      而 VIEW_MATRIX 在 SurroundRenderer 里可能被改写 ⇒ 必须在那之前。
        logOpenCheckIfDue(frameNanos, event, minecraft, boards,
                ox, oy, oz, frameYawDeg, animSpin, animScale, player);

        // 🆕 本帧的"按下 + 正在加载"（状态机在 ShanhaiHoloMenuPending，这里只取本帧两个数）
        //    ⚠️ 面板模式下【不画】按下/加载：那两者是"点了某块菜单板、等服务端开配方面板"
        //       的反馈，面板那几格是纯客户端即时生效的（点了当场就变），没有"正在加载"这回事。
        final long nowNanos = System.nanoTime();
        pressBoard = (panelMode == ShanhaiHoloMenuPanel.MODE_NONE && ShanhaiHoloMenuPending.isPending())
                ? ShanhaiHoloMenuPending.boardIndex() : -1;
        pressAmount = pressBoard >= 0 ? ShanhaiHoloMenuPending.pressAmount(nowNanos) : 0.0f;
        pressBreath = pressBoard >= 0 ? ShanhaiHoloMenuPending.breath(nowNanos) : 0.0f;

        // 🔴 2026-10-06（顺手修，见报告的「每帧开销」一节）：
        //   原来是 `new BufferBuilder(1 << 18)`。取证：{@code BufferBuilder(int capacity)} 的实现是
        //   {@code this.buffer = MemoryTracker.create(capacity * 6);}（源码原文），
        //   而 {@code MemoryTracker.create} 直接走 LWJGL 的 {@code malloc}
        //   ⇒ 那一行 = **每渲染帧 malloc 1,572,864 字节（1.5 MB）堆外内存**，60 fps 下 ≈ 94 MB/s。
        //   对照原版自己的用法：{@code RenderBuffers} 里那两个
        //   {@code MultiBufferSource.immediate(new BufferBuilder(256))} 都是 **字段**（建一次、反复用），
        //   容量只用 256（= 1536 字节）。
        //   ⇒ 本轮【仍然每帧新建】（最保守的改法：不引入"复用同一个 builder"那一类的状态残留风险），
        //     只把容量从 1<<18 降到 {@link #PER_FRAME_BUFFER_CAPACITY}（= 1<<14 ⇒ 98,304 字节）。核算（逐条都可数）：
        //       单"批"顶点的峰值 ≤ 第 2 遍 5 块板 × 148 顶点 × 16 字节 ≈ 11.8 KB
        //       （同一个 builder 会给所有 RenderType 用，但每次换 RenderType 都先 endBatch 再 begin，
        //         而 begin 里会 rewind ⇒ 峰值是【单批】而不是整帧累计）
        //       ⇒ 96 KiB 是"整帧几何总量 1108 顶点 ≈ 17.7 KB"的 5.5 倍、
        //         更是"单批峰值 ≈ 11.8 KB"的 8 倍，绝不会走到 ensureCapacity 那条"+2 MB realloc"的路。
        // 🔴 2026-10-07（联机 P3）：容量**跟着可见人数走**（设计 §4.4 的那笔账）。
        //    原来是固定 1<<14 = 6144 顶点；"1 本地 + 3 远程"的账是 ≈10101 顶点 ⇒ 3 人就撞上
        //    `ensureCapacity` 那条"+2 MB realloc"的路（每帧一次，60 fps ≈ 120 MB/s）。
        //    下面这个函数按 `1 + 远程人数` 取 2 的幂，且**下限仍是原来的 1<<14**
        //    ⇒ 独狼玩的时候一个字节都不多花（与改动前逐位相同）。
        final int bufferCapacity = ShanhaiHoloPose.bufferCapacityFor(1 + remoteCount);
        // ---- 🆕 2026-11-08：ontop 本帧闸门 ＋ 原 GL 深度状态采样（只在"真要画板"的这一支里做）----
        //   位置：early-return 两支之后、`try` 之前 —— 那一支一块板都不画，所以它不碰任何 GL 状态。
        //   采样放在【我们设任何状态之前】：这就是"上家留给我们的深度状态"，画完要原样还回去。
        ontopFrame = ontopOn;
        textDisplayMode = ontopFrame ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL;
        if (ontopFrame) {
            captureGlDepthState();
        }
        final MultiBufferSource.BufferSource buffers =
                MultiBufferSource.immediate(new BufferBuilder(bufferCapacity));
        try {
            // 🆕 2026-10-06（用户第 ② 条点单）：命令面板【只画两个框】。
            //   可见行由 ShanhaiHoloMenuPanel#rowVisible 一个口径说了算（输入层读的是同一个函数）
            //   ⇒ 没画出来的行不可能被点到，画出来的行不可能点不到。
            //   ⚠️ 这块板【还在板表里】、位姿一个数都没动 —— 只是这一帧不画它，
            //      所以"5 块行板"这套几何（第 6 条点单时定的）原样保留。
            // ---- 第 1 遍：底板层（写深度）----
            //   ⚠️ 2026-10-07（P3）：整段包一层 `if (localVisible)` —— 本地收起时这一遍不跑，
            //      但下面"别人那 5 块板"照跑（否则"自己没开投影就看不见别人"，与拍板④相反）。
            final VertexConsumer plate = buffers.getBuffer(plateType());
            if (localVisible) {
                for (int i = 0; i < ShanhaiHoloMenuTuning.BOARD_COUNT; i++) {
                    if (!rowDrawn(i)) {
                        continue;
                    }
                    pushBoard(poseStack, boards[i]);
                    drawPlateLayers(plate, poseStack.last().pose(), i, tSec);
                    probeBoards++;                  // 🆕 遮挡探针：这一块板【真的提交了】顶点
                    poseStack.popPose();
                }
            }
            // ---- 🆕 2026-10-07（联机 P3）：【别人那 5 块板】----
            //   🔴 位置刻意选在"本地第 1 遍（底板，写深度）之后"：
            //      远程板也走 plateType ⇒ 它也写深度 ⇒
            //      "远程板挡远程板""本地板挡远程板"全由深度缓冲一次做对，不需要任何排序逻辑。
            //   🔴 面板模式（设置/命令）**不画**（用户拍板①）—— 这里传的永远是"5 块菜单板"，
            //      所以"你在点哪一格"和输入框里的字不会漏给别人。
            drawRemoteBoards(poseStack, camera, buffers, remoteCount, tSec, event.getPartialTick());
            // ---- 🆕 第 1.5 遍：环绕层（数据环 + 星尘网格）----
            //   🔴 位置是刻意的：底板这一遍【写深度】已经把五块板的位置占住了 ⇒
            //      环绕层跑到板背后的部分会被深度测试真的挡住；
            //      而它自己【不写深度】（加法发光）⇒ 不会挡住后面的世界几何。
            //   🔴 它【不碰】那 5 块板：板位姿、排布、层序、动效一个字都没动，
            //      环绕层只是"照同样的位姿算出"板面禁区（见 ShanhaiHoloSurround 的规则 ③）。
            //   ⚠️ 板面禁区只认【画出来的】那些板：没画的行给 0 块（见 surroundBoards）。
            final ShanhaiHoloMenuLayout.Board[] surroundRows = surroundBoards(boards);
            final int surroundCount = rowCountCache;
            if (localVisible) {
                ShanhaiHoloSurroundRenderer.render(event, buffers, viewMatrix,
                        net.minecraft.util.Mth.lerp(event.getPartialTick(), player.xOld, player.getX()),
                        net.minecraft.util.Mth.lerp(event.getPartialTick(), player.yOld, player.getY()),
                        net.minecraft.util.Mth.lerp(event.getPartialTick(), player.zOld, player.getZ()),
                        ox, oy, oz, frameYawDeg, animSpin, animScale, surroundRows, surroundCount,
                        tSec, globalAlpha);
            }
            // ---- 第 2 遍：发光层（加法）----
            final VertexConsumer glow = buffers.getBuffer(glowType());
            final float breath = breath(tSec);
            if (localVisible) {
                for (int i = 0; i < ShanhaiHoloMenuTuning.BOARD_COUNT; i++) {
                    if (!rowDrawn(i)) {
                        continue;
                    }
                    pushBoard(poseStack, boards[i]);
                    drawGlowLayers(glow, poseStack.last().pose(), i, tSec, breath);
                    // 🆕 面板模式：被指到的那一行补一根竖条（"指着谁"不含糊）
                    if (panelMode != ShanhaiHoloMenuPanel.MODE_NONE && i == panelHoverRow) {
                        drawPanelHoverMark(glow, poseStack.last().pose());
                    }
                    // 🆕 命令面板：输入框 / 返回框 的外框（第 ② 条点单：两块各一圈、都只占一行）
                    if (panelMode == ShanhaiHoloMenuPanel.MODE_COMMAND
                            && (i == ShanhaiHoloMenuPanel.COMMAND_INPUT_ROW
                            || i == ShanhaiHoloMenuPanel.COMMAND_BACK_ROW)) {
                        drawPanelCommandField(glow, poseStack.last().pose(), i, breath);
                    }
                    poseStack.popPose();
                }
            }
            // ---- 第 3 遍：文字（原版 Font 通道）----
            final Font font = minecraft.font;
            if (localVisible) {
                for (int i = 0; i < ShanhaiHoloMenuTuning.BOARD_COUNT; i++) {
                    if (!rowDrawn(i)) {
                        continue;
                    }
                    pushBoard(poseStack, boards[i]);
                    if (panelMode == ShanhaiHoloMenuPanel.MODE_NONE) {
                        drawTextLayers(font, buffers, poseStack.last().pose(), i, tSec);
                    } else {
                        drawPanelRowText(font, buffers, poseStack.last().pose(), i);
                    }
                    poseStack.popPose();
                }
            }
            // ---- 第 4 遍（🆕）：「正在加载」那根走马灯进度条（纯几何，跟着按下那块板走）----
            if (localVisible && panelMode == ShanhaiHoloMenuPanel.MODE_NONE
                    && pressBoard >= 0 && pressBoard < boards.length) {
                final VertexConsumer bar = buffers.getBuffer(glowType());
                pushBoard(poseStack, boards[pressBoard]);
                drawLoadingSweep(bar, poseStack.last().pose(), nowNanos);
                poseStack.popPose();
            }
        } catch (RuntimeException e) {
            // 渲染线程抛异常 = 直接崩客户端。这里兜一层：宁可这一帧少画点东西，
            // 也不要把用户的存档会话打断（同 ShanhaiRecipeStats#onServerStarted 的口径）。
            com.shanhai.ShanhaiMod.LOGGER.error("[SHANHAI-HOLO] render failed (这一帧未画完，游戏继续)", e);
        } finally {
            try {
                buffers.endBatch();
            } finally {
                // ---- 🔴 画完必须恢复（ontop 只管我们这几块板）----
                //   ① 各 RenderType 的 clear 已经把深度测试留在"关"（原版 DepthTestStateShard 的收尾就这样）；
                //   ② 这里再按【本帧开头采样到的原值】还原一次 —— 于是紧随其后的别人（云层/黑洞透镜/HUD）
                //      看到的深度状态与"我们没画过"时完全一样。两步都在同一个 finally 里：中间抛异常也照样恢复。
                restoreGlDepthState();
                if (ontopFrame) {
                    logRenderStateIfDue(frameNanos);
                }
                ontopFrame = false;
                textDisplayMode = Font.DisplayMode.NORMAL;
                // 🆕 遮挡探针：本帧真的画完了 ⇒ 把计数发布成"探针行读的那一份"。
                //   位置在 endBatch 之后（顶点已经真的提交进 GL 了），在下面那串复位之前
                //   （globalAlpha 马上就要被重置成 1.0f，重置后再取就取到假值了）。
                probePublish();
                pressBoard = -1;
                pressAmount = 0.0f;
                pressBreath = 0.0f;
                globalAlpha = 1.0f;
                panelMode = ShanhaiHoloMenuPanel.MODE_NONE;
                panelHoverRow = -1;
                panelHoverCell = -1;
                poseStack.popPose();
            }
        }
        // ---- 🆕 2026-11-08 / 11-09：三档（＋D 档）调试色块 ----
        //   🔴 位置：整段正常绘制【之后】（上面的 finally 已经把 poseStack 弹回基矩阵、把 GL 深度状态还回去）
        //      ⇒ 这一段<b>一个改动的字都没有</b>地接在后面，正常板的绘制与读数（probeVerts 等）一个字节不变。
        //   🔴 位姿全部【读】本帧已经算好的局部量（boards / ox,oy,oz / frameYawDeg / animSpin / animScale），
        //      不重新推导任何一个数 ⇒ "板子变换"与正常那 5 块板是同一批数。
        //   🔴 只在菜单开着（menu_on=true）时画；默认关（/shanhai_add holo debugbox on 才开）。
        debugBoxes(poseStack, event, camera, boards, ox, oy, oz,
                frameYawDeg, animSpin, animScale, tSec, frameNanos);
    }

    /**
     * 🆕 展开/收回动画每走完一段（或被中途反转）就打一行 —— 用户点名的判据：
     * 「⏱ 动画时长要能读出来（日志打一行，例如 {@code holo_anim open ms=…}）」。
     */
    private static void logAnim(ShanhaiHoloMenuAnim.Step step) {
        final String kind = switch (step.event()) {
            case ShanhaiHoloMenuAnim.EV_OPENED -> "open";
            case ShanhaiHoloMenuAnim.EV_CLOSED -> "close";
            case ShanhaiHoloMenuAnim.EV_REVERSED_TO_CLOSE -> "reversed to=close";
            default -> "reversed to=open";
        };
        // 🆕 2026-11-08：**唯一的**"面板从关→开、且展开完毕"的落点 ⇒ 下一渲染帧打一行 holo_open_check。
        //   放在这里（而不是放在 setEnabled / toggle 里）的理由很硬：动画推进有两个调用点
        //   （tick 的 onClientTickEnd 与卡顿兜底 resyncIfTicksStalled），两者都走本方法
        //   ⇒ 只有一个判据，不会出现"某一条路开了却没人自检"。
        if (step.event() == ShanhaiHoloMenuAnim.EV_OPENED) {
            openCheckDue = true;
        }
        com.shanhai.ShanhaiMod.LOGGER.info("[SHANHAI-HOLO] holo_anim {} ms={} progress={} {}",
                kind, step.elapsedMs(),
                String.format(java.util.Locale.ROOT, "%.3f", step.progress()),
                ShanhaiHoloMenuAnim.describe());
    }

    // ------------------------------------------------------------------ 🆕 2026-10-10（A/B）动画推进点 + 长帧诊断

    /** 上一渲染帧的时刻（{@code 0} = 还没画过第一帧）。 */
    private static long lastRenderFrameNanos;

    /** 上一次打 {@code holo_frame_gap} 的时刻（限流 1 秒 1 行，免得卡顿时刷屏）。 */
    private static long lastGapLogNanos;

    /** 上一次"动画被推进"的时刻（tick 侧或兜底侧，两者共用）。{@code 0} = 还没推过。 */
    private static long lastAdvanceNanos;

    /** 帧间隔超过这个值就打一行（与 {@link ShanhaiHoloMenuAnim#MAX_FRAME_DT_SEC} 同一个 250 ms 口径）。 */
    private static final long FRAME_GAP_WARN_NANOS = 250_000_000L;

    /** 诊断日志的限流间隔（1 秒）。 */
    private static final long FRAME_GAP_LOG_THROTTLE_NANOS = 1_000_000_000L;

    // ------------------------------------------------------------------ 🆕 2026-11-08：板子可见性判据
    //
    //   用户报的现象：「猫在的时候我还是打不开创始现实修改模块的面版」。
    //   而"看不到板"在日志里【没有任何指纹】—— 旧日志里既没有"开没开"，也没有"在不在视野里"。
    //   这一节只做一件事：每 500 ms 留一行可判读数：
    //     [SHANHAI-HOLO] visibility menu_on=… progress=… dist=… ndc=(x,y) in_frustum=… behind=…
    //                            screen=(hud_x,hud_y) env_near=…
    //   ⇒ 下次"看不到"时，一眼就能分开三种病因（判据定义见 {@link ShanhaiHoloViewMath} 类注释 §2/§3）：
    //     ① 根本没开（progress=0.000，且 local_drawn=false）②开了但在视锥外（in_frustum=false）
    //     ③ 在视锥内却看不见（那就是遮挡 / 深度 / 透明度问题）
    //   ⚠️ 纯观测：不改任何位姿、不改任何开关、不进任何绘制路径 —— 只是读已经算好的量。

    /** 可见性读数的限流（500 ms / 行）。 */
    private static final long VIS_LOG_THROTTLE_NANOS = 500_000_000L;

    /** 这一帧该打 visibility 了吗（限流的唯一判据 —— 调用方据此决定要不要先算板心）。 */
    private static boolean visLogDue(long frameNanos) {
        return lastVisLogNanos == 0L || frameNanos - lastVisLogNanos >= VIS_LOG_THROTTLE_NANOS;
    }

    /** 上一次打 visibility 的时刻（{@code 0} = 还没打过）。 */
    private static long lastVisLogNanos;

    /** 复用的视图矩阵 / MVP（<b>不</b>每帧 new；只在打日志那一下算一次）。 */
    private static final Matrix4f VIS_VIEW = new Matrix4f();
    private static final Matrix4f VIS_MVP = new Matrix4f();

    /**
     * MVP 的<b>列主序</b>副本（{@code m[c*4+r] = M[r][c]}，= {@code Matrix4f.get(float[])} 的排布）。
     * <p>纯函数 {@link ShanhaiHoloViewMath#proj} 吃它；排布口径由
     * {@link #checkProjConventionOnce()} 在游戏里、由离线驱动 JOML 交叉核对过。
     */
    private static final float[] VIS_MVP_COL = new float[16];

    /** 列主序约定的自检矩阵（与 {@link #VIS_MVP_COL} 分开，免得自检把真读数冲掉）。 */
    private static final float[] VIS_CONV_PROBE = new float[16];

    /** 一次性自检的闸门。 */
    private static boolean projConvChecked;

    /** 本帧"画出来的"板心世界坐标（x,y,z 三连），只给可见性读数用。 */
    private static final double[] VIS_CENTERS = new double[ShanhaiHoloMenuTuning.BOARD_COUNT * 3];

    /** {@link #VIS_CENTERS} 里有效板数（0 = 这一帧一块板都没画）。 */
    private static int visCenterCount;

    /** 板心解算的复用暂存（不每块板 new 一个 double[3]）。 */
    private static final double[] VIS_SCRATCH = new double[3];

    // ---- 快照：最后一次打 visibility 时的现场（给 /shanhai menu dump 用；只读） ----

    /** 有没有过至少一次 visibility 读数（= MVP 可用）。 */
    private static boolean visValid;
    /** 那次读数的时刻。 */
    private static long visSnapshotNanos;
    /**
     * {@link #ensureVisMvp} 上一次真正重算 MVP 时的帧时刻（纳秒）。
     * <p>
     * 同一帧里 visibility 那条路可能进来两次（收起分支一次、正常分支一次），
     * 这两次都用同一个 {@code frameNanos} ⇒ 只算一次矩阵，省一次矩阵乘。
     * <p>
     * ⚠️ 与 {@link #visSnapshotNanos} 不是一件事：那是"上一次打印读数"，这是"上一次重算矩阵"。
     */
    private static long visMvpFrameNanos;
    /** 那一帧<b>本地那 5 块板</b>画没画（false = {@code !localVisible}；别人的板可能与它无关）。 */
    private static boolean visSnapshotDrawn;
    private static int visPanelMode;
    private static String visLayoutName = "ring";
    private static float visDistance;
    private static double visOx;
    private static double visOy;
    private static double visOz;
    private static float visFrameYaw;
    private static float visSpin;
    private static float visScale;
    private static float visProgress;
    private static boolean visMenuOn;
    private static int visGuiW = 1;
    private static int visGuiH = 1;
    private static double visCamX;
    private static double visCamY;
    private static double visCamZ;
    private static int visBoardsIn;
    private static int visBoardTotal;
    private static float visNdcX;
    private static float visNdcY;
    private static float visGroupDist;
    private static boolean visGroupBehind;
    private static boolean visGroupIn;

    /**
     * 🔴 <b>B 的落点</b>：客户端 tick（{@code TickEvent.ClientTickEvent.Phase.END}）推进一次动画。
     *
     * <p>为什么是 tick 而不是渲染帧：见 {@link #onRenderLevelStage} 里那段（顺序：右键事件在同一 tick 更早）。
     * <p>为什么<b>不</b>判"有没有世界"：没有世界的 tick 里也要推进（退出世界 / 重登 / 死亡界面
     * 都会走到这里），否则 {@code progress} 会停在中途，下次进世界第一帧就是"半开的板"。
     * 推进的目标永远只是 {@link ShanhaiHoloMenuState#enabled()} 这一个布尔，所以推进到两端就自然停住
     * （{@code advanceFrame} 到端点后每一步都返回 {@code EV_NONE}，没有额外开销）。
     *
     * <p>⚠️ 它由 {@code ShanhaiHoloMenuInput#onClientTick} 调用（那个类已经订阅了同一个事件，
     * 不再新增一个订阅点 ⇒ "同一个决定只有一个落点"）。
     */
    public static void onClientTickEnd() {
        final long now = System.nanoTime();
        final ShanhaiHoloMenuAnim.Step step =
                ShanhaiHoloMenuAnim.advanceFrame(ShanhaiHoloMenuState.enabled(), now);
        lastAdvanceNanos = now;
        if (step.changed()) {
            logAnim(step);
        }
    }

    /**
     * 🆕（A）<b>渲染帧间隔诊断</b>：只读时间、不改任何状态。
     *
     * <p>上一轮那个"把猫放出来就看不到 5 块板"的唯一现场指纹，就是"某一帧 ≥ 1.53 s"
     * （anim 行上 {@code progress=0.714 = 0.25/0.35} 说明上一帧至少 250 ms 前）。
     * 旧代码里这种长帧不产生任何日志 ⇒ 排查只能靠推断。现在它一定会留一行痕：
     * <pre>
     *   [SHANHAI-HOLO] holo_frame_gap ms=1534 since=render_frame tick_gap_ms=1520 …
     * </pre>
     * 两个间隔分开报，是为了区分<b>两种完全不同的病</b>：
     * <ul>
     *   <li>{@code ms} 大而 {@code tick_gap_ms} 也大 ⇒ 整个主线程卡住（GC / 资源重载 / 区块重建）；</li>
     *   <li>{@code ms} 大而 {@code tick_gap_ms} 小 ⇒ 只有渲染慢（阴影/光追/几何爆炸）；
     *       这种情况下 tick 侧推进<b>照样</b>在工作 ⇒ 不会再有"开了却一帧没画"。</li>
     * </ul>
     */
    private static void logFrameGap(long frameNanos) {
        final long prev = lastRenderFrameNanos;
        lastRenderFrameNanos = frameNanos;
        if (prev == 0L) {
            return;                                     // 第一帧没有"上一帧"，不报
        }
        if (frameNanos - prev <= FRAME_GAP_WARN_NANOS) {
            return;
        }
        if (frameNanos - lastGapLogNanos < FRAME_GAP_LOG_THROTTLE_NANOS) {
            return;                                     // 限流 1 行/秒
        }
        lastGapLogNanos = frameNanos;
        final long tickGap = lastAdvanceNanos == 0L ? -1L : (frameNanos - lastAdvanceNanos) / 1_000_000L;
        com.shanhai.ShanhaiMod.LOGGER.warn("[SHANHAI-HOLO] holo_frame_gap ms={} since=render_frame "
                        + "tick_gap_ms={} menu_on={} progress={}",
                (frameNanos - prev) / 1_000_000L, tickGap, ShanhaiHoloMenuState.enabled(),
                String.format(java.util.Locale.ROOT, "%.3f", ShanhaiHoloMenuAnim.open01()));
    }

    /**
     * tick 侧<b>停摆</b>时的兜底推进（默认恒不触发：正常帧间隔 16 ms、tick 50 ms）。
     *
     * <p>为什么需要它：推进点搬到 tick 之后，<b>只推进动画的仍然是 tick</b>；
     * 而"暂停屏 / 主线程长时间卡住"这两种情况下 tick 可能不再发事件（暂停时原版仍会渲染世界，
     * 但世界内容不 tick）。那时画面还在画，动画却停在中途 ⇒ 玩家看到一块<b>永远半开</b>的板。
     * 判据只有一条：<b>距上一次推进已经 > 250 ms</b> ⇒ 这一帧自己补一步（dt 照样被
     * {@link ShanhaiHoloMenuAnim#MAX_FRAME_DT_SEC} 封顶，所以最多走 0.25 s，不会一跳到底）。
     *
     * <p>⚠️ 它<b>不</b>会在正常游玩里生效（60 fps ⇒ 每帧 gap≈16 ms，20 Hz 的 tick 让 gap≤50 ms），
     * 所以它不会把"tick 推进"这条主修又偷偷改回"渲染帧推进"。
     */
    private static void resyncIfTicksStalled(long frameNanos) {
        final long last = lastAdvanceNanos;
        if (last == 0L || frameNanos - last <= FRAME_GAP_WARN_NANOS) {
            return;
        }
        final ShanhaiHoloMenuAnim.Step step =
                ShanhaiHoloMenuAnim.advanceFrame(ShanhaiHoloMenuState.enabled(), frameNanos);
        lastAdvanceNanos = frameNanos;
        if (step.changed()) {
            logAnim(step);
            com.shanhai.ShanhaiMod.LOGGER.warn("[SHANHAI-HOLO] holo_anim_resync reason=tick_stalled "
                            + "tick_gap_ms={} {}", (frameNanos - last) / 1_000_000L,
                    ShanhaiHoloMenuAnim.describe());
        }
    }

    /** 供离线判读："动画上一次被推进"到现在多久（毫秒；没推过 = {@code -1}）。 */
    public static long sinceLastAdvanceMs(long nowNanos) {
        return lastAdvanceNanos == 0L ? -1L : (nowNanos - lastAdvanceNanos) / 1_000_000L;
    }

    // ------------------------------------------------------------------ 🆕 2026-11-08：可见性读数

    /**
     * <b>板子可见性读数</b>（每 500 ms 最多一行）—— 回答"到底画没画、在不在视野里"。
     *
     * <p>两个调用点，覆盖两种完全不同的现场：
     * <ol>
     *   <li><b>一块板都没画</b>（本地完全收起 + 没有别人的板）：{@code local_drawn=false}，
     *       板组中心退回"锚点"（= 要开的话会出现在哪）；</li>
     *   <li><b>正要画</b>：板组中心 = 这一帧画出来的那些板的板心平均值，并附 {@code boards_in=k/n}。</li>
     * </ol>
     *
     * <p>判据定义（逐条都能对到 {@link ShanhaiHoloViewMath#proj}）：
     * <pre>
     *   in_frustum = 不 behind，且 |ndc.x|≤1、|ndc.y|≤1、−1≤ndc.z≤1（z 在近远裁剪面之间）
     *   behind     = 投影前 w ≤ 0（相机空间 z ≥ 0）
     *   screen     = GUI 坐标（左上角原点、+Y 朝下）
     *   dist       = 相机 → 板组中心 的距离（格）
     * </pre>
     * ⚠️ 本方法<b>只读</b> state/anim/事件，写自己的诊断快照；不复用 {@code CULL_*} 那几把
     * 静态矩阵（那几把只在联机剔除里填，可能还是上一帧的值）。
     */
    private static void logVisibilityIfDue(long frameNanos, Minecraft mc, RenderLevelStageEvent event,
                                           Matrix4f cameraPose, boolean drawn,
                                           double ox, double oy, double oz,
                                           float frameYawDeg, float animSpinDeg, float animScale,
                                           net.minecraft.client.player.LocalPlayer player) {
        if (lastVisLogNanos != 0L && frameNanos - lastVisLogNanos < VIS_LOG_THROTTLE_NANOS) {
            return;
        }
        lastVisLogNanos = frameNanos;
        checkProjConventionOnce();

        final Vec3 cam = event.getCamera().getPosition();
        final int guiW = mc.getWindow() == null ? 0 : mc.getWindow().getGuiScaledWidth();
        final int guiH = mc.getWindow() == null ? 0 : mc.getWindow().getGuiScaledHeight();
        // 真正的"世界 → NDC"：proj · (相机姿态 · T(−camPos))（与 CommandScreen 的屏幕投影同一句）
        ensureVisMvp(event, cameraPose, frameNanos);

        // 板组中心：画出来的板心取平均；一块都没画 ⇒ 用锚点（"要开的话会出现在哪"）
        double cx;
        double cy;
        double cz;
        if (visCenterCount > 0) {
            cx = 0.0;
            cy = 0.0;
            cz = 0.0;
            for (int i = 0; i < visCenterCount; i++) {
                cx += VIS_CENTERS[i * 3];
                cy += VIS_CENTERS[i * 3 + 1];
                cz += VIS_CENTERS[i * 3 + 2];
            }
            cx /= visCenterCount;
            cy /= visCenterCount;
            cz /= visCenterCount;
        } else {
            final Vec3 anchor = ShanhaiHoloMenuState.position();
            cx = anchor.x;
            cy = anchor.y;
            cz = anchor.z;
        }
        final ShanhaiHoloViewMath.Proj group =
                ShanhaiHoloViewMath.proj(VIS_MVP_COL, cx, cy, cz, guiW, guiH);
        int inFrustum = 0;
        for (int i = 0; i < visCenterCount; i++) {
            if (ShanhaiHoloViewMath.proj(VIS_MVP_COL,
                    VIS_CENTERS[i * 3], VIS_CENTERS[i * 3 + 1], VIS_CENTERS[i * 3 + 2],
                    guiW, guiH).inFrustum()) {
                inFrustum++;
            }
        }
        final double dist = Math.sqrt((cx - cam.x) * (cx - cam.x)
                + (cy - cam.y) * (cy - cam.y) + (cz - cam.z) * (cz - cam.z));

        // ---- 快照（给 /shanhai menu dump；同一线程写、同一线程读）----
        visValid = true;
        visSnapshotNanos = frameNanos;
        visSnapshotDrawn = drawn;
        visPanelMode = ShanhaiHoloMenuState.panelMode();
        visLayoutName = ShanhaiHoloMenuState.layoutName();
        visDistance = ShanhaiHoloMenuState.distance();
        visOx = ox;
        visOy = oy;
        visOz = oz;
        visFrameYaw = frameYawDeg;
        visSpin = animSpinDeg;
        visScale = animScale;
        visProgress = ShanhaiHoloMenuAnim.open01();
        visMenuOn = ShanhaiHoloMenuState.enabled();
        visGuiW = guiW;
        visGuiH = guiH;
        visCamX = cam.x;
        visCamY = cam.y;
        visCamZ = cam.z;
        visNdcX = group.ndcX();
        visNdcY = group.ndcY();
        visGroupDist = (float) dist;
        visGroupBehind = group.behind();
        visGroupIn = group.inFrustum();
        visBoardsIn = inFrustum;
        visBoardTotal = visCenterCount;

        com.shanhai.ShanhaiMod.LOGGER.info(
                "[SHANHAI-HOLO] visibility menu_on={} progress={} dist={} ndc={} in_frustum={} "
                        + "behind={} screen=({},{}) env_near={} panel={} layout={} boards_in={}/{} "
                        + "local_drawn={} {} {} {}",
                visMenuOn,
                ShanhaiHoloViewMath.fmt3(visProgress),
                ShanhaiHoloViewMath.fmt2(dist),
                group.ndcText(),
                group.inFrustum(),
                group.behind(),
                group.screenX(), group.screenY(),
                ShanhaiHoloEnvScan.anyForeign(),
                ShanhaiHoloMenuPanel.modeName(visPanelMode),
                visLayoutName,
                inFrustum, visCenterCount, drawn,
                // 🆕 2026-11-08：去抖闸门的累计读数（"这一轮到底丢掉了几次连点"）
                ShanhaiHoloMenuToggleGate.describe(),
                // 🆕 2026-11-08：生命态三字段（字段名固定：hp= dead= death_time=，放行末）
                //   判据：dead=true 且 local_drawn=true + boards_in>0 ⇒ 死态不是阻断因素。
                vitalsLine(player),
                // 🆕 2026-11-09：遮挡探针（🔴 追加在同一拍的行末，字段名固定，见 occlProbeLine）
                //   为什么挂在这一拍：holo_open_check 是【一次性】的（关→开那一帧），
                //   配不上"持续看不见"；visibility 是 500 ms 一次 ⇒ 看不见的那几秒里有 10+ 个采样点。
                occlProbeLine(mc)
                        // 🆕 2026-11-08（ontop 实验）：再往后追加四个字段（**同一个行末**，不新增行）：
                        //   ontop= / depth_at_center= / board_ndc_z= / depth_occluded=
                        //   判据见 ShanhaiHoloOntop §2：occluded = depth_at_center < (ndc_z*0.5+0.5)
                        + " " + ontopProbeLine(mc));
    }

    /** 生命态三字段的一行文本（{@code hp=20.00 dead=false death_time=0}）；口径见 {@link ShanhaiHoloVitals}。 */
    private static String vitalsLine(net.minecraft.client.player.LocalPlayer p) {
        return p == null
                ? ShanhaiHoloVitals.NONE.line()
                : ShanhaiHoloVitals.of(p.getHealth(), p.isDeadOrDying(), p.deathTime).line();
    }

    // ------------------------------------------------------------------ 🆕 2026-11-09：遮挡探针
    //
    //   口径（只谈几何与 GL，不点名任何模组）：我们自己在 AFTER_PARTICLES 提交；本工程里必然排在我们之后、
    //   且整屏合成的是我们自己的黑洞透镜（AFTER_WEATHER）。别人若在我们之后铺了一层全屏画面，
    //   这一行不会替你说"是谁" —— 它只给 screen= 与那六个 GL 读数，让你自己去对。
    //   ⇒ 剩下的可能只有两条，而它们原本都没有字段：
    //     ① 我们根本没提交可见内容（0 板 / alpha≈0 / scale≈0）；② 提交了却看不见（遮挡 / 深度 / 透明度）。
    //   本节点只做一件事：把这两条变成同一拍、可 grep、可离线判定的读数（拼装/判定全在
    //   {@link ShanhaiHoloOccluderProbe} 那个纯类里，能不开游戏逐字验证）。
    //
    //   🔴 三条硬约束（逐条都在下面的实现里看得见）：
    //     ① 纯只读：不关特效、不搬阶段、不改任何几何/布局/开关/动作分派；
    //     ② 不引新的每帧分配/反射循环：GL 采样与 visibility 同一个 500 ms 闸门（1 次 / 500 ms）；
    //        一个 16 字节的 direct ByteBuffer 在类加载时分配一次、之后永远复用；
    //     ③ 任何异常只 WARN 一次，之后静默降级成 na —— **绝不许把渲染打断**。
    //        🔴 也**不许**用"反正会 catch"当理由去反射别人的类：探针里没有一处按名字找第三方类。
    //
    //   ⚠️ 时间口径（必须写清楚）：visibility 这一行打在"位姿算好、板还一块都没提交"的那一刻，
    //      所以 drawn_boards/quads/verts/alpha/scale 报的是【上一帧画完时发布的那一份】（滞后 1 帧 ≈16 ms）。
    //      这不是偷懒：要"同一拍"就只能等画完再打，而那一行必须留在 visibility 上 ⇒ 只能取上一帧。
    //      而 500 ms 一行的采样密度下，16 ms 的滞后完全不影响判读。

    /** GL 状态采样的时刻（{@code 0} = 还没采过）。闸门与 {@link #VIS_LOG_THROTTLE_NANOS} 同一个 500 ms。 */
    private static long probeGlNanos;

    /** 这一次采样有没有成功（false ⇒ 六个 GL 字段一律打 {@code na}，<b>不许</b>打假 0/1）。 */
    private static boolean probeGlValid;

    /** 采样到的 GL 状态（{@link #sampleGlIfDue} 写、{@link #occlProbeLine} 读）。 */
    private static boolean glBlend;
    private static boolean glDepthTest;
    private static boolean glDepthWrite;
    private static boolean glMaskR = true;
    private static boolean glMaskG = true;
    private static boolean glMaskB = true;
    private static boolean glMaskA = true;
    private static int glSrcAlpha = -1;
    private static int glDstAlpha = -1;

    /**
     * {@code glGetBooleanv(GL_COLOR_WRITEMASK)} 的落点（<b>类加载时分配一次</b>，永不 per-frame 分配）。
     * <p>LWJGL 3 的这个重载要求 direct 缓冲；{@code Checks.check(buffer, 1)} 只要求 remaining ≥ 1 字节，
     * 给 16 字节是留余量（GLboolean 1 字节 × 4 位，只读前 4 个字节）。
     * <p>⚠️ 每次查之前必须 {@code clear()}：{@code memAddress(buffer)} 是"起始地址 + position"。
     */
    private static final ByteBuffer GL_MASK_BUF = ByteBuffer.allocateDirect(16);

    // ---- 黑洞透镜读数（只读既有状态；读不到就永久 na）----

    private static boolean probeBhUsable = true;
    private static boolean probeBhWarned;

    private static boolean probeGlWarned;

    // ---- 本帧 / 上一帧"到底提交了没有" ----

    /** 本帧真正提交了顶点的板数（本地 + 远程；每帧开头清零）。 */
    private static int probeBoards;
    /** 本帧提交的顶点数（{@link #vert} 里 +1；每帧开头清零）。 */
    private static int probeVerts;
    /** 本帧逐顶点乘进去的 alpha。 */
    private static float probeAlpha = 1.0f;
    /** 本帧的整块缩放。 */
    private static float probeScale = 1.0f;

    /** 上一帧画完时发布的读数（= 探针行读的那一份；滞后 1 帧，见本节开头的时间口径）。 */
    private static int probeSubBoards;
    private static int probeSubVerts;
    private static float probeSubAlpha;
    private static float probeSubScale = 1.0f;

    /** 一帧开头：本帧计数清零（只写本类自己的探针字段，别的什么都不碰）。 */
    private static void probeFrameBegin() {
        probeBoards = 0;
        probeVerts = 0;
        probeAlpha = 1.0f;
        probeScale = 1.0f;
    }

    /** 本帧画完了：把计数发布成"探针行读的那一份"。 */
    private static void probePublish() {
        probeSubBoards = probeBoards;
        probeSubVerts = probeVerts;
        probeSubAlpha = probeAlpha;
        probeSubScale = probeScale;
    }

    /**
     * 这一拍<b>一块板都画不了</b>（本地完全收起、且没有别人的板 ⇒ 方法在这里就 return 了）：
     * 显式发布"0 板 0 顶点 / alpha=0"。
     * <p>为什么不是"保持上一帧"：那会把"根本没画"读成"上一帧画得挺好"，是最会骗人的一种读数。
     */
    private static void probePublishNothing() {
        probeBoards = 0;
        probeVerts = 0;
        probeAlpha = 0.0f;
        probeScale = 1.0f;
        probePublish();
    }

    /**
     * 只读采样六个 GL 状态（{@link #onRenderLevelStage} 的<b>最开头</b>调，限流 500 ms）。
     *
     * <p>⚠️ 这六个量回答的是"<b>上家把 GL 状态留成什么样</b>"，所以必须在
     * {@code plateType()}/{@code glowType()}/{@code endBatch()} 这些会改状态的东西之前读。
     *
     * <p>判据（看数时怎么用，取证报告 §③ 给了同一套）：
     * <pre>
     *   blend=1 且 src=770(SRC_ALPHA) 且 dst=1(ONE)  ⇒ 有人把加法混合留在了我们这个阶段之前
     *   depth_test=0                                  ⇒ 深度测试被关了（板子会穿透地形、也可能被别的东西盖）
     *   depth_write=0                                 ⇒ 深度写被关了（板子不再占位，后面的东西能盖上来）
     *   color_mask 不是 1111                          ⇒ 某个颜色通道被屏蔽（画面会缺色/整块不见）
     * </pre>
     */
    private static void sampleGlIfDue(long frameNanos) {
        if (probeGlNanos != 0L && frameNanos - probeGlNanos < VIS_LOG_THROTTLE_NANOS) {
            return;                                     // 限流：与 visibility 同一个 500 ms 节拍
        }
        probeGlNanos = frameNanos;
        try {
            glBlend = GL11.glIsEnabled(GL11.GL_BLEND);
            glDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            glDepthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            // GL_BLEND_SRC_ALPHA / DST_ALPHA 不是 GL 1.1 的常量（1.4 的 glBlendFuncSeparate 才有）⇒ 在 GL14 里
            glSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            glDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            final ByteBuffer mask = GL_MASK_BUF;
            mask.clear();                               // 必须：memAddress() 取的是 position 处的地址
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            glMaskR = mask.get(0) != 0;
            glMaskG = mask.get(1) != 0;
            glMaskB = mask.get(2) != 0;
            glMaskA = mask.get(3) != 0;
            probeGlValid = true;
        } catch (Throwable t) {
            probeGlValid = false;                       // ⇒ 行里六个 GL 字段全打 na（不放假值）
            warnProbeOnce("gl", t);
        }
    }

    /**
     * 黑洞透镜这一帧的状态（只读两个<b>既有</b>读数，不新造任何状态）。
     *
     * <pre>
     *   没有中心  ⇒ SKIP_NO_CENTER   （BlackHoleFrameStats.SKIP_NO_CENTER / lastCenter()==null）
     *   有中心    ⇒ BlackHoleLensRenderer.lastPath()
     *                （lens-after-weather / lens-after-composite / fallback-quad / inside-overlay / none）
     * </pre>
     * ⚠️ 透镜在 {@code AFTER_WEATHER}（比我们晚），所以它这里读到的 {@code lastPath()} 是
     * <b>上一帧</b>的值（同样滞后 1 帧）。"上一帧走了哪条路"正是我们要的：它在不在画。
     */
    private static String bhText() {
        if (!probeBhUsable) {
            return "na";
        }
        try {
            final String skip = com.shanhai.client.render.blackhole.BlackHoleFrameStats.lastSkip();
            if (com.shanhai.client.render.blackhole.BlackHoleFrameStats.SKIP_NO_CENTER.equals(skip)
                    || com.shanhai.client.render.blackhole.BlackHoleFrameStats.lastCenter() == null) {
                return "SKIP_NO_CENTER";
            }
            final String path = com.shanhai.client.render.blackhole.BlackHoleLensRenderer.lastPath();
            return (path == null || path.isEmpty()) ? "none" : path;
        } catch (Throwable t) {
            probeBhUsable = false;                      // 永久降级成 na
            warnProbeOnce("bh", t);
            return "na";
        }
    }

    /**
     * 探针行的<b>唯一</b>拼装点：把这一拍采到的原始值交给纯类 {@link ShanhaiHoloOccluderProbe}。
     *
     * <p>本方法只<b>读</b>：本类自己的探针字段 + {@link ShanhaiHoloEnvScan} 的扫描快照 +
     * {@code mc.screen} + 上面两个缓存读数。不写任何与绘制相关的东西。
     */
    private static String occlProbeLine(Minecraft mc) {
        final String screen = (mc == null || mc.screen == null)
                ? "none" : mc.screen.getClass().getSimpleName();
        return ShanhaiHoloOccluderProbe.line(new ShanhaiHoloOccluderProbe.Sample(
                ShanhaiHoloEnvScan.foreignCount(),             // count：附近 48 格内外来命名空间的实体数
                ShanhaiHoloEnvScan.foreignTypesText(),         // types：其中出现过的注册名（去重、最多 4 条）
                ShanhaiHoloEnvScan.foreignNearest(),           // nearest：最近那个的距离（格）
                probeGlValid,                               // gl_valid：采到没采到
                glBlend, glDepthTest, glDepthWrite,         // blend / depth_test / depth_write
                glMaskR, glMaskG, glMaskB, glMaskA,         // color_mask 四位（RGBA）
                glSrcAlpha, glDstAlpha,                     // src / dst（GL 枚举原值）
                screen,                                     // screen：有没有 Screen 压在世界上面
                bhText(),                                   // bh：我们自己黑洞透镜那一帧的路径
                probeSubBoards,                             // drawn_boards：上一帧提交的板数
                probeSubVerts / 4,                          // quads：本类几何全是 QUADS（文字走 Font，不计）
                probeSubVerts,                              // verts：上一帧提交的顶点数
                probeSubAlpha,                              // alpha：逐顶点乘进去的那个
                probeSubScale));                            // scale：整块缩放
    }

    /**
     * 探针的异常<b>只 WARN 一次</b>（三个闸门各管一路：gl / bh / ontop）。
     * <p>为什么要限一次：它每 500 ms 跑一次，若每次都 WARN，一条坏读数就能把日志刷爆
     * —— 而这条日志是给人判读用的，刷爆等于没有。
     */
    private static void warnProbeOnce(String what, Throwable t) {
        if ("gl".equals(what)) {
            if (probeGlWarned) {
                return;
            }
            probeGlWarned = true;
        } else if ("ontop".equals(what)) {
            if (probeOntopWarned) {
                return;
            }
            probeOntopWarned = true;
        } else {
            if (probeBhWarned) {
                return;
            }
            probeBhWarned = true;
        }
        com.shanhai.ShanhaiMod.LOGGER.warn(
                "[SHANHAI-HOLO] occl_probe {} 读数不可用（只报这一次，之后静默降级成 na；不影响渲染）: {}",
                what, String.valueOf(t));
    }

    // ------------------------------------------------------------------ 🆕 2026-11-08：ontop（画到最上层）实验
    //
    //   本节的四件事，一件一件都能离线判读（纯逻辑在 ShanhaiHoloOntop）：
    //     ① 运行时开关       /shanhai_add holo ontop [on|off]  ⇒ 打 holo_ontop on=true|false（不落配置，默认 on）
    //     ② 本帧原始的 GL 深度状态采样＋画完恢复（别人的绘制看得见的就是这个值）
    //     ③ 事件行           holo_render_state depth_test=<orig> -> 0 ontop=true boards=<n> …
    //     ④ 板心深度读数     visibility 行末 ontop= depth_at_center= board_ndc_z= depth_occluded=
    //
    //   🔴 三条硬约束（都在下面的实现里看得见）：
    //     · 关深度测试只包住我们这几块板（走 RenderType 的深度状态块，见 OntopDepthShard），
    //       画完由 restoreGlDepthState() 还原；
    //     · 任何异常只 WARN 一次（warnProbeOnce("ontop", …)），之后静默降级成 na —— 绝不许打断渲染；
    //     · 深度读数 1 次 / 500 ms（glReadPixels 是一次 GPU 同步点，不许每帧读）。

    /** 开关的公开读法（命令层与日志都用它，不直接读字段）。 */
    public static boolean ontop() {
        return ontopOn;
    }

    /**
     * 设开关（<b>不落配置</b>：下次进世界回到 {@link ShanhaiHoloOntop#DEFAULT_ON}）。
     *
     * @return 设置之后的值（永远是 {@code on}，把"命令回了什么"与"状态是什么"钉在一起）
     */
    public static boolean setOntop(boolean on) {
        ontopOn = on;
        return ontopOn;
    }

    /** 翻转（{@code /shanhai_add holo ontop} 无参那一支）。 */
    public static boolean toggleOntop() {
        return setOntop(!ontopOn);
    }

    /** {@code ontop} 相关的异常只报一次的闸门（与 gl/bh 两个闸门并列）。 */
    private static boolean probeOntopWarned;

    /** 本帧开头采样的"上家留给我们的"深度状态。<b>只在 {@link #ontopFrame} 时采</b>（省两个 GL 查询）。 */
    private static void captureGlDepthState() {
        try {
            origDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            origDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        } catch (Throwable t) {
            origDepthTest = true;                   // 采样失败 ⇒ 按原版默认（开 + LEQUAL）恢复
            origDepthFunc = 515;
            warnProbeOnce("ontop", t);
        }
    }

    /**
     * 把深度状态还原成本帧开头采样到的样子。
     *
     * <p>⚠️ 位置在 {@code buffers.endBatch()} <b>之后</b>：各 RenderType 的 clear 已经把深度测试留在"关"
     * （原版收尾就是这样），而紧随其后的云层/黑洞透镜/HUD 看到的状态必须与"我们没画过"时一样。
     * <p>⚠️ 调用点包在 finally 里 ⇒ 中途抛异常也照样恢复。
     */
    private static void restoreGlDepthState() {
        if (!ontopFrame) {
            return;
        }
        try {
            if (origDepthTest) {
                RenderSystem.enableDepthTest();
            } else {
                RenderSystem.disableDepthTest();
            }
            RenderSystem.depthFunc(origDepthFunc);
        } catch (Throwable t) {
            warnProbeOnce("ontop", t);
        }
    }

    /** 事件行的限流（2 秒 / 行；状态变化那一刻不受限，另一路见 {@link #ontopLogSaid}）。 */
    private static final long ONTOP_LOG_THROTTLE_NANOS = 2_000_000_000L;

    /** 上一次打事件行的时刻 / 有没有打过。 */
    private static long ontopLogNanos;
    private static boolean ontopLogSaid;

    /**
     * 事件行：{@code holo_render_state depth_test=<orig> -> 0 ontop=true boards=<n> …}。
     *
     * <p>限流：<b>首次</b>必打（= 开关被打开 / 刚进世界）+ 之后 2 秒最多一行 ⇒ 既不刷屏，
     * 任何一段日志窗口里又一定捞得到。
     * <p>末尾三个字段是专为实验加的：
     * <pre>
     *   text=see_through  文字那一遍走的原版 RenderType（见 textDisplayMode）
     *   depth_write=0     板不再写深度（否则会把"最上层"卖给了后面的云层/透镜）
     *   restored=true     画完之后 GL 深度状态确实回到了本帧开头的样子（这条是"没污染别人"的机械判据）
     * </pre>
     */
    private static void logRenderStateIfDue(long frameNanos) {
        final boolean first = !ontopLogSaid;
        if (!first && frameNanos - ontopLogNanos < ONTOP_LOG_THROTTLE_NANOS) {
            return;
        }
        ontopLogSaid = true;
        ontopLogNanos = frameNanos;
        boolean restored = false;
        try {
            restored = GL11.glIsEnabled(GL11.GL_DEPTH_TEST) == origDepthTest
                    && GL11.glGetInteger(GL11.GL_DEPTH_FUNC) == origDepthFunc;
        } catch (Throwable t) {
            warnProbeOnce("ontop", t);
        }
        com.shanhai.ShanhaiMod.LOGGER.info(
                "[SHANHAI-HOLO] {} text=see_through depth_write=0 orig_func={} restored={}",
                ShanhaiHoloOntop.renderStateLine(origDepthTest, probeBoards),
                origDepthFunc, restored);
    }

    // ---- 板心深度读数 ----

    /**
     * 深度缓冲的落点（<b>类加载时分配一次</b>，永不 per-frame 分配）。
     * <p>LWJGL 的 {@code glReadPixels(…, FloatBuffer)} 要求 direct 缓冲；1×1 只需 4 字节，
     * 给 4 字节正好（不给自己留"读多了越界"的机会）。
     */
    private static final FloatBuffer GL_DEPTH_BUF = ByteBuffer.allocateDirect(4).asFloatBuffer();

    /** 上一次真正读到深度的时刻（限流用；{@code 0} = 还没读过）。 */
    private static long lastDepthReadNanos;

    /** 这一拍那三个字段的文本（读不到/不适用 ⇒ {@code na}；口径全在 {@link ShanhaiHoloOntop}）。 */
    private static String depthAtCenterText = ShanhaiHoloOntop.NA;
    private static String boardNdcZText = ShanhaiHoloOntop.NA;
    private static String depthOccludedText = ShanhaiHoloOntop.NA;

    /**
     * visibility 行末那四个字段的<b>唯一</b>拼装点。
     *
     * <p>⚠️ 时间口径（必须写清楚）：这一行打在"位姿算好、板还一块都没提交"的那一刻 ⇒
     * 此刻深度缓冲里只有<b>世界几何</b>（地形/实体，本帧的），我们的板还没进去
     * ⇒ 读到的正是"板心那个像素上，世界挡在前面多近"。这正是要比的那个数。
     */
    private static String ontopProbeLine(Minecraft mc) {
        refreshDepthAtCenterIfDue(mc);
        return ShanhaiHoloOntop.probeSuffix(ontopOn, depthAtCenterText, boardNdcZText, depthOccludedText);
    }

    /**
     * 读板心那一个像素的深度（{@code glReadPixels} 1×1，{@code GL_DEPTH_COMPONENT}，1 次 / 500 ms）。
     *
     * <p>板心取<b>本帧第一块画出来的板</b>（{@code VIS_CENTERS[0]}）——
     * 它是 {@code visibility} 那一行 {@code boards_in} 计数的第一个样本，两者同一个板、同一帧。
     *
     * <p>下面每一条 {@code return} 都是同一个原则：<b>宁可 na，不许编数</b>。
     * <pre>
     *   没画板 / 没窗口 / 投影不可用（behind、屏外、NDC 非有限）  ⇒ na
     *   限流窗口内（&lt; 500 ms）                                  ⇒ na（不拿上一拍的值冒充这一拍）
     *   glReadPixels 抛异常 / 读回非有限或不在 [0,1]              ⇒ na（+ 只 WARN 一次）
     * </pre>
     */
    private static void refreshDepthAtCenterIfDue(Minecraft mc) {
        depthAtCenterText = ShanhaiHoloOntop.NA;
        boardNdcZText = ShanhaiHoloOntop.NA;
        depthOccludedText = ShanhaiHoloOntop.NA;
        if (visCenterCount <= 0 || mc == null || mc.getWindow() == null
                || visGuiW <= 0 || visGuiH <= 0) {
            return;
        }
        final long now = System.nanoTime();
        if (lastDepthReadNanos != 0L && now - lastDepthReadNanos < ShanhaiHoloOntop.DEPTH_READ_THROTTLE_NANOS) {
            return;
        }
        final ShanhaiHoloViewMath.Proj p = ShanhaiHoloViewMath.proj(VIS_MVP_COL,
                VIS_CENTERS[0], VIS_CENTERS[1], VIS_CENTERS[2], visGuiW, visGuiH);
        if (p.behind() || !p.inFrustum() || p.screenX() < 0 || p.screenY() < 0
                || !Float.isFinite(p.ndcZ())) {
            return;
        }
        boardNdcZText = ShanhaiHoloOntop.ndcText(p.ndcZ());
        final int fbW = mc.getWindow().getWidth();
        final int fbH = mc.getWindow().getHeight();
        if (fbW <= 0 || fbH <= 0) {
            return;
        }
        // GUI 缩放坐标 → 真实帧缓冲像素（glReadPixels 要后者）；GUI 的 +Y 朝下，GL 的 +Y 朝上 ⇒ 翻一次
        final int px = clampPixel((int) Math.floor(p.screenX() * (double) fbW / (double) visGuiW), fbW - 1);
        final int pyTop = clampPixel((int) Math.floor(p.screenY() * (double) fbH / (double) visGuiH), fbH - 1);
        final int glY = fbH - 1 - pyTop;
        // 🆕 2026-11-09：改走【带诊断】的读取（{@link #readWindowDepth}）。
        //   改动前这里只有一次 glReadPixels + "值在不在 [0,1]"，而 glReadPixels 一旦被 GL 拒了，
        //   那块 FloatBuffer <b>一个字节都不会被写</b> ⇒ 取到的是初值 0.0f
        //   ⇒ 日志里是一个<b>看着很真的</b> {@code depth_at_center=0.000000}。
        //   现在任一条不成立（PBO 被绑 / 非主 FBO / FBO 不完整 / 无深度附件 / GL 错误 / 值越界）
        //   都给一个有名字的 reason，并且<b>那个数不许当读数用</b>。
        final ShanhaiHoloDebugBox.DepthSample d = readWindowDepth(px, glY);
        depthBoardSample = d;
        depthFailReason = d.reason();
        if (!ShanhaiHoloDebugBox.depthReadOk(d.reason())) {
            logDepthReadStateIfChanged(d, "board");
            return;                                 // 读失败 ⇒ na + 原因（不许拿旧值/初值冒充）
        }
        lastDepthReadNanos = now;
        depthAtCenterText = ShanhaiHoloOntop.depthText(d.value());
        depthOccludedText = ShanhaiHoloOntop.occludedText(d.value(), p.ndcZ());
    }

    /** 像素坐标钳到 {@code [0, max]}（板心刚好在屏幕边缘时四舍五入会越界 1 像素）。 */
    private static int clampPixel(int v, int max) {
        return v < 0 ? 0 : (v > max ? max : v);
    }

    // ------------------------------------------------------------------ 🆕 2026-11-08：开面板自检
    //
    //   用户原话：「猫在的时候打不开面板」。取证已把它拆成两条独立病根（见 ShanhaiHoloOpenCheck）：
    //     ① 摆位（ring 72° 均布 ⇒ 70° 视场下只有 board0 在视锥内）；② 连点抵消。
    //   🔴 用户明确叫停摆位改动（「然后不要再修摆位了」）⇒ 本轮【只把"看不到"变成可判】：
    //      面板从关→开【走完动画】的那一刻打一行 holo_open_check，
    //      boards_in < 5（或四角没全进）时再追加一行 holo_open_warn。
    //   🔴 纯观测：不返回任何"要不要转一下"的东西，也不改 ox/oy/oz / 框偏航 / 任何板位姿。

    /** {@code true} = 已经发生过一次"关→开"的完整展开，下一渲染帧要打一行 {@code holo_open_check}。 */
    private static boolean openCheckDue;

    /**
     * 开面板自检专用的"世界 → NDC"三件套（视图 / MVP / 列主序副本）。
     * <p>🔴 <b>刻意与 visibility 那三把（{@code VIS_*}）分开</b>：{@code VIS_MVP_COL} 是
     * {@code /shanhai menu dump} 的 {@code visibility_dump} 的数据源，而那份 JSON 会同时打出
     * 它当时的快照时刻（{@code frame.age_ms}）。若自检把它刷成"更晚一帧"的矩阵，
     * 就会出现"矩阵是新的、快照是旧的"这种混口径读数 —— 宁可不共用一个数组。
     */
    private static final Matrix4f OC_VIEW = new Matrix4f();
    private static final Matrix4f OC_MVP = new Matrix4f();
    private static final float[] OC_MVP_COL = new float[16];

    /** {@link #OC_MVP_COL} 是为哪一帧算的（同一帧只算一次）。 */
    private static long ocMvpFrameNanos = -1L;

    /**
     * 把"世界 → NDC"的 MVP 算进 {@link #VIS_MVP_COL}（{@code m[c*4+r]} 列主序，= JOML
     * {@code Matrix4f.get(float[])}}）。
     *
     * <p>同一帧只算一次（visibility 那条路自己可能一帧进来两次：
     * 收起分支一次、正常分支一次）。
     *
     * @param cameraPose 相机姿态矩阵（<b>不含</b> −camPos；由调用方给，见两个调用点）
     */
    private static void ensureVisMvp(RenderLevelStageEvent event, Matrix4f cameraPose, long frameNanos) {
        if (visMvpFrameNanos == frameNanos) {
            return;
        }
        visMvpFrameNanos = frameNanos;
        final Vec3 cam = event.getCamera().getPosition();
        ShanhaiHoloSurroundView.composeView(VIS_VIEW, cameraPose, cam.x, cam.y, cam.z);
        VIS_MVP.set(event.getProjectionMatrix()).mul(VIS_VIEW);
        VIS_MVP.get(VIS_MVP_COL);
    }

    /** 同上，但写进自检专用的三件套（{@link #OC_MVP_COL}；与 visibility 不共用任何数组）。 */
    private static void ensureOpenMvp(RenderLevelStageEvent event, Matrix4f cameraPose, long frameNanos) {
        if (ocMvpFrameNanos == frameNanos) {
            return;
        }
        ocMvpFrameNanos = frameNanos;
        final Vec3 cam = event.getCamera().getPosition();
        ShanhaiHoloSurroundView.composeView(OC_VIEW, cameraPose, cam.x, cam.y, cam.z);
        OC_MVP.set(event.getProjectionMatrix()).mul(OC_VIEW);
        OC_MVP.get(OC_MVP_COL);
    }

    /**
     * 面板<b>从关→开、且展开动画走完</b>的那一帧打一次自检（{@link ShanhaiHoloMenuAnim#EV_OPENED}）。
     *
     * <p>为什么钉在"展开完毕"而不是"按下右键那一拍"：
     * 按下的那一帧 {@code progress} 还是 0（{@code localVisible=false}，一块板都没画），
     * 那时的读数回答不了"他等下到底能不能看见"。展开完毕那一帧
     * {@code animScale=1 / animSpin=0 / (ox,oy,oz) == anchor}（逐位），
     * 那才屏幕上真正摆着的几何。
     *
     * <p>⚠️ 面板（设置/命令）开着的那一帧<b>不</b>打：那时画的是面板那一列行板，
     * 五块菜单板根本没画出来 —— 用"5 块板"的口径去读它只会造出一条会撒谎的读数。
     */
    private static void logOpenCheckIfDue(long frameNanos, RenderLevelStageEvent event, Minecraft mc,
                                          ShanhaiHoloMenuLayout.Board[] boards,
                                          double ox, double oy, double oz, float frameYawDeg,
                                          float animSpinDeg, float animScale,
                                          net.minecraft.client.player.LocalPlayer player) {
        if (!openCheckDue) {
            return;
        }
        openCheckDue = false;
        if (panelMode != ShanhaiHoloMenuPanel.MODE_NONE) {
            return;
        }
        ensureOpenMvp(event, VIEW_MATRIX, frameNanos);
        final Vec3 cam = event.getCamera().getPosition();
        final int guiW = mc.getWindow() == null ? 0 : mc.getWindow().getGuiScaledWidth();
        final int guiH = mc.getWindow() == null ? 0 : mc.getWindow().getGuiScaledHeight();
        final ShanhaiHoloOpenCheck.Report r = ShanhaiHoloOpenCheck.evaluate(
                OC_MVP_COL, guiW, guiH, boards, ox, oy, oz, frameYawDeg, animSpinDeg, animScale,
                cam.x, cam.y, cam.z, ShanhaiHoloMenuState.layoutName());
        com.shanhai.ShanhaiMod.LOGGER.info("[SHANHAI-HOLO] {} {}{} env_near={} {}",
                r.line(), r.detailSuffix(),
                // 🆕 生命态三字段（同一拍：开面板那一刻的客户端状态）
                " " + vitalsLine(player),
                ShanhaiHoloEnvScan.anyForeign(),
                ShanhaiHoloMenuToggleGate.describe());
        final String warn = r.warnLine();
        if (warn != null) {
            // 🔴 只是告警：不自动改朝向、不自动换布局、不动任何板位姿（用户 2026-11-08 点名）
            com.shanhai.ShanhaiMod.LOGGER.warn("[SHANHAI-HOLO] {} layout={} corners_in={}/{} "
                            + "corners_behind={} corners_out={} dist={} face_camera={} "
                            + "（仅告警：不自动改朝向/布局）",
                    warn, ShanhaiHoloMenuState.layoutName(), r.cornersIn(), r.cornersTotal(),
                    r.behind(), r.outOfNdc(), ShanhaiHoloViewMath.fmt2(r.dist()), r.faceCamera());
        }
    }

    /**
     * <b>一次性</b>自检：把"列主序 {@code m[c*4+r]} 的约定"与 JOML 自己的
     * {@code Matrix4f.transform(Vector4f)} 对一遍同一个点。
     *
     * <p>为什么要它：{@link ShanhaiHoloViewMath#proj} 用纯 {@code float[]} 做矩阵乘，而
     * "数组下标 ↔ 矩阵行列"一旦搞反，读数会变成另一套（而且看着还挺像），
     * 那种错<b>不会有任何症状</b>，只会把诊断带沟里。所以第一次打 visibility 时对一次，
     * 结果进日志（{@code pass=false} 就是"矩阵排布搞反了"，一眼可见）。
     */
    private static void checkProjConventionOnce() {
        if (projConvChecked) {
            return;
        }
        projConvChecked = true;
        final Matrix4f m = new Matrix4f().perspective(
                (float) Math.toRadians(70.0), 1920.0f / 1080.0f, 0.05f, 1000.0f);
        m.get(VIS_CONV_PROBE);
        final org.joml.Vector4f v = new org.joml.Vector4f(1.5f, 2.5f, -7.5f, 1.0f);
        m.transform(v);
        final ShanhaiHoloViewMath.Proj p =
                ShanhaiHoloViewMath.proj(VIS_CONV_PROBE, 1.5, 2.5, -7.5, 1920, 1080);
        final float jx = v.x / v.w;
        final float jy = v.y / v.w;
        final float jz = v.z / v.w;
        final boolean ok = Math.abs(jx - p.ndcX()) <= 1.0e-5f
                && Math.abs(jy - p.ndcY()) <= 1.0e-5f
                && Math.abs(jz - p.ndcZ()) <= 1.0e-5f;
        com.shanhai.ShanhaiMod.LOGGER.info(
                "[SHANHAI-HOLO] holo_proj_selftest pass={} joml_ndc=({},{},{}) pure_ndc=({},{},{}) "
                        + "w={} in_frustum={}（列主序 m[c*4+r]=M[r][c]，与 JOML Matrix4f.get(float[]) 同排布）",
                ok, ShanhaiHoloViewMath.fmt4(jx), ShanhaiHoloViewMath.fmt4(jy), ShanhaiHoloViewMath.fmt4(jz),
                ShanhaiHoloViewMath.fmt4(p.ndcX()), ShanhaiHoloViewMath.fmt4(p.ndcY()),
                ShanhaiHoloViewMath.fmt4(p.ndcZ()), ShanhaiHoloViewMath.fmt2(p.w()), p.inFrustum());
        if (!ok) {
            com.shanhai.ShanhaiMod.LOGGER.error("[SHANHAI-HOLO] holo_proj_selftest FAILED ⇒ "
                    + "列主序约定与 JOML 不一致，visibility 的 ndc/in_frustum 读数不可信");
        }
    }

    /** JSON 数字：非有限值一律写 {@code null}（口径与实现只有一处：{@link ShanhaiHoloViewMath#jsonNum}）。 */
    private static String jsonNum(double v) {
        return ShanhaiHoloViewMath.jsonNum(v);
    }

    /**
     * {@code /shanhai menu dump} 的 <b>visibility_dump</b>：把 5 块板的世界坐标 / 朝向 / 屏幕投影 /
     * 附近外来实体 / 玩家规范化 yaw、pitch 一次打进日志。
     *
     * <p>数据来源分两种（JSON 里的 {@code "src"} 会写明，不混）：
     * <pre>
     *   last_drawn_frame  —— 最后一帧【真的画了板】的现场（同一个 ox/oy/oz、框偏航、动画自转/缩放）；
     *   static_estimate   —— 还没画过（或恰好那一帧一块没画）：用状态层的锚点 + 框偏航估算，
     *                        scale=1、自转=0（也就是说：忽略展开动画的原点插值与 0.004 格抖动）
     * </pre>
     * 投影用的是<b>最后一次</b> visibility 读数的 MVP（最多 500 ms 前）；{@code frame.age_ms} 会写明。
     */
    public static String visibilityDumpJson(net.minecraft.client.player.LocalPlayer p) {
        final StringBuilder sb = new StringBuilder(2048);
        final long now = System.nanoTime();
        final boolean lastFrame = visValid && visSnapshotDrawn;
        final int mode = lastFrame ? visPanelMode : ShanhaiHoloMenuState.panelMode();
        final String layout = lastFrame ? visLayoutName : ShanhaiHoloMenuState.layoutName();
        final float distance = lastFrame ? visDistance : ShanhaiHoloMenuState.distance();
        final boolean canProject = visValid;

        // ---- frame ----
        sb.append("{\"frame\":");
        if (!visValid) {
            sb.append("{\"src\":\"unavailable\",\"reason\":\"no_visibility_frame_yet\"}");
        } else {
            sb.append("{\"src\":\"").append(lastFrame ? "last_drawn_frame" : "static_estimate")
                    .append("\",\"age_ms\":").append((now - visSnapshotNanos) / 1_000_000L)
                    .append(",\"local_drawn\":").append(visSnapshotDrawn)
                    .append(",\"menu_on\":").append(visMenuOn)
                    .append(",\"progress\":").append(ShanhaiHoloViewMath.fmt3(visProgress))
                    .append(",\"panel\":\"").append(ShanhaiHoloMenuPanel.modeName(visPanelMode))
                    .append('"')
                    .append(",\"layout\":\"").append(visLayoutName).append('"')
                    .append(",\"distance\":").append(ShanhaiHoloViewMath.fmt3(visDistance))
                    .append(",\"gui\":[").append(visGuiW).append(',').append(visGuiH).append(']')
                    .append(",\"camera\":[").append(ShanhaiHoloViewMath.fmt3(visCamX)).append(',')
                    .append(ShanhaiHoloViewMath.fmt3(visCamY)).append(',')
                    .append(ShanhaiHoloViewMath.fmt3(visCamZ)).append(']')
                    .append(",\"group_ndc\":[").append(jsonNum(visNdcX)).append(',')
                    .append(jsonNum(visNdcY)).append(']')
                    .append(",\"group_in_frustum\":").append(visGroupIn)
                    .append(",\"group_behind\":").append(visGroupBehind)
                    .append(",\"group_dist\":").append(ShanhaiHoloViewMath.fmt2(visGroupDist))
                    .append(",\"boards_in\":" ).append(visBoardsIn)
                    .append(",\"boards_drawn\":").append(visBoardTotal)
                    .append('}');
        }

        // ---- 本行几何用哪套原点/偏航 ----
        final double ox;
        final double oy;
        final double oz;
        final float frameYaw;
        final float spin;
        final float scale;
        if (lastFrame) {
            ox = visOx;
            oy = visOy;
            oz = visOz;
            frameYaw = visFrameYaw;
            spin = visSpin;
            scale = visScale;
        } else if (mode == ShanhaiHoloMenuPanel.MODE_NONE) {
            final Vec3 anchor = ShanhaiHoloMenuState.position();
            ox = anchor.x;
            oy = anchor.y;
            oz = anchor.z;
            frameYaw = ShanhaiHoloMenuState.frameYaw();
            spin = 0.0f;
            scale = 1.0f;
        } else {
            final double[] c = ShanhaiHoloMenuState.panelCenter(p.getEyePosition(1.0f));
            ox = c[0];
            oy = c[1];
            oz = c[2];
            frameYaw = ShanhaiHoloMenuState.panelFrameYaw();
            spin = 0.0f;
            scale = 1.0f;
        }
        sb.append(",\"origin\":[").append(ShanhaiHoloViewMath.fmt3(ox)).append(',')
                .append(ShanhaiHoloViewMath.fmt3(oy)).append(',')
                .append(ShanhaiHoloViewMath.fmt3(oz)).append(']')
                .append(",\"frame_yaw\":").append(ShanhaiHoloViewMath.fmt3(frameYaw))
                .append(",\"anim_spin_deg\":").append(ShanhaiHoloViewMath.fmt3(spin))
                .append(",\"anim_scale\":").append(ShanhaiHoloViewMath.fmt3(scale));

        // ---- 玩家（yaw/pitch 规范化到 (-180,180]，raw 只在越界时给） ----
        sb.append(",\"player\":{\"yaw\":").append(ShanhaiHoloViewMath.yawText(p.getYRot()))
                .append(",\"pitch\":").append(ShanhaiHoloViewMath.pitchText(p.getXRot()))
                .append(",\"pos\":[")
                .append(ShanhaiHoloViewMath.fmt3(p.getX())).append(',')
                .append(ShanhaiHoloViewMath.fmt3(p.getY())).append(',')
                .append(ShanhaiHoloViewMath.fmt3(p.getZ())).append(']');
        if (ShanhaiHoloViewMath.angleOutOfRange(p.getYRot(), p.getXRot())) {
            sb.append(",\"yaw_raw\":").append(ShanhaiHoloClickRules.angleText(p.getYRot()))
                    .append(",\"pitch_raw\":").append(ShanhaiHoloClickRules.angleText(p.getXRot()));
        }
        sb.append('}');
        // ---- 附近外来实体 ----
        sb.append(",\"env\":").append(ShanhaiHoloEnvScan.dumpJson());
        // ---- 🆕 2026-11-08：客户端玩家生命态（判用户提出的"死亡态假设"）----
        //   字段名固定：hp / dead / deathTime（hp 非有限值 ⇒ null，口径与 ShanhaiHoloViewMath#jsonNum 一致）。
        //   与同一份 JSON 里的 frame.local_drawn / frame.boards_in 对起来读即可分开两种病因：
        //     dead=true 且 local_drawn=true + boards_in>0 ⇒ 死态不是阻断因素。
        sb.append(",\"client\":")
                .append(ShanhaiHoloVitals.of(p.getHealth(), p.isDeadOrDying(), p.deathTime).json());
        // ---- 5 块板 ----
        sb.append(",\"boards\":[");
        final ShanhaiHoloMenuLayout.Board[] boards =
                mode == ShanhaiHoloMenuPanel.MODE_NONE
                        ? ShanhaiHoloMenuLayout.boardsOf(layout, distance)
                        : ShanhaiHoloMenuPanel.rows();
        int n = 0;
        for (int i = 0; i < boards.length && i < ShanhaiHoloMenuTuning.BOARD_COUNT; i++) {
            if (!ShanhaiHoloMenuPanel.rowVisible(mode, i)) {
                continue;                       // 这一帧根本没画它
            }
            final ShanhaiHoloMenuLayout.Board b = boards[i];
            ShanhaiHoloViewMath.boardCenterWorld(VIS_SCRATCH, ox, oy, oz, frameYaw + spin, scale,
                    b.x(), b.y(), b.z());
            final float worldYaw = ShanhaiHoloViewMath.normalizeDeg(frameYaw + spin + b.rotY());
            final double halfW = ShanhaiHoloMenuTuning.BOARD_HALF_WIDTH_BLOCKS * b.scale() * scale;
            final double halfH = ShanhaiHoloMenuTuning.BOARD_HALF_HEIGHT_BLOCKS * b.scale() * scale;
            if (n > 0) {
                sb.append(',');
            }
            n++;
            final double dx = VIS_SCRATCH[0] - p.getX();
            final double dy = VIS_SCRATCH[1] - (p.getY() + ShanhaiHoloMenuTuning.EYE_HEIGHT_BLOCKS);
            final double dz = VIS_SCRATCH[2] - p.getZ();
            // 每块板那一段由【纯函数】拼（NaN→null、引号转义、括号配平都在它自己身上，离线验过）
            final ShanhaiHoloViewMath.Proj bp = canProject
                    ? ShanhaiHoloViewMath.proj(VIS_MVP_COL, VIS_SCRATCH[0], VIS_SCRATCH[1],
                            VIS_SCRATCH[2], visGuiW, visGuiH)
                    : null;
            final int c4 = canProject
                    ? ShanhaiHoloViewMath.cornersInFrustum(null, VIS_MVP_COL,
                            VIS_SCRATCH[0], VIS_SCRATCH[1], VIS_SCRATCH[2], worldYaw,
                            halfW, halfH, visGuiW, visGuiH)
                    : 0;
            sb.append(ShanhaiHoloViewMath.boardJson(i, ShanhaiHoloMenuTuning.LABEL_ZH[i],
                    VIS_SCRATCH[0], VIS_SCRATCH[1], VIS_SCRATCH[2],
                    b.x(), b.y(), b.z(), b.rotX(), b.rotY(), worldYaw, b.scale() * scale,
                    Math.sqrt(dx * dx + dy * dy + dz * dz), bp, c4));
        }
        sb.append("]}");
        return sb.toString();
    }

    // ------------------------------------------------------------------ 🆕 联机 P3：别人那 5 块板

    /**
     * 这一帧可见的远程玩家（<b>复用数组，不每帧 new</b>）。
     * <p>长度 64 是个硬上限：真到那个规模，帧预算早就被顶点数吃光了（见 {@link ShanhaiHoloPose#bufferCapacityFor}），
     * 与其静默变慢，不如让这里的上限看得见。
     */
    private static final net.minecraft.client.player.AbstractClientPlayer[] REMOTE_PEERS =
            new net.minecraft.client.player.AbstractClientPlayer[64];

    /** 上一帧真正画了几个人（只在<b>变化时</b>打一行日志：不刷屏，但"到底画没画"一眼可判）。 */
    private static int lastDrawnPeers = -1;

    private static int drawnPeers;
    private static int drawnBoards;
    private static long drawnFrames;

    /** 叠加剔除用的 scratch（世界 → 裁剪）：同一帧只算一次，见 {@link #collectRemotePeers}。 */
    private static final Matrix4f CULL_VIEW = new Matrix4f();
    private static final Matrix4f CULL_MVP = new Matrix4f();
    private static final org.joml.Vector4f CULL_POINT = new org.joml.Vector4f();

    /** 屏幕外剔除的宽容边（NDC 单位）。1.5 ⇒ 宁可多画一点点，也绝不剔掉半个还在画面里的板子。 */
    private static final float CULL_NDC = 1.5f;

    /**
     * 🔴 <b>这一帧要画几个别人</b> —— 联机渲染的<b>唯一判据</b>（设计 §2.2 的三级剔除）：
     * <ol>
     *   <li><b>同维度 + 不是我自己</b>：{@code ClientLevel.players()} 本来就只含当前维度；</li>
     *   <li><b>他自己开着 + 他的 DEF 到了</b>：{@code ShanhaiHoloRemoteState.paintable}
     *       （没收到 DEF 就<b>不画</b> —— 否则会用我自己的 15 项去画他的星空）；</li>
     *   <li><b>距离</b>：{@code distanceToSqr <= RENDER_DISTANCE_SQR}（64 格，用户拍板③）；</li>
     *   <li><b>屏幕外</b>：把眼睛位置经 MVP 投到 NDC，|x| 或 |y| 超出 {@link #CULL_NDC} 就剔。
     *       ⚠️ 这一级<b>不是可选项</b>：批数与顶点都随人数线性涨（设计 §4.5），
     *       剔掉"背后那 20 个人"是"人多也不崩"的唯一理由。</li>
     * </ol>
     * 结果写进 {@link #REMOTE_PEERS}，返回人数。容量与真实绘制用的是<b>同一个数组</b>
     * ⇒ 不可能出现"算容量时算 2 个、画的时候画 5 个"。
     */
    private static int collectRemotePeers(Minecraft minecraft,
                                          net.minecraft.client.player.LocalPlayer self,
                                          RenderLevelStageEvent event) {
        if (!ShanhaiHoloRemoteView.showOthersBoards() && !ShanhaiHoloRemoteView.showOthersSurround()) {
            return 0;                                   // 两个开关都关 ⇒ 连遍历都不做
        }
        final net.minecraft.client.multiplayer.ClientLevel level = minecraft.level;
        if (level == null) {
            return 0;
        }
        // MVP 同一帧只算一次（设计 §2.5：VIEW_FULL/MVP/PROJ 别每人算一遍）
        final Vec3 cam = event.getCamera().getPosition();
        ShanhaiHoloSurroundView.composeView(CULL_VIEW, VIEW_MATRIX, cam.x, cam.y, cam.z);
        CULL_MVP.set(event.getProjectionMatrix()).mul(CULL_VIEW);

        int n = 0;
        for (net.minecraft.client.player.AbstractClientPlayer other : level.players()) {
            if (other == null || other == self) {
                continue;
            }
            if (!ShanhaiHoloRemoteState.paintable(other.getUUID())) {
                continue;
            }
            if (other.distanceToSqr(self) > ShanhaiHoloMenuNetwork.RENDER_DISTANCE_SQR) {
                continue;
            }
            if (!onScreen(other.getEyePosition(event.getPartialTick()))) {
                continue;
            }
            if (n < REMOTE_PEERS.length) {
                REMOTE_PEERS[n++] = other;
            }
        }
        return n;
    }

    /** 世界坐标是不是在画面里（宽容 1.5 个 NDC）。 */
    private static boolean onScreen(Vec3 world) {
        CULL_POINT.set((float) world.x, (float) world.y, (float) world.z, 1.0f);
        CULL_MVP.transform(CULL_POINT);
        if (CULL_POINT.w <= 1.0e-4f) {
            return false;                               // 在相机平面后面
        }
        final float x = CULL_POINT.x / CULL_POINT.w;
        final float y = CULL_POINT.y / CULL_POINT.w;
        return x >= -CULL_NDC && x <= CULL_NDC && y >= -CULL_NDC && y <= CULL_NDC;
    }

    /**
     * 🆕 <b>画别人的 5 块菜单板</b>（联机 P3 的主体）。
     *
     * <h4>为什么"画出来的一定和他自己看到的一样"</h4>
     * 位姿走 {@link ShanhaiHoloPose#landingForRemote}（与本地 {@code advanceHolo} 落地那一支
     * <b>同一个式子</b>），帧变换走 {@link #applyHoloFrame}（与本地同一个）、
     * 板落位走 {@link #poseBoard}（与本地同一个）、三层绘制走
     * {@link #drawPlateLayers}/{@link #drawGlowLayers}/{@link #drawTextLayers}
     * （与本地同一个）⇒ 全链路<b>没有第二份公式</b>。
     * 离线套件里有一条断言专门钉这件事（远程 vs 本地 34 个数逐位相等 + rotLerp 负对照）。
     *
     * <h4>🔴 三件"远程不许画"的东西（都要在进这个循环之前摆平）</h4>
     * <ol>
     *   <li><b>按下/加载</b>：{@code pressBoard/pressAmount} 是<b>本地</b>状态（他按没按下去，
     *       别人不该知道）⇒ 这里临时置 -1/0，出来后<b>原样还原</b>
     *       （本地第 2、4 遍还要用它们）。</li>
     *   <li><b>展开动画</b>：{@code animSpin=0 / animScale=1}（"已经展开完毕"的静止态），
     *       而 {@code globalAlpha} 是逐顶点乘进去的静态量 ⇒ 临时置 1.0 再还原。</li>
     *   <li><b>面板</b>：别人只画 5 块菜单板，永远不画设置/命令面板（用户拍板①）
     *       ⇒ 这里不碰 {@code panelMode}，也永远不调 {@code drawPanelRowText} 那一组。</li>
     * </ol>
     *
     * @param count 上一层已经算好的可见人数（{@link #collectRemotePeers} 的结果）
     */
    private static void drawRemoteBoards(PoseStack poseStack, Vec3 camera,
                                         MultiBufferSource.BufferSource buffers, int count,
                                         float tSec, float partialTick) {
        if (count <= 0) {
            if (lastDrawnPeers != 0) {
                lastDrawnPeers = 0;
                com.shanhai.ShanhaiMod.LOGGER.info(
                        "{} holo_mp_draw peers=0（没有可见的别人：没开开关/他关着/没收到 DEF/超 64 格/屏幕外）",
                        ShanhaiHoloRemoteView.PREFIX);
            }
            return;
        }
        // 保存本地那三个"每帧算一次"的静态量 —— 远程那一遍要用不同的值，用完必须还原
        final int savedPressBoard = pressBoard;
        final float savedPressAmount = pressAmount;
        final float savedPressBreath = pressBreath;
        final float savedGlobalAlpha = globalAlpha;
        pressBoard = -1;
        pressAmount = 0.0f;
        pressBreath = 0.0f;
        globalAlpha = 1.0f;
        try {
            final Font font = Minecraft.getInstance().font;
            for (int k = 0; k < count; k++) {
                final net.minecraft.client.player.AbstractClientPlayer other = REMOTE_PEERS[k];
                final ShanhaiHoloRemoteState.Entry st = ShanhaiHoloRemoteState.of(other.getUUID());
                if (st == null || !st.enabled || !st.defReceived) {
                    continue;                            // 两次遍历之间他改了？那就这一帧跳过
                }
                final String layout = layoutNameOf(st.layout);
                // ⚠️ 眼睛位置只取一次（三次调用会白算两遍插值）
                final Vec3 eye = other.getEyePosition(partialTick);
                final ShanhaiHoloPose.Landing landing = ShanhaiHoloPose.landingForRemote(
                        layout, st.distance, eye.x, eye.y, eye.z,
                        other.yRotO, other.getYRot(), partialTick);
                // ---- 一个 Frame：push（applyHoloFrame 里）→ 板 → pop ----
                applyHoloFrame(poseStack, camera, landing.cx(), landing.cy(), landing.cz(),
                        landing.frameYawDeg(), 0.0f, 1.0f, tSec);
                try {
                    final ShanhaiHoloMenuLayout.Board[] bs = landing.boards();
                    final VertexConsumer rp = buffers.getBuffer(plateType());
                    for (int i = 0; i < ShanhaiHoloMenuTuning.BOARD_COUNT && i < bs.length; i++) {
                        poseBoard(poseStack, bs[i], -1, 0.0f);
                        drawPlateLayers(rp, poseStack.last().pose(), i, tSec);
                        poseStack.popPose();
                        drawnBoards++;
                        probeBoards++;              // 🆕 遮挡探针：别人的板也算"这一帧提交了"
                    }
                    final VertexConsumer rg = buffers.getBuffer(glowType());
                    final float breath = breath(tSec);
                    for (int i = 0; i < ShanhaiHoloMenuTuning.BOARD_COUNT && i < bs.length; i++) {
                        poseBoard(poseStack, bs[i], -1, 0.0f);
                        drawGlowLayers(rg, poseStack.last().pose(), i, tSec, breath);
                        poseStack.popPose();
                        drawnBoards++;
                    }
                    for (int i = 0; i < ShanhaiHoloMenuTuning.BOARD_COUNT && i < bs.length; i++) {
                        poseBoard(poseStack, bs[i], -1, 0.0f);
                        drawTextLayers(font, buffers, poseStack.last().pose(), i, tSec);
                        poseStack.popPose();
                        drawnBoards++;
                    }
                } finally {
                    // 🔴 与 applyHoloFrame 里那个 pushPose 配对：无论中间抛不抛都要弹回来
                    poseStack.popPose();
                }
                drawnPeers++;
                drawnFrames++;
                if (k == 0) {
                    // 每人最多一行读数（只打第一个人，免得人多刷屏）
                    com.shanhai.ShanhaiMod.LOGGER.info(
                            "{} holo_mp_remote_draw uuid={} layout={} distance={} yaw_rotLerp={} {}",
                            ShanhaiHoloRemoteView.PREFIX,
                            other.getUUID().toString().substring(0, 8), layout, st.distance,
                            ShanhaiHoloPose.yawDeg(other.yRotO, other.getYRot(), partialTick),
                            ShanhaiHoloPose.describe(landing.cx(), landing.cy(), landing.cz(),
                                    landing.frameYawDeg()));
                }
            }
            if (lastDrawnPeers != count) {
                lastDrawnPeers = count;
                com.shanhai.ShanhaiMod.LOGGER.info(
                        "{} holo_mp_draw peers={} boards={} capacity_verts={} frames={}",
                        ShanhaiHoloRemoteView.PREFIX, count, drawnBoards,
                        ShanhaiHoloPose.bufferCapacityFor(1 + count), drawnFrames);
            }
        } finally {
            pressBoard = savedPressBoard;
            pressAmount = savedPressAmount;
            pressBreath = savedPressBreath;
            globalAlpha = savedGlobalAlpha;
        }
    }

    /** 排布序号 → 几何核认的那个名字（{@code ShanhaiHoloMenuState.Layout} 的 ordinal）。 */
    private static String layoutNameOf(int ordinal) {
        return switch (ordinal) {
            case ShanhaiHoloRemoteCodec.LAYOUT_COLUMN -> "column";
            case ShanhaiHoloRemoteCodec.LAYOUT_ROW -> "row";
            default -> "ring";
        };
    }

    // ------------------------------------------------------------------ 板变换

    /**
     * 🆕 2026-10-07（联机 P1）<b>把一个【已经算好的】全息位姿压成相机空间的 PoseStack 帧</b>。
     *
     * <p>它就是原来 {@code onRenderLevelStage} 里那一段（{@code pushPose → translate(−cam) →
     * mulPose(YP, frameYaw+spin) → 可选 scale → applyJitter}）<b>原样搬出来</b>，
     * 唯一的变化是"数从哪来"：原来是就地读本方法的局部变量与本类的静态字段，
     * 现在是<b>参数</b>。
     *
     * <h4>为什么可以这样抽（逐位一致的证明）</h4>
     * <pre>
     *   抽出前：t = (ox−cam.x, …)、R = Rot_Y(frameYaw+spin) 都在这里算，就地乘进去
     *   抽出后：t、R 是【同一批数】由调用方传进来 ⇒ 同一个式子、同一个乘法顺序 ⇒ 逐位相同
     * </pre>
     * 调用方一处都没变（本地的四条绘制遍仍然紧接着它跑），所以"本地那条路径的读数逐位不变"。
     *
     * <h4>🔴 {@code pushPose} 与调用方 {@code finally} 里的 {@code popPose} 是一对，不许拆</h4>
     * 本方法负责 {@code pushPose}，配对的那个 {@code poseStack.popPose()} 留在
     * {@code onRenderLevelStage} 的 {@code finally} 里 —— 这是"渲染线程抛异常也不会把矩阵栈搞歪"
     * 的唯一保险（{@code try} 一开始就压进去、任何出口都弹出来）。
     * <b>把这个 push 挪到 try 之外/之后、或者把 pop 挪进本方法，都会破坏这条配对。</b>
     *
     * @param poseStack  事件给的（只有相机旋转）
     * @param camera     这一帧的相机世界坐标（{@code event.getCamera().getPosition()}）
     * @param ox,oy,oz   全息原点世界坐标（已含展开动画插值）
     * @param frameYawDeg 框偏航（已含朝向档 / 环绕档语义）
     * @param animSpinDeg 展开动画的自转（度）
     * @param animScale  展开动画的整块缩放（{@code 1.0f} = 不动，会整段跳过）
     * @param tSec       这一帧的时刻（秒）
     */
    private static void applyHoloFrame(PoseStack poseStack, Vec3 camera,
                                       double ox, double oy, double oz,
                                       float frameYawDeg, float animSpinDeg, float animScale,
                                       float tSec) {
        poseStack.pushPose();
        // 平移到世界的锚点（相机相对坐标：事件给的 PoseStack 已经是"相机视图矩阵"）
        poseStack.translate(ox - camera.x, oy - camera.y, oz - camera.z);
        // 【全息本体坐标系】落地。⚠️ 这里用的是【框偏航】而不是玩家偏航（两者在旧档里是 180−yaw 的关系、
        // 在环绕档里恒为 0；面板那一列另有它的朝向档）—— 值一律由 ShanhaiHoloMenuState 算好
        // （advanceHolo / panelFrameYaw / ShanhaiHoloPose.landing），本行不再自己推导。
        poseStack.mulPose(Axis.YP.rotationDegrees(frameYawDeg + animSpinDeg));
        // 展开时的整块缩放（1 ⇒ 不动）
        if (animScale != 1.0f) {
            poseStack.scale(animScale, animScale, animScale);
        }
        // 全息本体抖动（甲案的 holoJit：周期末尾 4% 的时间里跳两下）
        applyJitter(poseStack, tSec);
    }

    /**
     * 把一块板的局部位姿压到 {@code PoseStack} 上。
     *
     * <p>顺序固定 <b>位移 → 自转 → 缩放</b>，且缩放取
     * {@code BLOCKS_PER_BU × 该板自己的 scale} ⇒ 进入板内坐标系后
     * <b>1 个坐标单位 = 1 bu</b>，板内的一切尺寸都能直接照抄甲案的 CSS px。
     *
     * <p>🆕 2026-10-06：如果这块板正是"刚被点下去、正在加载"的那一块，
     * 在自转之后、缩尺之前<b>多压一层"按进去"的变换</b>——
     * 沿板面法线往观众的反方向挪 {@link ShanhaiHoloMenuPending#PRESS_DEPTH_BU} bu、
     * 并且横竖缩 {@link ShanhaiHoloMenuPending#PRESS_SHRINK}。
     * <p>⚠️ 这两下都作用在<b>板自己的局部坐标系</b>里，所以环绕档下每一块板被按下去的方向
     * 都是"顺着它自己的法线往里"，不会出现"有的板往左、有的往右"。
     *
     * <p>🔴 2026-10-07（联机 P1）：把"按下"那两个量<b>改成参数</b>（原来是读本类的静态字段
     * {@code pressBoard} / {@code pressAmount}）⇒ 于是远程那一遍可以传
     * {@code poseBoard(…, -1, 0f)}（"他有没有按下去"是本地的状态，<b>远程不该画</b>），
     * 而本地那一路的读数逐位不变（传进去的就是原来那两个字段的值）。
     */
    private static void poseBoard(PoseStack poseStack, ShanhaiHoloMenuLayout.Board board,
                                  int pressBoard, float pressAmount) {
        poseStack.pushPose();
        poseStack.translate(board.x(), board.y(), board.z());
        if (board.rotY() != 0.0f) {
            poseStack.mulPose(Axis.YP.rotationDegrees(board.rotY()));
        }
        if (board.rotX() != 0.0f) {
            poseStack.mulPose(Axis.XP.rotationDegrees(board.rotX()));
        }
        if (board.index() == pressBoard && pressAmount > 0.0f) {
            poseStack.translate(0.0f, 0.0f, ShanhaiHoloMenuPending.PRESS_DEPTH_BU * pressAmount);
            final float shrink = 1.0f - ShanhaiHoloMenuPending.PRESS_SHRINK * pressAmount;
            poseStack.scale(shrink, shrink, 1.0f);
        }
        final float s = ShanhaiHoloMenuTuning.BLOCKS_PER_BU * board.scale();
        poseStack.scale(s, s, s);
    }

    /**
     * 本地那一路的板变换 = {@link #poseBoard} 喂本帧的"按下"状态（原实现，一行没变）。
     *
     * <p>⚠️ 与 {@code poseBoard} 是<b>同一个函数体的两次调用</b>，不是两份公式：
     * 这里只是把本类那两个静态字段（{@code pressBoard} / {@code pressAmount}）当参数传下去。
     */
    private static void pushBoard(PoseStack poseStack, ShanhaiHoloMenuLayout.Board board) {
        poseBoard(poseStack, board, pressBoard, pressAmount);
    }

    /**
     * 该板的亮度倍率。
     *
     * <p>🔴 2026-10-06（用户点单「还是5个按键，修改成1个是配方修改，另外4个是未启用」）：
     * <b>唯一可用的那一块</b>（{@link ShanhaiHoloMenuBoards#ENABLED_INDEX} = 「配方修改」）
     * 乘 {@link ShanhaiHoloMenuTuning#HIGHLIGHT_BRIGHT}，其余四块乘
     * {@link ShanhaiHoloMenuTuning#DISABLED_BRIGHT} ⇒ 观感上"只有一块是通电的"。
     * <p>⚠️ 这里<b>只改亮度倍率</b>：几何、位姿、层序、动效周期一个字都没动
     * （用户已验收过那套观感）。
     */
    // ------------------------------------------------------------------ 🆕 面板"画哪几行"

    /**
     * 🆕 2026-10-06（用户第 ② 条点单）：<b>这一块板这一帧要不要画</b>。
     *
     * <p>菜单模式：五块板都画（<b>一个字都没动</b>）。
     * 面板模式：只画 {@link ShanhaiHoloMenuPanel#rowVisible} 说"可见"的那些行 ——
     * 命令面板因此只剩【输入框】与【返回框】两个框。
     *
     * <p>⚠️ 没画出来的行<b>仍然在板表里</b>（位姿、行距、板尺寸一个数都没改），
     * 只是这一帧不提交顶点 ⇒ 那套"一块板 = 一行"的几何原样保留。
     */
    private static boolean rowDrawn(int index) {
        return panelMode == ShanhaiHoloMenuPanel.MODE_NONE
                || ShanhaiHoloMenuPanel.rowVisible(panelMode, index);
    }

    /** 上面那个函数算出来的"这一帧要喂给环绕层几块板"（见 {@link #surroundBoards}）。 */
    private static int rowCountCache;

    /** 复用的板表（环绕层要 5 块以内的板算禁区；不每帧 new）。 */
    private static final ShanhaiHoloMenuLayout.Board[] SURROUND_BOARDS =
            new ShanhaiHoloMenuLayout.Board[ShanhaiHoloMenuTuning.BOARD_COUNT];

    /**
     * 🆕 这一帧喂给环绕层哪些板（环绕层用它们算"板面禁区"，见 {@code ShanhaiHoloSurround} 规则 ③）。
     *
     * <p>面板模式下<b>只喂画出来的行</b>：否则没画出来的那三行也会在环绕层上挖出三块
     * 看不见的洞（禁区是按板面算的，与"画没画"无关）。
     *
     * @return 菜单模式 = 原数组（长度 5）；面板模式 = 复用的静态数组，
     *         <b>有效长度写在 {@link #rowCountCache} 里</b>（调用方紧接着读它）
     */
    private static ShanhaiHoloMenuLayout.Board[] surroundBoards(ShanhaiHoloMenuLayout.Board[] boards) {
        if (panelMode == ShanhaiHoloMenuPanel.MODE_NONE) {
            rowCountCache = boards.length;
            return boards;
        }
        int k = 0;
        for (int i = 0; i < boards.length && i < ShanhaiHoloMenuTuning.BOARD_COUNT; i++) {
            if (ShanhaiHoloMenuPanel.rowVisible(panelMode, i)) {
                SURROUND_BOARDS[k++] = boards[i];
            }
        }
        rowCountCache = k;
        return SURROUND_BOARDS;
    }

    private static float brightOf(int index) {
        // 🆕 面板模式：画出来的行板一律"通电"（那是可点的表，不是"未启用的按键"），
        //    被射线指到的那一行再亮一档（"你现在指着这一行"）。
        if (panelMode != ShanhaiHoloMenuPanel.MODE_NONE) {
            return index == panelHoverRow
                    ? ShanhaiHoloMenuTuning.HIGHLIGHT_BRIGHT * 1.18f
                    : ShanhaiHoloMenuTuning.HIGHLIGHT_BRIGHT;
        }
        final float base = ShanhaiHoloMenuBoards.isEnabled(index)
                ? ShanhaiHoloMenuTuning.HIGHLIGHT_BRIGHT
                : ShanhaiHoloMenuTuning.DISABLED_BRIGHT;
        if (index != pressBoard) {
            return base;
        }
        // 🆕 被按下去的那一块：在它自己的亮度上再加一层随呼吸起伏的加成
        //    （观感 = "这一块正在工作"）。不点的时候这一项恒为 0，观感与已验收的一模一样。
        return base * (1.0f + ShanhaiHoloMenuPending.PRESS_BRIGHT_BOOST * pressAmount * pressBreath);
    }

    /**
     * 🆕 2026-10-06：<b>面板一行的文字</b>（设置 / 命令）。
     *
     * <p>一行 = 一块板，横切成 {@link ShanhaiHoloMenuPanel#CELLS} 格：
     * 格 0 是标签（亮白），格 1..3 是选项/按钮。颜色只用三个既有常量：
     * <pre>
     *   标签           {@code CN_TEXT}      （与菜单板中文标签同一个白）
     *   普通选项/按钮  {@code INDEX_TEXT}   （淡青，看得出来"这是个能点的东西"）
     *   当前生效的那档 {@code CORNER}       （金色 —— 与四角括号同一个金）
     * </pre>
     * ⚠️ 文字位置由 {@link ShanhaiHoloMenuPanel#cellLeftBu} 给（与命中判定
     * {@link ShanhaiHoloMenuPanel#cellOf} 严格互逆）⇒ <b>画在哪与点在哪不可能漂</b>。
     */
    private static void drawPanelRowText(Font font, MultiBufferSource buffers, Matrix4f m, int row) {
        final boolean hovered = row == panelHoverRow;
        for (int cell = 0; cell < ShanhaiHoloMenuPanel.CELLS; cell++) {
            String text = ShanhaiHoloMenuPanel.cellText(panelMode, row, cell);
            if (text == null || text.isEmpty()) {
                continue;
            }
            // 🆕 2026-10-06（第二轮）："格 0 是不是标签"改成问面板 —— 「环绕特效」那一行的
            //    格 0 是【一颗按钮】（「关」），「高级调参」那一行的格 0 也是（整行可点）。
            //    写死 `cell == 0` 会把它们画成标签色/标签字号。
            final boolean isLabel = ShanhaiHoloMenuPanel.isLabelCell(panelMode, row, cell);
            // 🆕 命令面板的【输入框】那一行：字号更大 + 空的时候用暗色（那句话是占位符，不是你打的字）。
            final boolean isCommandInput = panelMode == ShanhaiHoloMenuPanel.MODE_COMMAND
                    && row == ShanhaiHoloMenuPanel.COMMAND_INPUT_ROW && isLabel;
            if (isCommandInput) {
                // 画出来的是"输入本身"；只有超过 30 字（板宽装不下）时才留尾巴。
                // ⚠️ 这只是一处【绘制宽度】的裁剪；面板上"写的是什么"的口径永远是 inputDisplay()。
                text = ShanhaiHoloMenuPanel.visibleCommandTail();
            }
            final boolean current = !isCommandInput && ShanhaiHoloMenuPanel.isCurrent(text);
            final boolean clickable = ShanhaiHoloMenuPanel.clickable(panelMode, row, cell);
            int argb;
            if (isCommandInput) {
                argb = ShanhaiHoloMenuPanel.inputIsPlaceholder()
                        ? ShanhaiHoloMenuTuning.EN_TEXT                  // 占位符：暗（不是内容）
                        : ShanhaiHoloMenuTuning.CN_TEXT;                 // 真输入：亮
            } else if (current) {
                argb = ShanhaiHoloMenuTuning.CORNER;
            } else if (isLabel) {
                argb = ShanhaiHoloMenuTuning.CN_TEXT;
            } else if (clickable) {
                argb = hovered ? ShanhaiHoloMenuTuning.CN_TEXT : ShanhaiHoloMenuTuning.INDEX_TEXT;
            } else {
                argb = ShanhaiHoloMenuTuning.EN_TEXT;
            }
            final float x = ShanhaiHoloMenuPanel.cellLeftBu(cell)
                    + (isLabel ? PANEL_CELL_PAD_BU : PANEL_CELL_PAD_BU + 4.0f);
            drawSpaced(font, buffers, m, text, x, PANEL_TEXT_TOP_Y_BU, argb,
                    isCommandInput ? PANEL_INPUT_SCALE : (isLabel ? PANEL_LABEL_SCALE : PANEL_OPTION_SCALE),
                    isLabel ? 1.2f : 1.0f);
        }
    }

    /**
     * 🆕 <b>命令面板的两个框</b>（用户第 ② 条点单：一输入、一返回）。
     *
     * <pre>
     *   输入框（{@link ShanhaiHoloMenuPanel#COMMAND_INPUT_ROW}）：整块淡底 + 四边细框（亮一档）
     *   返回框（{@link ShanhaiHoloMenuPanel#COMMAND_BACK_ROW}）：同上，底色略暗（"这是出口"）
     * </pre>
     * 🔴 两块都是<b>一行一块、各自一条完整竖框</b>。修复前输入区是"两块行板拼起来"
     * （中间那半块还被单独画成一个空框 —— 用户看到的就是"怎么还有5个框"）。
     *
     * <h4>为什么画在【加法发光那一遍】</h4>
     * 那一遍 {@code WriteMaskStateShard(true, false)} = <b>不写深度</b>
     * ⇒ 这个框永远不会挡住同一面上随后画的文字（第 3 遍），也不会与底板 z-fighting。
     * 代价是它只能"加亮"不能"压暗"——对一个全息输入框来说，加亮正是要的。
     *
     * @param row {@link ShanhaiHoloMenuPanel#COMMAND_INPUT_ROW} 或
     *            {@link ShanhaiHoloMenuPanel#COMMAND_BACK_ROW}
     */
    private static void drawPanelCommandField(VertexConsumer vc, Matrix4f m, int row, float breath) {
        final float hw = ShanhaiHoloMenuTuning.BOARD_WIDTH_BU * 0.5f;
        final float hh = ShanhaiHoloMenuTuning.BOARD_HEIGHT_BU * 0.5f;
        final float a = 0.55f * breath;
        final float b = PANEL_FIELD_BORDER_BU;
        // 底色：输入框亮一档（"这里是打字的"）、返回框暗一档（"这是出口"）
        final float face = (row == ShanhaiHoloMenuPanel.COMMAND_INPUT_ROW ? 0.34f : 0.26f) * a;
        rect(vc, m, -hw, -hh, hw, hh, ShanhaiHoloMenuTuning.LAYER_INNER_GLOW_BU,
                ShanhaiHoloMenuTuning.PLATE_LT, ShanhaiHoloMenuTuning.PLATE_LB,
                ShanhaiHoloMenuTuning.PLATE_RT, ShanhaiHoloMenuTuning.PLATE_LT, face);
        outlineRect(vc, m, -hw, -hh, hw, hh, ShanhaiHoloMenuTuning.LAYER_OUTLINE_BU,
                b, ShanhaiHoloMenuTuning.INDEX_TICK, a);
    }

    /**
     * 🆕 面板上"被指到的那一行"左侧那根竖条（加法混合那一遍）。
     * <p>它存在是为了让高亮不只靠亮度：亮度差在明亮的天空背景下不一定看得出，
     * 而一根贴着左边缘的实心竖条是"指着谁"最不含糊的说法。
     */
    private static void drawPanelHoverMark(VertexConsumer vc, Matrix4f m) {
        final float hw = ShanhaiHoloMenuTuning.BOARD_WIDTH_BU * 0.5f;
        final float hh = ShanhaiHoloMenuTuning.BOARD_HEIGHT_BU * 0.5f;
        rect(vc, m, -hw + 1.0f, -hh + 3.0f, -hw + 4.0f, hh - 3.0f,
                ShanhaiHoloMenuTuning.LAYER_EDGE_BU,
                ShanhaiHoloMenuTuning.FLOW_COLOR, ShanhaiHoloMenuTuning.FLOW_COLOR,
                ShanhaiHoloMenuTuning.FLOW_COLOR, ShanhaiHoloMenuTuning.FLOW_COLOR, 1.0f);
    }

    // ------------------------------------------------------------------ 第 1 遍

    /** 底板 + 顶边高光 + 左侧流光条 + 外描边 + 四角括号 + 序号分隔线（普通 alpha、写深度）。 */
    private static void drawPlateLayers(VertexConsumer vc, Matrix4f m, int index, float tSec) {
        final float hw = ShanhaiHoloMenuTuning.BOARD_WIDTH_BU * 0.5f;
        final float hh = ShanhaiHoloMenuTuning.BOARD_HEIGHT_BU * 0.5f;
        final float bright = brightOf(index);
        final int edgePulse = pulseColor(ShanhaiHoloMenuTuning.EDGE_BAR_BRIGHT, tSec, bright);

        // ① 底板：left→right 的渐变（甲案 linear-gradient(100deg, ...)）
        rect(vc, m, -hw, -hh, hw, hh, ShanhaiHoloMenuTuning.LAYER_PLATE_BU,
                brightColor(ShanhaiHoloMenuTuning.PLATE_LB, bright),
                brightColor(ShanhaiHoloMenuTuning.PLATE_RB, bright),
                brightColor(ShanhaiHoloMenuTuning.PLATE_RT, bright),
                brightColor(ShanhaiHoloMenuTuning.PLATE_LT, bright),
                1.0f);

        // ② 顶边高光（甲案 inset 0 1px 0）
        rect(vc, m, -hw, hh - ShanhaiHoloMenuTuning.TOP_HIGHLIGHT_H_BU, hw, hh,
                ShanhaiHoloMenuTuning.LAYER_TOP_HL_BU,
                ShanhaiHoloMenuTuning.TOP_HIGHLIGHT, ShanhaiHoloMenuTuning.TOP_HIGHLIGHT,
                ShanhaiHoloMenuTuning.TOP_HIGHLIGHT, ShanhaiHoloMenuTuning.TOP_HIGHLIGHT,
                bright);

        // ③ 左侧那道竖向流光条（甲案 .slab::after，透明→亮→透明）
        vGradBar(vc, m, -hw, -hw + ShanhaiHoloMenuTuning.EDGE_BAR_W_BU, -hh, hh,
                ShanhaiHoloMenuTuning.LAYER_EDGE_BU,
                ShanhaiHoloMenuTuning.EDGE_BAR_CLEAR, edgePulse, 1.0f);

        // ④ 外描边（甲案 border:1px）
        outlineRect(vc, m, -hw, -hh, hw, hh, ShanhaiHoloMenuTuning.LAYER_OUTLINE_BU,
                ShanhaiHoloMenuTuning.BORDER_W_BU, ShanhaiHoloMenuTuning.OUTLINE, bright);

        // ⑤ 四角括号（甲案 .br 的四个 L 形角标）
        cornerBrackets(vc, m, hw, hh, ShanhaiHoloMenuTuning.LAYER_CORNER_BU,
                ShanhaiHoloMenuTuning.CORNER, bright);

        // ⑥ 序号后那根 1×12 的分隔竖线（甲案 .idx::after；它是一块纯几何，所以放在这一遍，
        //    而不是混进第 3 遍的文字批次里）
        final float tickX = -hw + ShanhaiHoloMenuTuning.INDEX_TICK_LEFT_BU;
        final float tickTop = hh - ShanhaiHoloMenuTuning.INDEX_TICK_TOP_Y_BU;
        rect(vc, m, tickX, tickTop - ShanhaiHoloMenuTuning.INDEX_TICK_H_BU,
                tickX + ShanhaiHoloMenuTuning.INDEX_TICK_W_BU, tickTop,
                ShanhaiHoloMenuTuning.LAYER_CORNER_BU,
                ShanhaiHoloMenuTuning.INDEX_TICK, ShanhaiHoloMenuTuning.INDEX_TICK,
                ShanhaiHoloMenuTuning.INDEX_TICK, ShanhaiHoloMenuTuning.INDEX_TICK,
                bright);
    }

    // ------------------------------------------------------------------ 第 2 遍

    /** 内发光 + 描边外发光 + 四角外发光 + 描边流光 + 扫描线 + 色散干扰线（加法混合）。 */
    private static void drawGlowLayers(VertexConsumer vc, Matrix4f m, int index, float tSec, float breath) {
        final float hw = ShanhaiHoloMenuTuning.BOARD_WIDTH_BU * 0.5f;
        final float hh = ShanhaiHoloMenuTuning.BOARD_HEIGHT_BU * 0.5f;
        final float bright = brightOf(index);
        final float a = breath * bright;

        // ① 内发光：整块板面叠一层很淡的亮色（甲案 inset 0 0 26px rgba(60,180,225,.16)）
        rect(vc, m, -hw, -hh, hw, hh, ShanhaiHoloMenuTuning.LAYER_INNER_GLOW_BU,
                ShanhaiHoloMenuTuning.PLATE_LT, ShanhaiHoloMenuTuning.PLATE_LB,
                ShanhaiHoloMenuTuning.PLATE_RT, ShanhaiHoloMenuTuning.PLATE_LT, 0.35f * a);

        // ② 描边外发光：比描边粗 3 bu、更暗一圈
        outlineRect(vc, m, -hw, -hh, hw, hh, ShanhaiHoloMenuTuning.LAYER_OUTLINE_BU,
                ShanhaiHoloMenuTuning.BORDER_W_BU * 3.0f, ShanhaiHoloMenuTuning.OUTLINE, 0.45f * a);

        // ③ 四角括号外发光
        cornerBrackets(vc, m, hw, hh, ShanhaiHoloMenuTuning.LAYER_CORNER_BU,
                ShanhaiHoloMenuTuning.CORNER_GLOW, 1.4f * a);

        // ④ 描边流光：一小段高光绕板一周（甲案 railRun：3.6 秒一圈）
        final float perimeter = 2.0f * (2.0f * hw + 2.0f * hh);
        final float phase = phase01(tSec, ShanhaiHoloMenuTuning.OUTLINE_FLOW_PERIOD_SEC);
        flowSegment(vc, m, hw, hh, ShanhaiHoloMenuTuning.LAYER_OUTLINE_BU,
                phase * perimeter, ShanhaiHoloMenuTuning.FLOW_SEGMENT_BU,
                ShanhaiHoloMenuTuning.FLOW_THICKNESS_BU, a);

        // ⑤ 扫描线：自下而上扫一趟（甲案 barRise：3.4 秒一趟）
        final float scanPhase = phase01(tSec, ShanhaiHoloMenuTuning.SCAN_PERIOD_SEC);
        final float scanY = -ShanhaiHoloMenuTuning.SCAN_TRAVEL_BU
                + scanPhase * (2.0f * ShanhaiHoloMenuTuning.SCAN_TRAVEL_BU);
        hGradBar(vc, m, -ShanhaiHoloMenuTuning.SCAN_W_BU * 0.5f, ShanhaiHoloMenuTuning.SCAN_W_BU * 0.5f,
                scanY - ShanhaiHoloMenuTuning.SCAN_H_BU * 0.5f, scanY + ShanhaiHoloMenuTuning.SCAN_H_BU * 0.5f,
                ShanhaiHoloMenuTuning.LAYER_SCAN_BU, ShanhaiHoloMenuTuning.SCAN_BAR,
                fadeInOut(scanPhase) * a);

        // ⑥ 色散干扰线：比扫描线宽、慢 1.15 秒、暖粉色（甲案 .glitch）
        final float glitchPhase = phase01(tSec - ShanhaiHoloMenuTuning.GLITCH_DELAY_SEC,
                ShanhaiHoloMenuTuning.GLITCH_PERIOD_SEC);
        final float glitchY = -ShanhaiHoloMenuTuning.SCAN_TRAVEL_BU
                + glitchPhase * (2.0f * ShanhaiHoloMenuTuning.SCAN_TRAVEL_BU);
        hGradBar(vc, m, -ShanhaiHoloMenuTuning.GLITCH_W_BU * 0.5f, ShanhaiHoloMenuTuning.GLITCH_W_BU * 0.5f,
                glitchY - 0.5f, glitchY + 0.5f,
                ShanhaiHoloMenuTuning.LAYER_SCAN_BU, ShanhaiHoloMenuTuning.GLITCH_BAR,
                0.7f * fadeInOut(glitchPhase) * a);

        // ⑦ 🆕 被按下去的那一块：整圈描边闪一下（随呼吸起伏）—— "你点到我了" 的第一眼反馈。
        //    不点的时候 pressAmount=0 ⇒ 这一条 alpha 也是 0，画了等于没画（不会改变已验收的观感）。
        if (index == pressBoard) {
            outlineRect(vc, m, -hw, -hh, hw, hh, ShanhaiHoloMenuTuning.LAYER_OUTLINE_BU,
                    ShanhaiHoloMenuTuning.BORDER_W_BU * 2.0f,
                    ShanhaiHoloMenuTuning.FLOW_COLOR,
                    2.4f * pressAmount * pressBreath);
        }
    }

    // ------------------------------------------------------------------ 第 3 遍（文字）

    /**
     * 序号 + 中文（三遍色散）+ 英文副标题。
     *
     * <p>🔴 2026-10-06：<b>「未启用」那几块只画一遍、颜色整体压暗</b>（用户点单的"另外 4 个是未启用"）。
     * 色散（红/青两遍影子）是"通电/发光"的观感 —— 给一块写着「未启用」的板加发光影子会自相矛盾，
     * 所以那两遍只对可用的那块画。<b>排版位置、字号、字距全部沿用原值</b>。
     */
    private static void drawTextLayers(Font font, MultiBufferSource buffers, Matrix4f m, int index, float tSec) {
        final float hw = ShanhaiHoloMenuTuning.BOARD_WIDTH_BU * 0.5f;
        final float left = -hw;
        final boolean live = ShanhaiHoloMenuBoards.isEnabled(index);
        final float dim = live ? 1.0f : ShanhaiHoloMenuTuning.DISABLED_TEXT_MUL;

        // ① 左侧序号（甲案 .idx{left:17px;font-size:12px}，原版字号按 9px 行高算）
        drawSpaced(font, buffers, m,
                ShanhaiHoloMenuTuning.LABEL_INDEX[index],
                left + ShanhaiHoloMenuTuning.INDEX_LEFT_BU,
                ShanhaiHoloMenuTuning.INDEX_TOP_Y_BU,
                dimmed(ShanhaiHoloMenuTuning.INDEX_TEXT, dim),
                1.0f, 0.0f);

        // ② 中文标签 —— 色散三遍（甲案 text-shadow:±1px 红/青）
        //    偏移量按 chroma 周期在 1.0~1.7 bu 之间往返（甲案 chroma 关键帧）
        final float chromaPhase = phase01(tSec, ShanhaiHoloMenuTuning.CHROMA_PERIOD_SEC);
        final float wobble = 0.5f - 0.5f * (float) Math.cos(2.0 * Math.PI * chromaPhase);
        final float chroma = ShanhaiHoloMenuTuning.CHROMA_OFFSET_MIN_BU
                + wobble * (ShanhaiHoloMenuTuning.CHROMA_OFFSET_MAX_BU - ShanhaiHoloMenuTuning.CHROMA_OFFSET_MIN_BU);
        final String zh = ShanhaiHoloMenuTuning.LABEL_ZH[index];
        final float zhX = left + ShanhaiHoloMenuTuning.LABEL_LEFT_BU;
        if (live) {
            drawSpaced(font, buffers, m, zh, zhX + chroma, ShanhaiHoloMenuTuning.CN_TEXT_TOP_Y_BU,
                    ShanhaiHoloMenuTuning.CN_CHROMA_WARM, ShanhaiHoloMenuTuning.CN_TEXT_SCALE,
                    ShanhaiHoloMenuTuning.CN_LETTER_SPACING_BU);
            drawSpaced(font, buffers, m, zh, zhX - chroma, ShanhaiHoloMenuTuning.CN_TEXT_TOP_Y_BU,
                    ShanhaiHoloMenuTuning.CN_CHROMA_COOL, ShanhaiHoloMenuTuning.CN_TEXT_SCALE,
                    ShanhaiHoloMenuTuning.CN_LETTER_SPACING_BU);
        }
        drawSpaced(font, buffers, m, zh, zhX, ShanhaiHoloMenuTuning.CN_TEXT_TOP_Y_BU,
                dimmed(ShanhaiHoloMenuTuning.CN_TEXT, dim), ShanhaiHoloMenuTuning.CN_TEXT_SCALE,
                ShanhaiHoloMenuTuning.CN_LETTER_SPACING_BU);

        // ④ 英文副标题 —— 🆕 但"正在加载"的那一块，这一行换成中文「正在加载」（呼吸明暗）
        if (index == pressBoard) {
            // 用中文字号（CN_TEXT_SCALE）画在这一行：中文在 EN 的 1 倍字号下只有 9 bu 高，
            // 在 2.8 格外看不清 —— 而"看不清的提示"等于没有提示。
            drawSpaced(font, buffers, m, ShanhaiHoloMenuPending.LOADING_TEXT, zhX,
                    ShanhaiHoloMenuPending.LOADING_TEXT_TOP_Y_BU,
                    dimmed(ShanhaiHoloMenuTuning.CN_TEXT, pressBreath),
                    ShanhaiHoloMenuTuning.CN_TEXT_SCALE,
                    ShanhaiHoloMenuTuning.CN_LETTER_SPACING_BU);
        } else {
            drawSpaced(font, buffers, m, ShanhaiHoloMenuTuning.LABEL_EN[index], zhX,
                    ShanhaiHoloMenuTuning.EN_TEXT_TOP_Y_BU, dimmed(ShanhaiHoloMenuTuning.EN_TEXT, dim),
                    ShanhaiHoloMenuTuning.EN_TEXT_SCALE, ShanhaiHoloMenuTuning.EN_LETTER_SPACING_BU);
        }
    }

    /**
     * 🆕 「正在加载」下面那根<b>走马灯</b>进度条（纯几何，加法混合那一遍）。
     *
     * <p>🔴 它是<b>不确定进度</b>：客户端根本不知道服务端还要多久
     * （用户也说了"毕竟需要加载时间"）⇒ 画一个真百分比就是<b>在界面上放假数据</b>
     * （本工程血规矩：<b>宁可缺，不可假</b>）。
     * <p>观感 = 一根细长条，中间一段高光从左到右循环跑；底下再压一层很暗的"轨道"，
     * 让"它有多长"看得出来。
     */
    private static void drawLoadingSweep(VertexConsumer vc, Matrix4f m, long nowNanos) {
        final float hw = ShanhaiHoloMenuTuning.BOARD_WIDTH_BU * 0.5f;
        final float left = -hw + ShanhaiHoloMenuPending.SWEEP_LEFT_BU;
        final float right = left + ShanhaiHoloMenuPending.SWEEP_WIDTH_BU;
        final float yTop = ShanhaiHoloMenuPending.SWEEP_TOP_Y_BU;
        final float yBot = yTop - ShanhaiHoloMenuPending.SWEEP_BAR_H_BU;
        final float a = pressAmount * pressBreath;

        // 轨道（整条，很暗）：只画底线，让人知道"这根条有多长"
        rect(vc, m, left, yBot, right, yBot + 1.0f, ShanhaiHoloMenuTuning.LAYER_SCAN_BU,
                ShanhaiHoloMenuTuning.INDEX_TICK, ShanhaiHoloMenuTuning.INDEX_TICK,
                ShanhaiHoloMenuTuning.INDEX_TICK, ShanhaiHoloMenuTuning.INDEX_TICK, 0.9f * a);

        // 走马灯那一段高光：宽度固定 = 总宽的 28%，位置随 sweep01 循环
        final float head = left + ShanhaiHoloMenuPending.SWEEP_WIDTH_BU * ShanhaiHoloMenuPending.sweep01(nowNanos);
        final float segW = ShanhaiHoloMenuPending.SWEEP_WIDTH_BU * 0.28f;
        // 两端渐隐（复用 hGradBar 的"两端透明、中间最亮"）
        hGradBar(vc, m, head - segW * 0.5f, head + segW * 0.5f, yBot, yTop,
                ShanhaiHoloMenuTuning.LAYER_SCAN_BU, ShanhaiHoloMenuTuning.SCAN_BAR, 1.0f * a);
    }

    /**
     * {@code 0xAARRGGBB} 整体乘一个倍率（alpha 与 RGB 一起）——「未启用」那几块的压暗用它。
     * <p>⚠️ 与 {@link #brightColor} 不是一回事：那个只乘 RGB，这个连 alpha 一起压。
     */
    private static int dimmed(int argb, float mul) {
        if (mul >= 1.0f) {
            return argb;
        }
        final int a = clampByte((int) (((argb >>> 24) & 0xFF) * mul));
        final int r = clampByte((int) (((argb >>> 16) & 0xFF) * mul));
        final int g = clampByte((int) (((argb >>> 8) & 0xFF) * mul));
        final int b = clampByte((int) ((argb & 0xFF) * mul));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * 逐字符绘制，<b>自己实现字距</b>（原版 {@code Font} 没有 letter-spacing）。
     *
     * <p>🔴 Y 轴翻转见类注释 §4：文字矩阵是 {@code translate(x,y,0) · scale(k, −k, 1)}，
     * 因为 {@code Font} 的 y 向下增大，而板内坐标系 y 向上增大。
     *
     * <p>⚠️ {@code x/y} 是<b>文字行左上角</b>在板内坐标里的位置（不是基线）。
     */
    private static void drawSpaced(Font font, MultiBufferSource buffers, Matrix4f board,
                                   String text, float x, float topY, int argb,
                                   float textScale, float letterSpacing) {
        final int rgb = alphaScaled(argb, globalAlpha);   // 🆕 展开/收回时文字跟着一起淡
        float cursor = 0.0f;
        for (int i = 0; i < text.length(); i++) {
            final String ch = charString(text.charAt(i));
            // 🔴 复用 {@link #SCRATCH_MATRIX}（原来是每字符一次 new Matrix4f(board)）：
            //    先 set(board) 复位，再平移+缩放 —— 与原来那句逐位等价。
            final Matrix4f tm = SCRATCH_MATRIX.set(board);
            tm.translate(x + cursor, topY, 0.0f);
            tm.scale(textScale, -textScale, 1.0f);
            // 🆕 2026-11-08（ontop）：模式是【每帧一个值】——
            //   NORMAL     ⇒ 原版 RenderType.text（lequal），与改动前逐位一致；
            //   SEE_THROUGH⇒ 原版 RenderType.textSeeThrough（javap 核过：NO_DEPTH_TEST + 只写颜色）
            //                ⇒ 文字跟着板一起在最上层，否则"板浮出来了、字还被墙挡着"，
            //                  看图判定时会被误读成"半对"。
            font.drawInBatch(ch, 0.0f, 0.0f, rgb, false, tm, buffers,
                    textDisplayMode, 0, LIGHT_FULL_BRIGHT);
            cursor += font.width(ch) * textScale + letterSpacing;
        }
    }

    // ------------------------------------------------------------------ 几何小工具

    /** 一个矩形：{@code x∈[x0,x1]}、{@code y∈[y0,y1]}，四角颜色按 (左下, 右下, 右上, 左上)。 */
    private static void rect(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, float z,
                             int cLB, int cRB, int cRT, int cLT, float alphaMul) {
        vert(vc, m, x0, y0, z, cLB, alphaMul);
        vert(vc, m, x1, y0, z, cRB, alphaMul);
        vert(vc, m, x1, y1, z, cRT, alphaMul);
        vert(vc, m, x0, y1, z, cLT, alphaMul);
    }

    private static void vert(VertexConsumer vc, Matrix4f m, float x, float y, float z, int argb, float alphaMul) {
        probeVerts++;                                     // 🆕 遮挡探针：本帧提交的顶点数（+1/顶点）
        int a = (int) (((argb >>> 24) & 0xFF) * alphaMul * globalAlpha);
        if (a < 0) {
            a = 0;
        } else if (a > 255) {
            a = 255;
        }
        vc.vertex(m, x, y, z);
        vc.color((argb >>> 16) & 0xFF, (argb >>> 8) & 0xFF, argb & 0xFF, a);
        vc.endVertex();
    }

    /**
     * 🆕 <b>只压 alpha</b> 的颜色（展开动画的淡入淡出给文字用）。
     * <p>⚠️ 与 {@link #dimmed(int, float)} 不是一回事：那个连 RGB 一起压（观感是"变暗"），
     * 这个只压 alpha（观感才是"淡出"）。{@code mul >= 1} 时原样返回。
     */
    private static int alphaScaled(int argb, float mul) {
        if (mul >= 1.0f) {
            return argb;
        }
        final int a = clampByte((int) (((argb >>> 24) & 0xFF) * mul));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /** 矩形的四条边（线宽 {@code w}）。 */
    private static void outlineRect(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1,
                                    float z, float w, int c, float alphaMul) {
        rect(vc, m, x0, y0, x1, y0 + w, z, c, c, c, c, alphaMul);
        rect(vc, m, x0, y1 - w, x1, y1, z, c, c, c, c, alphaMul);
        rect(vc, m, x0, y0 + w, x0 + w, y1 - w, z, c, c, c, c, alphaMul);
        rect(vc, m, x1 - w, y0 + w, x1, y1 - w, z, c, c, c, c, alphaMul);
    }

    /** 四角的 L 形角标（甲案 {@code .br}：11×11 的方框只画朝内的两条边，内缩 3）。 */
    private static void cornerBrackets(VertexConsumer vc, Matrix4f m, float hw, float hh, float z,
                                       int c, float alphaMul) {
        final float s = ShanhaiHoloMenuTuning.CORNER_SIZE_BU;
        final float in = ShanhaiHoloMenuTuning.CORNER_INSET_BU;
        final float w = ShanhaiHoloMenuTuning.BORDER_W_BU;
        // 左上角：上边条 + 左边条
        rect(vc, m, -hw + in, hh - in - w, -hw + in + s, hh - in, z, c, c, c, c, alphaMul);
        rect(vc, m, -hw + in, hh - in - s, -hw + in + w, hh - in, z, c, c, c, c, alphaMul);
        // 右上角
        rect(vc, m, hw - in - s, hh - in - w, hw - in, hh - in, z, c, c, c, c, alphaMul);
        rect(vc, m, hw - in - w, hh - in - s, hw - in, hh - in, z, c, c, c, c, alphaMul);
        // 左下角
        rect(vc, m, -hw + in, -hh + in, -hw + in + s, -hh + in + w, z, c, c, c, c, alphaMul);
        rect(vc, m, -hw + in, -hh + in, -hw + in + w, -hh + in + s, z, c, c, c, c, alphaMul);
        // 右下角
        rect(vc, m, hw - in - s, -hh + in, hw - in, -hh + in + w, z, c, c, c, c, alphaMul);
        rect(vc, m, hw - in - w, -hh + in, hw - in, -hh + in + s, z, c, c, c, c, alphaMul);
    }

    /** 竖向三段渐变条（两端透明、中间最亮）—— 甲案左侧那道流光条。 */
    private static void vGradBar(VertexConsumer vc, Matrix4f m, float x0, float x1, float y0, float y1,
                                 float z, int cClear, int cBright, float alphaMul) {
        final float ym = (y0 + y1) * 0.5f;
        rect(vc, m, x0, y0, x1, ym, z, cClear, cClear, cBright, cBright, alphaMul);
        rect(vc, m, x0, ym, x1, y1, z, cBright, cBright, cClear, cClear, alphaMul);
    }

    /** 横向三段渐变条（两端透明、中间最亮）—— 扫描线 / 干扰线。 */
    private static void hGradBar(VertexConsumer vc, Matrix4f m, float x0, float x1, float y0, float y1,
                                 float z, int c, float alphaMul) {
        final int clear = c & 0x00FFFFFF;
        final float xm = (x0 + x1) * 0.5f;
        rect(vc, m, x0, y0, xm, y1, z, clear, c, c, clear, alphaMul);
        rect(vc, m, xm, y0, x1, y1, z, c, clear, clear, c, alphaMul);
    }

    /**
     * 沿板周长的一段流光（甲案 {@code railRun}）。
     *
     * <p>把这段高光按 {@link #FLOW_SEGMENTS} 段采样：先用 {@link #perimX}/{@link #perimY}
     * 把"周长距离"映射回板上的点，再逐段画成短条。这样<b>跨越直角拐角时也是连续的</b>
     * （不会在角上断开），代价只是 20 个小四边形。
     */
    private static void flowSegment(VertexConsumer vc, Matrix4f m, float hw, float hh, float z,
                                    float sStart, float length, float thickness, float alphaMul) {
        final int n = FLOW_SEGMENTS;
        float prevX = perimX(hw, hh, sStart);
        float prevY = perimY(hw, hh, sStart);
        for (int k = 1; k <= n; k++) {
            final float s = sStart + length * k / n;
            final float x = perimX(hw, hh, s);
            final float y = perimY(hw, hh, s);
            final float fadeHead = 1.0f - (float) k / n;   // 尾端更亮、头端渐隐
            final float fadeTail = 1.0f - (float) (k - 1) / n;
            stripe(vc, m, prevX, prevY, x, y, thickness, z,
                    ShanhaiHoloMenuTuning.FLOW_COLOR, ShanhaiHoloMenuTuning.FLOW_COLOR,
                    alphaMul * fadeTail, alphaMul * fadeHead);
            prevX = x;
            prevY = y;
        }
    }

    /** 两点之间的一根短条（粗细方向 = 与该段垂直）。 */
    private static void stripe(VertexConsumer vc, Matrix4f m, float ax, float ay, float bx, float by,
                               float thickness, float z, int ca, int cb, float mulA, float mulB) {
        final float dx = bx - ax;
        final float dy = by - ay;
        final float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1.0e-6f) {
            return;
        }
        final float nx = -dy / len * (thickness * 0.5f);
        final float ny = dx / len * (thickness * 0.5f);
        vert(vc, m, ax + nx, ay + ny, z, ca, mulA);
        vert(vc, m, bx + nx, by + ny, z, cb, mulB);
        vert(vc, m, bx - nx, by - ny, z, cb, mulB);
        vert(vc, m, ax - nx, ay - ny, z, ca, mulA);
    }

    // ---- 周长参数化（矩形边界，从左上角起顺时针，与 CSS 的"边框流动"同一个走法）----

    /** 周长距离 {@code s} → 板内 x。超出周长会按模取（周而复始）。 */
    private static float perimX(float hw, float hh, float s) {
        final float w = 2.0f * hw;
        final float h = 2.0f * hh;
        float t = s % (2.0f * (w + h));
        if (t < 0.0f) {
            t += 2.0f * (w + h);
        }
        if (t < w) {
            return -hw + t;          // 上边：左 → 右
        }
        t -= w;
        if (t < h) {
            return hw;               // 右边：上 → 下
        }
        t -= h;
        if (t < w) {
            return hw - t;           // 下边：右 → 左
        }
        return -hw;                  // 左边：下 → 上
    }

    /** 周长距离 {@code s} → 板内 y（与 {@link #perimX} 必须成对使用）。 */
    private static float perimY(float hw, float hh, float s) {
        final float w = 2.0f * hw;
        final float h = 2.0f * hh;
        float t = s % (2.0f * (w + h));
        if (t < 0.0f) {
            t += 2.0f * (w + h);
        }
        if (t < w) {
            return hh;               // 上边
        }
        t -= w;
        if (t < h) {
            return hh - t;           // 右边
        }
        t -= h;
        if (t < w) {
            return -hh;              // 下边
        }
        return -hh + (t - w);        // 左边
    }

    // ------------------------------------------------------------------ 时间与颜色小工具

    /** {@code t / period} 的小数部分，落在 {@code [0,1)}。 */
    private static float phase01(float tSec, float period) {
        if (period <= 0.0f) {
            return 0.0f;
        }
        float p = (tSec % period) / period;
        return p < 0.0f ? p + 1.0f : p;
    }

    /** 0 → 0、1 → 0 的单峰淡入淡出（甲案 barRise 的 opacity 关键帧）。 */
    private static float fadeInOut(float phase) {
        final float x = Math.max(0.0f, Math.min(1.0f, phase));
        return (float) Math.sin(Math.PI * x);
    }

    /** 整体呼吸（甲案 breathe 5.4s：0.82 ↔ 1.0）。 */
    private static float breath(float tSec) {
        final float p = phase01(tSec, ShanhaiHoloMenuTuning.BREATH_PERIOD_SEC);
        final float s = 0.5f - 0.5f * (float) Math.cos(2.0 * Math.PI * p);   // 0..1
        return ShanhaiHoloMenuTuning.BREATH_MIN
                + s * (ShanhaiHoloMenuTuning.BREATH_MAX - ShanhaiHoloMenuTuning.BREATH_MIN);
    }

    /** 左侧流光条的明暗往返（甲案 edgeRun 3.1s：.25 ↔ .85）。 */
    private static float edgeRunAlpha(float tSec) {
        final float p = phase01(tSec, ShanhaiHoloMenuTuning.EDGE_RUN_PERIOD_SEC);
        final float s = 0.5f - 0.5f * (float) Math.cos(2.0 * Math.PI * p);
        return 0.25f + s * (0.85f - 0.25f);
    }

    /** 按亮度倍率放大 RGB（alpha 原样；用 {@code vert} 的 alphaMul 单独调）。 */
    private static int brightColor(int argb, float mul) {
        if (mul == 1.0f) {
            return argb;
        }
        final int r = clampByte((int) (((argb >>> 16) & 0xFF) * mul));
        final int g = clampByte((int) (((argb >>> 8) & 0xFF) * mul));
        final int b = clampByte((int) ((argb & 0xFF) * mul));
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** {@link #brightColor} + 用 {@link #edgeRunAlpha} 调 alpha。 */
    private static int pulseColor(int argb, float tSec, float mul) {
        final int base = brightColor(argb, mul);
        final int a = clampByte((int) (((base >>> 24) & 0xFF) * edgeRunAlpha(tSec)));
        return (base & 0x00FFFFFF) | (a << 24);
    }

    private static int clampByte(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /**
     * 全息本体抖动（甲案 {@code holoJit 6.4s steps(1,end)}：周期末尾 96%/97%/98% 处各跳一下）。
     * <p>幅度只有 {@link ShanhaiHoloMenuTuning#JITTER_BU} bu ≈ 0.004 格 ——
     * 是"全息不稳定"的暗示，不是真的在晃（大了会晕）。
     */
    private static void applyJitter(PoseStack poseStack, float tSec) {
        final float p = phase01(tSec, ShanhaiHoloMenuTuning.JITTER_PERIOD_SEC);
        if (p < 0.96f) {
            return;
        }
        final int k = (int) ((p - 0.96f) / 0.01f);
        final float j = ShanhaiHoloMenuTuning.JITTER_BU * ShanhaiHoloMenuTuning.BLOCKS_PER_BU;
        switch (k) {
            case 0 -> poseStack.translate(j, -j, 0.0f);
            case 1 -> poseStack.translate(-j, j, 0.0f);
            case 2 -> poseStack.translate(j, 0.0f, 0.0f);
            default -> {
                // 第 4 帧回到原位：什么都不做
            }
        }
    }

    // ==================================================================== 🆕 2026-11-08/09：三档（＋D 档）调试色块
    //
    //   现场（用户实测）：visibility 说 menu_on=true / boards_in=5/5 / local_drawn=true / quads=270 /
    //   alpha=1.000 / scale=1.000，而屏幕上什么都没有；ontop 开/关都一样。
    //   新读数（2026-11-08 14:1x 真机 74 帧）：depth_occluded=1 ×18（depth_at_center=0.000000），
    //   depth_at_center=na ×55 ⇒ 要么"有东西贴在脸前把整屏盖住"，要么"深度根本读不到"。
    //
    //   ⇒ 本节点只做一件事：在同一帧里画四档【最保险】的色块，把三种病因分开：
    //       A 纯原版管线（相机空间直出）        → 判"渲染通路坏不坏"
    //       B 走 ShanhaiHoloSurroundView 那一条 → 判"我们的相机变换送没送对位置"
    //       C 走板子的完整位姿链（材质换纯色）  → 判"板子自身变换坏不坏（NaN / 缩放 0）"
    //       D 与 A 同位、但深度测试开着        → 判"是不是被那个 0 深度挡住"
    //   判定/拼行/NaN 检查全在纯类 {@link ShanhaiHoloDebugBox} 里（可离线验），本类只采样 + 画。
    //
    //   🔴 硬约束（逐条在下面的实现里看得见）：
    //     ① 四档一律 POSITION_COLOR 直出：不贴图、不用本类自造的 RenderType（plateType/glowType）、
    //        关混合（颜色 alpha 恒 255）⇒ 颜色不被任何混合状态影响；
    //     ② A/B/C 画之前【强制】关深度测试、D 画之前【强制】开 —— 而且每次都用 GL 真值回读验证
    //        （{@link #forceDepthTest}：RenderSystem 的 BooleanState 有缓存，缓存与真值分叉时那一句是空操作，
    //          这正是"ontop 打开了却还是被挡"的一种可能成因）；
    //     ③ 画完把深度/写掩码/混合/剔除/着色器颜色调制<b>一次性恢复成本帧开头的真值</b>，并回读验证；
    //     ④ 顶点<b>不</b>过 {@link #vert}（那个会累进 probeVerts = "正常板"的读数）⇒ 探针读数一个字节不变；
    //     ⑤ 任何异常只 WARN 一次，绝不把渲染打断（连"日志"都在画之前就打好）。

    /** 运行时开关（<b>不落配置</b>；默认值的唯一一份定义 = {@link ShanhaiHoloDebugBox#DEFAULT_ON}）。 */
    private static boolean debugBoxOn = ShanhaiHoloDebugBox.initial();

    /** 开这个开关以来渲染到第几帧（日志 {@code frame=}；即使这一帧被限流没打日志，值也在涨）。 */
    private static long debugBoxFrames;

    /** 上一次打 debugbox 行的时刻（{@code 0} = 还没打过）。 */
    private static long debugBoxLogNanos;

    /** 日志限流：1 行 / 秒（用户点名「每帧（1/秒限流）一行」）。 */
    private static final long DEBUG_BOX_LOG_THROTTLE_NANOS = 1_000_000_000L;

    /** debugbox 的异常只报一次的闸门（与 gl/bh/ontop 那三个闸门并列，但<b>不共用</b>它们的标志位）。 */
    private static boolean debugBoxWarned;

    // ---- 四档的几何（单位：格）----

    /** A/B/D 离相机多远。 */
    private static final float DEBUG_DIST_BLOCKS = 3.0f;
    /** A/B 那个色块的半边长（1×1 格）。 */
    private static final float DEBUG_BLOCK_HALF = 0.5f;
    /** D 那个小方块的半边长（0.6×0.6 格，画在 A 的正中间：看得见就说明深度没挡）。 */
    private static final float DEBUG_DOT_HALF = 0.3f;
    /**
     * D 的第二个黄点在 A 正上方多少格。
     * <p>为什么需要它：板带（五块板的上下总跨度）半高约 0.97 格，而 {@code ontop=off} 时板子<b>会写深度</b>
     * ⇒ 放在正中间的那个黄点会被我们自己的板挡住，看起来像"被世界深度遮挡"（假阳性）。
     * 1.6 格在 3 格远处对应 NDC y ≈ 0.76（70° 视场）⇒ 稳在屏幕内，且已在板带之上。
     */
    private static final float DEBUG_DOT_UP_BLOCKS = 1.6f;
    /** B 在<b>相机空间</b>里往左偏多少格（让 A 与 B 能同时在屏上分辨出来；位置仍由 composeView 那条算）。 */
    private static final float DEBUG_B_LEFT_BLOCKS = 1.25f;

    /** A 档：不透明纯红。 */
    private static final int DEBUG_COLOR_A = 0xFFFF0000;
    /** B 档：不透明品红（同一块色块，只是坐标走了 composeView）。 */
    private static final int DEBUG_COLOR_B = 0xFFFF00FF;
    /** C 档：不透明纯绿（板子的完整变换，材质换成最简单的 POSITION_COLOR）。 */
    private static final int DEBUG_COLOR_C = 0xFF00FF00;
    /** D 档：不透明黄（与 A 同一个位置，但深度测试开着）。 */
    private static final int DEBUG_COLOR_D = 0xFFFFFF00;

    // ---- 复用的矩阵 / 数组（不每帧 new）----

    private static final Matrix4f DBG_VIEW = new Matrix4f();
    private static final Matrix4f DBG_BOARD = new Matrix4f();
    private static final Matrix4f DBG_MVP = new Matrix4f();
    private static final float[] DBG_MVP_COL = new float[16];
    private static final float[] DBG_POSE16 = new float[16];
    private static final float[] DBG_VIEW16 = new float[16];
    private static final float[] DBG_BOARD16 = new float[16];

    /** B 档四个角的世界坐标（x,y,z × 4；复用，不每帧 new）。 */
    private static final double[] DBG_B_CORNERS = new double[12];

    /** 复用的 {@code POSITION_COLOR} 批次（容量单位是顶点数；内部 {@code *6} 字节 ⇒ 256 顶点 = 1536 字节）。 */
    private static BufferBuilder dbgBuilder;

    // ---- 本帧开头的 GL 真值（画完按它恢复）----

    private static boolean dbgOrigDepthTest = true;
    private static int dbgOrigDepthFunc = 515;
    private static boolean dbgOrigDepthMask = true;
    private static boolean dbgOrigBlend;
    private static boolean dbgOrigCull = true;
    private static final float[] DBG_ORIG_SHADER_COLOR = {1.0f, 1.0f, 1.0f, 1.0f};

    // ---- 上一帧画的时候回读到的真值（日志里 depth_a / depth_d / restore_ok；滞后 1 帧 ≈16 ms）----

    /** A 档画的时候深度测试真值（{@code 0}/{@code 1}；{@code -1} = 没画过、{@code -2} = 强制失败）。 */
    private static int dbgLastDepthA = -1;
    /** D 档画的时候深度测试真值。 */
    private static int dbgLastDepthD = -1;
    /** 上一帧画完把 GL 状态原样还回去了吗。 */
    private static boolean dbgLastRestoreOk = true;

    // ---- 深度读数（板心那个像素 / 本帧最早那次；诊断见纯类 §4）----

    /** 板心那一次读的完整原始事实（{@code null} = 还没读过）。 */
    private static ShanhaiHoloDebugBox.DepthSample depthBoardSample;

    /** 本帧最早那次（屏幕正中心）读的完整原始事实。 */
    private static ShanhaiHoloDebugBox.DepthSample depthEarlySample;

    /** 板心那次读的结论（{@link ShanhaiHoloDebugBox#depthFailReason}；{@code na} = 还没读过）。 */
    private static String depthFailReason = ShanhaiHoloDebugBox.NA;

    /** 本帧最早那次读的时刻（{@code 0} = 还没读过）。 */
    private static long depthEarlyNanos;

    /** 1 秒 1 次（与 debugbox 行同一个节拍；{@code glReadPixels} 是一次 GPU 同步点，不许每帧读）。 */
    private static final long DEPTH_EARLY_THROTTLE_NANOS = 1_000_000_000L;

    /** 上一次 WARN 过的深度读失败原因（只在<b>变化</b>时打一行；恢复了再打一行 ok）。 */
    private static String depthLastFailReason;

    /** 开关的公开读法（命令层与日志都用它，不直接读字段）。 */
    public static boolean debugBox() {
        return debugBoxOn;
    }

    /**
     * 设开关（<b>不落配置</b>：下次进世界回到 {@link ShanhaiHoloDebugBox#DEFAULT_ON} = off）。
     *
     * @return 设置之后的值（把"命令回了什么"与"状态是什么"钉在一起）
     */
    public static boolean setDebugBox(boolean on) {
        debugBoxOn = on;
        return debugBoxOn;
    }

    /** 翻转（{@code /shanhai_add holo debugbox} 无参那一支）。 */
    public static boolean toggleDebugBox() {
        return setDebugBox(!debugBoxOn);
    }

    /**
     * 🆕 <b>四档调试色块</b>（{@code /shanhai_add holo debugbox on} 才跑）。
     *
     * <p>调用点在整个 {@code onRenderLevelStage} 的<b>最末尾</b>（正常板的四遍绘制与 GL 恢复之后），
     * 所以这里的 {@code poseStack.last().pose()} 就是"只有相机旋转、没有 −camPos"的那个基矩阵。
     *
     * <p>顺序（用户硬要求）：<b>先把读数算出来并打日志，再画</b> —— 即使一个像素都没画出来，日志也要有。
     *
     * @param poseStack  事件给的 PoseStack（此处等于基矩阵，本方法只读它、只在里面 push/pop 两次）
     * @param camera     这一帧的相机世界坐标
     * @param boards     这一帧的板表（菜单 5 块 / 面板若干行；与正常绘制是同一个数组）
     * @param ox,oy,oz   这一帧的全息原点（已含展开动画的原点插值）
     * @param frameYawDeg 框偏航 / @param animSpinDeg 动画自转 / @param animScale 整块缩放
     * @param tSec       这一帧的时刻（秒；抖动与 applyHoloFrame 用的是同一个数）
     */
    private static void debugBoxes(PoseStack poseStack, RenderLevelStageEvent event, Vec3 camera,
                                   ShanhaiHoloMenuLayout.Board[] boards,
                                   double ox, double oy, double oz,
                                   float frameYawDeg, float animSpinDeg, float animScale,
                                   float tSec, long frameNanos) {
        if (!debugBoxOn) {
            return;
        }
        // 🔴 整段再包一层：这一段是<b>额外</b>的实验，任何异常都不许冒到事件派发里（那会崩客户端）。
        //   只 WARN 一次，之后静默降级（与 occl_probe 那三个闸门同口径）。
        try {
            debugBoxesImpl(poseStack, event, camera, boards, ox, oy, oz,
                    frameYawDeg, animSpinDeg, animScale, tSec, frameNanos);
        } catch (Throwable t) {
            warnDebugBoxOnce(t);
        }
    }

    /** {@link #debugBoxes} 的实体（外层已经兜过异常）。 */
    private static void debugBoxesImpl(PoseStack poseStack, RenderLevelStageEvent event, Vec3 camera,
                                       ShanhaiHoloMenuLayout.Board[] boards,
                                       double ox, double oy, double oz,
                                       float frameYawDeg, float animSpinDeg, float animScale,
                                       float tSec, long frameNanos) {
        debugBoxFrames++;
        final Minecraft mc = Minecraft.getInstance();
        final boolean menuOn = ShanhaiHoloMenuState.enabled();
        final Matrix4f pose = poseStack.last().pose();

        // ---- ① 相机姿态那 16 个元素 + 世界空间的三根轴 ----
        //   相机姿态矩阵只有旋转 ⇒ 它的【行】就是"相机空间的 x/y/z 轴在世界里的方向"：
        //     row0 = 右、row1 = 上、row2 = 后（⇒ 视线 = −row2）
        //   于是 (a,b,c) 这个相机空间偏移在世界里 = a·row0 + b·row1 + c·row2（= R^T·v）。
        pose.get(DBG_POSE16);
        final boolean poseFinite = !ShanhaiHoloDebugBox.hasNonFinite(DBG_POSE16);
        double lx = -pose.m20();
        double ly = -pose.m21();
        double lz = -pose.m22();
        final double lLen = Math.sqrt(lx * lx + ly * ly + lz * lz);
        if (lLen > 1.0e-6 && Double.isFinite(lLen)) {              // 姿态矩阵是正交的 ⇒ 本来就长 1
            lx /= lLen;
            ly /= lLen;
            lz /= lLen;
        }

        // ---- ② 走我们的相机变换（composeView）那一条 ----
        ShanhaiHoloSurroundView.composeView(DBG_VIEW, pose, camera.x, camera.y, camera.z);
        DBG_VIEW.get(DBG_VIEW16);
        final boolean viewFinite = !ShanhaiHoloDebugBox.hasNonFinite(DBG_VIEW16);

        // ---- ③ 第一块板的完整位姿（applyHoloFrame → poseBoard，两个都是既有函数，一个字没改）----
        final boolean hasBoard = boards != null && boards.length > 0;
        boolean boardFinite = false;
        double boardWidth = Double.NaN;
        double w0x = Double.NaN;
        double w0y = Double.NaN;
        double w0z = Double.NaN;
        float ndcX = Float.NaN;
        float ndcY = Float.NaN;
        if (hasBoard && poseFinite) {
            // 与 drawRemoteBoards 完全相同的配对方式：applyHoloFrame 自己 push 一层、本方法负责配对的 pop。
            poseStack.pushPose();
            try {
                applyHoloFrame(poseStack, camera, ox, oy, oz, frameYawDeg, animSpinDeg, animScale, tSec);
                try {
                    // 🔴 按下状态一律 -1/0：它是"本地点没点"的现场，调试色块不该被它带偏
                    poseBoard(poseStack, boards[0], -1, 0.0f);
                    DBG_BOARD.set(poseStack.last().pose());         // 拷出来（下面还要 pop）
                } finally {
                    poseStack.popPose();                            // 弹 poseBoard 那一层
                }
            } finally {
                poseStack.popPose();                                // 弹 applyHoloFrame 那一层
            }
            DBG_BOARD.get(DBG_BOARD16);
            boardFinite = !ShanhaiHoloDebugBox.hasNonFinite(DBG_BOARD16);
            // 板心的【世界】坐标 = 相机位置 + 矩阵的平移列（板内原点 = 板心 = 底板所在平面）
            w0x = camera.x + DBG_BOARD.m03();
            w0y = camera.y + DBG_BOARD.m13();
            w0z = camera.z + DBG_BOARD.m23();
            // 板在世界上有多宽（格）：矩阵第 0 列的长度 = "1 bu 多少格" ⇒ × 板宽（bu）
            final double col0 = Math.sqrt(DBG_BOARD.m00() * DBG_BOARD.m00()
                    + DBG_BOARD.m10() * DBG_BOARD.m10()
                    + DBG_BOARD.m20() * DBG_BOARD.m20());
            boardWidth = col0 * ShanhaiHoloMenuTuning.BOARD_WIDTH_BU;
            // ---- ④ 现有投影（与 visibility 那条同一个口径：proj · composeView）----
            final int guiW = mc == null || mc.getWindow() == null ? 0 : mc.getWindow().getGuiScaledWidth();
            final int guiH = mc == null || mc.getWindow() == null ? 0 : mc.getWindow().getGuiScaledHeight();
            DBG_MVP.set(event.getProjectionMatrix()).mul(DBG_VIEW);
            DBG_MVP.get(DBG_MVP_COL);
            final ShanhaiHoloViewMath.Proj p =
                    ShanhaiHoloViewMath.proj(DBG_MVP_COL, w0x, w0y, w0z, guiW, guiH);
            ndcX = p.ndcX();
            ndcY = p.ndcY();
        }

        // ---- ⑤ 决策（纯类唯一出口）+ 日志（🔴 在画之前）----
        final ShanhaiHoloDebugBox.Plan plan = ShanhaiHoloDebugBox.plan(new ShanhaiHoloDebugBox.Gates(
                menuOn, hasBoard, poseFinite, viewFinite, boardFinite));
        // pose_has_nan = "相机 pose 与板子位姿的 16 个元素里有没有 NaN/Inf"（用户原话）；
        // 两者单独看的话：A 那几档看 poseFinite、C 看 boardFinite（日志里另有 board_nan= 一个字段）。
        final boolean poseHasNan = !poseFinite || !boardFinite;
        if (debugBoxLogNanos == 0L || frameNanos - debugBoxLogNanos >= DEBUG_BOX_LOG_THROTTLE_NANOS) {
            debugBoxLogNanos = frameNanos;
            com.shanhai.ShanhaiMod.LOGGER.info("[SHANHAI-HOLO] {}",
                    ShanhaiHoloDebugBox.line(new ShanhaiHoloDebugBox.Sample(
                            debugBoxFrames, plan,
                            camera.x, camera.y, camera.z,
                            lx, ly, lz,
                            poseHasNan, !boardFinite,
                            w0x, w0y, w0z,
                            ndcX, ndcY,
                            sampleValue(depthBoardSample), sampleReason(depthBoardSample),
                            sampleValue(depthEarlySample),
                            boardWidth,
                            dbgLastDepthA, dbgLastDepthD, dbgLastRestoreOk)));
        }

        // ---- ⑥ 画（⚠️ 只在菜单开着时；任何异常只 WARN 一次，绝不影响主流程）----
        if (!menuOn) {
            return;                                                 // 用户硬要求：menu_on=false 一块都不画
        }
        try {
            drawDebugTiers(camera, pose, plan, boardFinite ? DBG_BOARD : null);
        } catch (Throwable t) {
            warnDebugBoxOnce(t);
        }
    }

    /**
     * 真正把四档画出来（{@link #debugBoxes} 的下半段）：
     * 采 GL 真值 → A/B/C（<b>强制关</b>深度测试）→ D（<b>强制开</b>）→ 按真值恢复。
     *
     * <p>🔴 为什么分成<b>两批</b>（两次 {@code end()+drawWithShader}）：
     * 我们是直接 {@code BufferUploader.drawWithShader} 直出、不走 RenderType 的
     * {@code setupRenderState}，所以"深度测试开不开"在<b>提交那一刻</b>生效
     * ⇒ A/B/C 与 D 必须各自 flush 一次，否则两档会拿到同一份 GL 状态。
     */
    private static void drawDebugTiers(Vec3 camera, Matrix4f pose,
                                       ShanhaiHoloDebugBox.Plan plan, Matrix4f boardMatrix) {
        captureDebugBoxGlState();                                   // 只读真值（画完按它恢复）
        try {
            // ---- A/B/C：先【强制】关深度测试（用户硬要求第 1 条：否则 A 也会被同一个 0 深度挡住）----
            forceDepthTest(false);
            forceDepthFunc(GL11.GL_LEQUAL);
            forceDepthMask(false);                                  // 不写深度：别把调试色块卖给别人
            forceBlend(false);                                      // 不依赖混合（颜色 alpha 恒 255）
            forceCull(false);                                       // 面朝哪边都能看见（C 档的板是单面几何）
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);     // 颜色调制必须是 1（别人可能留了 0）
            dbgLastDepthA = depthTestFlag();
            final BufferBuilder bb = dbgBuilder();
            bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            // ---- A：纯原版管线（顶点用相机空间的数，坐标<b>完全</b>不碰 composeView / 板子几何）----
            if (plan.a().submitted()) {
                dbgQuadCamAxis(bb, pose, 0.0f, 0.0f, -DEBUG_DIST_BLOCKS,
                        DEBUG_BLOCK_HALF, DEBUG_BLOCK_HALF, DEBUG_COLOR_A);
            }
            // ---- B：同一个 1×1 色块，但坐标先换成【世界坐标】再过 composeView ----
            if (plan.b().submitted()) {
                fillDebugBCorners(camera, pose);
                dbgQuadWorld(bb, DBG_VIEW, DBG_B_CORNERS, DEBUG_COLOR_B);
            }
            // ---- C：板子的完整位姿（applyHoloFrame → poseBoard 的原样复用），材质换成不透明纯绿 ----
            if (plan.c().submitted() && boardMatrix != null) {
                dbgQuad(bb, boardMatrix,
                        0.0f, 0.0f, ShanhaiHoloMenuTuning.LAYER_PLATE_BU,
                        ShanhaiHoloMenuTuning.BOARD_WIDTH_BU * 0.5f,
                        ShanhaiHoloMenuTuning.BOARD_HEIGHT_BU * 0.5f,
                        DEBUG_COLOR_C);
            }
            dbgFlush(bb);

            // ---- D：与 A【同一个位置】、同一条管线，只把深度测试开着 ----
            //   判据（用户点名）：A 看得见、D 看不见 ⇒ 确认是深度遮挡（那个 0 深度真的挡在前面）。
            //   ⚠️ D 画两个黄点（同一个深度状态、同一个批次）：
            //      D0 在 A 的正中间（用户点名的"同位对照"）；
            //      D1 在 A 正上方 {@link #DEBUG_DOT_UP_BLOCKS} 格（> 板带半高 0.97 格 ⇒ 避开"我们自己的板"
            //         在 ontop=off 时写下的深度 —— 那会把 D0 挡掉，制造"被遮挡"的假阳性）。
            //   两点的读法：D0 没出来、D1 出来 ⇒ 挡在中间的是我们自己的板面；
            //             两个都没出来 ⇒ 真有一个贴脸的东西（或整个深度缓冲被别人写满了）。
            forceDepthTest(true);
            forceDepthFunc(GL11.GL_LEQUAL);
            forceDepthMask(false);
            dbgLastDepthD = depthTestFlag();
            bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            if (plan.d().submitted()) {
                dbgQuadCamAxis(bb, pose, 0.0f, 0.0f, -DEBUG_DIST_BLOCKS,
                        DEBUG_DOT_HALF, DEBUG_DOT_HALF, DEBUG_COLOR_D);
                dbgQuadCamAxis(bb, pose, 0.0f, DEBUG_DOT_UP_BLOCKS, -DEBUG_DIST_BLOCKS,
                        DEBUG_DOT_HALF, DEBUG_DOT_HALF, DEBUG_COLOR_D);
            }
            dbgFlush(bb);
        } finally {
            // 🔴 兜底：万一 begin() 之后抛了异常，builder 会停在 building=true，
            //   下一次 begin() 直接 IllegalStateException("Already building!") ⇒ 以后再也画不出来。
            try {
                if (dbgBuilder != null && dbgBuilder.building()) {
                    dbgBuilder.discard();
                }
            } catch (Throwable ignore) {
                dbgBuilder = null;                                  // 状态已经坏了 ⇒ 丢掉，下次新造一个
            }
            restoreDebugBoxGlState();
        }
    }

    // ------------------------------------------------------------------ 四档的几何

    /**
     * B 档四个角的<b>世界坐标</b>：相机空间里中心 {@code (−1.25, 0, −3)}、半边长 1 格的那块方形，
     * 逐点换到世界系（{@code world = cam + R^T·p_cam}）。
     *
     * <p>为什么先把坐标算成世界坐标：B 档要验的正是"<b>世界坐标 → 相机空间</b>"那一跳（{@link ShanhaiHoloSurroundView}）。
     * 若把它写成"相机空间的坐标配相机空间的矩阵"，就把被测的那一步绕过去了。
     *
     * <p>⚠️ 为什么 B 不在 A 的<b>正后方同一格</b>：两档位置完全一样时后画的会盖住先画的，
     * 判据就退化成"看见一块红"——分不清是谁画的。往左偏 1.25 格后
     * （同一个距离、同一个尺寸）两档在屏上能同时读到，而"偏了多少"本身也是被测变换的一部分。
     */
    private static void fillDebugBCorners(Vec3 camera, Matrix4f pose) {
        final float h = DEBUG_BLOCK_HALF;
        final double cx = -DEBUG_B_LEFT_BLOCKS;
        final double cz = -DEBUG_DIST_BLOCKS;
        camToWorld(DBG_B_CORNERS, 0, camera, pose, cx - h, -h, cz);   // 左下
        camToWorld(DBG_B_CORNERS, 1, camera, pose, cx + h, -h, cz);   // 右下
        camToWorld(DBG_B_CORNERS, 2, camera, pose, cx + h, h, cz);    // 右上
        camToWorld(DBG_B_CORNERS, 3, camera, pose, cx - h, h, cz);    // 左上
    }

    /**
     * 相机空间点 → 世界坐标：{@code world = cam + R^T·(x,y,z)}，而
     * {@code R^T·(a,b,c) = a·row0 + b·row1 + c·row2}（相机姿态矩阵的行 = 相机三轴在世界里的方向）。
     */
    private static void camToWorld(double[] out12, int slot, Vec3 camera, Matrix4f pose,
                                   double x, double y, double z) {
        final int o = slot * 3;
        out12[o] = camera.x + x * pose.m00() + y * pose.m10() + z * pose.m20();
        out12[o + 1] = camera.y + x * pose.m01() + y * pose.m11() + z * pose.m21();
        out12[o + 2] = camera.z + x * pose.m02() + y * pose.m12() + z * pose.m22();
    }

    /** 一个轴对齐四边形（四个角：左下 → 右下 → 右上 → 左上；与 {@link #rect} 同一个绕向）。 */
    private static void dbgQuad(VertexConsumer vc, Matrix4f m, float cx, float cy, float cz,
                                float hx, float hy, int argb) {
        dbgVert(vc, m, cx - hx, cy - hy, cz, argb);
        dbgVert(vc, m, cx + hx, cy - hy, cz, argb);
        dbgVert(vc, m, cx + hx, cy + hy, cz, argb);
        dbgVert(vc, m, cx - hx, cy + hy, cz, argb);
    }

    /**
     * 画一个"相机轴线对齐"的四边形：中心与半边长都按<b>相机空间</b>给，顶点按世界方向写。
     *
     * <p>🔴 <b>为什么不能把相机空间的数直接喂进顶点</b>：本类所有几何的顶点坐标都是
     * "世界坐标 − camPos"（类注释 §3 的取证结论），而矩阵是 {@code R}（世界 → 相机）。
     * 相机空间的 {@code (0,0,−3)} 必须先换成<b>世界方向</b> {@code R^T·(0,0,−3)} 才配得上那一跳
     * （{@code R^T·p = x·row0 + y·row1 + z·row2}）；直接把 {@code (0,0,−3)} 喂进去会被 {@code R}
     * 再转一次 ⇒ 色块跑到屏幕侧面去（"画了却不在期望位置"这种错很难从截图上看出来是谁的锅）。
     */
    private static void dbgQuadCamAxis(VertexConsumer vc, Matrix4f pose,
                                       float cx, float cy, float cz,
                                       float hx, float hy, int argb) {
        for (int k = 0; k < 4; k++) {
            final float px = cx + ((k == 0 || k == 3) ? -hx : hx);   // 左下 → 右下 → 右上 → 左上
            final float py = cy + ((k == 0 || k == 1) ? -hy : hy);
            dbgVert(vc, pose,
                    px * pose.m00() + py * pose.m10() + cz * pose.m20(),
                    px * pose.m01() + py * pose.m11() + cz * pose.m21(),
                    px * pose.m02() + py * pose.m12() + cz * pose.m22(),
                    argb);
        }
    }

    /** 一个四边形，四个角<b>已经是</b>矩阵所要求的坐标系（B 档用世界坐标）。 */
    private static void dbgQuadWorld(VertexConsumer vc, Matrix4f m, double[] xyz12, int argb) {
        for (int i = 0; i < 4; i++) {
            dbgVert(vc, m, (float) xyz12[i * 3], (float) xyz12[i * 3 + 1], (float) xyz12[i * 3 + 2], argb);
        }
    }

    /**
     * 一个顶点：{@code POSITION_COLOR}，<b>不贴图、不带法线、顶点位置由我们手动乘矩阵</b>
     * （不用 {@code VertexConsumer#vertex(Matrix4f,…)}：那个每次 new 一个 {@code Vector4f}）。
     *
     * <p>🔴 <b>不</b>走 {@link #vert}：那个会把顶点数累进 {@code probeVerts}（"正常板"的读数），
     * 调试色块绝不能污染那条读数。
     */
    private static void dbgVert(VertexConsumer vc, Matrix4f m, float x, float y, float z, int argb) {
        // (M·v).x = m00·x + m01·y + m02·z + m03（JOML：mRC = 第 R 行第 C 列，平移在 m03/m13/m23）
        final float tx = m.m00() * x + m.m01() * y + m.m02() * z + m.m03();
        final float ty = m.m10() * x + m.m11() * y + m.m12() * z + m.m13();
        final float tz = m.m20() * x + m.m21() * y + m.m22() * z + m.m23();
        vc.vertex(tx, ty, tz);
        vc.color((argb >>> 16) & 0xFF, (argb >>> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
        vc.endVertex();
    }

    /** 收批次并立刻交给 GPU（等价于原版 {@code Tesselator.end()}，但用的是我们自己的 builder）。 */
    private static void dbgFlush(BufferBuilder bb) {
        BufferUploader.drawWithShader(bb.end());
    }

    /** 复用的 builder（一帧最多 4 个四边形 = 16 顶点；256 顶点已经 16 倍余量）。 */
    private static BufferBuilder dbgBuilder() {
        if (dbgBuilder == null) {
            dbgBuilder = new BufferBuilder(256);
        }
        return dbgBuilder;
    }

    // ------------------------------------------------------------------ GL 状态：采真值 / 强制 / 恢复

    /**
     * 🔴 <b>强制</b>把深度测试设成 on/off，并用 <b>GL 真值回读</b>验证。
     *
     * <p>为什么不能只写一句 {@code RenderSystem.enableDepthTest()}：
     * {@code GlStateManager.DepthState.mode} 是个带缓存的 {@code BooleanState}
     * （源码原文：{@code setEnabled} 里 {@code if (p_84591_ != this.enabled)} 才发 {@code glEnable/glDisable}）。
     * 一旦有别的模组用<b>裸 GL 调用</b>改过深度测试，这个缓存就会和真值分叉 ⇒
     * 我们这句"关深度"会变成<b>空操作</b>，实验得到假阴性（"ontop 打开了却还是被挡"就是这种形态之一）。
     * ⇒ 这里一律：先走 RenderSystem（让缓存同步）→ 回读真值 → 不一致就直接发 GL 调用。
     *
     * @return 设置之后 {@code glIsEnabled(GL_DEPTH_TEST)} 的真值 == 目标值
     */
    private static boolean forceDepthTest(boolean on) {
        if (on) {
            RenderSystem.enableDepthTest();
        } else {
            RenderSystem.disableDepthTest();
        }
        if (GL11.glIsEnabled(GL11.GL_DEPTH_TEST) == on) {
            return true;
        }
        if (on) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        } else {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
        return GL11.glIsEnabled(GL11.GL_DEPTH_TEST) == on;
    }

    /** 同样"强制 + 回读"（{@code GlStateManager._depthFunc} 也带缓存：值没变就不发 GL 调用）。 */
    private static void forceDepthFunc(int func) {
        RenderSystem.depthFunc(func);
        if (GL11.glGetInteger(GL11.GL_DEPTH_FUNC) != func) {
            GL11.glDepthFunc(func);
        }
    }

    /** 同样"强制 + 回读"（{@code GlStateManager._depthMask} 也带缓存）。 */
    private static boolean forceDepthMask(boolean on) {
        RenderSystem.depthMask(on);
        if (GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK) == on) {
            return true;
        }
        GL11.glDepthMask(on);
        return GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK) == on;
    }

    /** 同样"强制 + 回读"（{@code BlendState.mode} 也是 BooleanState）。 */
    private static boolean forceBlend(boolean on) {
        if (on) {
            RenderSystem.enableBlend();
        } else {
            RenderSystem.disableBlend();
        }
        if (GL11.glIsEnabled(GL11.GL_BLEND) == on) {
            return true;
        }
        if (on) {
            GL11.glEnable(GL11.GL_BLEND);
        } else {
            GL11.glDisable(GL11.GL_BLEND);
        }
        return GL11.glIsEnabled(GL11.GL_BLEND) == on;
    }

    /** 同样"强制 + 回读"（{@code CullState.enable} 也是 BooleanState）。 */
    private static boolean forceCull(boolean on) {
        if (on) {
            RenderSystem.enableCull();
        } else {
            RenderSystem.disableCull();
        }
        if (GL11.glIsEnabled(GL11.GL_CULL_FACE) == on) {
            return true;
        }
        if (on) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        } else {
            GL11.glDisable(GL11.GL_CULL_FACE);
        }
        return GL11.glIsEnabled(GL11.GL_CULL_FACE) == on;
    }

    /** 深度测试现在到底开没开（GL 真值）：{@code 1}/{@code 0}；查询失败 ⇒ {@code -1}。 */
    private static int depthTestFlag() {
        try {
            return GL11.glIsEnabled(GL11.GL_DEPTH_TEST) ? 1 : 0;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 本帧开头（我们改任何状态之前）的 GL 真值 —— 只读，不写。 */
    private static void captureDebugBoxGlState() {
        try {
            dbgOrigDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            dbgOrigDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            dbgOrigDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            dbgOrigBlend = GL11.glIsEnabled(GL11.GL_BLEND);
            dbgOrigCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            // getShaderColor() 返回的是 RenderSystem 的<b>内部数组</b> ⇒ 必须拷贝，不能存引用
            final float[] c = RenderSystem.getShaderColor();
            System.arraycopy(c, 0, DBG_ORIG_SHADER_COLOR, 0, 4);
        } catch (Throwable t) {
            // 采不到就按原版默认值恢复（开 / LEQUAL / 写深度 / 不混合 / 剔除开 / 颜色 1）
            dbgOrigDepthTest = true;
            dbgOrigDepthFunc = 515;
            dbgOrigDepthMask = true;
            dbgOrigBlend = false;
            dbgOrigCull = true;
            DBG_ORIG_SHADER_COLOR[0] = 1.0f;
            DBG_ORIG_SHADER_COLOR[1] = 1.0f;
            DBG_ORIG_SHADER_COLOR[2] = 1.0f;
            DBG_ORIG_SHADER_COLOR[3] = 1.0f;
            warnDebugBoxOnce(t);
        }
    }

    /**
     * 把 GL 状态按<b>本帧开头的真值</b>一次性还回去，并回读验证（{@code restore_ok} 就是它）。
     *
     * <p>与 {@link #restoreGlDepthState()} 同一个做法（那个只管 ontop 的深度二件套，
     * 而且只在 {@code ontopFrame} 时才动手）；这里管的是调试色块动过的<b>五件</b>：
     * 深度测试 / 深度函数 / 深度写掩码 / 混合 / 剔除，外加着色器颜色调制。
     */
    private static void restoreDebugBoxGlState() {
        try {
            forceDepthMask(dbgOrigDepthMask);
            forceDepthFunc(dbgOrigDepthFunc);
            forceDepthTest(dbgOrigDepthTest);
            forceBlend(dbgOrigBlend);
            forceCull(dbgOrigCull);
            RenderSystem.setShaderColor(DBG_ORIG_SHADER_COLOR[0], DBG_ORIG_SHADER_COLOR[1],
                    DBG_ORIG_SHADER_COLOR[2], DBG_ORIG_SHADER_COLOR[3]);
            final float[] now = RenderSystem.getShaderColor();
            dbgLastRestoreOk = GL11.glIsEnabled(GL11.GL_DEPTH_TEST) == dbgOrigDepthTest
                    && GL11.glGetInteger(GL11.GL_DEPTH_FUNC) == dbgOrigDepthFunc
                    && GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK) == dbgOrigDepthMask
                    && GL11.glIsEnabled(GL11.GL_BLEND) == dbgOrigBlend
                    && GL11.glIsEnabled(GL11.GL_CULL_FACE) == dbgOrigCull
                    && now != null && now.length >= 4
                    && now[0] == DBG_ORIG_SHADER_COLOR[0] && now[1] == DBG_ORIG_SHADER_COLOR[1]
                    && now[2] == DBG_ORIG_SHADER_COLOR[2] && now[3] == DBG_ORIG_SHADER_COLOR[3];
        } catch (Throwable t) {
            dbgLastRestoreOk = false;
            warnDebugBoxOnce(t);
        }
    }

    // ------------------------------------------------------------------ 🆕 深度可读性诊断（用户硬要求第 3、4 条）

    /**
     * 本帧<b>最早</b>的一次深度采样：屏幕正中心那个像素（1 秒 1 次，纯只读）。
     *
     * <p>为什么放在 {@code onRenderLevelStage} 的<b>最开头</b>：
     * 那时我们一个 GL 写调用都还没发过 ⇒ 读到的是"上家留给我们的"帧缓冲现场。
     * 位置信息不需要（中心像素是固定的），所以这一次读<b>比板心那次更早</b> ——
     * 用户点名的"把读取时机挪到本帧最早能拿到主帧缓冲深度的地方"落在这里。
     *
     * <p>它回答的问题很具体：{@code depth_at_center=na} 那 55 帧到底是
     * "这个像素上没东西可读"还是"整个帧缓冲都读不到"（后者才是 FBO / PBO 那类问题）。
     */
    private static void sampleMainDepthEarlyIfDue(long frameNanos) {
        if (depthEarlyNanos != 0L && frameNanos - depthEarlyNanos < DEPTH_EARLY_THROTTLE_NANOS) {
            return;
        }
        depthEarlyNanos = frameNanos;
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) {
            depthEarlySample = null;
            return;
        }
        final int fbW = mc.getWindow().getWidth();
        final int fbH = mc.getWindow().getHeight();
        if (fbW <= 0 || fbH <= 0) {
            depthEarlySample = null;
            return;
        }
        // 帧缓冲像素坐标（GL 原点在左下；屏幕正中心 ⇒ 直接 (w/2, h/2)，不需要翻 Y）
        depthEarlySample = readWindowDepth(clampPixel(fbW / 2, fbW - 1), clampPixel(fbH / 2, fbH - 1));
        logDepthReadStateIfChanged(depthEarlySample, "early");
    }

    /**
     * 读一个像素的<b>窗口深度</b>，并把"这次读到底成没成"的全部原始事实一起带回来。
     *
     * <p>🔴 六项检查（任一项不成立 ⇒ 那个数<b>不许</b>当读数用，见
     * {@link ShanhaiHoloDebugBox#depthFailReason}）：
     * <pre>
     *   ① GL_PIXEL_PACK_BUFFER_BINDING = 0        （绑着 PBO 时 glReadPixels 根本不写我们的 FloatBuffer）
     *   ② GL_READ_FRAMEBUFFER_BINDING == 主帧缓冲  （Iris/Oculus 一类会把世界画进自己的 FBO）
     *   ③ glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE
     *   ④ 那个 FBO 有深度附件（GL_DEPTH_ATTACHMENT 或 GL_DEPTH_STENCIL_ATTACHMENT ≠ GL_NONE）
     *   ⑤ glGetError() == 0（读之前先把历史错误取干净，不许把别人的错误算到自己头上）
     *   ⑥ 读回来的值在 [0,1] 且有限
     * </pre>
     * 全部通过才返回 {@link ShanhaiHoloDebugBox#DEPTH_OK}。
     *
     * @param pixelX,pixelY 帧缓冲像素坐标（GL 原点在左下）
     */
    private static ShanhaiHoloDebugBox.DepthSample readWindowDepth(int pixelX, int pixelY) {
        int readFbo = -1;
        int mainFbo = -1;
        int packBuf = -1;
        int fboStatus = -1;
        int hasDepth = ShanhaiHoloDebugBox.DEPTH_ATTACH_UNKNOWN;
        int glErr = 0;
        double value = Double.NaN;
        try {
            drainGlErrors(8);
            mainFbo = mainFrameBufferId();
            readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            packBuf = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            fboStatus = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);
            hasDepth = depthAttachmentState();
            GL_DEPTH_BUF.clear();                   // 必须：LWJGL 按 position 处的地址写
            GL11.glReadPixels(pixelX, pixelY, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, GL_DEPTH_BUF);
            glErr = GL11.glGetError();
            value = GL_DEPTH_BUF.get(0);            // 绝对下标（读完之后 position 仍是 0）
        } catch (Throwable t) {
            glErr = -1;                             // -1 = 读取本身抛异常（也归 gl_error 那一档）
            warnDebugBoxOnce(t);
        }
        final String reason = ShanhaiHoloDebugBox.depthFailReason(packBuf, readFbo, mainFbo, fboStatus,
                hasDepth, glErr, value);
        return new ShanhaiHoloDebugBox.DepthSample(value, reason, readFbo, mainFbo, packBuf,
                fboStatus, hasDepth, glErr, pixelX, pixelY);
    }

    /** 主帧缓冲的 FBO id（{@code Minecraft.getMainRenderTarget().frameBufferId}；取不到 ⇒ {@code -1}）。 */
    private static int mainFrameBufferId() {
        final Minecraft mc = Minecraft.getInstance();
        final com.mojang.blaze3d.pipeline.RenderTarget target =
                mc == null ? null : mc.getMainRenderTarget();
        return target == null ? -1 : target.frameBufferId;
    }

    /** 把 GL 错误队列取干净（最多 8 次；GL 的错误队列是累积的，读到 NO_ERROR 为止）。 */
    private static void drainGlErrors(int max) {
        for (int i = 0; i < max; i++) {
            if (GL11.glGetError() == GL11.GL_NO_ERROR) {
                return;
            }
        }
    }

    /**
     * 读帧缓冲有没有深度附件（三态）。
     * <p>默认帧缓冲（id 0）不是这个查询能回答的对象 ⇒ 一律"未知"，不猜。
     */
    private static int depthAttachmentState() {
        if (GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) == 0) {
            return ShanhaiHoloDebugBox.DEPTH_ATTACH_UNKNOWN;
        }
        final int depth = attachmentObjectType(GL30.GL_DEPTH_ATTACHMENT);
        final int depthStencil = attachmentObjectType(GL30.GL_DEPTH_STENCIL_ATTACHMENT);
        if (depth < 0 && depthStencil < 0) {
            return ShanhaiHoloDebugBox.DEPTH_ATTACH_UNKNOWN;    // 两次查询都不被接受
        }
        return depth > 0 || depthStencil > 0
                ? ShanhaiHoloDebugBox.DEPTH_ATTACH_YES           // GL_NONE = 0
                : ShanhaiHoloDebugBox.DEPTH_ATTACH_NO;
    }

    /** 一个附件的对象类型；查询不被接受 / 抛异常 ⇒ {@code -1}（"没问出来"，不是"没有"）。 */
    private static int attachmentObjectType(int attachment) {
        try {
            return GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, attachment,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 深度读的<b>状态变化</b>才打日志：进失败态打一行 {@code depth_read_fail reason=…}，
     * 恢复时打一行 {@code depth_read_ok …}（同一个原因不重复报，免得 1 秒 1 行把日志刷爆）。
     */
    private static void logDepthReadStateIfChanged(ShanhaiHoloDebugBox.DepthSample s, String where) {
        final String reason = s == null ? ShanhaiHoloDebugBox.NA : s.reason();
        if (ShanhaiHoloDebugBox.depthReadOk(reason)) {
            if (depthLastFailReason == null) {
                return;                                         // 一直好着 ⇒ 什么都不打
            }
            depthLastFailReason = null;
            com.shanhai.ShanhaiMod.LOGGER.info("[SHANHAI-HOLO] {}",
                    ShanhaiHoloDebugBox.depthOkLine(s, where));
            return;
        }
        if (reason.equals(depthLastFailReason)) {
            return;                                             // 同一个原因不重复报
        }
        depthLastFailReason = reason;
        com.shanhai.ShanhaiMod.LOGGER.warn("[SHANHAI-HOLO] {}",
                ShanhaiHoloDebugBox.depthFailLine(s, where));
    }

    /** {@link ShanhaiHoloDebugBox.DepthSample} 里的值（{@code null} = 还没读过 ⇒ {@code NaN}）。 */
    private static double sampleValue(ShanhaiHoloDebugBox.DepthSample s) {
        return s == null ? Double.NaN : s.value();
    }

    /** {@link ShanhaiHoloDebugBox.DepthSample} 里的结论（{@code null} = 还没读过 ⇒ {@code na}）。 */
    private static String sampleReason(ShanhaiHoloDebugBox.DepthSample s) {
        return s == null ? ShanhaiHoloDebugBox.NA : s.reason();
    }

    /** debugbox 的异常<b>只 WARN 一次</b>（与 gl/bh/ontop 那三个闸门并列，但<b>不共用</b>它们的标志位）。 */
    private static void warnDebugBoxOnce(Throwable t) {
        if (debugBoxWarned) {
            return;
        }
        debugBoxWarned = true;
        com.shanhai.ShanhaiMod.LOGGER.warn(
                "[SHANHAI-HOLO] debugbox 读数不可用（只报这一次，之后静默降级；不影响正常渲染）: {}",
                String.valueOf(t));
    }
}

