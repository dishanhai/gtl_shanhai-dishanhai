package com.shanhai.client.holo;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import com.shanhai.common.holo.ShanhaiHoloMenuBoards;

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
 *   <li><b>深度测试开着</b>（{@code LEQUAL}）⇒ 中间隔着的方块会<b>真的挡住它</b>。
 *       HUD 永远不会被方块挡住 —— 这一条就是"不是 HUD"的可判定证据。</li>
 *   <li><b>朝向是插值的</b>⇒ 快速扭头时它慢半拍，<b>那半拍里你看到的是它的侧面</b>，
 *       近大远小成立（见 {@link ShanhaiHoloMenuState} §3）。</li>
 * </ol>
 *
 * <h2>2. 🔴 图层顺序与两种混合模式（一个静态 VBO 都没有，逐帧重建）</h2>
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
     * 每帧那个 {@code BufferBuilder} 的<b>容量参数</b>。
     * <p>⚠️ 传进去的数<b>不是字节数</b>：{@code BufferBuilder(int capacity)} 内部做的是
     * {@code MemoryTracker.create(capacity * 6)}（源码原文）⇒ 1&lt;&lt;14 = 16384 对应
     * <b>98,304 字节（96 KiB）</b>，正好是"峰值单批 ≈ 11.8 KB"的 8 倍。
     * <p>对照：原来写的是 {@code 1 << 18} ⇒ 1,572,864 字节（1.5 MB）/ 帧。
     */
    private static final int PER_FRAME_BUFFER_CAPACITY = 1 << 14;

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

    /**
     * 底板层：普通 alpha、<b>写深度</b>、不剔除、深度测试 {@code lequal}。
     * <p>写深度是刻意的：五块板隔着 0.08~0.32 格的前后关系<b>由深度决定</b>，
     * 否则半透明板会按提交顺序互相糊在一起。
     */
    private static RenderType plateType() {
        if (plateType == null) {
            plateType = RenderType.create(
                    "shanhai_holo_plate",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    1 << 16,
                    false,
                    true,
                    RenderType.CompositeState.builder()
                            .setShaderState(POSITION_COLOR_SHADER)
                            .setTransparencyState(ALPHA_BLEND)
                            .setDepthTestState(new RenderStateShard.DepthTestStateShard("lequal", 515))
                            .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, true))
                            .setCullState(new RenderStateShard.CullStateShard(false))
                            .createCompositeState(false));
        }
        return plateType;
    }

    /** 发光层：加法混合、<b>不写深度</b>（它只叠加亮度，不该挡住后面的东西）。 */
    private static RenderType glowType() {
        if (glowType == null) {
            glowType = RenderType.create(
                    "shanhai_holo_glow",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    1 << 16,
                    false,
                    true,
                    RenderType.CompositeState.builder()
                            .setShaderState(POSITION_COLOR_SHADER)
                            .setTransparencyState(ADDITIVE_BLEND)
                            .setDepthTestState(new RenderStateShard.DepthTestStateShard("lequal", 515))
                            .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, false))
                            .setCullState(new RenderStateShard.CullStateShard(false))
                            .createCompositeState(false));
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
        // 🆕 2026-10-06（用户点单）：投影【从玩家脚下舒展开 / 关闭时收回】。
        //    ⚠️ 推进动画这一步必须在"开关边判"【之前】——收起来的那 0.28 秒里
        //       逻辑开关已经是 false 了，但画面还得继续画（否则就是"啪"地消失）。
        final long frameNanos = System.nanoTime();
        final ShanhaiHoloMenuAnim.Step step =
                ShanhaiHoloMenuAnim.advanceFrame(ShanhaiHoloMenuState.enabled(), frameNanos);
        if (step.changed()) {
            logAnim(step);
        }
        if (ShanhaiHoloMenuAnim.hidden()) {
            return;                             // 完全收起 ⇒ 与"从没开过"一样便宜
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final net.minecraft.client.player.LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }

        // 推进跟随插值（渲染线程，每帧一次；理由见 ShanhaiHoloMenuState §5）
        //   ⚠️ 面板开着的时候【也要】推 —— 否则关掉面板回到五块板时，锚点的上一帧时刻是陈旧的，
        //      那一下会看到整块"飞"过去（dt 被夹到 0.25s，但插值会真的走一大步）。
        ShanhaiHoloMenuState.advance(player, event.getPartialTick());

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
        // 原点：从【玩家脚底】插值到锚点。展开完毕时 (ox,oy,oz) == anchor（逐位），
        // 所以"动画加进来之前那套观感"不会被这一行改动。
        final double ox = player.getX() + (anchor.x - player.getX()) * ease;
        final double oy = player.getY() + (anchor.y - player.getY()) * ease;
        final double oz = player.getZ() + (anchor.z - player.getZ()) * ease;

        poseStack.pushPose();
        // 🆕 2026-10-06：环绕层（数据环 + 星尘网格）要的是【原始视图矩阵】（世界 → 相机）。
        //     事件给的 poseStack 在被 translate / mulPose 之前就是它，此后就会被改 —— 所以先拷一份。
        //     复用一把静态矩阵（就是本文件里 SCRATCH_MATRIX 那个套路），不进每帧的对象账。
        //     🔴 它【只有相机旋转、没有 −camPos】：环绕层自己补平移（ShanhaiHoloSurroundView）。
        final Matrix4f viewMatrix = VIEW_MATRIX.set(poseStack.last().pose());
        // 平移到世界的锚点（相机相对坐标：事件给的 PoseStack 已经是"相机视图矩阵"）
        poseStack.translate(ox - camera.x, oy - camera.y, oz - camera.z);
        // 【全息本体坐标系】落地。⚠️ 这里用的是【框偏航】而不是玩家偏航（两者在旧档里是 180−yaw 的关系、
        // 在环绕档里恒为 0；面板那一列另有它的朝向档）—— 值一律由 ShanhaiHoloMenuState 算好
        // （advance / panelFrameYaw），本行不再自己推导。
        poseStack.mulPose(Axis.YP.rotationDegrees(frameYawDeg + animSpin));
        // 展开时的整块缩放（1 ⇒ 不动）
        if (animScale != 1.0f) {
            poseStack.scale(animScale, animScale, animScale);
        }
        // 全息本体抖动（甲案的 holoJit：周期末尾 4% 的时间里跳两下）
        applyJitter(poseStack, tSec);

        // 🔴 板位姿来自几何核的同一个函数（环境档 = 绕玩家一圈；旧两档 = 甲案桶形弧面）
        // 🆕 面板模式 = ShanhaiHoloMenuLayout.panelRows()（5 块行板竖着叠）。
        final ShanhaiHoloMenuLayout.Board[] boards =
                panelMode == ShanhaiHoloMenuPanel.MODE_NONE
                        ? ShanhaiHoloMenuLayout.boardsOf(
                                ShanhaiHoloMenuState.layoutName(), ShanhaiHoloMenuState.distance())
                        : ShanhaiHoloMenuPanel.rows();

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
        final MultiBufferSource.BufferSource buffers =
                MultiBufferSource.immediate(new BufferBuilder(PER_FRAME_BUFFER_CAPACITY));
        try {
            // 🆕 2026-10-06（用户第 ② 条点单）：命令面板【只画两个框】。
            //   可见行由 ShanhaiHoloMenuPanel#rowVisible 一个口径说了算（输入层读的是同一个函数）
            //   ⇒ 没画出来的行不可能被点到，画出来的行不可能点不到。
            //   ⚠️ 这块板【还在板表里】、位姿一个数都没动 —— 只是这一帧不画它，
            //      所以"5 块行板"这套几何（第 6 条点单时定的）原样保留。
            // ---- 第 1 遍：底板层（写深度）----
            final VertexConsumer plate = buffers.getBuffer(plateType());
            for (int i = 0; i < ShanhaiHoloMenuTuning.BOARD_COUNT; i++) {
                if (!rowDrawn(i)) {
                    continue;
                }
                pushBoard(poseStack, boards[i]);
                drawPlateLayers(plate, poseStack.last().pose(), i, tSec);
                poseStack.popPose();
            }
            // ---- 🆕 第 1.5 遍：环绕层（数据环 + 星尘网格）----
            //   🔴 位置是刻意的：底板这一遍【写深度】已经把五块板的位置占住了 ⇒
            //      环绕层跑到板背后的部分会被深度测试真的挡住；
            //      而它自己【不写深度】（加法发光）⇒ 不会挡住后面的世界几何。
            //   🔴 它【不碰】那 5 块板：板位姿、排布、层序、动效一个字都没动，
            //      环绕层只是"照同样的位姿算出"板面禁区（见 ShanhaiHoloSurround 的规则 ③）。
            //   ⚠️ 板面禁区只认【画出来的】那些板：没画的行给 0 块（见 surroundBoards）。
            final ShanhaiHoloMenuLayout.Board[] surroundRows = surroundBoards(boards);
            final int surroundCount = rowCountCache;
            ShanhaiHoloSurroundRenderer.render(event, buffers, viewMatrix,
                    net.minecraft.util.Mth.lerp(event.getPartialTick(), player.xOld, player.getX()),
                    net.minecraft.util.Mth.lerp(event.getPartialTick(), player.yOld, player.getY()),
                    net.minecraft.util.Mth.lerp(event.getPartialTick(), player.zOld, player.getZ()),
                    ox, oy, oz, frameYawDeg, animSpin, animScale, surroundRows, surroundCount,
                    tSec, globalAlpha);
            // ---- 第 2 遍：发光层（加法）----
            final VertexConsumer glow = buffers.getBuffer(glowType());
            final float breath = breath(tSec);
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
            // ---- 第 3 遍：文字（原版 Font 通道）----
            final Font font = minecraft.font;
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
            // ---- 第 4 遍（🆕）：「正在加载」那根走马灯进度条（纯几何，跟着按下那块板走）----
            if (panelMode == ShanhaiHoloMenuPanel.MODE_NONE
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
            buffers.endBatch();
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
        com.shanhai.ShanhaiMod.LOGGER.info("[SHANHAI-HOLO] holo_anim {} ms={} progress={} {}",
                kind, step.elapsedMs(),
                String.format(java.util.Locale.ROOT, "%.3f", step.progress()),
                ShanhaiHoloMenuAnim.describe());
    }

    // ------------------------------------------------------------------ 板变换

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
     */
    private static void pushBoard(PoseStack poseStack, ShanhaiHoloMenuLayout.Board board) {
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
            font.drawInBatch(ch, 0.0f, 0.0f, rgb, false, tm, buffers,
                    Font.DisplayMode.NORMAL, 0, LIGHT_FULL_BRIGHT);
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
}
