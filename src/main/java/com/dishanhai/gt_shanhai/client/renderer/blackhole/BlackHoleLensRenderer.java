package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.config.GuixuClientConfig;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import org.joml.Matrix4f;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.IntBuffer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 山海重构 · <b>主机中心黑洞的「真引力透镜」渲染件</b>（一级 = 屏幕空间透镜；二级 = 程序化贴图 quad；三级 = 不画）。
 *
 * <h2>0. 它凭什么算"真扭曲"</h2>
 * 不是贴一张旋涡图，也不是粒子：<b>把这一帧已经渲染好的背景（方块 + 天空 + 云）拷一份当纹理，
 * 再按引力偏折角把每个像素的采样点搬到别处</b>。所以背景的像素级内容真的被搬动了 —— 石头缝、树影、
 * 云边都会被拉成环。数学与光的走向见 {@code blackhole.fsh} 顶部的注释。
 *
 * <h2>1. 挂载点 = {@code RenderLevelStageEvent.Stage.AFTER_WEATHER}</h2>
 * 逐条取证（都在本地反编译源码 / Forge patch 里，不靠推断）：
 * <ol>
 *   <li><b>它一定在半透明之后</b>：{@code LevelRenderer.renderLevel} 里 云（{@code renderClouds}）→
 *      {@code RenderStateShard.f_110127_}(weather 状态) → 雨雪（{@code m_109703_}）→
 *      <b>{@code dispatchRenderStage(AFTER_WEATHER)}</b> → 世界边界（{@code m_173012_}）→
 *      （Fabulous 时）透明层合成 {@code transparencyChain.process}。⇒ 我们画的时候，
 *      固体 / 半透明方块 / 实体 / 粒子 / 云 / 雨都已经在帧缓冲里了。</li>
 *   <li><b>为什么不用 {@code AFTER_TRANSLUCENT_BLOCKS}</b>：那一档由 Forge 在
 *      {@code m_172993_(RenderType.translucent())} 内部派发，Forge 自己的 javadoc 就写着
 *      "Due to how transparency sorting works, this stage may not work properly with translucency.
 *      If you intend to render translucency, try using AFTER_TRIPWIRE_BLOCKS or AFTER_PARTICLES"
 *      （{@code RenderLevelStageEvent.java} 第 188-192 行原文）。</li>
 *   <li><b>为什么不用 {@code AFTER_PARTICLES}</b>：云与雨在它之后才画。我们要<b>拷一份含云的背景</b>
 *      来做透镜，拷在云之前就会漏掉云；而且画在云之前的话，云会被画到黑洞上面（云在深度上更远，
 *      但它是在我们之后画的、深度测试对着"没有黑洞"的深度缓冲）。</li>
 *   <li><b>为什么不画 {@code AFTER_LEVEL}</b>：那一档在 {@code GameRenderer} 里派发，Forge 传进去的
 *      poseStack 是<b>投影栈</b>（{@code GameRenderer.renderLevel} 局部变量 {@code posestack}，
 *      里面是 {@code getProjectionMatrix(fov)} + bobView），拿它当 modelview 用会把世界算歪。
 *      （本件其实不用 poseStack 做顶点变换，但仍不想踩这个坑。）</li>
 * </ol>
 *
 * <h2>2. 🔴 遮挡（任务书要求"说清"）</h2>
 * <ul>
 *   <li><b>黑洞背后的方块不会穿透</b>：quad 的 4 个顶点都落在「黑洞中心所在、与视线垂直的平面」上，
 *      其 NDC 深度 = 黑洞中心的深度；{@code DepthTestStateShard("lequal")} 拿它和主渲染目标的
 *      深度缓冲逐像素比较 ⇒ 前面的机器 / 墙 / 栅栏会把黑洞<b>真的挡住</b>（是逐像素的，不是整块）。
 *      <b>代价</b>：黑洞被当成一个平面而不是球，所以"贴着黑洞侧面的方块"遮挡边缘会比真球略宽一点点。</li>
 *   <li><b>不会污染后面的绘制</b>：{@code WriteMaskStateShard(true, false)} ⇒ <b>不写深度</b>。
 *      这样我们不会在场景里留下一块"看不见的实心平面"（写深度的话，之后画的云会被这块平面挡掉）。</li>
 *   <li><b>拿不到的</b>：被遮挡的像素我们<b>采不到它背后的东西</b>（屏幕空间只有"已经画出来的颜色"），
 *      所以透镜边缘不会"看到墙后面"。要补这一层就得拿深度做一次重投影（见报告 §7 的后续）。</li>
 * </ul>
 *
 * <h2>3. 成本上限（任务书的第 3 条）</h2>
 * 逐帧开销 = <b>一次子矩形 GPU 拷贝</b>（W×H 像素）+ <b>一个 quad 的 W×H 个片元</b>，<b>没有全屏 raymarch</b>：
 * <ol>
 *   <li>距离 &gt; {@link #MAX_DISTANCE_BLOCKS} 格 ⇒ 直接不进函数体；</li>
 *   <li>屏幕半径 &lt; {@link #MIN_LENS_RADIUS_PX} 像素 ⇒ 不画（看不清，省一次拷贝）；</li>
 *   <li>矩形面积超过 {@link #MAX_PIXEL_FRACTION} × 全屏像素（且有 {@link #MAX_TEX_SIDE} 的硬上限）⇒
 *       按面积等比缩小矩形，并把"透镜强度掩码"一起收进去（画面只是变小，不会出现硬边）；</li>
 *   <li>背景只拷<b>矩形那一块</b>（{@code glCopyTexSubImage2D} 的 src 矩形参数），不是全屏；</li>
 *   <li>开关 = 客户端配置 {@code [debug] blackhole_enabled}。</li>
 * </ol>
 *
 * <h2>4. 三级兜底 + 可断言的日志</h2>
 * <pre>
 *   一级（完整透镜）失败 ⇒ {@code [SHANHAI-BH] fallback=2 reason=<原因>} ⇒ 同一帧立刻改画二级
 *   二级（贴图 quad）失败 ⇒ {@code [SHANHAI-BH] fallback=3 reason=<原因>} ⇒ 从此什么都不画
 * </pre>
 * 原因串是<b>固定词表 + 异常类名</b>（无空白，见 {@link BlackHoleFallbackGuard#sanitize}）：
 * {@code shader_load:*} / {@code uniform_missing:*} / {@code bgtex:*} / {@code copy_gl_error:*} /
 * {@code copy:*} / {@code lens_draw:*} / {@code fallback_tex:*} / {@code quad_draw:*} /
 * {@code iris_shaderpack_active}。
 *
 * <h2>5. 为什么 shader 是"自己 lazy new"而不是走 {@code RegisterShadersEvent}</h2>
 * {@code RegisterShadersEvent} 是在<b>资源重载</b>里同步跑的：那里抛异常会把整次重载带崩
 * （最坏直接 CrashReport），根本没机会"降级"。所以这里改成<b>第一次真正要画的时候</b>
 * {@code new ShaderInstance(...)}，外面套 {@code catch (Throwable)} ⇒ 编译/加载失败 100% 落到
 * {@code fallback=2}，而不是崩游戏。资源重载时由 {@link BlackHoleReloadHook} 把缓存作废，下次重试。
 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BlackHoleLensRenderer {

    /** 日志前缀（与任务书逐字一致）。 */
    public static final String PREFIX = BlackHoleFallbackGuard.PREFIX;

    // ==========================================================================================
    // 可调常数（都在一屏之内能看出来；报告 §7 标了"待确认"）
    // ==========================================================================================

    /**
     * 视觉史瓦西半径（方块）。视界在屏幕上 = {@code B_CRIT × 这个值} 像素半径。
     *
     * <p>🔴 2026-10-25 放大（用户「黑洞有点太小了」）：{@code 1.0 → 2.0}，见
     * {@link BlackHoleLensSpec#RS_BLOCKS_DEFAULT}（<b>可见黑盘半径 2.60 → 5.20 格，2.0 倍</b>）。
     * <p>真正的"生效值"＝这个数 × {@code BlackHoleTuning.scale()}（{@code /shanhai bh scale} 可改）。
     */
    public static final double RS_BLOCKS = BlackHoleLensSpec.RS_BLOCKS_DEFAULT;

    /**
     * 透镜/拷贝矩形的半径 = RS_BLOCKS × 这个值。
     *
     * <p>🔴 2026-10-25 放大：{@code 3.1 → 11.5}。原来的 3.1 <b>小于</b>吸积盘外缘（11 Rs），
     * 于是着色器里那张盘被矩形裁得只剩 {@code 3.0..3.1 Rs} 的一条细边 ⇒ 画面只剩"黑盘 + 细环"= 一颗球。
     * 见 {@link BlackHoleLensSpec#LENS_RANGE_RS}。
     */
    public static final double LENS_RANGE_RS = BlackHoleLensSpec.LENS_RANGE_RS;

    /**
     * 透镜屏幕半径的<b>下限</b>（像素）—— <b>不再是"小于就不画"的闸门</b>。
     *
     * <p>🔴 2026-10-24 傍晚用户原话：「<b>还有为什么黑洞太远了就不画了，我要他一直画</b>」
     * ⇒ 原来那两道成本闸门按下面口径处置：
     * <ul>
     *   <li>旧的 {@code MAX_DISTANCE_BLOCKS = 64}（距离 &gt; 64 格直接 return）—— <b>整条删除</b>，
     *       常量也一起删掉；现在距离只进日志 {@code dist=}、只参与像素尺度换算；</li>
     *   <li>旧的 {@code if (!(lensHalfPx >= MIN_LENS_RADIUS_PX)) return;}（屏幕半径 &lt; 8 px 不画）——
     *       改成<b>抬到下限继续画</b>（{@code lensHalfPx = max(lensHalfPx, MIN_LENS_RADIUS_PX)}）。</li>
     * </ul>
     * ⇒ 现在<b>只有三种情况允许不画</b>：没有中心（{@code center_empty}）／总开关关闭
     * （{@code enabled_off}）／shader 失败降级到三级（{@code dead_level3} / {@code quad_failed}）。
     * 另外"相机背后 / 视锥外 / 整块在画面外"这种<b>几何上无意义</b>的情况仍然不画（那不是"太远"）。
     *
     * <p>为什么 8 是合理下限：{@code LENS_RANGE_RS = 11.5}，半边长 8 px ⇒ 整个透镜矩形 16×16 px，
     * 可见黑盘（{@code B_CRIT}）只有 {@code 8 / 11.5 × 2.598 ≈ 1.8} px 半径 ——
     * 也就是用户说的"远到极小尺寸时视觉上只是一个亮点"，但<b>确实在画</b>。
     */
    public static final double MIN_LENS_RADIUS_PX = 8.0D;

    // ---- path= 词表（用户以后唯一的判据行见 logFrameReadout）----

    /** 一级（真透镜），走"Iris 合成之后"这条路（常态）。 */
    public static final String PATH_LENS_AFTER_COMPOSITE = "lens-after-composite";
    /** 一级，退回 AFTER_WEATHER 画（新 mixin 没生效时的退路）。 */
    public static final String PATH_LENS_AFTER_WEATHER = "lens-after-weather";
    /** 二级：程序化贴图公告板。 */
    public static final String PATH_FALLBACK_QUAD = "fallback-quad";
    /** 这一帧什么都没画。 */
    public static final String PATH_NONE = "none";
    /** 🔴 相机在洞里 ⇒ 走"洞内视角"（不是屏幕空间透镜）。 */
    public static final String PATH_INSIDE = "inside-overlay";

    /** {@code stage=}：合成之后的注入点（与 mixin 里 {@code @At} 的取值逐字对应）。 */
    public static final String STAGE_GAME_RENDERER =
            "GameRenderer@renderLevel:AFTER_INVOKE:LevelRenderer.renderLevel";
    /**
     * {@code stage=}：<b>首选</b>注入点 —— {@code GameRenderer.renderLevel} 里
     * {@code RenderSystem.clear(IZ)V} 的<b>前面</b>（在 585「调 LevelRenderer」之后、616「清深度」之前）。
     */
    public static final String STAGE_GAME_RENDERER_BEFORE_DEPTH_CLEAR =
            "GameRenderer@renderLevel:BEFORE_RenderSystem.clear";
    /** {@code stage=}：合成之后的兜底注入点（{@code GameRenderer.renderLevel} 的 TAIL，无 target 字符串）。 */
    public static final String STAGE_GAME_RENDERER_TAIL = "GameRenderer@renderLevel:TAIL";
    /** {@code stage=}：AFTER_WEATHER 退路。 */
    public static final String STAGE_AFTER_WEATHER = "AFTER_WEATHER";

    /**
     * 一帧最多为黑洞花掉的全屏像素比例（像素预算）。
     *
     * <p>🔴 2026-10-25：{@code 0.30 → 0.45}。透镜矩形从 3.1 Rs 放大到 11.5 Rs 之后，
     * 同一个机位下矩形面积约是原来的 {@code (11.5/3.1)² ≈ 13.8 倍}；0.30 的预算会把矩形裁到
     * "只盖住盘的一半"，盘的外缘会出现一圈<b>硬边</b>（着色器不对盘做边缘羽化，见 fsh §⑤）。
     * 0.45 是为"尽量装下整张盘"给的余量；仍然有上限（{@link #MAX_TEX_SIDE} ²）、
     * 仍然每帧只画一次。如果实测掉帧，把它调回 0.30（那时近距离会看到盘被裁）。
     */
    public static final double MAX_PIXEL_FRACTION = 0.45D;

    /** 背景拷贝贴图的硬上限边长（= 矩形边长上限；2048² 的 RGBA8 = 16 MB 显存，只在真需要时分配）。 */
    public static final int MAX_TEX_SIDE = 2048;

    /** 背景拷贝贴图的初始边长。 */
    public static final int BASE_TEX_SIDE = 512;

    /** 二级兜底贴图的边长（程序化生成；🔴 2026-10-25：256 → 512，见 {@link BlackHoleLensSpec#FALLBACK_TEX_SIDE}）。 */
    public static final int FALLBACK_TEX_SIDE = BlackHoleLensSpec.FALLBACK_TEX_SIDE;

    /** 吸积盘亮度总增益（只影响盘的加法亮度）。 */
    public static final float DISK_GAIN = BlackHoleLensSpec.DISK_GAIN;

    /** 光子环亮度总增益。 */
    public static final float RING_GAIN = BlackHoleLensSpec.RING_GAIN;

    /**
     * 吸积盘在世界空间里的法线（归一化在着色器里做）。
     *
     * <p>🔴 2026-10-25：改为<b>从 {@link BlackHoleLensSpec#diskNormal()} 派生</b>
     * （倾角 18° → 32°，理由见那份 spec 的注释：环面视角下 18° 的盘是"一条头发丝"，
     * 画面上就只剩一颗球）。这样"一级用的法线"和"二级贴图用的椭圆压扁比"永远是同一个倾角。
     * <p>⚠️ 仍定在<b>世界</b>空间（不是相机空间）：否则玩家一转头，盘会跟着转（一眼假）。
     */
    private static final float[] WORLD_DISK_NORMAL = BlackHoleLensSpec.diskNormal();

    // ==========================================================================================
    // GL 常量（用字面量 + 注释，避免多引一个 LWJGL 依赖）
    // ==========================================================================================
    private static final int GL_TEXTURE_2D = 3553;
    private static final int GL_TEXTURE_MIN_FILTER = 10241;
    private static final int GL_TEXTURE_MAG_FILTER = 10240;
    private static final int GL_TEXTURE_WRAP_S = 10242;
    private static final int GL_TEXTURE_WRAP_T = 10243;
    private static final int GL_LINEAR = 9729;
    private static final int GL_CLAMP_TO_EDGE = 33071;
    private static final int GL_RGBA8 = 32856;
    private static final int GL_RGBA = 6408;
    private static final int GL_UNSIGNED_BYTE = 5121;
    private static final int GL_LEQUAL = 515;
    private static final int GL_ALWAYS = 519;

    private static final ResourceLocation SHADER_ID = new ResourceLocation("gt_shanhai", "guixu_blackhole");

    /** 必需 uniform：任何一个在链接后的程序里找不到 ⇒ 当场降二级（宁可画贴图，也别画出一坨错的）。 */
    private static final String[] REQUIRED_UNIFORMS = {
            "uProjMat", "uBhView", "uDiskNormal", "uScreenSize", "uBgRect", "uBgTexel",
            "uHolePx", "uLensRadiusPx", "uRs", "uTanHalfFov", "uAspect", "uTime",
            "uDiskGain", "uRingGain", "uStrength"
    };

    /** {@code fallback=} 行走 WARN（好找），{@code restore}/boot 走 INFO。 */
    private static final Consumer<String> LOG_SINK = line -> {
        if (line.contains(" fallback=")) {
            GTDishanhaiMod.LOGGER.warn(line);
        } else {
            GTDishanhaiMod.LOGGER.info(line);
        }
    };

    // ==========================================================================================
    // 状态（只在渲染线程读写；MC 的渲染线程 = 主线程）
    // ==========================================================================================
    private static BlackHoleFallbackGuard guard;

    private static ShaderInstance lensShader;
    private static boolean shaderTried;

    private static RenderType lensType;
    /** 无遮挡版（{@code depth=none}）：{@code always} 深度测试；与 {@code lensType(boolean)} 分开缓存。 */
    private static RenderType lensTypeNoDepth;
    private static RenderType quadType;

    /**
     * 二级兜底贴图（程序化生成、只注册一次）。
     *
     * <p>🔴 2026-10-25：这里<b>不持有 GL 句柄、不建 IntBuffer</b>，只持有一个
     * {@code NativeImage} 生成的 {@code DynamicTexture} 注册出来的 {@link ResourceLocation}
     * ⇒ 生命周期交给 {@code TextureManager}，我们一个 GL 调用都不发。
     */

    private static ResourceLocation fallbackTex;
    private static boolean fallbackTexTried;
    private static boolean fallbackTexOk;
    private static boolean fallbackDrewLogged;
    /** 世界空间尺寸（{@link BlackHoleLensSpec#describe}）只打一次 INFO —— 报告/日志都能直接读。 */
    private static boolean specLogged;

    private static int bgTexId = -1;
    private static int bgTexSide;
    private static boolean copyVerified;

    private static int fbTexId = -1;

    private static String centerRawCache;
    private static double[] centerCache;
    private static boolean centerWarned;

    private static boolean bootLogged;
    private static boolean idleLogged;
    private static boolean lensOnLogged;
    /** 上一次打过的 "lens 模式 × 光影包" 组合（只在变化时打一行，避免每帧刷屏）。 */
    private static String lensEnvLogged;

    /** 渲染帧计数（`BlackHoleIris` 的重查节流 + "mixin 一直没生效"的判据都用它）。 */
    private static int frameCounter;
    /** 合成之后的绘制入口是否真的被调过（mixin 生效的判据）。 */
    private static boolean mixinSeen;
    /** AFTER_WEATHER 跑了、mixin 没跑的帧数。 */
    private static int mixinMissedFrames;
    /** "mixin 装不上 ⇒ 已退回 AFTER_WEATHER" 只报一次。 */
    private static boolean mixinWarned;
    /** AFTER_WEATHER 采集好的这一帧读数，等合成之后取走。 */
    private static Frame pending;
    /** 最后一次真正画出去的路（{@link #PATH_LENS_AFTER_COMPOSITE} 等）。 */
    private static String lastPath = PATH_NONE;
    /** {@code path=/draw=} 那一行的节流（毫秒）：1 秒一行，够跟手又不刷屏。 */
    private static long lastFrameLogMs;

    private BlackHoleLensRenderer() {}

    // ==========================================================================================
    // 一帧的读数：AFTER_WEATHER 采样 → 合成之后绘制
    // ==========================================================================================

    /**
     * 一帧的全部几何读数。
     *
     * <h2>为什么要在 AFTER_WEATHER 采、却到"合成之后"才画</h2>
     * 2026-10-24 傍晚用户的第 0 优先级要求：「<b>能不能不管开不开光影，直接注入 iris</b>」
     * —— 要的是「无论光影开/关，黑洞都走一级真透镜」，但<b>不是</b>去改 Iris 的 shader 源码。
     * <p>取证（{@code oculus-mc1.20.1-1.8.0.jar} 反编译，见报告 §0）：
     * <pre>
     *   net/irisshaders/iris/mixin/MixinLevelRenderer#iris$endLevelRender
     *     &#64;Inject(method = LevelRenderer.renderLevel(&hellip;)V, at = &#64;At(value = "RETURN", shift = Shift.BEFORE))
     *     → pipeline.finalizeLevelRendering() → finalPassRenderer.renderFinalPass()
     *   net/irisshaders/iris/mixin/MixinGameRenderer#iris$runColorSpace
     *     &#64;Inject(method = GameRenderer.renderLevel(&hellip;)V, at = &#64;At("TAIL"))
     *     → pipeline.finalizeGameRendering() → colorSpaceConverter.process(mainTexId)
     * </pre>
     * ⇒ <b>合成发生在 {@code LevelRenderer.renderLevel} 的 RETURN</b>；
     * 而 {@code GameRenderer.renderLevel} 在同一个方法里<b>调</b>了它（obf {@code fjq.a(FJeij;)V} 的
     * {@code 585: invokevirtual fjv.a(Leij;FJZLemz;Lfjq;Lfjw;Lorg/joml/Matrix4f;)V}，
     * {@code fjv} = LevelRenderer、{@code fjq} = GameRenderer）。
     * ⇒ 在本 mixin 的注入点（那句话<b>之后</b>）主渲染目标里已经是<b>合成好的本帧画面</b>。
     * <p>但矩阵最干净的采样点在 AFTER_WEATHER：那时 {@code RenderSystem.getModelViewMatrix()}
     * 就是相机旋转矩阵（{@code LevelRenderer} 自己 {@code applyModelViewMatrix} 过）。
     * 合成之后那个 GL 状态可能已被 Iris 改掉，所以这里把矩阵<b>算一次、存下来</b>。
     */
    static final class Frame {
        float[] view;               // 相机旋转矩阵（列主序；事件给的，只含旋转）
        float[] proj;               // 投影矩阵（列主序）
        double[] camPos;            // 相机世界坐标
        double[] center;            // 黑洞中心世界坐标
        double[] holeScreen;        // {pxX, pxY, ndcZ}（像素，左下原点）
        float[] camUp;              // 相机 up 向量（二级公告板用；合成之后拿不到 Camera 对象）
        float[] camLeft;            // 相机 left 向量
        /**
         * 黑洞中心在<b>相机空间</b>里的位置（= {@code view · (center − camPos)}）。
         *
         * <p>🔴 2026-10-24 深夜修：这个值<b>必须</b>是相机空间。重构时一度把它换成"世界空间差值"
         * ⇒ 着色器里 {@code tca = dot(bh, dir)} 算出来的碰撞参数 {@code b} 全错 ⇒
         * 黑盘/光子环/吸积盘三件的判据<b>全部落空</b>，屏幕上只剩 warp 一层
         * （用户原话：「黑洞光看到透镜了，那黑洞呢」）。见 {@link BlackHoleShaderProbe} §0。
         */
        float[] bhView;
        /** 这一帧的渲染刻（同一个 tick 画两次 = 双重绘制，会 WARN）。 */
        int renderTick;
        /**
         * 我们这个注入点上，主渲染目标的<b>深度缓冲里还是不是本帧世界</b>。
         *
         * <p>🔴 2026-10-24 深夜，用户第二条：「<b>为什么有方块挡着还能看到透镜效果</b>」。
         * 原因不是"我们没开深度测试"（`lequal` 一直都在），而是<b>注入点选在了 TAIL</b> ——
         * vanilla 在 {@code LevelRenderer.renderLevel} 返回之后、{@code renderItemInHand} 之前
         * 调了一次 {@code RenderSystem.clear(256)}（= 清 DEPTH，obf {@code fjq.a(FJeij)V} 的 offset 616）。
         * ⇒ 那个点上深度缓冲已经全是 1.0，`lequal` 恒通过 = <b>没有遮挡</b>。
         * <p>所以"深度可用"= ①光影没生效 <b>且</b> ②注入点在清深度之前。两个条件都由调用方给。
         */
        boolean depthPreserved;
        double tanHalfFov;
        double dist;                // 相机到黑洞的欧氏距离（格）—— 只进日志，不再是闸门
        double forwardDist;         // 相机到黑洞的"前向距离"（格）—— 像素尺度只由它决定
        double rsBlocks;
        double screenR;             // 黑盘在屏幕上的像素半径（用户要看的读数）
        double lensHalfPx;          // 实际使用的透镜半边长（已被 MIN_LENS_RADIUS_PX 抬过）
        double maxPixels;
        double rectX0;
        double rectY0;
        double rectW;
        double rectH;
        double rectHalf;
        int screenW;
        int screenH;
        float renderSeconds;
        long stampNanos;
        /** 这一帧的 parts 位掩码（见 {@link BlackHoleShaderProbe#parts}；1 秒重算一次）。 */
        int partsSerial;
        /**
         * 🔴 相机在<b>洞里</b>吗（2026-10-25 用户：「飞到黑洞里面、往外看的时候，透镜不渲染了」）。
         *
         * <p>判据：{@code dist ≤ 可见黑盘半径 × INSIDE_FACTOR}。命中时走
         * {@link #drawInside} 的"洞内视角"，<b>不再</b>走屏幕空间透镜
         * （透镜那套几何假设"洞是一个在相机前方的点"，洞内这个前提根本不成立）。
         */
        boolean inside;
        /** 洞内时：可见黑盘半径（格）。 */
        double insideShadowR;
        /** 洞内时：{@code dist / insideShadowR}（0 = 正在球心）。 */
        double insideRatio;

        /** parts 位掩码（最近一次算出来的）。 */
        int parts() {
            return partsSerial;
        }
    }

    // ==========================================================================================
    // 入口
    // ==========================================================================================

    /**
     * 主入口之一：{@link RenderLevelStageEvent.Stage#AFTER_WEATHER}。
     *
     * <p>它负责<b>采样 + 校验</b>，然后把这一帧的读数放进 {@link #pending}，等
     * {@code ShanhaiBlackHoleGameRendererMixin} 在 {@code LevelRenderer.renderLevel} 返回之后取走并绘制。
     *
     * <p>为什么这一档是"采样点"：那时 {@code LevelRenderer} 已经
     * {@code posestack.mulPoseMatrix(levelPose); RenderSystem.applyModelViewMatrix();}
     * ⇒ 事件给的 poseStack 矩阵就是相机旋转矩阵（本件 {@code toView} 的唯一输入）。
     *
     * <h2>🔴 为什么它自己<b>也</b>能画（而不是只采样）</h2>
     * 如果新 mixin 因为任何原因没装上（别的 mixin 改了同一个方法 / 注入点被优化掉），
     * {@link #mixinSeen} 会一直是 false ⇒ 本方法退回到"就在 AFTER_WEATHER 画"，
     * 行为与 2026-10-24 之前完全一致（这一档在光影生效时确实拿不到本帧世界，会降级二级——
     * 但<b>有画面</b>，不是黑屏）。{@code path=lens-after-weather} 就是这条退路的标记。
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return;                                     // 稳态开销 = 一次枚举比较
        }
        final Frame f = capture(event);
        if (f == null) {
            return;                                     // capture 里已经 noteSkip（原因见 lastSkip）
        }
        pending = f;
        if (mixinSeen) {
            return;                                     // 常态：合成之后由 mixin 取走并绘制
        }
        // 退路：mixin 还没生效（或根本装不上）⇒ 就在这一档画，跟 2026-10-24 之前一样
        if (++mixinMissedFrames == 40 && !mixinWarned) {
            mixinWarned = true;
            GTDishanhaiMod.LOGGER.warn("{} mixin_missing stage=AFTER_WEATHER reason=合成后的绘制入口连续 {} 帧未生效"
                            + "（黑洞仍会画，但光影生效时拿不到本帧背景 ⇒ 会走二级）",
                    PREFIX, mixinMissedFrames);
        }
        final Frame taken = pending;
        pending = null;
        renderFrame(guard(), taken, PATH_LENS_AFTER_WEATHER, STAGE_AFTER_WEATHER);
    }

    /**
     * 主入口之二：<b>合成之后</b>（由 {@code ShanhaiBlackHoleGameRendererMixin} 调）。
     *
     * <p>注入点 = {@code GameRenderer.renderLevel} 里 {@code LevelRenderer.renderLevel(&hellip;)V}
     * 这句话的 {@code Shift.AFTER}：
     * <ul>
     *   <li>Iris 已经在 {@code LevelRenderer.renderLevel} 的 RETURN 里做过
     *       {@code finalizeLevelRendering()}（合成）⇒ 主渲染目标里是<b>本帧</b>的世界；</li>
     *   <li>此刻还没 {@code renderItemInHand}（手持物不会被透镜扭曲），
     *       也还没 {@code finalizeGameRendering}（Iris 的色彩空间转换会把我们的像素一起处理）；</li>
     *   <li>主渲染目标仍然是当前的写目标（{@code GuixuHostFxRenderer} 的 {@code copyBackground}
     *       里再 {@code bindWrite(false)} 一次兜底）。</li>
     * </ul>
     *
     * @param stage 注入点的字符串形式（原样进日志的 {@code stage=}，便于回读时确认是哪一处）
     * @return 这一帧是否走了一级（真透镜）
     */
    public static boolean drawAfterComposite(String stage, boolean depthPreserved) {
        mixinSeen = true;
        mixinMissedFrames = 0;
        final Frame f = pending;
        pending = null;
        if (f == null) {
            return false;                               // AFTER_WEATHER 那一档就没通过（原因见 lastSkip）
        }
        if (System.nanoTime() - f.stampNanos > 50_000_000L) {
            return false;                               // 上一帧的残渣：别拿旧矩阵画新帧
        }
        f.depthPreserved = depthPreserved;
        return renderFrame(guard(), f, PATH_LENS_AFTER_COMPOSITE, stage);
    }

    /**
     * 采样本帧读数（不画）。
     *
     * @return null = 这一帧不该画（原因已写进 {@link BlackHoleFrameStats#lastSkip()}）
     */
    private static Frame capture(RenderLevelStageEvent event) {
        final BlackHoleFallbackGuard g = guard();
        if (g.isDead()) {
            BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_DEAD);
            return null;                                // 三级：从此什么都不画
        }
        if (!enabled()) {
            BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_DISABLED);
            return null;
        }
        final double[] center = resolveCenter();
        if (center == null) {
            BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_NO_CENTER);
            noteIdle();
            return null;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_NO_LEVEL);
            return null;
        }
        final Camera camera = event.getCamera();
        final PoseStack poseStack = event.getPoseStack();
        if (camera == null || poseStack == null) {
            BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_NO_LEVEL);
            return null;
        }
        final RenderTarget main = mc.getMainRenderTarget();
        if (main == null || main.width <= 0 || main.height <= 0) {
            BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_NO_LEVEL);
            return null;
        }
        final Frame f = new Frame();
        f.screenW = main.width;
        f.screenH = main.height;

        // ---- 距离：🔴 2026-10-24 傍晚按用户要求【不再是闸门】
        //   用户原话：「还有为什么黑洞太远了就不画了，我要他一直画」
        //   ⇒ 原来的 `if (!(dist <= MAX_DISTANCE_BLOCKS)) return;` 整条删除（那个常量也一起删了）。
        //     距离现在只做两件事：① 进日志（dist=）② 算像素尺度（用前向距离，见下）。
        final Vec3 camPos = camera.getPosition();
        final double dx = center[0] - camPos.x;
        final double dy = center[1] - camPos.y;
        final double dz = center[2] - camPos.z;
        f.camPos = new double[]{camPos.x, camPos.y, camPos.z};
        f.center = center;
        f.dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // ---- 🔴 洞内判定（2026-10-25 用户：「飞到黑洞里面、往外看的时候，透镜不渲染了」）
        //   改前这里有<b>两道</b>假设"洞是一个在相机前方的点"的闸门，洞内时两道都会把画面吞掉：
        //     ① `if (!(bhView[2] < -0.05F)) return;`      ⇒ 洞在身后/身侧 ⇒ SKIP_BEHIND
        //     ② `projectToScreen(...)` 的 `cw > 1e-6` 判据  ⇒ 洞在相机原点附近 ⇒ cw≈0 ⇒ SKIP_OUT_OF_FRUSTUM
        //   （还有第三处隐患：`pxPerBlock = (H/2)/(tanHalf*forward)` 在 forward→0 时爆掉。）
        //   这三条都不是"用户要的取舍"，而是几何前提不成立 ⇒ 洞内改走"洞内视角"这条路。
        final double shadowR = BlackHoleLensMath.B_CRIT * rsBlocks();
        if (f.dist <= shadowR * BlackHoleLensSpec.INSIDE_FACTOR) {
            f.inside = true;
            f.insideShadowR = shadowR;
            f.insideRatio = shadowR > 1.0e-6D ? f.dist / shadowR : 0.0D;
            // 洞内不投影洞心（那会退化成除零）；整屏都要画，rect 直接给全屏
            f.rectX0 = 0.0D;
            f.rectY0 = 0.0D;
            f.rectW = f.screenW;
            f.rectH = f.screenH;
            f.rectHalf = Math.min(f.screenW, f.screenH) * 0.5D;
            f.holeScreen = new double[]{f.screenW * 0.5D, f.screenH * 0.5D, 0.0D};
            f.renderSeconds = (event.getRenderTick() + event.getPartialTick()) / 20.0F;
            f.renderTick = event.getRenderTick();
            f.stampNanos = System.nanoTime();
            // 洞内也要相机基向量（画赤道盘带与视界环）
            final org.joml.Vector3f up0 = camera.getUpVector();
            final org.joml.Vector3f left0 = camera.getLeftVector();
            f.camUp = new float[]{up0.x(), up0.y(), up0.z()};
            f.camLeft = new float[]{left0.x(), left0.y(), left0.z()};
            // 盘法线换成相机空间（洞内画"赤道带"要用）
            final Matrix4f vm0 = new Matrix4f(poseStack.last().pose());
            final float[] view0 = new float[16];
            vm0.get(view0);
            f.view = view0;
            final float[] diskN0 = new float[3];
            BlackHoleLensMath.toView(view0, new double[]{WORLD_DISK_NORMAL[0], WORLD_DISK_NORMAL[1],
                    WORLD_DISK_NORMAL[2]}, new double[]{0.0D, 0.0D, 0.0D}, diskN0);
            final double nl = Math.sqrt(diskN0[0] * diskN0[0] + diskN0[1] * diskN0[1]
                    + diskN0[2] * diskN0[2]);
            f.bhView = nl > 1.0e-9D
                    ? new float[]{(float) (diskN0[0] / nl), (float) (diskN0[1] / nl), (float) (diskN0[2] / nl)}
                    : new float[]{0.0F, 1.0F, 0.0F};
            f.tanHalfFov = 1.0D;
            return f;
        }

        // ---- 世界 → 相机空间（事件矩阵只含旋转，必须自己减 camPos；见 BlackHoleLensMath §2）
        final Matrix4f viewM = new Matrix4f(poseStack.last().pose());
        f.view = new float[16];
        viewM.get(f.view);
        final float[] bhView = new float[3];
        BlackHoleLensMath.toView(f.view, center, f.camPos, bhView);
        f.bhView = bhView;                              // ← 着色器的 uBhView 直接取它（相机空间）
        if (!(bhView[2] < -0.05F)) {
            BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_BEHIND);
            return null;                                // 在相机背后 / 贴在镜头上（不是距离闸门）
        }

        final Matrix4f projM = new Matrix4f(event.getProjectionMatrix());
        f.proj = new float[16];
        projM.get(f.proj);
        f.tanHalfFov = BlackHoleLensMath.tanHalfFovY(f.proj);
        if (!(f.tanHalfFov >= 0.02D) || f.tanHalfFov > 20.0D) {
            BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_BAD_PROJECTION);
            return null;
        }
        f.holeScreen = new double[3];
        if (!BlackHoleLensMath.projectToScreen(f.proj, bhView, f.screenW, f.screenH, f.holeScreen)) {
            BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_OUT_OF_FRUSTUM);
            return null;
        }
        if (!(f.holeScreen[2] > -1.0D) || !(f.holeScreen[2] < 1.0D)) {
            BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_OUT_OF_FRUSTUM);
            return null;                                // 视锥外（渲染层面无意义）
        }

        f.forwardDist = -bhView[2];
        final double pxPerBlock = (f.screenH * 0.5D) / (f.tanHalfFov * f.forwardDist);
        f.rsBlocks = rsBlocks();
        final double rsPx = f.rsBlocks * pxPerBlock;
        f.screenR = f.rsBlocks * BlackHoleLensMath.B_CRIT * pxPerBlock;
        final double lensHalfPx = rsPx * LENS_RANGE_RS;
        if (!specLogged) {
            specLogged = true;
            GTDishanhaiMod.LOGGER.info("{} spec {}（改前：rs=1.00格 黑盘半径=2.60格 透镜半径=3.10格 "
                            + "吸积盘=3.00..11.00格 盘/黑盘=4.23x 贴图=256²）",
                    PREFIX, BlackHoleLensSpec.describe(f.rsBlocks));
        }

        // ---- 🔴 屏幕半径太小【仍然画】：按最小值画（用户 2026-10-24 傍晚："我要他一直画"）
        //   改前：`if (!(lensHalfPx >= MIN_LENS_RADIUS_PX)) { noteSkip(SKIP_TOO_SMALL); return null; }`
        //   现在：抬到 MIN_LENS_RADIUS_PX 继续走；只有"没有中心 / 开关关闭 / shader 失败"三种情况才不画。
        f.lensHalfPx = Math.max(lensHalfPx, MIN_LENS_RADIUS_PX);

        // ---- 像素预算：把矩形收进预算内（掩码跟着收，画面不会出现硬边）
        f.maxPixels = Math.min((double) MAX_TEX_SIDE * MAX_TEX_SIDE,
                f.screenW * (double) f.screenH * MAX_PIXEL_FRACTION);
        final BlackHoleLensMath.Rect rect = BlackHoleLensMath.rectAround(
                f.holeScreen[0], f.holeScreen[1], f.lensHalfPx, f.screenW, f.screenH, f.maxPixels);
        if (rect.w < 4.0D || rect.h < 4.0D) {
            BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_NO_RECT);
            return null;                                // 整个黑洞都在画面外（不是"太小"）
        }
        f.rectX0 = rect.x0;
        f.rectY0 = rect.y0;
        f.rectW = rect.w;
        f.rectH = rect.h;
        f.rectHalf = rect.half;

        final org.joml.Vector3f up = camera.getUpVector();
        final org.joml.Vector3f left = camera.getLeftVector();
        f.camUp = new float[]{up.x(), up.y(), up.z()};
        f.camLeft = new float[]{left.x(), left.y(), left.z()};
        f.renderSeconds = (event.getRenderTick() + event.getPartialTick()) / 20.0F;
        f.renderTick = event.getRenderTick();
        f.stampNanos = System.nanoTime();
        return f;
    }

    /**
     * 拿一帧读数去画（一级 → 失败立刻二级；两条路互斥）。
     *
     * @param path  {@link #PATH_LENS_AFTER_COMPOSITE} 或 {@link #PATH_LENS_AFTER_WEATHER}
     * @param stage 注入点的字符串（进日志）
     * @return 是否走了一级（真透镜）
     */
    private static boolean renderFrame(BlackHoleFallbackGuard g, Frame f, String path, String stage) {
        final BlackHoleTuning.LensMode lensMode = BlackHoleTuning.lensMode();
        // 🔴 2026-10-24 傍晚：判定源换成 BlackHoleIris（旧的 getPipeline().isPresent() 恒 true，见那个类 §1）
        final int frame = frameCounter++;
        final boolean shaderpack = BlackHoleIris.update(frame);
        // 深度（两个条件都要）：
        //   ① 光影没生效 —— Iris 生效时它把世界画进自己的缓冲、只把颜色合成回来；
        //   ② 注入点在 vanilla 清深度之前 —— `RenderSystem.clear(256)` 在 renderLevel 返回后、
        //      renderItemInHand 之前跑；选在它之后（例如 TAIL）即使 shaderpack=false 也没有遮挡。
        final boolean depthOk = !shaderpack && f.depthPreserved;
        noteLensEnvironment(g, lensMode, shaderpack, depthOk, path, stage);
        noteBoot(f, shaderpack, path);
        warnIfDoubleDrawn(f);

        // ---- 🔴 洞内：整段换成"洞内视角"（不走屏幕空间透镜，理由见 capture 里的洞内判定）
        if (f.inside) {
            final boolean drewInside = drawInside(g, f);
            lastPath = drewInside ? PATH_INSIDE : PATH_NONE;
            logFrameReadout(f, PATH_INSIDE, stage, shaderpack, depthOk, false, false, 0);
            if (drewInside) {
                BlackHoleFrameStats.noteDraw(false, f.center == null ? f.camPos : f.center,
                        f.rsBlocks <= 0.0D ? 2.0D : f.rsBlocks, f.dist, f.rectW, f.rectH,
                        f.screenW, f.screenH, 0.7D, shaderpack);
            } else {
                BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_QUAD_FAILED);
            }
            return drewInside;
        }

        // ON = 用户强制一级；AUTO = 默认（🔴 现在合成之后拿得到本帧背景 ⇒ 光影生效也走一级）；
        // OFF = 用户明确要二级（背景不扭曲）。
        final boolean allowFull = lensMode != BlackHoleTuning.LensMode.OFF;

        // ---- parts 位掩码：只在与日志同一节流窗口里算（64×64 采样，1 秒一次，可忽略）
        final long nowMs = System.currentTimeMillis();
        if (nowMs - lastFrameLogMs >= FRAME_LOG_MS) {
            f.partsSerial = BlackHoleShaderProbe.parts(paramsOf(f), PARTS_SAMPLES);
        }

        boolean drewFull = false;
        if (allowFull && g.level() == BlackHoleFallbackGuard.LEVEL_FULL) {
            drewFull = drawFullLens(g, f, depthOk);
            if (drewFull && !lensOnLogged) {
                lensOnLogged = true;
                GTDishanhaiMod.LOGGER.info(
                        "{} lens_on path={} rect=[{},{} {}x{}] rsPx={} dist={} budget_max_px={}",
                        PREFIX, path, f1(f.rectX0), f1(f.rectY0), f1(f.rectW), f1(f.rectH),
                        f1(f.screenR), f1(f.dist), f0(f.maxPixels));
            }
        }

        // ---- 二级（一级不可用 / 刚降级 / 用户强制：同一帧立刻顶上，绝不空一帧）
        //   ⚠️ 这里【不能】写成 `if (g.level() == LEVEL_QUAD)`：环境原因那条路只是
        //      "本帧不用一级"，guard 的级别仍然是 FULL ⇒ 那样写等于什么都不画。
        boolean drewQuad = false;
        if (!drewFull && !g.isDead()) {
            drewQuad = drawFallbackQuad(g, f);
        }
        lastPath = drewFull ? path : (drewQuad ? PATH_FALLBACK_QUAD : PATH_NONE);
        logFrameReadout(f, path, stage, shaderpack, depthOk, drewFull, drewQuad, f.parts());
        if (drewFull || drewQuad) {
            BlackHoleFrameStats.noteDraw(drewFull, f.center, f.rsBlocks, f.dist,
                    f.rectW, f.rectH, f.screenW, f.screenH, f.tanHalfFov, shaderpack);
        } else {
            BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_QUAD_FAILED);
        }
        return drewFull;
    }

    /**
     * 🔴 <b>用户以后唯一的判据行</b>（原话：「这行是他以后唯一的判据，不能骗人」）。
     *
     * <p>格式（固定词表、无空白值，{@code split(" ")} 可切）：
     * <pre>
     *   [SHANHAI-BH] path=lens-after-composite stage=GameRenderer@renderLevel:AFTER_INVOKE shaderpack=false
     *                depth=ok owner=lens dist=21.2 screenR=48.9 draw=yes
     * </pre>
     * <ul>
     *   <li>{@code path=} <b>这一帧真的走了哪条路</b>（{@code lens-after-composite} /
     *       {@code lens-after-weather} / {@code fallback-quad} / {@code none}）
     *       —— 旧实现里 {@code owner=lens level=1} 只是"hostfx 没接管"，不是"透镜画了"，
     *       两者矛盾时以这行为准（见 {@link #lastPath()}）；</li>
     *   <li>{@code shaderpack=} = <b>光影真的在生效</b>（{@code BlackHoleIris.pipelineActive()}），
     *       <b>不是</b>"装了 Oculus"；</li>
     *   <li>{@code depth=ok|none}：{@code none} = 主目标深度不是本帧世界 ⇒ 本帧是<b>无遮挡透镜版</b>
     *       （透镜 RenderType 换成 {@code always} 深度测试，不伪造遮挡）；</li>
     *   <li>{@code dist=} 格 / {@code screenR=} 黑盘屏幕像素半径 / {@code draw=yes|no}。</li>
     * </ul>
     * 1 秒一行（{@link #FRAME_LOG_MS}）。
     */
    private static void logFrameReadout(Frame f, String path, String stage, boolean shaderpack,
                                        boolean depthOk, boolean drewFull, boolean drewQuad,
                                        int parts) {
        final long now = System.currentTimeMillis();
        if (now - lastFrameLogMs < FRAME_LOG_MS) {
            return;
        }
        lastFrameLogMs = now;
        final String drawnPath = drewFull ? path : (drewQuad ? PATH_FALLBACK_QUAD : PATH_NONE);
        final boolean missing = drewFull && (parts & BlackHoleShaderProbe.PART_REQUIRED)
                != BlackHoleShaderProbe.PART_REQUIRED;
        if (f.inside) {
            // 洞内没有屏幕空间透镜 ⇒ parts 不适用（别让它误报 parts_missing）
            return;
        }
        GTDishanhaiMod.LOGGER.info(
                "{} path={} stage={} shaderpack={} depth={} depthPreserved={} inside={} owner={} "
                        + "dist={} screenR={} parts={} draw={}",
                PREFIX, drawnPath, stage, shaderpack, depthOk ? "ok" : "none",
                f.depthPreserved, f.inside, (drewFull || drewQuad || PATH_INSIDE.equals(drawnPath))
                        ? "lens" : "none",
                f1(f.dist), f1(f.screenR), BlackHoleShaderProbe.describe(parts),
                (drewFull || drewQuad || PATH_INSIDE.equals(drawnPath)) ? "yes" : "no");
        if (missing) {
            // 走到这里 = 着色器"画了"但四件里少了至少一件 ⇒ 这正是 2026-10-24 深夜那个 bug 的形态。
            // 必须 WARN：不能再让"draw=yes"冒充"黑洞看得见"。
            GTDishanhaiMod.LOGGER.warn("{} parts_missing 期望含 core+ring+disk+glow，实际 parts={} "
                            + "（bhView={},{},{} rs={} lensR={} rect=[{},{} {}x{}]）",
                    PREFIX, BlackHoleShaderProbe.describe(parts),
                    f2(f.bhView[0]), f2(f.bhView[1]), f2(f.bhView[2]), f2(f.rsBlocks),
                    f1(f.rectHalf), f1(f.rectX0), f1(f.rectY0), f1(f.rectW), f1(f.rectH));
        }
    }

    /** {@code path=/draw=} 行的节流（毫秒）。 */
    public static final long FRAME_LOG_MS = 1_000L;

    /** {@code parts=} 的采样密度（每轴点数）：光子环只有 0.075 Rs 宽，64 才不容易漏。 */
    public static final int PARTS_SAMPLES = 64;

    /** 上一帧的渲染刻 + 帧号，用来抓"同一个 tick 画两次"。 */
    private static int lastDrawnRenderTick = Integer.MIN_VALUE;
    private static boolean doubleDrawWarned;

    /**
     * 🔴 双重绘制探针：同一个渲染刻里 {@link #renderFrame} 跑了两次 = 两处入口都在画（会重叠、且更耗）。
     *
     * <p>用户 2026-10-24 深夜要求「把旧 stage 的重复绘制全部撤掉，保证同一帧只画一次、不重叠」。
     * 正常设计下不可能发生（{@code pending} 取走就没了），所以这条只是<b>保险</b>：
     * 真出现了就打一行 WARN，并<b>拒画第二次</b>（宁可这一帧少画一次，也不要叠两层）。
     */
    private static void warnIfDoubleDrawn(Frame f) {
        if (f.renderTick == lastDrawnRenderTick) {
            if (!doubleDrawWarned) {
                doubleDrawWarned = true;
                GTDishanhaiMod.LOGGER.warn("{} double_draw tick={}（同一帧画了两次，已拒第二次）",
                        PREFIX, f.renderTick);
            }
            return;
        }
        lastDrawnRenderTick = f.renderTick;
    }

    /**
     * 把"这一帧为什么走一级/二级"讲清楚。
     *
     * <p>只在<b>组合变化时</b>打一行，不会每帧刷屏。
     */
    private static void noteLensEnvironment(BlackHoleFallbackGuard g, BlackHoleTuning.LensMode mode,
                                            boolean shaderpack, boolean depthOk, String path,
                                            String stage) {
        final String tag = mode.id() + "/" + shaderpack + "/" + depthOk + "/" + path;
        if (Objects.equals(tag, lensEnvLogged)) {
            return;
        }
        lensEnvLogged = tag;
        if (mode == BlackHoleTuning.LensMode.OFF) {
            g.logOnce(BlackHoleFallbackGuard.LEVEL_QUAD, "lens_off_by_user");
            GTDishanhaiMod.LOGGER.info("{} lens_mode mode=off shaderpack={} note=用户强制二级（背景不扭曲）",
                    PREFIX, shaderpack);
        } else if (shaderpack) {
            g.logRestored();
            GTDishanhaiMod.LOGGER.info("{} lens_mode mode={} shaderpack=true depth={} path={} stage={} "
                            + "iris={} note=光影生效，但我们的注入点在合成之后 ⇒ 拿得到本帧背景 ⇒ 走一级",
                    PREFIX, mode.id(), depthOk ? "ok" : "none", path, stage, BlackHoleIris.describe());
        } else {
            g.logRestored();
            GTDishanhaiMod.LOGGER.info("{} lens_mode mode={} shaderpack=false path={} stage={} iris={} note=一级",
                    PREFIX, mode.id(), path, stage, BlackHoleIris.describe());
        }
    }

    // ==========================================================================================
    // 洞内视角（相机在黑洞里）
    // ==========================================================================================

    /**
     * <b>洞内视角</b>：人站在洞里往外看时画什么。
     *
     * <h2>🔴 为什么要新开一条路（用户 2026-10-25：「飞到黑洞里面、往外看的时候，透镜不渲染了」）</h2>
     * 屏幕空间透镜整条数学都建立在"<b>洞是一个在相机前方的点</b>"上：
     * {@code bhView[2] < 0}、{@code projectToScreen} 的 {@code cw > 0}、{@code pxPerBlock = (H/2)/(tan·forward)}。
     * 人进到洞里，这三条前提<b>全部失效</b>（洞在身侧/身后、cw≈0、forward→0），
     * 改前就是被前两条闸门整段吞掉 ⇒ 屏幕上什么效果都没有。
     *
     * <h2>物理上"洞内"应该看到什么（诚实说明，这也是取舍）</h2>
     * 史瓦西视界<b>以内</b>所有光线都朝奇点走 ⇒ 严格物理答案是"全黑"。
     * 用户明确要「至少洞内不要整段消失」⇒ 我按<b>可玩的近似</b>画四层（全部纯顶点、零贴图、零裸 GL）：
     * <ol>
     *   <li><b>暗穹顶</b>：整屏压暗到 alpha {@value #INSIDE_DOME_ALPHA}（世界还在，但明显"进了个东西里"）；</li>
     *   <li><b>视界环</b>：贴着屏幕边缘的一圈冷白蓝光晕 —— 视界把你整个包住；</li>
     *   <li><b>赤道盘带</b>：吸积盘从<b>内侧</b>可见。盘是过洞心的平面 ⇒ 它在屏幕上的像是
     *       该平面与成像面的<b>交线</b>（一条直线，由相机空间的盘法线算出），
     *       物理上从球心看它近似一条线，所以这里给它一个最小可见宽度；</li>
     *   <li><b>中心微光</b>：屏幕正中一小团加性光斑（避免"纯黑一片"）。</li>
     * </ol>
     * <p>⚠️ 这条<b>不是</b>引力透镜（没有背景采样、没有偏折），日志里 {@code path=inside-overlay}
     * 与 {@code path=lens-after-composite} 严格区分开 —— 不会拿它冒充"真透镜"。
     *
     * @return 是否真的画了（false = 画的过程中抛异常，已降级）
     */
    private static boolean drawInside(BlackHoleFallbackGuard g, Frame f) {
        try {
            final double aspect = (double) f.screenW / (double) f.screenH;
            // 几何全部来自纯类 BlackHoleInsideGeometry（离线出图器逐四边形复算同一份数据，
            // 避免上轮"预览器自己另写一遍 ⇒ 图对、游戏错"的形态）
            final List<BlackHoleInsideGeometry.Quad> quads = BlackHoleInsideGeometry.quads(
                    aspect, f.insideRatio, f.bhView /* 洞内时这里存的是盘法线（相机空间） */,
                    f.renderSeconds, true);
            final MultiBufferSource.BufferSource buffers =
                    MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
            // 先画不透明的暗穹顶（alpha 混合），再画加性层 —— 两批、两次 endBatch
            final RenderType blend = insideBlendLayer();
            final RenderType add = insideAdditiveLayer();
            final VertexConsumer vb = buffers.getBuffer(blend);
            final VertexConsumer va = buffers.getBuffer(add);
            final float invA = (float) (1.0D / aspect);
            for (BlackHoleInsideGeometry.Quad q : quads) {
                final VertexConsumer vc = q.additive() ? va : vb;
                final double[] c = q.uv8();
                for (int i = 0; i < 4; i++) {
                    vc.vertex((float) (c[i * 2] * invA), (float) c[i * 2 + 1], 0.0F)
                            .color(q.r(), q.g(), q.b(), q.a()).endVertex();
                }
            }
            buffers.endBatch(blend);
            buffers.endBatch(add);

            if (!insideLogged) {
                insideLogged = true;
                GTDishanhaiMod.LOGGER.info("{} inside_view dist={} shadowR={} ratio={} quads={} "
                                + "layers=dome+horizon+diskband+coreglow band={} "
                                + "note=洞内没有屏幕空间透镜（几何前提不成立）⇒ 走近似视角；"
                                + "物理上视界内应为全黑，这是按「别整段消失」做的取舍",
                        PREFIX, f1(f.dist), f1(f.insideShadowR), f2(f.insideRatio), quads.size(),
                        BlackHoleInsideGeometry.hasDiskBand(f.bhView));
            }
            return true;
        } catch (Throwable t) {
            g.degradeTo(BlackHoleFallbackGuard.LEVEL_NONE,
                    "inside_draw:" + t.getClass().getSimpleName());
            GTDishanhaiMod.LOGGER.warn("{} inside_draw_failed 放弃洞内绘制", PREFIX, t);
            return false;
        }
    }

    /** 洞内视角只报一次的那行 INFO。 */
    private static boolean insideLogged;

    /** 暗穹顶的不透明度（洞内把世界压暗到还剩 20%）。 */
    public static final float INSIDE_DOME_ALPHA = 0.80F;

    private static double clamp01(double v) {
        return v < 0.0D ? 0.0D : (v > 1.0D ? 1.0D : v);
    }

    /**
     * 在 (u,v) 坐标里画一个轴对齐矩形。
     *
     * <p>(u,v) 的约定：{@code v ∈ [-1,1]} 对应屏幕高度，{@code u ∈ [-aspect, aspect]} 对应宽度
     * ⇒ 同一个半径在 u/v 上是<b>圆</b>（不会因为宽银幕被拉成椭圆）。
     * 转 NDC 只差一次 {@code ndcX = u/aspect}。
     */
    private static void insideQuad(VertexConsumer vc, Frame f, double u0, double v0, double u1, double v1,
                                   float r, float g, float b, float a) {
        final double aspect = (double) f.screenW / (double) f.screenH;
        vc.vertex((float) (u0 / aspect), (float) v0, 0.0F).color(r, g, b, a).endVertex();
        vc.vertex((float) (u1 / aspect), (float) v0, 0.0F).color(r, g, b, a).endVertex();
        vc.vertex((float) (u1 / aspect), (float) v1, 0.0F).color(r, g, b, a).endVertex();
        vc.vertex((float) (u0 / aspect), (float) v1, 0.0F).color(r, g, b, a).endVertex();
    }

    /** 任意四边形（顶点按逆时针给，每个顶点 (u,v)），颜色/透明度统一。 */
    private static void insideQuadUV(VertexConsumer vc, Frame f,
                                     double u0, double v0, double u1, double v1,
                                     double u2, double v2, double u3, double v3,
                                     float r, float g, float b, float a) {
        final double aspect = (double) f.screenW / (double) f.screenH;
        vc.vertex((float) (u0 / aspect), (float) v0, 0.0F).color(r, g, b, a).endVertex();
        vc.vertex((float) (u1 / aspect), (float) v1, 0.0F).color(r, g, b, a).endVertex();
        vc.vertex((float) (u2 / aspect), (float) v2, 0.0F).color(r, g, b, a).endVertex();
        vc.vertex((float) (u3 / aspect), (float) v3, 0.0F).color(r, g, b, a).endVertex();
    }

    /** 洞内暗穹顶层：{@code POSITION_COLOR}、alpha 混合、<b>不测深度不写深度</b>、不剔除。 */
    private static RenderType insideBlendLayer;

    private static RenderType insideBlendLayer() {
        if (insideBlendLayer == null) {
            insideBlendLayer = RenderType.create(
                    "shanhai_blackhole_inside_dome",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    4096,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(new RenderStateShard.ShaderStateShard(
                                    GameRenderer::getPositionColorShader))
                            .setTransparencyState(ALPHA_BLEND)
                            // 洞内是"相机就在里面"，深度没有意义；不测也不写，免得把手/世界切一半
                            .setDepthTestState(new RenderStateShard.DepthTestStateShard("always", GL_ALWAYS))
                            .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, false))
                            .setCullState(new RenderStateShard.CullStateShard(false))
                            .createCompositeState(false));
        }
        return insideBlendLayer;
    }

    /** 洞内发光层：{@code POSITION_COLOR}、加性、不测/不写深度、不剔除。 */
    private static RenderType insideAdditiveLayer;

    private static RenderType insideAdditiveLayer() {
        if (insideAdditiveLayer == null) {
            insideAdditiveLayer = RenderType.create(
                    "shanhai_blackhole_inside_add",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    8192,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(new RenderStateShard.ShaderStateShard(
                                    GameRenderer::getPositionColorShader))
                            .setTransparencyState(new RenderStateShard.TransparencyStateShard(
                                    "shanhai_blackhole_inside_additive",
                                    () -> {
                                        RenderSystem.enableBlend();
                                        RenderSystem.blendFunc(
                                                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                                                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
                                    },
                                    () -> {
                                        RenderSystem.disableBlend();
                                        RenderSystem.defaultBlendFunc();
                                    }))
                            .setDepthTestState(new RenderStateShard.DepthTestStateShard("always", GL_ALWAYS))
                            .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, false))
                            .setCullState(new RenderStateShard.CullStateShard(false))
                            .createCompositeState(false));
        }
        return insideAdditiveLayer;
    }

    // ==========================================================================================
    // 一级：屏幕空间引力透镜
    // ==========================================================================================

    /**
     * 一级：屏幕空间引力透镜（<b>读一帧读数，不碰事件对象</b>）。
     *
     * <p>🔴 2026-10-24 傍晚把参数从"事件 + 一堆散值"改成 {@link Frame}：
     * 绘制点已经从 AFTER_WEATHER 挪到"Iris 合成之后"，那里<b>没有</b>
     * {@code RenderLevelStageEvent}（拿不到投影矩阵 / renderTick）。所以矩阵与时间在
     * AFTER_WEATHER 就存进 {@link Frame}，这里只读。
     *
     * @param depthOk false = 主渲染目标的深度<b>不是本帧世界</b>（Iris 合成掉了）⇒ 用无遮挡透镜版
     */
    private static boolean drawFullLens(BlackHoleFallbackGuard g, Frame f, boolean depthOk) {
        final ShaderInstance shader = ensureShader(g);
        if (shader == null) {
            return false;
        }
        if (!ensureBgTexture(g, (int) Math.ceil(f.rectW), (int) Math.ceil(f.rectH))) {
            return false;
        }
        final Minecraft mc = Minecraft.getInstance();
        final RenderTarget main = mc == null ? null : mc.getMainRenderTarget();
        if (main == null || main.width != f.screenW || main.height != f.screenH) {
            return false;                                // 分辨率换过 ⇒ 这一帧的像素读数作废
        }
        if (!copyBackground(g, main, f)) {
            return false;
        }
        try {
            final float[] diskN = new float[3];
            BlackHoleLensMath.toView(f.view, new double[]{WORLD_DISK_NORMAL[0], WORLD_DISK_NORMAL[1],
                    WORLD_DISK_NORMAL[2]}, new double[]{0.0D, 0.0D, 0.0D}, diskN);
            final float nx = diskN[0];
            final float ny = diskN[1];
            final float nz = diskN[2];
            final float nlen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (!(nlen > 1e-4F)) {
                return false;
            }

            // 🔴 2026-10-24 深夜：uBhView 必须是【相机空间】（着色器里 dir 就是相机空间射线）。
            //    之前这里错给了"世界空间差值" ⇒ b/bn 全错 ⇒ 黑盘/环/盘一件都不出（只剩 warp）。
            final double bnAtCenter = centerBn(f);
            if (!(bnAtCenter < BlackHoleShaderProbe.B_CRIT)) {
                // 中心处都进不了"被吞"的范围 ⇒ 这一帧不可能画出黑盘。这不该发生（几何自洽），
                // 发生了就是口径错了 ⇒ 宁可降级到二级贴图，也别交一个"只有 warp"的画面。
                g.degradeTo(BlackHoleFallbackGuard.LEVEL_QUAD, "bh_view_bad:bn=" + f2(bnAtCenter));
                GTDishanhaiMod.LOGGER.warn("{} bh_view_bad 中心处 bn={} ≥ b_crit={}（uBhView 口径错了？"
                                + " bhView={},{},{} dist={}）", PREFIX, f2(bnAtCenter),
                        f2(BlackHoleShaderProbe.B_CRIT), f2(f.bhView[0]), f2(f.bhView[1]),
                        f2(f.bhView[2]), f1(f.dist));
                return false;
            }
            shader.safeGetUniform("uProjMat").set(new Matrix4f().set(f.proj));
            shader.safeGetUniform("uBhView").set(f.bhView[0], f.bhView[1], f.bhView[2]);
            shader.safeGetUniform("uDiskNormal").set(nx / nlen, ny / nlen, nz / nlen);
            shader.safeGetUniform("uScreenSize").set((float) f.screenW, (float) f.screenH);
            shader.safeGetUniform("uBgRect").set((float) f.rectX0, (float) f.rectY0,
                    (float) f.rectW, (float) f.rectH);
            final float texel = 1.0F / (float) bgTexSide;
            shader.safeGetUniform("uBgTexel").set(texel, texel);
            shader.safeGetUniform("uHolePx").set((float) f.holeScreen[0], (float) f.holeScreen[1]);
            shader.safeGetUniform("uLensRadiusPx").set((float) f.rectHalf);
            shader.safeGetUniform("uRs").set((float) f.rsBlocks);
            shader.safeGetUniform("uTanHalfFov").set((float) f.tanHalfFov);
            shader.safeGetUniform("uAspect").set((float) f.screenW / (float) f.screenH);
            shader.safeGetUniform("uTime").set(f.renderSeconds);
            // 运行期可调的两个视觉量：偏折强度（几何）与盘亮度（颜色）
            // 🔴 2026-10-25：乘总增益 0.42（用户「引力透镜有点劲大」）；见 BlackHoleLensSpec.DEFLECT_GAIN
            shader.safeGetUniform("uStrength").set((float) deflectStrength());
            shader.safeGetUniform("uDiskGain").set((float) (DISK_GAIN * BlackHoleTuning.disk()));
            shader.safeGetUniform("uRingGain").set(RING_GAIN);

            // 背景贴图绑到 0 号纹理单元（见 bindTextureForShader 的注释：setShaderTexture 只"记账"不绑）
            bindTextureForShader(shader, bgTexId);
            drawLensQuad(f, depthOk);
            return true;
        } catch (Throwable t) {
            g.degradeTo(BlackHoleFallbackGuard.LEVEL_QUAD, "lens_draw:" + t.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 画一个 NDC 矩形（顶点已经是裁剪空间坐标；z 用黑洞中心平面的 NDC 深度 ⇒ 深度测试天然正确）。
     *
     * @param depthOk false = 深度缓冲不是本帧世界（Iris 合成掉了）⇒ 换成 {@code always} 深度测试的
     *                <b>无遮挡透镜版</b>（用户口径：「不要为此伪造遮挡」，这里只是"不假装有遮挡"）
     */
    private static void drawLensQuad(Frame f, boolean depthOk) {
        final float x0 = (float) (f.rectX0 / f.screenW * 2.0D - 1.0D);
        final float x1 = (float) ((f.rectX0 + f.rectW) / f.screenW * 2.0D - 1.0D);
        final float y0 = (float) (f.rectY0 / f.screenH * 2.0D - 1.0D);
        final float y1 = (float) ((f.rectY0 + f.rectH) / f.screenH * 2.0D - 1.0D);
        final float z = (float) f.holeScreen[2];
        final RenderType type = lensType(depthOk);
        final MultiBufferSource.BufferSource buffers =
                MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        final VertexConsumer vc = buffers.getBuffer(type);
        vc.vertex(x0, y0, z).endVertex();
        vc.vertex(x1, y0, z).endVertex();
        vc.vertex(x1, y1, z).endVertex();
        vc.vertex(x0, y1, z).endVertex();
        buffers.endBatch(type);
    }

    // ==========================================================================================
    // 二级：程序化贴图 quad
    // ==========================================================================================

    /**
     * 二级：<b>程序化贴图的公告板</b>（光影包生效 / 一级画不出来时的兜底）。
     *
     * <h2>🔴 2026-10-25 重做（父代理批准）—— 从"整条停用"改成"安全地画出来"</h2>
     * 2026-10-24 的事故：{@code hs_err_pid24204.log}，JVM 原生崩溃 EXCEPTION_ACCESS_VIOLATION，
     * Problematic frame {@code C [nvoglv64.dll+0xb9e4b0]}，Java 栈 =
     * {@code GlStateManager._texImage2D ← ensureFallbackTexture ← drawFallbackQuad ← onRenderLevelStage}，
     * 当时 {@code [SHANHAI-BH] fallback=2 reason=iris_shaderpack_active}。⇒ 处置是整条停用。
     * <p>现在那条路<b>换了实现</b>，三处根本差异（对应报告"改后兜底为什么不会再打崩 NVIDIA"）：
     * <ol>
     *   <li><b>贴图不走裸 GL</b>：{@code new NativeImage} → 逐像素 {@code setPixelRGBA}（纯 CPU）
     *       → {@code new DynamicTexture(image)} → {@code TextureManager.register(…)}
     *       ⇒ 上传由 MC 自己的 {@code TextureUtil.prepareImage} / {@code NativeImage.upload} 完成
     *       （它自己 bindTexture、自己设 pixel-store、自己走 {@code AbstractTexture} 生命周期）；
     *       本方法里<b>没有</b> {@code _genTexture} / {@code _texImage2D} / {@code IntBuffer}。</li>
     *   <li><b>只注册一次</b>（128×128、2 的幂）：{@code fallbackTexTried} 一置位就再不上传，
     *       不存在"每帧新建纹理"这种把驱动拖死的形态。</li>
     *   <li><b>失败只放弃绘制</b>：整个初始化包在 {@code try/catch(Throwable)} 里，
     *       失败 ⇒ {@code degradeTo(LEVEL_NONE, "fallback_tex:…")} ＋ 一行 WARN，<b>永不重试</b>。</li>
     * </ol>
     * <h2>画在哪（顶点空间是逐行核对过的，不是猜的）</h2>
     * {@code LevelRenderer.renderLevel} 在 {@code dispatchRenderStage(AFTER_WEATHER)} 之前已经
     * {@code posestack.mulPoseMatrix(levelPose); RenderSystem.applyModelViewMatrix();}
     * ⇒ 此刻 {@code RenderSystem.getModelViewMatrix()} <b>就是</b>相机旋转矩阵。
     * 所以顶点要写 <b>相机相对坐标</b>（{@code world − camPos}）且<b>不再过 pose 矩阵</b>
     * （{@code vc.vertex(x,y,z)} 的裸重载），否则旋转会被乘两遍。
     * 深度：{@code lequal} 测试照旧 ⇒ 挡在洞前面的方块仍然会挡住它。
     *
     * @param rsBlocks 视界半径（格）
     */
    private static boolean drawFallbackQuad(BlackHoleFallbackGuard g, Frame f) {
        if (!GuixuClientConfig.blackholeFallbackTexture()) {
            g.logOnce(BlackHoleFallbackGuard.LEVEL_NONE, "fallback_off_by_config");
            return false;
        }
        if (!ensureFallbackTexture(g)) {
            return false;
        }
        final double[] center = f.center;
        final double[] camPos = f.camPos;
        // 朝相机拉 0.05 格：避免与"正好在洞心那一层"的几何共面打架
        final double dist = f.dist;
        final double pull = dist > 1.0e-3D ? Math.min(0.05D, dist * 0.01D) : 0.0D;
        final double inv = dist > 1.0e-3D ? pull / dist : 0.0D;
        final float px = (float) (center[0] - camPos[0] - (center[0] - camPos[0]) * inv);
        final float py = (float) (center[1] - camPos[1] - (center[1] - camPos[1]) * inv);
        final float pz = (float) (center[2] - camPos[2] - (center[2] - camPos[2]) * inv);
        final float half = (float) (f.rsBlocks * LENS_RANGE_RS);
        if (!(half > 1.0e-3F)) {
            return false;
        }
        // 相机基向量在 AFTER_WEATHER 就存进 Frame 了（合成之后拿不到 Camera 对象）
        final float ux = f.camUp[0] * half;
        final float uy = f.camUp[1] * half;
        final float uz = f.camUp[2] * half;
        final float lx = f.camLeft[0] * half;
        final float ly = f.camLeft[1] * half;
        final float lz = f.camLeft[2] * half;
        final RenderType type = quadType(fallbackTex);
        final MultiBufferSource.BufferSource buffers =
                MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        final VertexConsumer vc = buffers.getBuffer(type);
        // 左下 → 右下 → 右上 → 左上（贴图的 v 从上往下，所以底边取 v=1）
        vc.vertex(px - lx - ux, py - ly - uy, pz - lz - uz).uv(0.0F, 1.0F).color(255, 255, 255, 255).endVertex();
        vc.vertex(px + lx - ux, py + ly - uy, pz + lz - uz).uv(1.0F, 1.0F).color(255, 255, 255, 255).endVertex();
        vc.vertex(px + lx + ux, py + ly + uy, pz + lz + uz).uv(1.0F, 0.0F).color(255, 255, 255, 255).endVertex();
        vc.vertex(px - lx + ux, py - ly + uy, pz - lz + uz).uv(0.0F, 0.0F).color(255, 255, 255, 255).endVertex();
        buffers.endBatch(type);
        if (!fallbackDrewLogged) {
            fallbackDrewLogged = true;
            GTDishanhaiMod.LOGGER.info("{} fallback_quad_ok（兜底：NativeImage+TextureManager 贴图，"
                            + "公告板半径={} 格、tex={}）", PREFIX, f1(half), fallbackTex);
        }
        return true;
    }

    /**
     * 二级贴图：<b>{@code NativeImage} 逐像素画 + {@code TextureManager} 注册</b>（只做一次）。
     *
     * <p>画的是一张静止的"黑洞示意"：黑盘（洞面）+ 光子环 + 引力光晕 + 一条更亮的赤道吸积带。
     * 半径比例与 {@link #LENS_RANGE_RS} 对齐 ⇒ 贴图里黑盘占 {@code 1/3.1} 的半宽，
     * 与几何半径 {@code rsBlocks × 3.1} 一比一。
     */
    private static boolean ensureFallbackTexture(BlackHoleFallbackGuard g) {
        if (fallbackTexTried) {
            return fallbackTexOk;
        }
        fallbackTexTried = true;
        try {
            final NativeImage image = new NativeImage(NativeImage.Format.RGBA,
                    FALLBACK_TEX_SIDE, FALLBACK_TEX_SIDE, false);
            paintFallbackImage(image);
            final DynamicTexture texture = new DynamicTexture(image);
            final net.minecraft.client.renderer.texture.TextureManager tm =
                    Minecraft.getInstance().getTextureManager();
            fallbackTex = tm.register("shanhai_blackhole_fallback", texture);
            fallbackTexOk = true;
            GTDishanhaiMod.LOGGER.info("{} fallback_tex_ok tex={} id={} side={} "
                            + "path=NativeImage+TextureManager（无裸 GL 纹理调用）",
                    PREFIX, fallbackTex, texture.getId(), FALLBACK_TEX_SIDE);
        } catch (Throwable t) {
            fallbackTexOk = false;
            g.degradeTo(BlackHoleFallbackGuard.LEVEL_NONE,
                    "fallback_tex:" + t.getClass().getSimpleName());
            GTDishanhaiMod.LOGGER.warn("{} fallback_tex_failed 放弃二级绘制（不重试）", PREFIX, t);
        }
        return fallbackTexOk;
    }

    /**
     * 往 {@link NativeImage} 里逐像素画黑洞示意（纯 CPU 算术，不碰 GL）。
     *
     * <p>🔴 2026-10-25：像素内容搬到 {@link BlackHoleLensSpec#fallbackPixel(double, double)}
     * —— 那份是<b>纯函数</b>，离线预览器（{@code temp/host-fx/probe/BlackHoleLookPng.java}）
     * 直接 import 它复算同一张图，所以"游戏里画的"和"报告里贴的"是同一份代码。
     *
     * <p>四件构件（用户口径「四条里少一条都算没做到」）：
     * ① 近黑核心盘 ② 明亮细光子环 ③ 赤道方向拉长的吸积带（内白热→中橙→外暗红）
     * ④ 外圈很淡的光晕 —— 逐条见那份 spec 的注释。
     */
    private static void paintFallbackImage(NativeImage img) {
        final int side = img.getWidth();
        final double c = side * 0.5D;
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                final double u = (x + 0.5D - c) / c;
                final double v = (y + 0.5D - c) / c;
                img.setPixelRGBA(x, y, BlackHoleLensSpec.fallbackPixel(u, v));
            }
        }
    }

    // ==========================================================================================
    // 资源：shader / 背景贴图 / 拷贝
    // ==========================================================================================

    private static ShaderInstance ensureShader(BlackHoleFallbackGuard g) {
        // 🔴 先看"用户要求模拟一次失败"（/shanhai bh test）：这条旗必须在 shaderTried 之前消费，
        //    否则已经加载好的 shader 会让 test 命令什么都不发生。
        if (BlackHoleTuning.consumeSimulatedFailure()) {
            shaderTried = true;
            g.degradeTo(BlackHoleFallbackGuard.LEVEL_QUAD, "shader_load:simulated_by_command");
            return null;
        }
        if (shaderTried) {
            return lensShader;
        }
        shaderTried = true;
        try {
            final ShaderInstance sh = new ShaderInstance(
                    Minecraft.getInstance().getResourceManager(), SHADER_ID, DefaultVertexFormat.POSITION);
            final String missing = firstMissingUniform(sh);
            if (missing != null) {
                try {
                    sh.close();
                } catch (Throwable ignored) {
                    // 关不掉就算了：它没被采用
                }
                g.degradeTo(BlackHoleFallbackGuard.LEVEL_QUAD, "uniform_missing:" + missing);
                return null;
            }
            lensShader = sh;
            GTDishanhaiMod.LOGGER.info("{} shader_ok id={} uniforms_checked={}",
                    PREFIX, SHADER_ID, REQUIRED_UNIFORMS.length);
            return sh;
        } catch (Throwable t) {
            g.degradeTo(BlackHoleFallbackGuard.LEVEL_QUAD, "shader_load:" + t.getClass().getSimpleName());
            return null;
        }
    }

    private static String firstMissingUniform(ShaderInstance sh) {
        for (String name : REQUIRED_UNIFORMS) {
            if (sh.getUniform(name) == null) {
                return name;
            }
        }
        return null;
    }

    private static boolean ensureBgTexture(BlackHoleFallbackGuard g, int needW, int needH) {
        final int need = Math.max(BASE_TEX_SIDE, Math.max(needW, needH));
        if (bgTexId != -1 && bgTexSide >= need) {
            return true;
        }
        try {
            int side = 1;
            while (side < need) {
                side <<= 1;
            }
            if (side > MAX_TEX_SIDE) {
                side = MAX_TEX_SIDE;
            }
            final int id = GlStateManager._genTexture();
            GlStateManager._bindTexture(id);
            GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            // 只分配存储（data = null）：内容由每帧的 glCopyTexSubImage2D 灌进去
            GlStateManager._texImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, side, side, 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, (IntBuffer) null);
            GlStateManager._bindTexture(0);
            if (bgTexId != -1) {
                GlStateManager._deleteTexture(bgTexId);
            }
            bgTexId = id;
            bgTexSide = side;
            return true;
        } catch (Throwable t) {
            g.degradeTo(BlackHoleFallbackGuard.LEVEL_QUAD, "bgtex:" + t.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 把主渲染目标的一块颜色<b>逐像素</b>拷进我们的贴图（{@code glCopyTexSubImage2D}）。
     *
     * <p>为什么选这条路（报告 §2 有四条候选的逐条结论）：
     * <ul>
     *   <li>它<b>不要求我们自己有 FBO</b>（拷贝源 = 当前 READ framebuffer，主目标正在上面画）；</li>
     *   <li>它可以只拷<b>一个子矩形</b> ⇒ 成本随黑洞在屏幕上的大小走，而不是随分辨率走；</li>
     *   <li>它是 1:1 像素拷贝 ⇒ 我们的 uv 换算是纯线性映射，不会引入缩放模糊
     *       （"wrp=0 时逐像素等于原画面"这条无缝性就是靠它成立的）。</li>
     * </ul>
     *
     * <p>第一次拷贝后会<b>排干历史 GL 错误 → 再拷一次 → 只认这一次的 {@code glGetError}</b>：
     * 一次会话只做一次，成本可忽略，但能保证"这条通路在你机器上到底行不行"是个<b>实测结论</b>而不是假设。
     */
    private static boolean copyBackground(BlackHoleFallbackGuard g, RenderTarget main, Frame f) {
        final int x = (int) Math.floor(f.rectX0);
        final int y = (int) Math.floor(f.rectY0);
        final int w = Math.min((int) Math.ceil(f.rectW), bgTexSide);
        final int h = Math.min((int) Math.ceil(f.rectH), bgTexSide);
        if (w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > main.width || y + h > main.height) {
            return false;
        }
        try {
            // GL_FRAMEBUFFER（36160）同时绑读/写 ⇒ bindWrite 之后 READ framebuffer 就是主目标
            main.bindWrite(false);
            GlStateManager._bindTexture(bgTexId);
            GlStateManager._glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, x, y, w, h);
            GlStateManager._bindTexture(0);

            if (!copyVerified) {
                copyVerified = true;
                drainGlErrors();
                GlStateManager._bindTexture(bgTexId);
                GlStateManager._glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, x, y, w, h);
                GlStateManager._bindTexture(0);
                final int err = GlStateManager._getError();
                if (err != 0) {
                    g.degradeTo(BlackHoleFallbackGuard.LEVEL_QUAD, "copy_gl_error:" + err);
                    return false;
                }
                GTDishanhaiMod.LOGGER.info("{} bgcopy_ok kind=glCopyTexSubImage2D max_side={}", PREFIX, bgTexSide);
            }
            return true;
        } catch (Throwable t) {
            g.degradeTo(BlackHoleFallbackGuard.LEVEL_QUAD, "copy:" + t.getClass().getSimpleName());
            return false;
        }
    }

    private static void drainGlErrors() {
        for (int i = 0; i < 32; i++) {
            if (GlStateManager._getError() == 0) {
                return;
            }
        }
    }

    /**
     * 把一张裸纹理 id 绑成"着色器里的 {@code Sampler0}"。
     *
     * <h2>🔴 为什么不能只写 {@code RenderSystem.setShaderTexture(0, id)}</h2>
     * 它是<b>只记账不绑定</b>的：{@code RenderSystem._setShaderTexture(int sampler, int textureId)}
     * 的实现只有一句 {@code shaderTextures[sampler] = textureId;}（`RenderSystem.java:839-843`），
     * 一个 GL 调用都没有。真正的绑定发生在
     * <ol>
     *   <li>{@code ShaderInstance.apply()} 里——但只对"JSON 里带 {@code file} 的 sampler"
     *       （{@code f_173328_} 值为 null 的直接跳过，见 `ShaderInstance.java:338-357`）；</li>
     *   <li>或调 {@code ShaderInstance.setSampler(name, Integer)} 塞一个"有值的 sampler"
     *       ——{@code apply()} 就会 {@code activeTexture(GL_TEXTURE0+j) + bindTexture(id)}。</li>
     * </ol>
     * 原版自己就是这么补的：{@code LevelRenderer.m_172993_} 在画区块前逐个
     * {@code shader.setSampler("Sampler" + i, RenderSystem.getShaderTexture(i))}
     * （`LevelRenderer.java:1450-1453`）——<b>这条"原版必须自己搭桥"的事实，就是
     * "setShaderTexture 不绑"的最硬证据</b>。
     *
     * <p>所以这里三重保险：①{@code setSampler}（正路，会设 sampler uniform + 绑）；
     * ②显式 {@code activeTexture(GL_TEXTURE0) + bindTexture(id)}（万一 location 取不到，uniform 默认就是 0，
     * 这样也能采到）；③{@code setShaderTexture} 记账（让读 {@code RenderSystem} 状态的代码看到一致的值）。
     */
    private static void bindTextureForShader(ShaderInstance shader, int textureId) {
        if (textureId == -1) {
            return;
        }
        if (shader != null) {
            try {
                shader.setSampler("Sampler0", textureId);
            } catch (Throwable ignored) {
                // 采样器名不在 JSON 里 / 程序没 link 好：走下面的显式绑定，仍然可能画出正确结果
            }
        }
        RenderSystem.activeTexture(33984);          // GL_TEXTURE0
        RenderSystem.bindTexture(textureId);
        RenderSystem.setShaderTexture(0, textureId);
    }

    // ==========================================================================================
    // RenderType
    // ==========================================================================================

    private static final RenderStateShard.ShaderStateShard LENS_SHADER_STATE =
            new RenderStateShard.ShaderStateShard(BlackHoleLensRenderer::lensShaderForState);
    private static final RenderStateShard.ShaderStateShard QUAD_SHADER_STATE =
            new RenderStateShard.ShaderStateShard(GameRenderer::getPositionTexColorShader);

    private static final RenderStateShard.TransparencyStateShard NO_BLEND =
            new RenderStateShard.TransparencyStateShard("shanhai_bh_opaque",
                    RenderSystem::disableBlend, () -> { });

    private static final RenderStateShard.TransparencyStateShard ALPHA_BLEND =
            new RenderStateShard.TransparencyStateShard("shanhai_bh_alpha",
                    () -> {
                        RenderSystem.enableBlend();
                        RenderSystem.defaultBlendFunc();
                    },
                    () -> {
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                    });

    private static ShaderInstance lensShaderForState() {
        final ShaderInstance sh = lensShader;
        if (sh == null) {
            throw new IllegalStateException("blackhole lens shader not loaded");
        }
        return sh;
    }

    /**
     * 一级的 RenderType：不剔除、<b>不写深度</b>、不混合（片元整块替换像素）。
     *
     * <p>⚠️ 这里的 "不混合" 还有第二道保险：{@code blackhole.json} 里<b>没有</b> {@code blend} 段，
     * 而 {@code ShaderInstance} 对缺失 blend 的处理是 {@code new BlendMode()}
     * ⇒ {@code apply()} 里会 {@code disableBlend()}。两道都写了，是因为它们在不同时机生效。
     *
     * <h2>🔴 两个版本（{@code depth=ok} / {@code depth=none}）</h2>
     * <ul>
     *   <li>{@code lequal}（{@code depth=ok}）：光影关着时，主目标的深度<b>就是本帧世界</b>
     *       ⇒ 挡在黑洞前面的机器/墙会逐像素挡住它（严格正确）；</li>
     *   <li>{@code always}（{@code depth=none}）：光影生效时，Iris 把世界画进自己的缓冲、
     *       最后整块合成到主目标，<b>主目标的深度不是本帧世界</b>（多半还是开帧时的 clear 值 1.0）。
     *       这时若继续用 {@code lequal}，比较实际上恒通过 —— 看着"能用"，但它<b>并不是遮挡</b>，
     *       只是恰好没被挡住。所以这里<b>显式换成 {@code always}</b>，日志里如实写 {@code depth=none}：
     *       用户口径是「<b>不要为此伪造遮挡</b>」⇒ 我们承认这一档是"无遮挡透镜版"。</li>
     * </ul>
     */
    private static RenderType lensType(boolean depthOk) {
        final RenderType cached = depthOk ? lensType : lensTypeNoDepth;
        if (cached != null) {
            return cached;
        }
        final RenderType made = RenderType.create(
                depthOk ? "shanhai_blackhole_lens" : "shanhai_blackhole_lens_nodepth",
                DefaultVertexFormat.POSITION,
                VertexFormat.Mode.QUADS,
                256,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(LENS_SHADER_STATE)
                        .setTransparencyState(NO_BLEND)
                        .setDepthTestState(depthOk
                                ? new RenderStateShard.DepthTestStateShard("lequal", GL_LEQUAL)
                                : new RenderStateShard.DepthTestStateShard("always", GL_ALWAYS))
                        .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, false))
                        .setCullState(new RenderStateShard.CullStateShard(false))
                        .createCompositeState(false));
        if (depthOk) {
            lensType = made;
        } else {
            lensTypeNoDepth = made;
        }
        return made;
    }

    /**
     * 二级的 RenderType：<b>只用原版 shader</b>（{@code position_tex_color}），alpha 混合、不写深度、不剔除。
     *
     * <p>🔴 2026-10-25：加了 {@code TextureStateShard}（之前没有贴图状态 ⇒ 采样器绑的是上一个渲染件留下的东西）。
     * 贴图本身由 {@link #ensureFallbackTexture} 通过 {@code TextureManager} 注册，这里只引用它的 {@code ResourceLocation}。
     */
    private static RenderType quadType(ResourceLocation texture) {
        if (quadType == null) {
            quadType = RenderType.create(
                    "shanhai_blackhole_fallback_quad",
                    DefaultVertexFormat.POSITION_TEX_COLOR,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(QUAD_SHADER_STATE)
                            .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                            .setTransparencyState(ALPHA_BLEND)
                            .setDepthTestState(new RenderStateShard.DepthTestStateShard("lequal", GL_LEQUAL))
                            .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, false))
                            .setCullState(new RenderStateShard.CullStateShard(false))
                            .createCompositeState(false));
        }
        return quadType;
    }

    /**
     * 当前降级级别（{@link BlackHoleFallbackGuard#LEVEL_FULL} / {@code LEVEL_QUAD} / {@code LEVEL_NONE}）。
     *
     * <p>给 {@code hostfx} 的兜底黑洞用：<b>只有 {@code LEVEL_NONE} 时</b>才轮到它顶上，
     * 保证同一帧只有一个地方画黑洞（父代理 2026-10-25 约束 2）。
     */
    public static int levelNow() {
        return guard().level();
    }

    // ==========================================================================================
    // 配置 / 日志 / 环境探测 / 重载
    // ==========================================================================================

    private static BlackHoleFallbackGuard guard() {
        if (guard == null) {
            guard = new BlackHoleFallbackGuard(LOG_SINK);
        }
        return guard;
    }

    private static boolean enabled() {
        ensureTuningInit();
        try {
            return BlackHoleTuning.enabled();
        } catch (Throwable t) {
            return false;                               // 理论上到不了（tuning 不抛）；防御
        }
    }

    /**
     * 把 toml 的默认值灌进 {@link BlackHoleTuning}（<b>只生效一次</b>）。
     *
     * <p>⚠️ 为什么不放构造期/静态初始化：配置在 {@code @Mod} 构造器里才 register，
     * 而本类可能先被 Forge 的事件订阅扫描加载（那时配置还没 load，{@code .get()} 会抛）。
     * ⇒ 放在"第一次真的要画"的位置 + try/catch，失败就下一帧再来（{@code initialized} 仍是 false）。
     */
    private static void ensureTuningInit() {
        if (BlackHoleTuning.isInitialized()) {
            return;
        }
        try {
            BlackHoleTuning.initFromConfig(
                    GuixuClientConfig.blackholeEnabled(),
                    GuixuClientConfig.blackholeScale(),
                    GuixuClientConfig.blackholeStrength());
            GTDishanhaiMod.LOGGER.info("{} tuning_init {} {}", PREFIX, BlackHoleTuning.describe(),
                    BlackHoleTuning.ranges());
        } catch (Throwable ignored) {
            // 配置还没加载 ⇒ 下一帧再试
        }
    }

    /** 这一帧真正用的视界半径（格）= 常数 × 运行期倍率。 */
    public static double rsBlocks() {
        ensureTuningInit();
        return RS_BLOCKS * BlackHoleTuning.scale();
    }

    /**
     * 这一帧的中心：<b>命令覆盖优先</b>，否则 toml；两者都做"整数维 +0.5"补正。
     *
     * <p>补正只在<b>值变化时</b>做一次并打一行日志（{@code center_snapped}），
     * 否则每帧一行会把日志刷爆。
     */
    private static double[] resolveCenter() {
        final double[] override = BlackHoleTuning.centerOverride();
        if (override != null) {
            centerRawCache = "\u0000cmd";               // 让 toml 一侧下次重新解析
            return override;
        }
        final String raw;
        try {
            raw = GuixuClientConfig.blackholeCenter();
        } catch (Throwable t) {
            return null;
        }
        if (!Objects.equals(raw, centerRawCache)) {
            centerRawCache = raw;
            final double[] parsed = BlackHoleDebugSpec.parse(raw);
            if (parsed == null) {
                centerCache = null;
                if (raw != null && !raw.trim().isEmpty() && !centerWarned) {
                    centerWarned = true;
                    GTDishanhaiMod.LOGGER.warn("{} {} value=\"{}\"（[debug] blackhole_center 必须是 \"x y z\" 三个数字）",
                            PREFIX, BlackHoleDebugSpec.REASON_MALFORMED, raw);
                }
            } else {
                final BlackHoleDebugSpec.Snap snap = BlackHoleDebugSpec.snapToCenter(parsed);
                centerCache = snap.coords;
                if (snap.snapped) {
                    GTDishanhaiMod.LOGGER.info(
                            "{} center_snapped src=toml dx={} dy={} dz={} from={} to={} note=整数坐标按方块中心+0.5",
                            PREFIX, f1(snap.dx), f1(snap.dy), f1(snap.dz),
                            BlackHoleDebugSpec.format(parsed), BlackHoleDebugSpec.format(snap.coords));
                }
            }
        }
        return centerCache;
    }

    private static void noteBoot(Frame f, boolean shaderpack, String path) {
        if (bootLogged) {
            return;
        }
        bootLogged = true;
        GTDishanhaiMod.LOGGER.info("{} boot level={} center={} dist={} screenR={} rs={} stage={} path={} "
                        + "shaderpack={} depth={} iris={}",
                PREFIX, guard().level(), BlackHoleDebugSpec.format(f.center), f1(f.dist),
                f1(f.screenR), f1(f.rsBlocks), STAGE_GAME_RENDERER, path, shaderpack,
                shaderpack ? "none" : "ok", BlackHoleIris.describe());
    }

    private static void noteIdle() {
        if (idleLogged) {
            return;
        }
        idleLogged = true;
        GTDishanhaiMod.LOGGER.info("{} idle reason={}（在 config/gt_shanhai/guixu-client.toml 的 [debug] blackhole_center "
                + "填 \"x y z\" 即可出图；本件默认什么都不画）", PREFIX, BlackHoleDebugSpec.REASON_EMPTY);
    }

    /**
     * 这一帧<b>真的走了哪条路</b>（{@link #PATH_LENS_AFTER_COMPOSITE} / {@link #PATH_FALLBACK_QUAD} / …）。
     *
     * <p>🔴 2026-10-24 傍晚用户口径：「<b>这一行是他以后唯一的判据，不能骗人</b>」。
     * 旧日志里的 {@code owner=lens level=1}（由 {@code HostFxBlackHole#logOwner} 打）回答的是
     * "<b>hostfx 有没有接管</b>"，<b>不是</b>"透镜画没画" —— 于是出现"日志说 lens level=1、
     * 实际画的是 fallback_quad_ok"的自相矛盾。真正的答案在这里。
     */
    public static String lastPath() {
        return lastPath;
    }

    /** 光影此刻是否真在生效（转发 {@link BlackHoleIris#pipelineActive()}；只读、不触发重探）。 */
    public static boolean shaderpackNow() {
        return BlackHoleIris.modLoaded() && BlackHoleIris.pipelineActive();
    }

    /** 这一帧的深度缓冲是不是"本帧世界"（{@code false} ⇒ 无遮挡透镜版）。 */
    public static boolean depthUsable() {
        return !shaderpackNow();
    }

    /** 资源重载后调用：作废 shader / 拷贝自检缓存（{@link BlackHoleReloadHook} 转调）。 */
    public static void onResourceReload() {
        closeShaderQuietly();
        shaderTried = false;
        copyVerified = false;
        lensOnLogged = false;
        lensEnvLogged = null;
        BlackHoleIris.invalidate();          // Oculus 的 pipeline 也可能被重载掉 ⇒ 立刻重探
        if (guard != null) {
            guard.reset();
        }
    }

    // ==========================================================================================
    // 给客户端命令用的入口（/shanhai bh status|test|reload）
    // ==========================================================================================

    /**
     * {@code /shanhai bh status} 的那一行（也是写进日志的那一行）。
     *
     * <p>把三处读数拼在一起：①兜底状态（{@link BlackHoleFallbackGuard}）、②运行期调参
     * （{@link BlackHoleTuning}）、③最近一帧的几何（{@link BlackHoleFrameStats}）。
     * 格式化本身在 {@link BlackHoleFrameStats#formatStatus} 里（纯函数，可离线逐字比对）。
     */
    public static String statusLine() {
        final BlackHoleFallbackGuard g = guard();
        ensureTuningInit();
        final double[] lastCenter = BlackHoleFrameStats.lastCenter();
        final double[] center = lastCenter != null ? lastCenter : resolveCenter();
        return BlackHoleFrameStats.formatStatus(
                g.level(), g.reason(), BlackHoleTuning.lensMode().id(),
                enabled(), BlackHoleTuning.scale(), BlackHoleTuning.strength(), BlackHoleTuning.disk(),
                BlackHoleFrameStats.lastSkip(), center, rsBlocks(), BlackHoleFrameStats.lastDist(),
                BlackHoleFrameStats.lastRectW(), BlackHoleFrameStats.lastRectH(),
                BlackHoleFrameStats.lastScreenFraction(), BlackHoleFrameStats.lastTanHalfFov(),
                BlackHoleFrameStats.lastShaderpack());
    }

    /** {@code status} 里的 0/1/2 档（= 内部 level − 1）。 */
    public static int statusTier() {
        return Math.max(0, Math.min(2, guard().level() - 1));
    }

    /**
     * {@code /shanhai bh test}：<b>故意</b>让"下一次 shader 首次加载"失败，用来验兜底路径。
     *
     * <p>三步缺一不可：
     * <ol>
     *   <li>立旗（{@link BlackHoleTuning#requestSimulatedFailure()}）—— 由 {@code ensureShader} 消费；</li>
     *   <li><b>关掉已加载的 shader 并把 {@code shaderTried} 清掉</b> —— 否则 {@code ensureShader}
     *       直接返回旧的实例，那条旗永远不会被消费（"敲了 test 但什么都没发生"）；</li>
     *   <li>{@code logRestored() + reset()} —— 让这一级降级<b>重新值得打一行</b>
     *       （否则"同一级别只报一次"会把这次的日志吞掉，test 就白敲了）。</li>
     * </ol>
     */
    public static void requestSimulatedFailure() {
        BlackHoleTuning.requestSimulatedFailure();
        closeShaderQuietly();
        shaderTried = false;
        copyVerified = false;
        lensOnLogged = false;
        lensEnvLogged = null;
        BlackHoleIris.invalidate();
        guard().logRestored();
        guard().reset();
        GTDishanhaiMod.LOGGER.info("{} test_requested note=下一帧会走「shader 加载失败」那条路（二级兜底）", PREFIX);
    }

    /** {@code /shanhai bh reload}：作废 shader 缓存 + 清掉待跑的模拟失败 + 允许重试一级。 */
    public static void reloadShaders() {
        BlackHoleTuning.consumeSimulatedFailure();
        closeShaderQuietly();
        shaderTried = false;
        copyVerified = false;
        lensOnLogged = false;
        lensEnvLogged = null;
        BlackHoleIris.invalidate();
        guard().logRestored();
        guard().reset();
        GTDishanhaiMod.LOGGER.info("{} reload_requested note=下一帧重新加载 shader（可重试一级）", PREFIX);
    }

    private static void closeShaderQuietly() {
        try {
            if (lensShader != null) {
                lensShader.close();
            }
        } catch (Throwable ignored) {
            // 关不掉也不影响：重新 new 一个
        }
        lensShader = null;
    }

    private static String f1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /**
     * 这一帧真正喂给着色器的 {@code uStrength} = 运行期 strength × {@link BlackHoleLensSpec#DEFLECT_GAIN}。
     *
     * <p>为什么要乘：{@code blackhole_strength} 的默认值已经落在用户的 {@code shanhai-client.toml} 里（1.0），
     * 改默认值对他不生效；乘在代码里才会立刻体现。{@code /shanhai bh strength 2} 仍在这个 0.42 之上再放大。
     */
    static double deflectStrength() {
        return BlackHoleTuning.strength() * BlackHoleLensSpec.DEFLECT_GAIN;
    }

    private static String f2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /**
     * 把一帧读数翻成 {@link BlackHoleShaderProbe} 的输入（<b>与给着色器的 uniform 逐项同值</b>）。
     *
     * <p>为什么不让 parts 直接猜：{@code uBhView} 那次事故（世界空间 vs 相机空间）正是"猜"出来的。
     * 这里每一项都取"真的喂进去的那个值"，所以 parts 与画面同源。
     */
    private static BlackHoleShaderProbe.Params paramsOf(Frame f) {
        final float[] n = diskNormalView(f);
        return new BlackHoleShaderProbe.Params(
                f.proj, f.bhView, n, f.screenW, f.screenH,
                f.rectX0, f.rectY0, f.rectW, f.rectH, f.rectHalf,
                f.holeScreen[0], f.holeScreen[1], f.rsBlocks, f.tanHalfFov,
                deflectStrength(),
                DISK_GAIN * BlackHoleTuning.disk(), RING_GAIN, f.renderSeconds);
    }

    /** 盘法线在相机空间里的方向（{@code drawFullLens} 与 parts 共用，避免两套账）。 */
    private static float[] diskNormalView(Frame f) {
        final float[] out = new float[3];
        BlackHoleLensMath.toView(f.view, new double[]{WORLD_DISK_NORMAL[0], WORLD_DISK_NORMAL[1],
                WORLD_DISK_NORMAL[2]}, new double[]{0.0D, 0.0D, 0.0D}, out);
        final double l = Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]);
        if (!(l > 1.0e-9D)) {
            return new float[]{0.0F, 1.0F, 0.0F};
        }
        return new float[]{(float) (out[0] / l), (float) (out[1] / l), (float) (out[2] / l)};
    }

    /**
     * 黑洞中心那个像素上的碰撞参数 {@code bn = b / Rs}（相机空间口径）。
     *
     * <p>自检用：中心处必须 {@code bn < B_CRIT}（否则连黑盘都画不出来）。
     * 2026-10-24 深夜那次事故里它是几十（因为 uBhView 给了世界空间差值），一眼就该报错。
     */
    static double centerBn(Frame f) {
        // 🔴 这是一条**真检查**（不是恒等式）：算的是"黑洞中心那个像素的射线"的碰撞参数。
        //    · 正确（bhView = 相机空间、holeScreen 由同一个 bhView 投影）⇒ bn ≈ 0 < B_CRIT（通过）；
        //    · 错误（bhView 被喂成世界空间差值 —— 2026-10-24 深夜那个 bug）⇒ 射线与 bh 差着一整个
        //      相机旋转角 ⇒ bn 大到几十 ⇒ 当场报红并降二级。
        //    ⚠️ 千万别写成 `tca = len`（那样 b 恒 0、永远绿）—— 那正是"检查器自己的假设错了"。
        //    数学本体在 BlackHoleShaderProbe.bnAtPixel（离线探针 PartsCheck 用的也是它）。
        return BlackHoleShaderProbe.bnAtHoleCenter(paramsOf(f));
    }

    private static String f0(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }
}
