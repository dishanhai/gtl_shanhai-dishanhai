package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.config.GuixuClientConfig;
import com.dishanhai.gt_shanhai.client.renderer.hostfx.HostFxGlassArDeferred;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuVisionCoreMachine;
import com.gregtechceu.gtceu.api.machine.MetaMachine;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.FloatBuffer;
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
     * ⇒ 基本停繪條件：没有中心（{@code center_empty}）／总开关关闭
     * （{@code enabled_off}）／shader 失败降级到三级（{@code dead_level3} / {@code quad_failed}）。
     * 另外"相机背后 / 视锥外 / 整块在画面外"这种<b>几何上无意义</b>的情况仍然不画（那不是"太远"）。
     * 主機來源另須保持世界、區塊與成型有效，並位於目前的區塊渲染距離內；手動預覽不套用此限制。
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

    /**
     * 洞内路径拷下来的那块背景的边长上限（像素）。
     *
     * <p>洞内要把<b>整屏</b>的世界压进窗口里，所以拷贝区域是"屏幕中心的正方形"：
     * {@code srcSide = min(屏宽, 屏高, 这个值)}。为什么是正方形而不是整屏：
     * 窗口在屏幕上是<b>圆</b>的、而"源圆"就是屏幕的内接圆（半径 = 半高），
     * 只有正方区域能同时装下那个圆（宽银幕左右两边本来也进不了圆）。
     * <p>上限取 {@link #MAX_TEX_SIDE}（2048）：4K 屏上取内接圆（丢一点边角），
     * 而不是为了一次洞内视角去分配 26 MB 以上的贴图。
     */
    public static final int INSIDE_MAX_SRC_SIDE = MAX_TEX_SIDE;

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
    // ---- 采样侧深度门（2026-10-25）用到的 GL 常量 ----
    /** 深度纹理的内部格式 / 格式（{@code glCopyTexSubImage2D} 从深度缓冲灌进来）。 */
    private static final int GL_DEPTH_COMPONENT24 = 33190;
    private static final int GL_DEPTH_COMPONENT = 6402;
    private static final int GL_FLOAT = 5126;
    /**
     * 🔴 2026-10-25 修 bug：{@code GL_DEPTH_COMPONENT24} 的 <b>唯一</b>合法 type 是它。
     *
     * <p>GL 4.6 规范表 8.2（valid combinations of format, type and sized internal format）里
     * {@code DEPTH_COMPONENT24} × {@code DEPTH_COMPONENT} 只允许 {@code UNSIGNED_INT}；
     * {@code FLOAT} 只对 {@code DEPTH_COMPONENT32F} 合法。改前给的是 {@code GL_FLOAT}
     * ⇒ 分配那一步就是 {@code INVALID_OPERATION}（贴图没有存储），而 GL 错误<b>不会抛异常</b>，
     * 日志里只看到"拷贝没报错"⇒ 采样门读到恒 0 ⇒ 把所有像素都判成前景 ⇒ <b>一点都不弯折</b>
     * （就是用户这次报的那句「连黑洞后面的方块也不影响了」）。
     */
    private static final int GL_UNSIGNED_INT = 5125;
    private static final int GL_NEAREST = 9728;
    /** 实测 GL 深度态用的三个枚举（{@code glIsEnabled} / {@code glGetInteger} / {@code glGetBoolean}）。 */
    private static final int GL_DEPTH_TEST = 2929;
    private static final int GL_DEPTH_FUNC = 2932;
    private static final int GL_DEPTH_WRITEMASK = 2930;
    /** 探针②用：此刻绑定的读/写 FBO（0 = 默认帧缓冲）。{@code glCopyTexSubImage2D} 读的是 READ 那个。 */
    private static final int GL_READ_FRAMEBUFFER_BINDING = 36008;
    private static final int GL_DRAW_FRAMEBUFFER_BINDING = 36006;
    /**
     * 探针①用：当前 program（35725，GL 2.0 的枚举，{@code GL11} 里没有它，所以自己写一份）。
     * 只读，不改状态。
     */
    private static final int GL_CURRENT_PROGRAM = 35725;

    private static final ResourceLocation SHADER_ID = new ResourceLocation("gt_shanhai", "guixu_blackhole");

    /**
     * 必需 uniform：任何一个在链接后的程序里找不到 ⇒ 当场降二级（宁可画贴图，也别画出一坨错的）。
     *
     * <p>🔴 2026-10-25 第四改：{@code uHoleDepth} <b>已从这里移出</b>，进了
     * {@link BlackHoleLensSpec#OPTIONAL_UNIFORMS}（可选）。原因见那份名单的 javadoc：
     * <b>「着色器里没人读它」和「着色器坏了」是两件事</b> —— 前者是 GLSL 的正常优化结果
     * （只声明不读的 uniform 会被整条删掉，{@code getUniform} 返回 {@code null}），
     * 把它当成"着色器坏了"的后果是<b>整条一级透镜永久降二级静态贴图</b>，
     * 而实机那次（用户「现在引力透镜完全不显示了」）正是这么发生的。
     * 现在：可选集合里的项取不到 ⇒ 只 INFO 一行，<b>不降级</b>；不在集合里 ⇒ 仍是硬失败。
     *
     * <p>⚠️ 与 {@link BlackHoleLensSpec#OPTIONAL_UNIFORMS} 合起来才等于 {@code blackhole.json}
     * 的 uniforms 全表（离线断言 {@code temp/bh-gate2/check-uniforms.ps1} 的 G1/G2 两条守着这个等式）。
     */
    private static final String[] REQUIRED_UNIFORMS = {
            "uProjMat", "uBhView", "uDiskNormal", "uScreenSize", "uBgRect", "uBgTexel",
            "uHolePx", "uLensRadiusPx", "uRs", "uTanHalfFov", "uAspect", "uTime",
            "uDiskGain", "uRingGain", "uStrength",
            // 🔴 2026-10-25：采样侧深度门（"只有黑洞后面的东西才参与弯折"）。缺了就等于门不存在，
            //   而"门不存在"正是用户报的那个 bug ⇒ 必须当成硬失败（降二级画贴图，日志有 reason）。
            //   uGateEps 是门的新容差（本工程有先例：JSON 里缺一个 uniform 不抛异常、也不生效）。
            "uDepthGate", "uGateEps"
    };

    /**
     * 可选 uniform（唯一来源 = {@link BlackHoleLensSpec#OPTIONAL_UNIFORMS}）。
     * 取不到 ⇒ 只 {@code INFO} 一行 {@code lens_uniform_optional_missing}，<b>不降级</b>。
     */
    private static final String[] OPTIONAL_UNIFORMS =
            BlackHoleLensSpec.OPTIONAL_UNIFORMS.toArray(new String[0]);

    /**
     * 「可选 uniform 缺失」这一行<b>一面会话只打一次</b>（按名字去重）。
     * 用集合而不是单个布尔：以后登记第二项时各自只报一次，不会互相吞掉。
     */
    private static final java.util.Set<String> OPTIONAL_MISSING_LOGGED = new java.util.HashSet<>();

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

    // ------------------------------------------------------------------------------------------
    // 🔴 2026-10-25 采样侧深度门（"只有黑洞后面的东西才参与弯折"）的资源与状态
    // ------------------------------------------------------------------------------------------

    /**
     * 深度拷贝贴图（{@code GL_DEPTH_COMPONENT24}，边长与 {@link #bgTexId} 相同 ⇒ 两者共用同一套 uv）。
     *
     * <p>为什么要单独拷一份：着色器不能直接采样"正在写的那个帧缓冲"的深度纹理
     * （那是 GL 的 feedback loop，未定义行为）。颜色已经是这么绕开的（{@link #bgTexId}），
     * 深度用<b>同一个 API</b>（{@code glCopyTexSubImage2D}；目标纹理是深度格式时源就是深度缓冲）。
     */
    private static int depthTexId = -1;
    /** 深度贴图边长；与 {@link #bgTexSide} 不一致时要重建（背景贴图会长大）。 */
    private static int depthTexSide;
    /** 第一次拷贝后做一次"排干历史错误 → 再拷 → 只认这次 glGetError"的实测（一次性）。 */
    private static boolean depthCopyVerified;
    /** 实测失败 ⇒ 永久关闭采样门（绝不拿脏深度把整块画面涂掉）。 */
    private static boolean depthGateDisabled;
    /** {@code depth_copy_ok} / {@code depth_tex_failed} 只各打一次。 */
    private static boolean depthTexLogged;
    /** {@code depth_tex_alloc gl=…} 只打一次（分配自检）。 */
    private static boolean depthAllocLogged;
    // ------------------------------------------------------------------------------------------
    // 🔴 2026-10-25：深度贴图【实测回读】探针（一面会话只做一次）
    //   为什么必须有它：`depth_copy_ok` 只证明"拷完 glGetError==0"，不证明"贴图里真有数"。
    //   实测日志就是这种形态：depth_copy_ok 而背景一点都不弯 ⇒ 唯一能分开
    //   「拷不进去」与「拷进去了但门判错」的证据就是"把贴图里的数读出来看一眼"。
    // ------------------------------------------------------------------------------------------
    private static boolean depthProbeDone;
    /** {@code depth_probe} 的结论：{@code ok} / {@code empty} / {@code failed} / {@code none}（还没跑）。 */
    private static String depthProbeVerdict = "none";
    /** 回读到的深度最小值/最大值（{@code verdict=none} 时是 {@link Float#NaN}）。 */
    private static float depthProbeMin = Float.NaN;
    private static float depthProbeMax = Float.NaN;
    /** {@code lens_gate} 那一行的节流（毫秒）与上一次的状态签名（变了就立刻打）。 */
    private static long lastLensGateLogMs;
    private static String lensGateTag;

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

    // ------------------------------------------------------------------------------------------
    // 🔴 2026-10-25 传送 bug 修复引入的状态（见报告 temp\bh-inside\报告-传送后位置错乱.md）
    // ------------------------------------------------------------------------------------------

    /**
     * {@code bh_view_bad} <b>连续</b>多少帧才认定"uBhView 口径真的错了"。
     *
     * <p>改前是"一帧超标就降级"，而 {@code degradeTo} 只降不升 ⇒ 一次瞬移/一次走动 bob 就把整局钉死。
     * 实机日志：{@code fallback=2 reason=bh_view_bad:bn_3.01}（{@code bhView=-29.65,-274.78,-629.52
     * dist=687.5}）之后 450+ 行读数全是 {@code path=fallback-quad}，再没回到一级。
     * <p>为什么是 30 帧（≈0.5 s）：真口径错（uBhView 给成世界空间差值那次）会<b>持续</b>超标，
     * 30 帧内一定抓得到；而瞬移那一帧、走动 bob、nausea 这类"一两帧的投影不一致"会被放过。
     */
    public static final int BH_VIEW_BAD_FRAMES = 30;

    /**
     * 渲染帧号：{@code AFTER_WEATHER} 每来一次 +1，<b>无论 capture 成功与否</b>。
     *
     * <p>用途只有一个：判断 {@link #pending} 是不是<b>本帧</b>采的。改前用 50 ms 墙钟当新鲜度，
     * 而 {@code capture()} 返回 null 时 {@code pending} <b>没被清</b> ⇒ 上一帧的
     * 相机世界坐标 / 投影矩阵 / 屏幕尺寸会被当帧拿去画。
     */
    private static int renderFrameSerial;
    /** {@link #pending} 是第几帧采的（{@code -1} = 没有待取读数）。 */
    private static int pendingSerial = -1;

    /** 相机瞬移 / 换维度检测（纯逻辑，{@link BlackHoleCameraGuard}）。 */
    private static final BlackHoleCameraGuard CAMERA_GUARD = new BlackHoleCameraGuard();
    /** 降级之后"试探性恢复一级"的退避节流（纯逻辑，{@link BlackHoleLevelRetry}）。 */
    private static final BlackHoleLevelRetry LEVEL_RETRY = new BlackHoleLevelRetry();

    /** {@code screenR} 被硬上限夹之前的原值（只进日志；没夹时为 {@code NaN}）。 */
    private static double screenRRaw = Double.NaN;

    /** {@code bh_view_bad} 连续超标帧数。 */
    private static int bhViewBadFrames;
    /** 这一串 {@code bh_view_bad} 已经打过 WARN（避免每帧一行）。 */
    private static boolean bhViewBadWarned;
    /** 二级四边形上一次为什么没画（进日志，避免"什么都不说"）。 */
    private static String lastQuadSkip = "none";
    /** {@code fallback_quad_ok} / {@code fallback_quad_pos} 只各打一次。 */
    private static boolean fallbackPosLogged;

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
        /**
         * 🔴 黑洞<b>中心平面</b>的窗口深度（0..1）= {@code (ndcZ+1)/2}。
         *
         * <p>它是"采样侧深度门"的唯一阈值：{@code 采样点深度 < 这个值} ⇒ 那个点比黑洞更近
         * ⇒ 它是<b>前景</b>，不许被当作弯折后的背景采样（用户口径：「只有黑洞后面的物体才参与弯折」）。
         * <p>为什么用"中心平面"而不是"洞面"（中心 − {@code B_CRIT·rs} 格）：写入门（quad 的 z）
         * 从 2026-10-24 起就是中心平面、实机验过"挡在洞前面的方块能挡住黑洞"；
         * 采样门与写入门取<b>同一个平面</b>，口径只有一个 —— 否则会出现"写入门放行、采样门拒绝"
         * 这种自相矛盾的帧（离奇偶现的闪烁就是这么来的）。
         */
        double holeDepthWin;
        /**
         * 🔴 2026-10-25：沿<b>相机视线轴</b>走 1 格，窗口深度变多少（窗口深度单位 / 格）。
         *
         * <p>为什么只需相机空间 z：标准透视投影里 {@code ndcZ = (A·z + B) / (C·z + D)}，
         * {@code A..D} 只出现在 proj 的第 3/4 行（{@code proj[10]/proj[14]/proj[11]/proj[15]}），
         * x/y 只进第 1/2 行 ⇒ <b>ndcZ 与 x/y 无关</b>，只由相机空间 z 决定。
         * 所以"洞心往前/往后 1 格"就是 {@code z ∓ 1}，不用碰方向向量。
         */
        double depthEpsPerBlock;
        /**
         * 采样门的容差（窗口深度单位）：{@code max(GATE_EPS_MIN, 0.75 × depthEpsPerBlock)}。
         *
         * <p>🔴 2026-10-25 再改（用户实测：「引力透镜现在又会扭曲它前面的物体了」）：
         * {@code GATE_EPS_MIN} 由 {@code 2e-4} 降到 {@code 1e-5}。
         * <p>为什么必须降：容差是<b>窗口深度</b>单位，洞越近 1 格折算的窗口深度越大 ——
         * {@code 2e-4} 在洞心 55 格外 ≈ <b>12.3 格</b>，等于把门放宽到"采样点必须比本像素
         * 更近 12 格以上才算前景"；而前景（环带 / 光束 / AR 玻璃）与背景的深度差常常不到
         * 12 格 ⇒ <b>门基本不生效</b>，前景颜色又被搬到别处（＝用户这次报的现象）。
         * 现在 {@code 1e-5} 在 55 格处只 ≈ <b>0.62 格</b>，而"按格算"那一项（0.75 格）
         * 反而更大 ⇒ 近处由物理项胜出（日志 {@code gateEpsSrc=blocks}），门回到"差大半格就拦"。
         * <p>⚠️ 容差仍是"窗口深度"单位、<b>不是</b>格；同样容差在远处折算的格数更多
         * （200 格处 {@code 1e-5} ≈ 8 格，改前是 ≈160 格）。所以每帧都要看
         * {@code gateEps=} / {@code gateEpsBlocks=}（按<b>当前</b>洞距现算，不是常量）
         * 与 {@code gateEpsSrc=}（本帧哪一项胜出），见 {@code lens_gate} 日志行。
         * <p>这一项只影响"鬼影压得多干净"，<b>不会</b>影响"背景弯不弯"
         * （判据是逐像素自比，见 blackhole.fsh ④b）。
         */
        double gateEps;
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
        /**
         * 🔴 这一帧<b>真正拷进贴图</b>的那块整数矩形（{@code copyBackground} 里算出来的
         * {@code x/y/w/h}），着色器的 {@code uBgRect} 必须用<b>它</b>而不是 {@link #rectX0} 等浮点值。
         *
         * <p>为什么：{@code glCopyTexSubImage2D} 的源起点被 {@code floor} 过，而 {@code rectX0}
         * 是带小数的（像素预算按比例缩过）。改前 {@code uBgRect} 给的是浮点值 ⇒
         * {@code uvSelf = (px − 466.7)·texel} 与贴图里第 i 个 texel 的<b>中心</b>差最多 1 个 texel，
         * 于是"warp=0 时逐像素等于原画面"这条无缝性其实是<b>不准</b>的（差半像素 + GL_LINEAR 采样）。
         * 现在两者取同一组整数 ⇒ 无弯折区与已画好的背景<b>逐像素相同</b>，
         * 采样侧深度门"退回自采样"才不会在边界上留下一条半像素的缝。
         */
        int copyX0;
        int copyY0;
        int copyW;
        int copyH;
        int screenW;
        int screenH;
        float renderSeconds;
        long stampNanos;
        /** 这一帧的 parts 位掩码（见 {@link BlackHoleShaderProbe#parts}；1 秒重算一次）。 */
        int partsSerial;
        /**
         * 🔴 相机在<b>洞里</b>吗（2026-10-25 用户：「飞到黑洞里面、往外看的时候，透镜不渲染了」）。
         *
         * <p>判据：{@code dist ≤ }{@link BlackHoleLensSpec#insideThresholdBlocks}（＝可见黑盘半径的
         * {@value BlackHoleLensSpec#INSIDE_FACTOR} 倍与"+{@value BlackHoleLensSpec#INSIDE_MARGIN_BLOCKS} 格"
         * 两者取大）。命中时走 {@link #drawInside} 的"洞内视角"，<b>不再</b>走屏幕空间透镜
         * （透镜那套几何假设"洞是一个在相机前方的点"，洞内这个前提根本不成立）。
         * <p>🔴 2026-10-25 傍晚：改前倍率是 {@code 1.02}，实机阈值 5.30 格而玩家最近只到 5.3 格
         * ⇒ <b>一次都没进过这个分支</b>（日志 {@code inside=true} 0 行）。现在倍率 1.6 ⇒ 8.31 格。
         */
        boolean inside;
        /** 这一帧真正用的洞内阈值（格）—— 进日志的 {@code insideThr=}，与 {@link #inside} 同源。 */
        double insideThr;
        /** 洞内时：可见黑盘半径（格）。 */
        double insideShadowR;
        /** 洞内时：{@code dist / insideShadowR}（0 = 正在球心，1 = 贴在球面上）—— <b>只进日志</b>。 */
        double insideRatio;
        /**
         * 洞内时：<b>吸积盘法线在相机空间的方向</b>（已归一化）。
         *
         * <p>只用来算"赤道方向"在屏幕上那条迹线（{@code a·u + b·v = c}），进而给金带权重。
         * <p>🔴 这是一个<b>独立字段</b>：改前它被塞在 {@link #bhView} 里（那个字段在洞外的语义是
         * "洞心在相机空间的位置"），两种语义共用一个字段 ⇒ 下一个人一定会读错。
         */
        float[] insideDiskN;
        /**
         * 🔴 洞内时：<b>进洞多深</b>，{@code clamp01(1 − dist / insideThr)}。
         * <ul>
         *   <li>{@code 0} = 正好在洞内判据的边界上（{@code dist = insideThr}）；</li>
         *   <li>{@code 1} = 相机正好在球心（{@code dist = 0}）。</li>
         * </ul>
         * 洞内这几件（窗口半径 / 拖影强度 / 色散 / 金带宽度 / 红移）<b>全部由它驱动</b>，
         * 口径见 {@link BlackHoleInsideSpec#windowRadius} 等纯函数。为什么不用 {@link #insideRatio}：
         * 那个量在 {@code dist > 可见黑盘半径} 时会饱和（dist=7.0 与 4.5 都 ≥5.196 ⇒ 同一个数）
         * —— 那正是用户打回第一版图时说的"四格看起来一模一样"的来源之一。这个量在整个洞内区间里严格单调。
         */
        double insideDepth;

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
        try {
            afterWeather(event);
        } finally {
            // 🔴 2026-10-25 第二十二改（退路）：mixin 没装上时，透镜就是在本档画的
            //   ⇒ "透镜之后" = 本方法出口，AR 在这里补提交。
            //   ⚠️ mixin 在线时【不许】在这里 flush：那一刻透镜还没画，提前提交就又回到
            //   "AR 被透镜弯折 + 遮罩盖不住黑洞"的老问题（用户 2026-10-25 的两条原话）。
            //   正常装机的 flush 在 {@link #drawAfterComposite} 的 finally 里。
            if (!mixinSeen) {
                HostFxGlassArDeferred.flushAfterLens();
            }
        }
    }

    /** {@link #onRenderLevelStage} 的本体（抽出来只为了能套上面那个 {@code finally}）。 */
    private static void afterWeather(RenderLevelStageEvent event) {
        renderFrameSerial++;                            // 本帧的唯一编号（capture 失败也 +1）
        noteCameraRebase();                             // 瞬移 / 换维度 ⇒ 作废跨帧相机缓存（+ 一行日志）
        final Frame f = capture(event);
        if (f == null) {
            clearPending();                             // 🔴 改前这里不清：上一帧的相机/投影/矩形会被当帧用
            return;                                     // capture 里已经 noteSkip（原因见 lastSkip）
        }
        pending = f;
        pendingSerial = renderFrameSerial;
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
        // 🔴 2026-10-25 第二十二改：AR 玻璃面板 + 灰色遮罩必须画在【透镜之后】。
        //    本方法的 finally 就是那个"之后"：无论这一帧透镜画了没有、走了哪条 return，
        //    出去之前都把 TESR 里捕获的 AR 提交一遍（{@link HostFxGlassArDeferred}）。
        //    为什么放在这里而不是事件里：三条 mixin 注入最终都收敛到本方法，且它<b>一定</b>
        //    在 `RenderSystem.clear(DEPTH)` 之前 —— 深度缓冲还在，AR 的方块遮挡因此不丢。
        try {
            final Frame f = pending;
            final int fSerial = pendingSerial;
            pending = null;
            pendingSerial = -1;
            if (f == null) {
                return false;                               // AFTER_WEATHER 那一档就没通过（原因见 lastSkip）
            }
            // 🔴 2026-10-25：新鲜度改由【帧号】判定（墙钟 50 ms 只是第二道）。
            //   改前只用 `System.nanoTime() - f.stampNanos > 50 ms`，而 50 ms 在 30 fps 下差不多是 2 帧；
            //   更糟的是 capture() 返回 null 时 pending 不会被清 ⇒ 那一份"旧相机 + 旧投影 + 旧矩形"
            //   会被当成这一帧的读数画出去（用户报的"传送回来后位置错了"就有这一层）。
            if (fSerial != renderFrameSerial) {
                return false;                               // 不是本帧采的 ⇒ 整份读数作废
            }
            if (System.nanoTime() - f.stampNanos > 40_000_000L) {
                return false;                               // 上一帧的残渣：别拿旧矩阵画新帧
            }
            f.depthPreserved = depthPreserved;
            return renderFrame(guard(), f, PATH_LENS_AFTER_COMPOSITE, stage);
        } finally {
            HostFxGlassArDeferred.flushAfterLens();
        }
    }

    /**
     * 采样本帧读数（不画）。
     *
     * <h2>🔴 闸门总表（洞内 vs 洞外 各走哪几道）—— 任务书第 3 条要求的"逐条列出"</h2>
     * 每一道都在下面代码里用 {@code // 闸门 G<i>_</i>} 标出，编号与本表逐位对应。
     * <b>"洞内"一栏写"跳过"的，代码里就必须在洞内判定<b>之前</b>或<b>之后</b>绕开它</b>；
     * 写"保留"的，两种视角都要过。判据一栏的 {@code SKIP_*} 就是日志/status 里的 {@code lastSkip}。
     *
     * <pre>
     *  编号 位置            判据（失败即 return null）                     洞内处置   理由
     *  ──── ────────────── ──────────────────────────────────────────── ────────── ────────────────────────────
     *  G1   capture 开头   g.isDead()（三级：兜底已放弃）                保留       level 可用性 —— 连兜底都没了
     *  G2   capture 开头   !enabled()（[debug] blackhole_enabled / bh off） 保留    owner 配置（总开关）
     *  G3   capture 开头   resolveCenter() == null（没配中心）            保留       owner 配置（画在哪）
     *  G4   capture 开头   mc == null || mc.level == null                保留       没有世界就没有相机
     *  G5   capture 开头   camera == null || poseStack == null           保留       洞内还要 poseStack 求盘法线
     *  G6   capture 开头   main == null || width<=0 || height<=0         保留       要屏幕尺寸（洞内要 aspect）
     *  G7   capture 中段   !(bhView[2] < −0.05)  洞在相机【前方】        跳过 ★     几何前提：洞内洞在身侧/身后
     *  G8   capture 中段   tanHalfFov ∉ [0.02, 20]（投影坏）            跳过 ★     只是透镜的像素尺度闸门；
     *                                                                              洞内若投影可用就顺手取 tan 给盘带
     *  G9   capture 中段   projectToScreen 返回 false（cw≈0，洞心投影不出） 跳过 ★   几何前提：洞心就在相机原点
     *  G10  capture 中段   holeScreen[2] ∉ (−1,1)（视锥外）              跳过 ★     几何前提（同上）
     *  G11  capture 末段   rect.w < 4px || rect.h < 4px（整块在画面外）   跳过 ★     洞内 rect 直接给全屏
     *  （G12 MIN_LENS_RADIUS_PX 抬升不是闸门：它从不 return，只是把矩形放大到下限）
     *  ──── renderFrame 里还有三道，见 {@link #renderFrame}：
     *  G13  全屏透镜前   lensMode != OFF（用户 /shanhai bh lens off）     洞内不适用  洞内根本不用屏幕空间透镜；
     *                                                                              洞内仍会画（见 renderFrame 注释）
     *  G14  全屏透镜前   g.level() == LEVEL_FULL                         保留        level 可用性
     *  G15  二级之前     !g.isDead()                                     保留        level 可用性
     * </pre>
     *
     * <p>⭐ 一句话：<b>"洞在相机前方 / 洞心能投影到屏幕 / 矩形落在画面里"这三族都是"洞是一个在相机前方的点"
     * 的几何推论 —— 人站在洞里时它们<b>定义上就不成立</b>，必须跳过；而"开关 / owner 配置 / level 可用性"
     * 是用户与降级状态机的契约，无论洞内洞外都得保留。</b>
     *
     * @return null = 这一帧不该画（原因已写进 {@link BlackHoleFrameStats#lastSkip()}）
     */
    private static Frame capture(RenderLevelStageEvent event) {
        final BlackHoleFallbackGuard g = guard();
        if (g.isDead()) {                               // 闸门 G1（洞内保留）：三级 = 兜底已放弃
            BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_DEAD);
            return null;                                // 三级：从此什么都不画
        }
        if (!enabled()) {                               // 闸门 G2（洞内保留）：owner 配置 · 总开关
            BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_DISABLED);
            return null;
        }
        final double[] center = resolveCenter();
        if (center == null) {                           // 闸门 G3（洞内保留）：owner 配置 · 没配中心
            BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_NO_CENTER);
            noteIdle();
            return null;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {           // 闸门 G4（洞内保留）：没有世界
            BlackHoleTuning.clearHostCenter();
            BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_NO_LEVEL);
            return null;
        }
        final Camera camera = event.getCamera();
        final PoseStack poseStack = event.getPoseStack();
        if (camera == null || poseStack == null) {      // 闸门 G5（洞内保留）：洞内也要 poseStack 求盘法线
            BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_NO_LEVEL);
            return null;
        }
        // 山海：獨立透鏡事件仍會在 BER 停止後執行，必須自己驗證主機來源而非等待 BER 清理。
        if (BlackHoleTuning.hasHostCenter()) {
            Vec3 cameraPos = camera.getPosition();
            BlockPos controller = BlockPos.of(BlackHoleTuning.hostCenterControllerPos());
            BlockPos centerPos = BlockPos.containing(center[0], center[1], center[2]);
            boolean available = GuixuClientConfig.hostFxEnabled()
                    && BlackHoleTuning.hostCenterInRange(mc.level, cameraPos.x, cameraPos.z,
                            mc.options.getEffectiveRenderDistance())
                    && mc.level.hasChunkAt(controller) && mc.level.hasChunkAt(centerPos);
            if (available) {
                available = MetaMachine.getMachine(mc.level, controller) instanceof GuixuVisionCoreMachine host
                        && (host.isFormed() || host.isHostFormedSync());
            }
            if (!available) {
                BlackHoleTuning.clearHostCenter();
                BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_NO_CENTER);
                return null;
            }
        }
        final RenderTarget main = mc.getMainRenderTarget();
        if (main == null || main.width <= 0 || main.height <= 0) {   // 闸门 G6（洞内保留）：屏幕尺寸
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
        //     ① `if (!(bhView[2] < -0.05F)) return;`      ⇒ 洞在身后/身侧 ⇒ SKIP_BEHIND      （= 下表 G7）
        //     ② `projectToScreen(...)` 的 `cw > 1e-6` 判据  ⇒ 洞在相机原点附近 ⇒ cw≈0 ⇒ SKIP_OUT_OF_FRUSTUM（G9）
        //   （还有第三处隐患：`pxPerBlock = (H/2)/(tanHalf*forward)` 在 forward→0 时爆掉。）
        //   这三条都不是"用户要的取舍"，而是几何前提不成立 ⇒ 洞内改走"洞内视角"这条路。
        //
        //   🔴 2026-10-25 傍晚：阈值从 1.02 倍抬到 1.6 倍（并且与"+2 格"取大），见
        //   BlackHoleLensSpec.INSIDE_FACTOR 的类注释 —— 改前阈值 5.30 格，实机最近只走到 5.3 格，
        //   判据一次都没成立过。现在 8.31 格。阈值常量只在 BlackHoleLensSpec 里定义，这里绝不另写一遍。
        final double rs0 = rsBlocks();
        final double shadowR = BlackHoleLensMath.B_CRIT * rs0;
        final double insideThr = BlackHoleLensSpec.insideThresholdBlocks(rs0);
        f.rsBlocks = rs0;
        f.insideThr = insideThr;
        if (f.dist <= insideThr) {
            f.inside = true;
            f.insideShadowR = shadowR;
            f.insideRatio = shadowR > 1.0e-6D ? f.dist / shadowR : 0.0D;
            // 进洞多深：0 = 刚跨进判据边界、1 = 正在球心。洞内的窗口半径/拖影/色散/金带宽度全由它驱动
            // （口径只在 BlackHoleInsideSpec 里定义：windowRadius / streakStrength / dispersion / bandSigma）
            f.insideDepth = insideThr > 1.0e-9D ? clamp01(1.0D - f.dist / insideThr) : 0.0D;
            // 洞内不投影洞心（那会退化成除零）。要拷的是"屏幕中心的正方形"：
            //   窗口在屏幕上是圆的 ⇒ 源圆 = 屏幕内接圆（半径 = 半高）⇒ 正方区域刚好装下它。
            //   这个矩形同时就是 glCopyTexSubImage2D 的源矩形（copyBackground 直接用 f.rect*）。
            final int srcSide = Math.max(64, Math.min(Math.min(f.screenW, f.screenH), INSIDE_MAX_SRC_SIDE));
            f.rectW = srcSide;
            f.rectH = srcSide;
            f.rectX0 = Math.floor((f.screenW - srcSide) * 0.5D);
            f.rectY0 = Math.floor((f.screenH - srcSide) * 0.5D);
            f.rectHalf = srcSide * 0.5D;
            f.holeScreen = new double[]{f.screenW * 0.5D, f.screenH * 0.5D, 0.0D};
            // 洞内没有"黑盘在屏幕上的像素半径"这个概念（视界把你整个包住了）⇒ 0，日志里如实写 0.0
            f.screenR = 0.0D;
            f.renderSeconds = (event.getRenderTick() + event.getPartialTick()) / 20.0F;
            f.renderTick = event.getRenderTick();
            f.stampNanos = System.nanoTime();
            // 洞内也要相机基向量（画赤道盘带与视界环）
            final org.joml.Vector3f up0 = camera.getUpVector();
            final org.joml.Vector3f left0 = camera.getLeftVector();
            f.camUp = new float[]{up0.x(), up0.y(), up0.z()};
            f.camLeft = new float[]{left0.x(), left0.y(), left0.z()};
            // 盘法线换成相机空间（洞内算"赤道方向"在屏上那条迹线要用）
            final Matrix4f vm0 = new Matrix4f(poseStack.last().pose());
            final float[] view0 = new float[16];
            vm0.get(view0);
            f.view = view0;
            final float[] diskN0 = new float[3];
            BlackHoleLensMath.toView(view0, new double[]{WORLD_DISK_NORMAL[0], WORLD_DISK_NORMAL[1],
                    WORLD_DISK_NORMAL[2]}, new double[]{0.0D, 0.0D, 0.0D}, diskN0);
            final double nl = Math.sqrt(diskN0[0] * diskN0[0] + diskN0[1] * diskN0[1]
                    + diskN0[2] * diskN0[2]);
            // 🔴 写进 insideDiskN（不是 bhView）：bhView 在洞外的语义是"洞心在相机空间的位置"，
            //   两种语义共用一个字段是 2026-10-24 那次事故的同款形态（读错了一次就是整屏不画）。
            f.insideDiskN = nl > 1.0e-9D
                    ? new float[]{(float) (diskN0[0] / nl), (float) (diskN0[1] / nl), (float) (diskN0[2] / nl)}
                    : new float[]{0.0F, 1.0F, 0.0F};
            // ---- 闸门 G7 / G8 / G9 / G10 / G11 在洞内【全部跳过】（见 capture 的闸门总表）。
            //   G7（洞在身前）、G9（洞心能投影）、G10（洞心在视锥内）、G11（矩形 ≥4px）都是
            //   "洞是一个在相机前方的点"的推论；洞内它们定义上不成立，跳过不是"放宽"，是"换了一套几何"。
            //   G8（tanHalfFov）在洞内【不是闸门】—— 屏幕空间量不需要它。
            //   但"若投影仍然可用"就顺手取来给赤道盘带用（盘带在屏幕上的位置与 tan 有关）；
            //   取不到就用 0.70（= 70° FOV，原版默认值）—— 这是<b>取值</b>而不是<b>闸门</b>，不会吞帧。
            f.proj = new float[16];
            final Matrix4f projM0 = new Matrix4f(event.getProjectionMatrix());
            projM0.get(f.proj);
            final double tan0 = BlackHoleLensMath.tanHalfFovY(f.proj);
            f.tanHalfFov = (Double.isFinite(tan0) && tan0 >= 0.02D && tan0 <= 20.0D)
                    ? tan0 : BlackHoleInsideSpec.DEFAULT_TAN_HALF_FOV;
            return f;
        }

        // ---- 世界 → 相机空间（事件矩阵只含旋转，必须自己减 camPos；见 BlackHoleLensMath §2）
        final Matrix4f viewM = new Matrix4f(poseStack.last().pose());
        f.view = new float[16];
        viewM.get(f.view);
        final float[] bhView = new float[3];
        BlackHoleLensMath.toView(f.view, center, f.camPos, bhView);
        f.bhView = bhView;                              // ← 着色器的 uBhView 直接取它（相机空间）
        if (!(bhView[2] < -0.05F)) {                    // 闸门 G7（洞内跳过）：洞在相机【前方】
            BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_BEHIND);
            return null;                                // 在相机背后 / 贴在镜头上（不是距离闸门）
        }

        final Matrix4f projM = new Matrix4f(event.getProjectionMatrix());
        f.proj = new float[16];
        projM.get(f.proj);
        f.tanHalfFov = BlackHoleLensMath.tanHalfFovY(f.proj);
        if (!(f.tanHalfFov >= 0.02D) || f.tanHalfFov > 20.0D) {     // 闸门 G8（洞内跳过）：投影坏
            BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_BAD_PROJECTION);
            return null;
        }
        f.holeScreen = new double[3];
        // 闸门 G9（洞内跳过）：洞心必须能投影出来（洞内 cw≈0 ⇒ 除零）
        if (!BlackHoleLensMath.projectToScreen(f.proj, bhView, f.screenW, f.screenH, f.holeScreen)) {
            BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_OUT_OF_FRUSTUM);
            return null;
        }
        // 闸门 G10（洞内跳过）：洞心必须落在视锥的深度范围内
        if (!(f.holeScreen[2] > -1.0D) || !(f.holeScreen[2] < 1.0D)) {
            BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_OUT_OF_FRUSTUM);
            return null;                                // 视锥外（渲染层面无意义）
        }
        // 🔴 采样侧深度门的阈值：洞心平面的窗口深度（与写入门同一个平面，口径唯一）
        f.holeDepthWin = f.holeScreen[2] * 0.5D + 0.5D;
        // 🔴 2026-10-25 修正：门的容差不再用固定 1e-6，而是"洞心沿视线轴再远 0.75 格"对应的窗口深度差。
        //   依据：ndcZ 只由相机空间 z 决定（见 Frame#depthEpsPerBlock 的注释），
        //   所以只要把 camera-space z 再减 1 格（更远）投一次，两个窗口深度之差就是"每格的深度分辨率"。
        f.depthEpsPerBlock = Math.abs(windowDepth(f.proj, bhView[2] - 1.0F)
                - windowDepth(f.proj, bhView[2]));
        // 投影退化（NaN）时退回下限：NaN 传给 GPU 会让 `dSamp < dSelf - NaN` 恒 false（门失效但安全），
        // 但"门失效"必须是**显式**的一条读数，不许靠 NaN 悄悄发生。
        f.gateEps = Double.isFinite(f.depthEpsPerBlock)
                ? Math.max(GATE_EPS_MIN, GATE_EPS_BLOCKS * f.depthEpsPerBlock)
                : GATE_EPS_MIN;

        // ---- 🔴 洞心离画面太远 ⇒ 那块矩形只是屏幕边缘的一条"薄片"，画出来就是"右下角那一团光"
        //   判据与取值见 BlackHoleFallbackQuad.ANCHOR_MARGIN_SCREENS 的注释。
        //   为什么在 G10 之后：G10 只判 NDC 深度（ndcZ ∈ (−1,1)），**从来不判 ndcX/ndcY** ——
        //   于是"洞心在画面右下方 6 个屏幕宽以外"这种局面一路过关，最后被 rectAround 夹成
        //   屏幕角上的一条薄片（实机日志 2880×1659 屏：rect=[2650.4,1642.8 138.5x16.2]）。
        //   ⚠️ 这是【洞外专属】闸门：洞内 rect 恒为屏幕中心的正方形，走不到这里。
        if (!BlackHoleFallbackQuad.anchorVisible(f.holeScreen[0], f.holeScreen[1],
                f.screenW, f.screenH)) {
            BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_OUT_OF_FRUSTUM);
            return null;
        }

        f.forwardDist = -bhView[2];
        // 🔴 这一行是"洞外专属"的像素尺度：分母里有 forwardDist ⇒ forward→0 时爆掉。
        //   洞内在上面就已经 return 了（走 drawInside，全程只有屏幕空间量），永远走不到这里。
        final double pxPerBlock = (f.screenH * 0.5D) / (f.tanHalfFov * f.forwardDist);
        f.rsBlocks = rs0;
        final double rsPx = f.rsBlocks * pxPerBlock;
        // 🔴 screenR 硬上限（任务书第 3 条）：min(screenR, 高度 × 2)，超限时把"原值/被夹掉多少"记下来，
        //   由 logFrameReadout 每秒一行写出去（`screenR_raw=` / `screenR_cut=`）。
        //   为什么要夹：screenR 的分母是【前向】距离，洞偏轴 85° 时前向距离只剩 2~3 格 ⇒ 读数能到 1700+ px，
        //   而真正画出去的那块矩形已经被夹到屏幕里了 —— 读数与画面彻底脱钩（实机日志里
        //   screenR=1725.9 与 parts=disk 出现在同一批帧上）。口径见 BlackHoleFallbackQuad.clampScreenR。
        screenRRaw = f.rsBlocks * BlackHoleLensMath.B_CRIT * pxPerBlock;
        f.screenR = BlackHoleFallbackQuad.clampScreenR(screenRRaw, f.screenH);
        final double lensHalfPx = rsPx * LENS_RANGE_RS;
        if (!specLogged) {
            specLogged = true;
            GTDishanhaiMod.LOGGER.info("{} spec {}（改前：rs=1.00格 黑盘半径=2.60格 透镜半径=3.10格 "
                            + "吸积盘=3.00..11.00格 盘/黑盘=4.23x 贴图=256² 洞内阈值=5.30格(×1.02)）",
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
        if (rect.w < 4.0D || rect.h < 4.0D) {        // 闸门 G11（洞内跳过）：整块在画面外
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
        // 🔴 2026-10-25 第二十改：**这一帧真正生效**的透镜档位 —— 主机接管优先（机器字段为准），
        //   没主机时仍是 /shanhai bh lens auto|on|off。这是本文件里【唯一】读透镜档位的地方：
        //   下面的 G13 闸门（allowFull）与日志都吃这一个变量，所以"机器关掉透镜"不会只关一半。
        //   ⚠️ 深度门（depthGateDisabled / depthTexOk / uDepthGate）一个字没动 —— 那是另一条线的改动面。
        final BlackHoleTuning.LensMode lensMode = BlackHoleTuning.effectiveLensMode();
        // 🔴 2026-10-24 傍晚：判定源换成 BlackHoleIris（旧的 getPipeline().isPresent() 恒 true，见那个类 §1）
        final int frame = frameCounter++;
        final boolean shaderpack = BlackHoleIris.update(frame);
        // 深度（两个条件都要）：
        //   ① 光影没生效 —— Iris 生效时它把世界画进自己的缓冲、只把颜色合成回来；
        //   ② 注入点在 vanilla 清深度之前 —— `RenderSystem.clear(256)` 在 renderLevel 返回后、
        //      renderItemInHand 之前跑；选在它之后（例如 TAIL）即使 shaderpack=false 也没有遮挡。
        final boolean depthOk = !shaderpack && f.depthPreserved;
        // 洞内时日志里的 path 口径也必须是洞内那条（否则 lens_mode/boot 行会写 lens-after-composite，
        // 与下面那条 path=inside-overlay 自相矛盾 —— "同一件事两行写法"正是上一轮日志骗人的形态）
        final String pathForLog = f.inside ? PATH_INSIDE : path;
        noteLensEnvironment(g, lensMode, shaderpack, depthOk, pathForLog, stage);
        noteBoot(f, shaderpack, pathForLog);
        warnIfDoubleDrawn(f);

        // ---- 🔴 降级必须可恢复（任务书第 1 条）
        //   改前：BlackHoleFallbackGuard.degradeTo() 【只降不升】（`if (newLevel <= this.level) return false;`），
        //   而唯一能抬回一级的 reset() 只有资源重载 / 两条命令会调 ⇒ 任何一次失败都把这一局钉死。
        //   实机日志证据：整份 latest.log 里只有一行 `fallback=2 reason=bh_view_bad:bn_3.01`，
        //   它之后的 450+ 行读数全是 `path=fallback-quad … level=2`，直到退出游戏都没回到一级。
        //   现在：按 BlackHoleLevelRetry 的退避窗口，把 level 抬回一级【在本帧内】真试一次
        //   （同一帧里若还是失败，renderFrame 下面照样一级→二级立刻顶上，绝不空帧，所以不会闪）。
        //   ⚠️ 必须同时清资源旗标：只 reset guard 是没用的，ensureShader() 见 shaderTried=true 会直接返回 null。
        if (LEVEL_RETRY.tick(g.level())) {
            final int from = g.level();
            g.reset();                                  // level → LEVEL_FULL（三级由 reset 自己拒掉）
            g.logRestored();                            // 清"已报过"的旗标 ⇒ 下次真降级还能再报一行
            shaderTried = false;                        // 允许重新 new ShaderInstance
            copyVerified = false;                       // 允许重新做背景拷贝自检
            bhViewBadFrames = 0;
            bhViewBadWarned = false;
            GTDishanhaiMod.LOGGER.info(
                    LEVEL_RETRY.line(from, BlackHoleFallbackGuard.LEVEL_FULL, "auto_backoff")
                            + " note=降级后按退避窗口重试一级（条件重新满足就回到一级，不再一降到底）");
        }

        // ---- 🔴 洞内：整段换成"洞内视角"（不走屏幕空间透镜，理由见 capture 里的洞内判定）
        //   ⚠️ 这一段<b>在</b> allowFull / level 判定<b>之前</b>：洞内根本没有屏幕空间透镜可用，
        //   所以 `lensMode=OFF`（= 用户要求"背景不扭曲、只看静态"）对洞内不构成"不许画"的理由 ——
        //   洞内这四层<b>一个像素的背景都不采样</b>，谈不上"扭曲背景"。若在这里也判 OFF 而不画，
        //   就等于复现用户报的那个 bug（站在洞里什么都不出）。level 可用性仍在下面对 G14/G15 保留。
        if (f.inside) {
            final boolean drewInside = drawInside(g, f);
            lastPath = drewInside ? PATH_INSIDE : PATH_NONE;
            // 🔴 2026-10-25 修掉"日志骗人"：改前这一行第 8 个实参写的是<b>字面量 `true`</b>
            //   （`logFrameReadout(…, false, false, true, 0)`），于是<b>不管 drawInside 返回什么</b>，
            //   读数行永远打 path=inside-overlay draw=yes —— 实机 12179 行日志里
            //   `inside=true` 24 行、`inside_shader_ok` 0 行、`fallback=2 reason=inside_uniform_missing:uScreenSize` 1 行，
            //   三者拼起来才是真相：<b>洞内一帧都没画</b>，而判据行说"画了"。
            //   现在传真的 drewInside；断在哪一段由 inside_break= 明确写出。
            logFrameReadout(f, PATH_INSIDE, stage, shaderpack, depthOk, false, false, drewInside, 0);
            if (drewInside) {
                BlackHoleFrameStats.noteDraw(false, f.center == null ? f.camPos : f.center,
                        f.rsBlocks <= 0.0D ? BlackHoleLensSpec.RS_BLOCKS_DEFAULT : f.rsBlocks,
                        f.dist, f.rectW, f.rectH,
                        f.screenW, f.screenH, f.tanHalfFov, shaderpack);
            } else {
                BlackHoleFrameStats.noteSkip(BlackHoleFrameStats.SKIP_QUAD_FAILED);
            }
            return drewInside;
        }

        // ON = 用户强制一级；AUTO = 默认（🔴 现在合成之后拿得到本帧背景 ⇒ 光影生效也走一级）；
        // OFF = 用户明确要二级（背景不扭曲）。—— 闸门 G13：只对<b>洞外</b>的屏幕空间透镜生效。
        final boolean allowFull = lensMode != BlackHoleTuning.LensMode.OFF;

        // ---- parts 位掩码：只在与日志同一节流窗口里算（64×64 采样，1 秒一次，可忽略）
        final long nowMs = System.currentTimeMillis();
        if (nowMs - lastFrameLogMs >= FRAME_LOG_MS) {
            f.partsSerial = BlackHoleShaderProbe.parts(paramsOf(f), PARTS_SAMPLES);
        }

        boolean drewFull = false;
        // 闸门 G14（洞内不适用 —— 洞内走的是上面那条分支）：只有一级可用才走全屏透镜
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
        //   闸门 G15（洞内不适用 —— 洞内走的是上面那条分支）：三级时才轮到"彻底不画"。
        boolean drewQuad = false;
        if (!drewFull && !g.isDead()) {
            drewQuad = drawFallbackQuad(g, f);
        }
        lastPath = drewFull ? path : (drewQuad ? PATH_FALLBACK_QUAD : PATH_NONE);
        logFrameReadout(f, path, stage, shaderpack, depthOk, drewFull, drewQuad, false, f.parts());
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
     *                depth=ok depthPreserved=true inside=false owner=lens dist=21.2 insideThr=8.3
     *                screenR=48.9 parts=warp|core|ring|disk|glow draw=yes
     * </pre>
     * <ul>
     *   <li>{@code path=} <b>这一帧真的走了哪条路</b>（{@code lens-after-composite} /
     *       {@code lens-after-weather} / {@code fallback-quad} / {@code inside-overlay} / {@code none}）
     *       —— 旧实现里 {@code owner=lens level=1} 只是"hostfx 没接管"，不是"透镜画了"，
     *       两者矛盾时以这行为准（见 {@link #lastPath()}）；</li>
     *   <li>{@code shaderpack=} = <b>光影真的在生效</b>（{@code BlackHoleIris.pipelineActive()}），
     *       <b>不是</b>"装了 Oculus"；</li>
     *   <li>{@code depth=ok|none}：{@code none} = 主目标深度不是本帧世界 ⇒ 本帧是<b>无遮挡透镜版</b>
     *       （透镜 RenderType 换成 {@code always} 深度测试，不伪造遮挡）；</li>
     *   <li>🔴 <b>三件套 {@code dist=… inside=… insideThr=…}</b>（任务书第 2 条）：
     *       {@code dist} = 相机到洞心的欧氏距离（格）、{@code insideThr} = 这一帧真正用的洞内阈值（格，
     *       来自 {@link BlackHoleLensSpec#insideThresholdBlocks}）、{@code inside} = 判据结果。
     *       <b>三个数必须同时出现</b> —— 改前只打 {@code dist}，于是"5.3 比 5.3004 差一点"这件事
     *       在 15896 行日志里完全看不出来（这也是那个 bug 藏了这么久的原因）；</li>
     *   <li>{@code screenR=} 黑盘屏幕像素半径（<b>洞内恒为 0.0</b>：视界把相机整个包住了，
     *       没有"屏幕上的黑盘半径"这个概念）；{@code parts=} 四件构件位掩码，
     *       <b>洞内恒为 {@link #PARTS_INSIDE}（{@code inside}）</b>：洞内没有 warp/黑盘/光子环的屏幕空间定义，
     *       不能让 {@code parts_missing} 在这里误报；{@code owner=} / {@code draw=} 口径不变。</li>
     * </ul>
     * 1 秒一行（{@link #FRAME_LOG_MS}）。
     *
     * <h2>🔴 2026-10-25 傍晚修的"日志骗人"点</h2>
     * 改前本方法第一句是 {@code if (f.inside) return;}（理由写的是"洞内没有屏幕空间透镜 ⇒ parts 不适用"）。
     * 后果是<b>洞内帧一行日志都不打</b>：用户实测 15896 行里 {@code inside=true} 出现 <b>0 次</b>，
     * 而真相可能是"从没进过洞内分支"、也可能是"进去了但这行被吞了"——<b>两种完全不同的病因，
     * 日志分不出来</b>。现在洞内帧照常打这一行（只是 {@code parts=inside}），三件套永远同时出现。
     */
    private static void logFrameReadout(Frame f, String path, String stage, boolean shaderpack,
                                        boolean depthOk, boolean drewFull, boolean drewQuad,
                                        boolean drewInside, int parts) {
        final long now = System.currentTimeMillis();
        if (now - lastFrameLogMs < FRAME_LOG_MS) {
            return;
        }
        lastFrameLogMs = now;
        // 洞内必须落到 PATH_INSIDE：改前这里没带 drewInside，于是洞内帧会被算成 path=none
        final String drawnPath = f.inside
                ? (drewInside ? PATH_INSIDE : PATH_NONE)
                : (drewFull ? path : (drewQuad ? PATH_FALLBACK_QUAD : PATH_NONE));
        final boolean drew = drewFull || drewQuad || drewInside;
        final boolean missing = drewFull && (parts & BlackHoleShaderProbe.PART_REQUIRED)
                != BlackHoleShaderProbe.PART_REQUIRED;
        // ---- 🔴 2026-10-25 传送 bug 之后新增的三列（原有 dist/inside/insideThr/screenR 一字不动）
        //   level=  guard 的降级级别（改前只在 hostfx 那行出现，`owner=lens` 会被误读成"一级画了"）；
        //   reason= 最近一次降级的原因（改前只有降级那一瞬间的一行 WARN，几万行日志里翻不到）；
        //   rect=   这一帧真正画的那块屏幕矩形（改前一级/二级各算各的，日志里没有它
        //           ⇒ "日志说 screenR=1725.9，画面却在右下角"这种脱钩永远对不上账）；
        //   screenR_raw= 只在被硬上限夹过时出现，后面跟 screenR_cut=（夹掉多少像素）。
        final BlackHoleFallbackGuard g = guard();
        final boolean screenRCut = Double.isFinite(screenRRaw)
                && screenRRaw > f.screenR + 0.05D;
        // ---- 🔴 洞内专属尾巴（2026-10-25）：`inside_break=` = 这一帧断在哪一段 ----
        //   为什么挂在<b>这一行</b>而不是另打一行：上一轮的教训就是"判据行说画了、别的行另有说法"，
        //   两行日志能互相矛盾。现在"画没画"和"断在哪"<b>同一行</b>，一句话读全。
        //   取值见 BlackHoleInsideProbe.BREAK_*：none/program/uniform/copy/quad/draw_gl/shader。
        final String insideTail = f.inside
                ? " inside_break=" + BlackHoleInsideProbe.breakToken()
                + " inside_reached=" + BlackHoleInsideProbe.reachedToken()
                + " inside_dbg=" + BlackHoleTuning.insideDebug().id()
                : "";
        GTDishanhaiMod.LOGGER.info(
                "{} path={} stage={} shaderpack={} depth={} depthPreserved={} inside={} owner={} "
                        + "dist={} insideThr={} screenR={} parts={} draw={} level={} reason={} rect={}{}",
                PREFIX, drawnPath, stage, shaderpack, depthOk ? "ok" : "none",
                f.depthPreserved, f.inside, drew ? "lens" : "none",
                f1(f.dist), f1(f.insideThr), f1(f.screenR),
                f.inside ? PARTS_INSIDE : BlackHoleShaderProbe.describe(parts),
                drew ? "yes" : "no",
                g.level(), g.reason(),
                "[" + f1(f.rectX0) + "," + f1(f.rectY0) + " " + f1(f.rectW) + "x" + f1(f.rectH) + "]",
                insideTail);
        if (screenRCut) {
            GTDishanhaiMod.LOGGER.info("{} screenR_clamped screenR={} screenR_raw={} screenR_cut={} "
                            + "cap={} (cap={}×屏高) screenH={} forwardDist={} note=读数已夹上限，"
                            + "这一帧的屏幕矩形见上一行 rect=",
                    PREFIX, f1(f.screenR), f1(screenRRaw),
                    f1(BlackHoleFallbackQuad.screenRCut(screenRRaw, f.screenH)),
                    f0(f.screenH * BlackHoleFallbackQuad.SCREEN_R_CAP_H),
                    f0(BlackHoleFallbackQuad.SCREEN_R_CAP_H), f.screenH, f1(f.forwardDist));
        }
        if (!drewFull && !drewInside && f.inside == false && !"none".equals(lastQuadSkip)) {
            GTDishanhaiMod.LOGGER.info("{} fallback_quad_skip why={} （二级这一帧没画，不是沉默失败）",
                    PREFIX, lastQuadSkip);
        }
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

    /**
     * 洞内帧在 {@code parts=} 那一列写的值（<b>不是</b>位掩码，是一个固定词）。
     *
     * <p>为什么需要它：{@code parts} 的语义是"屏幕空间透镜的四件构件在这块矩形里出现了几件"。
     * 洞内既没有 warp 也没有屏幕空间的黑盘/光子环 ⇒ 打 {@code parts=none} 会被当成"四件全缺"，
     * 打 {@code parts=warp|core|ring|disk|glow} 又是撒谎。所以给它一个专属词 {@code inside}，
     * 并与 {@code path=inside-overlay} 呼应。
     */
    public static final String PARTS_INSIDE = "inside";

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
            // 🔴 2026-10-25 第二十改：off 现在有两个来源 —— 命令（/shanhai bh lens off）与
            //    归墟视界枢核的机器字段（主机在场时它优先，见 BlackHoleTuning.effectiveLensMode）。
            //    这行日志必须说清是哪一个，否则"透镜为什么关着"永远查不到人。
            final boolean byHost = Boolean.FALSE.equals(BlackHoleTuning.hostLensEnabled());
            g.logOnce(BlackHoleFallbackGuard.LEVEL_QUAD, byHost ? "lens_off_by_host" : "lens_off_by_user");
            GTDishanhaiMod.LOGGER.info("{} lens_mode mode=off shaderpack={} why={} note={}",
                    PREFIX, shaderpack, byHost ? "host(机器字段)" : "user",
                    byHost ? "主机要求关闭引力透镜 ⇒ 只画黑盘+光子环+吸积盘，背景不弯折"
                            : "用户强制二级（背景不扭曲）");
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
     * <h2>🔴 2026-10-25 深夜重做（用户原话：「黑洞里面怎么变成这样了，我要那种真实坠入黑洞的感觉，
     * 而不是你这种一个贴图了事」）</h2>
     * 上一版这里是<b>5 层几何图元</b>（暗穹顶矩形 / 视界亮环 / 金色赤道带 / 中心光 / 视界外黑），
     * 颜色全是常量 ⇒ 背景是什么都长一个样 ⇒ 一眼就是贴图。这一版<b>一个图元都没有</b>：
     * <ol>
     *   <li><b>拷本帧背景</b>：{@link #copyBackground}（{@code glCopyTexSubImage2D}）拷的是
     *       <b>屏幕中心的正方形</b>（边长 = min(屏宽, 屏高, {@link #INSIDE_MAX_SRC_SIDE})），
     *       也就是"屏幕内接圆"那块世界 —— 与一级透镜<b>同一条</b>拷贝通道；</li>
     *   <li><b>全屏重投影</b>：{@code blackhole_inside} 着色器把这块世界按极坐标压缩进半径
     *       {@code windowR} 的圆窗，窗外压黑；圆窗边缘有更强的径向拖影（{@code TAPS} 个样本）、
     *       三通道色散、边缘角度扭曲、以及"光堆积"出来的亮边；</li>
     *   <li><b>参数只有一份</b>：窗口半径 / 拖影 / 色散 / 金带宽度 / 红移全部来自
     *       {@link BlackHoleInsideSpec}（同一个工厂 {@code of(...)} 也被离线出图器调用），
     *       随"进洞多深"{@code insideDepth} 单调变化 ⇒ 越深窗口越小、条纹越长。</li>
     * </ol>
     *
     * <h2>物理口径</h2>
     * 自由下落观测者：整扇窗按 {@code 1+z = 1+√(rs/r)} 偏红并变暗，窗口边缘（掠射光）相对偏蓝 ——
     * 推导与"为什么不是极端蓝移"见 {@link BlackHoleInsideSpec} 的类注释 §3。
     * <p>⚠️ 严格解要逐像素积分零测地线（全屏 raymarch），这一版<b>没有</b>做，属于一阶近似；
     * 日志里 {@code path=inside-overlay} 与 {@code path=lens-after-composite} 仍然严格分开，
     * 不冒充"屏幕空间透镜"。
     *
     * <h2>失败处置（改前是整条黑洞陪葬）</h2>
     * 洞内这条路的任何失败（着色器加载失败 / 拷贝失败 / GL 异常）只<b>停掉洞内</b>并把原因写进
     * {@code fallback=2 reason=inside_*}，<b>不会</b>再把 guard 降到三级 ——
     * 改前这里是 {@code degradeTo(LEVEL_NONE, ...)}，等于"洞内着色器一个笔误就把洞外一级也判死刑"。
     *
     * @return 是否真的画了（false = 着色器不可用 / 拷贝失败 / 抛异常）
     */
    private static boolean drawInside(BlackHoleFallbackGuard g, Frame f) {
        // ---- 隔离开关（任务书第 2 条）：默认 SAMPLE = 一行都不多走，行为与改前完全一致 ----
        final BlackHoleTuning.InsideDebug dbg = BlackHoleTuning.insideDebug();
        if (dbg == BlackHoleTuning.InsideDebug.SOLID) {
            return drawInsideSolid(g, f);
        }
        if (insideBroken) {
            BlackHoleInsideProbe.markBreak(BlackHoleInsideProbe.BREAK_SHADER, "insideBroken");
            return false;
        }
        final ShaderInstance shader = ensureInsideShader(g);
        if (shader == null) {
            return false;                    // 断点已由 ensureInsideShader 记成 program
        }
        if (!ensureBgTexture(g, (int) Math.ceil(f.rectW), (int) Math.ceil(f.rectH))) {
            BlackHoleInsideProbe.markBreak(BlackHoleInsideProbe.BREAK_COPY, "bgtex_alloc");
            return false;
        }
        final RenderTarget main = mainTargetOrNull(f, "main_size_mismatch");
        if (main == null) {
            return false;                                // 分辨率换过 ⇒ 这一帧的像素读数作废
        }
        if (!copyBackground(g, main, f)) {
            // copyBackground 内部已按需 markBreak；这里兜一层"返回 false 但没记断点"的漏网
            BlackHoleInsideProbe.markBreak(BlackHoleInsideProbe.BREAK_COPY, "copy_returned_false");
            return false;
        }
        return submitInsideQuad(g, f, main, shader, false);
    }

    /**
     * {@code /shanhai bh insidedebug solid}：<b>跳过背景拷贝</b>，让同一个着色器直接输出纯亮绿全屏。
     *
     * <h2>它回答的是哪一个问题（任务书第 2 条）</h2>
     * <ul>
     *   <li><b>看到绿</b> ⇒ 四边形提交这一路（RenderType / 深度 / FBO 绑定 / 注入点 / 着色器本身）
     *       全是通的 ⇒ 黑屏的原因一定在<b>采样那一段</b>（拷贝到的纹理是黑的 / uv 算错）；</li>
     *   <li><b>看不到绿</b> ⇒ 根本不关采样的事：四边形没提交、被后面的 pass 盖掉、
     *       或者被打进了别的 FBO ⇒ 去看注入点与 RenderType/深度。</li>
     * </ul>
     *
     * <h2>为什么用"同一个着色器 + uSolid 开关"而不是另建一个纯色 RenderType</h2>
     * 原版 {@code position_color} 的 vsh 会乘 {@code ProjMat * ModelViewMat}，而我们的顶点是
     * <b>裁剪空间</b>（−1..1）⇒ 走原版着色器得临时把两个矩阵改成单位阵（要动全局状态，
     * 一旦 pop 不干净会影响后面的 {@code renderItemInHand}）。用 {@code uSolid} 只改片元输出，
     * 其余管线一模一样 —— 这正好是"只隔离采样"想要的口径。
     * <p>代价：着色器本身没加载成功时 solid 也画不出来；那种情况断点已经是
     * {@code inside_break=program}，日志里说得很清楚。
     */
    private static boolean drawInsideSolid(BlackHoleFallbackGuard g, Frame f) {
        final ShaderInstance shader = ensureInsideShader(g);
        if (shader == null) {
            GTDishanhaiMod.LOGGER.warn("{} inside_solid_unavailable 洞内着色器没加载成功 ⇒ solid 也画不出来"
                    + "（断点已记为 inside_break=program）", PREFIX);
            return false;
        }
        if (!ensureBgTexture(g, (int) Math.ceil(f.rectW), (int) Math.ceil(f.rectH))) {
            BlackHoleInsideProbe.markBreak(BlackHoleInsideProbe.BREAK_COPY, "solid_bgtex_alloc");
            return false;
        }
        if (!hasInsideUniform(shader, "uSolid")) {
            // 没有 uSolid 就画不出绿块 —— 那时候"看不到绿"是假的阴性，不能让人据此排错。
            BlackHoleInsideProbe.markBreak(BlackHoleInsideProbe.BREAK_UNIFORM, "uSolid_missing");
            GTDishanhaiMod.LOGGER.warn("{} inside_solid_unavailable reason=uSolid_missing "
                    + "note=这一档画不出纯绿（看不到绿不代表没提交，别据此排错）", PREFIX);
            return false;
        }
        final RenderTarget main = mainTargetOrNull(f, "solid_main_unusable");
        if (main == null) {
            return false;
        }
        BlackHoleInsideProbe.logOnce("solid",
                "mode=solid solid_token=纯亮绿(0,255,0) note=跳过背景拷贝，同一个着色器只改片元输出"
                        + "（看到绿 ⇒ 提交这一路通；看不到绿 ⇒ 与采样无关）");
        return submitInsideQuad(g, f, main, shader, true);
    }

    /**
     * 主渲染目标（拿不到 / 尺寸对不上就返回 {@code null} 并记断点）。
     *
     * <p>为什么要比尺寸：{@code capture()} 里记的 {@code screenW/H} 与颜色拷贝的源矩形
     * 必须与<b>此刻</b>的主目标一致，否则 {@code glCopyTexSubImage2D} 会读到越界/旧像素。
     */
    private static RenderTarget mainTargetOrNull(Frame f, String why) {
        final Minecraft mc = Minecraft.getInstance();
        final RenderTarget main = mc == null ? null : mc.getMainRenderTarget();
        if (main == null || main.width != f.screenW || main.height != f.screenH) {
            BlackHoleInsideProbe.markBreak(BlackHoleInsideProbe.BREAK_COPY, why);
            return null;
        }
        return main;
    }

    /**
     * 真正提交那一个全屏四边形（sample 与 solid 共用；差别只有 {@code uSolid}）。
     *
     * <p>顺序是刻意的，每一步都在报告里对得上号：
     * <ol>
     *   <li>{@code setInsideUniforms} —— 探针④已经保证"取不到的项"会被点名，这里不再抛；</li>
     *   <li>{@code uSolid} —— 0 = 采样（默认） / 1 = 纯亮绿（{@code insidedebug solid}）；</li>
     *   <li>{@code bindTextureForShader} —— 三重保险把拷贝纹理绑成 {@code Sampler0}；</li>
     *   <li>{@code main.bindWrite(false)} —— 再绑一次写目标（别的 pass 可能换过它）；</li>
     *   <li>{@code drainGlErrors} —— 先排干历史错误，之后读到的错误才归因于这一趟；</li>
     *   <li>{@code drawInsideQuad} —— 探针①（声明值 + 设状态那一刻的实际 GL 值）；</li>
     *   <li>{@code glGetError} —— 探针⑤（绘制后）。错误非 0 ⇒ 返回 false，<b>不声称"画了"</b>。</li>
     * </ol>
     */
    private static boolean submitInsideQuad(BlackHoleFallbackGuard g, Frame f, RenderTarget main,
                                            ShaderInstance shader, boolean solid) {
        try {
            // 全部参数只在这一个地方算（离线出图器调的是同一个工厂，见报告 §5）
            final BlackHoleInsideSpec.Params p = BlackHoleInsideSpec.of(
                    f.screenW, f.screenH, (int) Math.round(f.rectW), f.insideDepth, f.dist,
                    f.insideShadowR, f.insideDiskN, f.tanHalfFov, f.renderSeconds,
                    BlackHoleTuning.insideWindowMul(), BlackHoleTuning.insideStreakOverride(),
                    1.0D / (double) bgTexSide);
            setInsideUniforms(shader, p);
            if (hasInsideUniform(shader, "uSolid")) {
                shader.safeGetUniform("uSolid").set(solid ? 1.0F : 0.0F);
            }
            bindTextureForShader(shader, bgTexId);
            // 提交前把主渲染目标重新绑成写目标：别的 pass（Iris / 后处理 / hostfx）可能把它换过，
            // 换过的话我们这四个顶点会画进别的 FBO —— 屏幕上什么都看不到，而日志一切正常。
            main.bindWrite(false);
            // 先排干历史 GL 错误 ⇒ 下面读到的错一定来自这一趟绘制（否则无法归因）
            drainGlErrors();
            BlackHoleInsideProbe.reach(BlackHoleInsideProbe.STAGE_QUAD);
            try {
                drawInsideQuad();
            } catch (Throwable t) {
                BlackHoleInsideProbe.markBreak(BlackHoleInsideProbe.BREAK_QUAD,
                        t.getClass().getSimpleName());
                throw t;
            }
            // ---- 探针⑤：绘制之后的 glGetError ----
            final int err = GlStateManager._getError();
            BlackHoleInsideProbe.reach(BlackHoleInsideProbe.STAGE_DRAW);
            BlackHoleInsideProbe.logOnce(BlackHoleInsideProbe.STAGE_DRAW,
                    "gl=" + err + " result=" + (err == 0 ? "ok" : "fail") + " solid=" + solid
                            + " note=绘制后的 glGetError（历史错误已在绘制前排干，所以这个值属于这一趟）");
            if (err != 0) {
                // 提交了但 GL 报错 ⇒ 不声称"画了"（上一轮就是把 draw=yes 当成了"看得见"）
                BlackHoleInsideProbe.markBreak(BlackHoleInsideProbe.BREAK_DRAW, "gl_" + err);
                return false;
            }
            if (!solid && !insideLogged) {
                insideLogged = true;
                GTDishanhaiMod.LOGGER.info("{} inside_view dist={} insideThr={} shadowR={} ratio={} {} "
                                + "src=[{},{} {}x{}] texSide={} windowMul={} streakOverride={} "
                                + "note=洞内=全屏重投影采样本帧颜色（窗内世界是真的背景，窗外压黑；"
                                + "没有任何几何图元）",
                        PREFIX, f1(f.dist), f1(f.insideThr), f1(f.insideShadowR), f2(f.insideRatio),
                        BlackHoleInsideSpec.describe(p), f1(f.rectX0), f1(f.rectY0), f1(f.rectW),
                        f1(f.rectH), bgTexSide, f2(BlackHoleTuning.insideWindowMul()),
                        BlackHoleTuning.insideStreakOverride() < 0.0D ? "auto"
                                : f2(BlackHoleTuning.insideStreakOverride()));
            }
            return true;
        } catch (Throwable t) {
            insideBroken = true;
            BlackHoleInsideProbe.markBreak(BlackHoleInsideProbe.BREAK_SHADER,
                    t.getClass().getSimpleName());
            g.logOnce(BlackHoleFallbackGuard.LEVEL_QUAD, "inside_draw:" + t.getClass().getSimpleName());
            GTDishanhaiMod.LOGGER.warn("{} inside_draw_failed 只停洞内绘制（洞外一级不受影响，"
                    + "改前这里会把整条黑洞降到三级）", PREFIX, t);
            return false;
        }
    }

    /** 洞内着色器实例（lazy new；失败只停洞内）。 */
    private static ShaderInstance insideShader;
    /** 洞内着色器是否已经试过加载（失败不每帧重试；{@code /shanhai bh reload} 会清掉）。 */
    private static boolean insideTried;
    /** 洞内绘制已经抛过异常 ⇒ 本会话不再试（避免每帧刷栈）。 */
    private static boolean insideBroken;
    /** {@code inside_view} 那行 INFO 只打一次。 */
    private static boolean insideLogged;

    /**
     * 加载时<b>取不到</b>的必需 uniform（空数组 = 全部取到）。
     *
     * <p>🔴 2026-10-25：上一版这里是"取不到就 close + 整条洞内永不绘制"，
     * 结果一个<b>声明了但着色器没用</b>的 {@code uScreenSize}（被 GLSL 优化掉）
     * 就让洞内一帧都没画。现在只记下来 + WARN，照画。
     */
    private static String[] insideUniformMissing = new String[0];
    /** 取到的必需 uniform 个数（日志用）。 */
    private static int insideUniformPresent;
    /** 逐帧 {@code set} 时"取不到"的 uniform 名（只在第一次遇到时打一行，避免每帧拼字符串）。 */
    private static final java.util.Set<String> insideSkippedUniforms = new java.util.LinkedHashSet<>();
    /** {@code inside_uniform_skip} 那行只打一次。 */
    private static boolean insideSkipLogged;

    /**
     * 洞内着色器的必需 uniform：{@link BlackHoleInsideSpec#UNIFORMS} 去掉采样器
     * （{@code Sampler0} 由 {@code setSampler}/{@code ShaderInstance} 自己管，{@code getUniform} 取不到它）。
     *
     * <p>这里<b>不另写一份名字表</b>：名单只有 {@link BlackHoleInsideSpec#UNIFORMS} 一处，
     * 静态检查（{@code temp/bh-inside/InsideSpecCheck.java}）还会拿它和 fsh 文本做集合比对。
     */
    private static String[] insideRequiredUniforms() {
        final java.util.List<String> out = new java.util.ArrayList<>();
        for (String name : BlackHoleInsideSpec.UNIFORMS) {
            if (!"Sampler0".equals(name)) {
                out.add(name);
            }
        }
        return out.toArray(new String[0]);
    }

    /**
     * 加载洞内着色器（第一次真正要画洞内的时候）。
     *
     * <p>与 {@link #ensureShader} 同一套理由（不在 {@code RegisterShadersEvent} 里 new：那里抛异常会把
     * 整次资源重载带崩），但<b>失败级别不同</b>：洞外一级不陪葬，见 {@link #drawInside} 的注释。
     */
    private static ShaderInstance ensureInsideShader(BlackHoleFallbackGuard g) {
        if (insideTried) {
            return insideShader;
        }
        insideTried = true;
        try {
            final ShaderInstance sh = new ShaderInstance(
                    Minecraft.getInstance().getResourceManager(),
                    new ResourceLocation("gt_shanhai", BlackHoleInsideSpec.SHADER_ID),
                    DefaultVertexFormat.POSITION);
            // ---- 探针③：编译 + 链接结果（🔴 成功也要打一行，带 program id）----
            //   为什么成功也要打：上一轮日志里既没有 inside_shader_ok 也没有 inside_shader_failed，
            //   于是"着色器到底加载成功没有"在日志里是<b>空白</b> —— 只能从后面那行
            //   `fallback=2 reason=inside_uniform_missing:uScreenSize` 反推。一进洞就该有答案。
            BlackHoleInsideProbe.reach(BlackHoleInsideProbe.STAGE_PROGRAM);
            BlackHoleInsideProbe.logOnce(BlackHoleInsideProbe.STAGE_PROGRAM,
                    "id=" + sh.getId() + " link=ok fmt=POSITION vert="
                            + BlackHoleInsideSpec.SHADER_ID + ".vsh frag="
                            + BlackHoleInsideSpec.SHADER_ID + ".fsh"
                            + " note=id≠-1 ⇒ 编译/链接这一关过了（断点不在 program）");
            // ---- 探针④：必需 uniform 逐个点名（缺哪个就写哪个，不再"第一个缺的就 return"）----
            final java.util.List<String> missing = new java.util.ArrayList<>();
            final java.util.List<String> present = new java.util.ArrayList<>();
            for (String name : insideRequiredUniforms()) {
                if (sh.getUniform(name) == null) {
                    missing.add(name);
                } else {
                    present.add(name);
                }
            }
            insideUniformMissing = missing.toArray(new String[0]);
            insideUniformPresent = present.size();
            BlackHoleInsideProbe.logOnce(BlackHoleInsideProbe.STAGE_UNIFORM,
                    "n=" + present.size() + "/" + insideRequiredUniforms().length
                            + " missing=" + missing
                            + " note=missing 里的项名就是「这一项没取到」的证据"
                            + "（Sampler0 由 setSampler 管，不在此检查）");
            if (!missing.isEmpty()) {
                // 🔴 改前：这里 close + return null ⇒ 整条洞内<b>一帧都不画</b>。
                //   为什么现在不这么做：驱动/编译器对"声明了但着色器没读"的 uniform 一律优化掉，
                //   这不是"着色器坏了"，而是"这个参数传不进去"。缺一个参数最多让画面退化，
                //   不该让整个洞内变黑 —— 而上一轮正是这样"一进洞就不渲染"的。
                GTDishanhaiMod.LOGGER.warn("{} inside_uniform_partial missing={} present={} "
                                + "note=缺的项会停在 json 默认值，洞内仍然照画"
                                + "（改前这里等于整条洞内一帧都不画）",
                        PREFIX, missing, present.size());
            }
            insideShader = sh;
            // 这一行保留旧词表（inside_shader_ok）—— 上一轮的报告/检索都认这个词；新加的是 program/uniform 两段探针。
            GTDishanhaiMod.LOGGER.info("{} inside_shader_ok id={} program={} uniforms_present={}/{} missing={}",
                    PREFIX, BlackHoleInsideSpec.SHADER_ID, sh.getId(), present.size(),
                    insideRequiredUniforms().length, missing);
            return sh;
        } catch (Throwable t) {
            insideShader = null;
            BlackHoleInsideProbe.logOnce(BlackHoleInsideProbe.STAGE_PROGRAM,
                    "id=-1 link=fail err=" + BlackHoleFallbackGuard.sanitize(t.getClass().getSimpleName()));
            BlackHoleInsideProbe.markBreak(BlackHoleInsideProbe.BREAK_PROGRAM,
                    t.getClass().getSimpleName());
            g.logOnce(BlackHoleFallbackGuard.LEVEL_QUAD,
                    "inside_shader:" + t.getClass().getSimpleName());
            GTDishanhaiMod.LOGGER.warn("{} inside_shader_failed 洞内不画（洞外一级不受影响）", PREFIX, t);
            return null;
        }
    }

    /**
     * 把 {@link BlackHoleInsideSpec.Params} 灌进着色器（逐项与 fsh / json 的 uniform 名同字）。
     *
     * <p>🔴 每一项都先问一句"这台机器上取得到吗"：取不到就<b>跳过</b>并把名字记下来（一次性进日志）。
     * 为什么不用 {@code try/catch} 包住 {@code safeGetUniform}：它抛的是
     * {@link IllegalArgumentException}，一帧抛一次 = 每帧一次异常构造 + 栈填充（掉帧），
     * 而且会掉到 {@link #drawInside} 最外层的 catch ⇒ {@code insideBroken} ⇒ 整条洞内永久停掉
     * （那就是"一进洞就不渲染"的另一种复现形态）。
     */
    private static void setInsideUniforms(ShaderInstance shader, BlackHoleInsideSpec.Params p) {
        if (hasInsideUniform(shader, "uCenterPx")) {
            shader.safeGetUniform("uCenterPx").set((float) p.centerPxX, (float) p.centerPxY);
        }
        if (hasInsideUniform(shader, "uSrcHalfPx")) {
            shader.safeGetUniform("uSrcHalfPx").set((float) p.srcHalfPx);
        }
        if (hasInsideUniform(shader, "uBgRect")) {
            shader.safeGetUniform("uBgRect").set((float) p.bgX0, (float) p.bgY0,
                    (float) p.bgW, (float) p.bgH);
        }
        if (hasInsideUniform(shader, "uBgTexel")) {
            shader.safeGetUniform("uBgTexel").set((float) p.bgTexel, (float) p.bgTexel);
        }
        if (hasInsideUniform(shader, "uBandNrm")) {
            shader.safeGetUniform("uBandNrm").set((float) p.bandNrmX, (float) p.bandNrmY);
        }
        if (hasInsideUniform(shader, "uBandOff")) {
            shader.safeGetUniform("uBandOff").set((float) p.bandOff);
        }
        if (hasInsideUniform(shader, "uTime")) {
            shader.safeGetUniform("uTime").set((float) p.time);
        }
        setVec4(shader, "uP0", p.p0);
        setVec4(shader, "uP1", p.p1);
        setVec4(shader, "uP2", p.p2);
        setVec4(shader, "uP3", p.p3);
        setVec4(shader, "uP4", p.p4);
        if (!insideSkippedUniforms.isEmpty() && !insideSkipLogged) {
            insideSkipLogged = true;
            GTDishanhaiMod.LOGGER.warn("{} inside_uniform_skip names={} note=这些项在本机驱动上取不到"
                            + "（GLSL 会把没被读到的 uniform 优化掉）⇒ 用到它们的算式停在 json 默认值，"
                            + "洞内仍然照画", PREFIX, insideSkippedUniforms);
        }
    }

    /** {@code getUniform(name) != null}；取不到时把名字记进 {@link #insideSkippedUniforms}（去重）。 */
    private static boolean hasInsideUniform(ShaderInstance shader, String name) {
        if (shader.getUniform(name) != null) {
            return true;
        }
        insideSkippedUniforms.add(name);
        return false;
    }

    private static void setVec4(ShaderInstance shader, String name, double[] v) {
        if (!hasInsideUniform(shader, name)) {
            return;
        }
        shader.safeGetUniform(name).set((float) v[0], (float) v[1], (float) v[2], (float) v[3]);
    }

    /**
     * 画覆盖整屏的一个四边形（顶点就是裁剪空间 −1..1；vsh 直接写 {@code gl_Position = vec4(Position,1)}）。
     *
     * <p>为什么这里<b>不用</b>改投影矩阵/模型视图矩阵（改前洞内那条路要 push 单位阵）：
     * 我们的 vsh 根本不看矩阵，顶点值本身就是裁剪空间 ⇒ 不需要动任何全局 GL 状态
     * （也就不会像上一版那样把外层的投影矩阵存档位覆盖掉）。
     */
    private static void drawInsideQuad() {
        final RenderType type = insideType();
        // ---- 探针①（声明值）：这一趟四边形<b>要求</b>的状态。它就是 MC 会应用的那一套（常量）----
        BlackHoleInsideProbe.logOnce("quad_declare",
                "rt=shanhai_blackhole_inside fmt=POSITION mode=QUADS vtx=4"
                        + " blend=off depth_test=always depth_mask=false color_write=on cull=off"
                        + " note=声明值（RenderType 常量）；真实 GL 值见 quad_setup");
        final MultiBufferSource.BufferSource buffers =
                MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        final VertexConsumer vc = buffers.getBuffer(type);
        vc.vertex(-1.0F, -1.0F, 0.0F).endVertex();
        vc.vertex(1.0F, -1.0F, 0.0F).endVertex();
        vc.vertex(1.0F, 1.0F, 0.0F).endVertex();
        vc.vertex(-1.0F, 1.0F, 0.0F).endVertex();
        buffers.endBatch(type);
        BlackHoleInsideProbe.logOnce("quad_endbatch",
                "endbatch=ok vtx=4 note=endBatch 正常返回（没有抛异常）");
    }

    /** 洞内那一趟的 RenderType：{@code POSITION}、<b>不混合</b>（片元整块替换像素）、不测不写深度、不剔除。 */
    private static RenderType insideType;

    private static RenderType insideType() {
        if (insideType == null) {
            insideType = RenderType.create(
                    "shanhai_blackhole_inside",
                    DefaultVertexFormat.POSITION,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(INSIDE_SHADER_STATE)
                            .setTransparencyState(NO_BLEND)
                            // 相机就在洞里：深度缓冲里是"壳"，任何深度测试都会把整屏挡掉 ⇒ always，且不写
                            .setDepthTestState(new RenderStateShard.DepthTestStateShard("always", GL_ALWAYS))
                            .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, false))
                            .setCullState(new RenderStateShard.CullStateShard(false))
                            .createCompositeState(false));
        }
        return insideType;
    }

    private static final RenderStateShard.ShaderStateShard INSIDE_SHADER_STATE =
            new RenderStateShard.ShaderStateShard(BlackHoleLensRenderer::insideShaderForState);

    private static ShaderInstance insideShaderForState() {
        final ShaderInstance sh = insideShader;
        if (sh == null) {
            BlackHoleInsideProbe.markBreak(BlackHoleInsideProbe.BREAK_QUAD, "shader_null_at_setup");
            throw new IllegalStateException("blackhole inside shader not loaded");
        }
        // ---- 探针①（真实值）：MC 只在这一趟四边形<b>真的要画</b>时才会调这个 Supplier ----
        //   ⇒ 这一行出现 == 四边形被提交了（比任何"我说我画了"都硬）；
        //   而此刻读到的 GL 状态就是即将用来画它的状态（blend / 深度测试 / 深度写 / 剔除）。
        BlackHoleInsideProbe.reach(BlackHoleInsideProbe.STAGE_QUAD);
        BlackHoleInsideProbe.logOnce("quad_setup",
                "shader=" + BlackHoleInsideSpec.SHADER_ID + " program=" + sh.getId()
                        + " gl_blend=" + glCap(GL11.GL_BLEND)
                        + " gl_depth_test=" + glCap(GL11.GL_DEPTH_TEST)
                        + " gl_depth_mask=" + glBool(GL11.GL_DEPTH_WRITEMASK)
                        + " gl_cull=" + glCap(GL11.GL_CULL_FACE)
                        + " gl_cur_program=" + GlStateManager._getInteger(GL_CURRENT_PROGRAM)
                        + " note=这一行本身就是「四边形已提交」的证据（Supplier 只在真绘制时被调）");
        return sh;
    }

    /** GL 能力开关 → {@code on|off}（探针用；只读，不改状态）。 */
    private static String glCap(int cap) {
        return GL11.glIsEnabled(cap) ? "on" : "off";
    }

    /** GL 布尔状态 → {@code on|off}（探针用；只读，不改状态）。 */
    private static String glBool(int pname) {
        return GL11.glGetBoolean(pname) ? "on" : "off";
    }

    // ==========================================================================================
    // 一级：屏幕空间引力透镜
    // ==========================================================================================

    /**
     * 采样门容差的<b>下限</b>（窗口深度单位）。见 {@link Frame#gateEps} 的注释。
     *
     * <p>为什么不再用改前的固定 {@code 1e-6}：那是"窗口深度"单位，洞在 30 格外时 1e-6 只等于
     * 0.02 格 ⇒ 一点点投影误差就让门把背景判成前景（= 用户最早报的"鬼影"）；
     * 而 1e-6 在 700 格外又等于 ≈9 格。固定值在这里两头都错，必须按"格"来定义容差。
     *
     * <p>🔴 2026-10-25 再改：{@code 2e-4 → 1e-5}。起因是用户实测原话
     * 「<b>引力透镜现在又会扭曲它前面的物体了</b>」。
     * <p>根因：{@code 2e-4} 是窗口深度单位，洞心 55 格外时 1 格 ≈ {@code 1.62e-5} 窗口深度
     * ⇒ {@code 2e-4 / 1.62e-5 ≈ 12.3 格}。也就是门被放宽成"采样点必须比本像素更近 12 格以上
     * 才算采到前景"，而前景（环带 / 光束 / AR 玻璃）与背景的深度差常常不到 12 格
     * ⇒ 门基本不生效，前景颜色又被搬到别处（= 用户看到的那句）。
     * <p>改后：{@code 1e-5} 在 55 格处 ≈ {@code 0.62 格}，而"按格算"那一项是 {@code 0.75 格}
     * ⇒ <b>近处由物理项胜出</b>（{@code gateEpsSrc=blocks}），门回到"差大半格就拦"。
     * 远处该项仍会盖过物理项（200 格处 ≈ 8 格；改前是 ≈160 格），只是量级小了一个半数量级。
     * <p>为什么下限不能直接删成 0：float32 在 {@code 0.999x} 处的分辨率 ≈ {@code 6e-8}
     * （24 位深度缓冲同理），极远处"每格只差 1 个 ulp"时门会被量化噪声触发。
     * {@code 1e-5} ≈ 168 个 ulp，够厚；{@code 1e-6} ≈ 17 个 ulp 是实际可用的下限（本轮未采用）。
     */
    static final double GATE_EPS_MIN = 1.0e-5D;

    /** 容差按"格"算：0.75 格（不到一格，避免把紧贴洞面的方块算成前景）。 */
    static final double GATE_EPS_BLOCKS = 0.75D;

    /**
     * 相机空间 z = {@code viewZ} 处的<b>窗口深度</b>（0..1，与深度缓冲同口径）。
     *
     * <p>只用 proj 的第 3/4 行：{@code ndcZ = (proj[10]·z + proj[14]) / (proj[11]·z + proj[15])}。
     * <b>不传 x/y</b>：标准透视投影里 {@code proj[2]=proj[6]=0}、{@code proj[3]=proj[7]=0}
     * ⇒ ndcZ 与 x/y 无关（这正是"沿视线轴加 1 格 = 相机空间 z 减 1"这条的依据）。
     *
     * @param viewZ 相机空间 z（相机朝 −z ⇒ <b>更远 = 更小</b>）
     * @return 窗口深度；投影退化时返回 {@link Double#NaN}
     */
    static double windowDepth(float[] proj, float viewZ) {
        final double cz = (double) proj[10] * viewZ + proj[14];
        final double cw = (double) proj[11] * viewZ + proj[15];
        if (!(Math.abs(cw) > 1e-9D) || !Double.isFinite(cz) || !Double.isFinite(cw)) {
            return Double.NaN;
        }
        return (cz / cw) * 0.5D + 0.5D;
    }

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
        // 🔴 2026-10-25：采样侧深度门要用同一块矩形的深度拷贝。只有在 depthOk（深度缓冲确实是本帧
        //   世界）时才值得建 / 才允许用它；否则一律不建（门必须关掉，不许拿脏深度乱拦）。
        final boolean depthTexOk = depthOk && ensureDepthTexture(g);
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
                // 🔴 2026-10-25 传送 bug 的真凶之一。
                //   改前：这一帧不达标 ⇒ 立刻 degradeTo(LEVEL_QUAD)，而那个降级【只降不升】⇒ 整局变贴图。
                //   实机日志：`fallback=2 reason=bh_view_bad:bn_3.01`，同一行写着
                //   `bhView=-29.65,-274.78,-629.52 dist=687.5` —— 洞在 687 格外、偏轴 24°。
                //   为什么"合规的投影"会在这里报错：bn 的判据是一个【角度】量
                //   （b = dist · sinθ，bn = b / Rs），所以同样的角度误差 Δθ 换算出来的 bn 与 dist 成正比。
                //   0.5° 的投影不一致：在 30 格处 = 0.26 格 = bn 0.13（绿）；在 687 格处 = 6.0 格 = bn 3.0（红）。
                //   ⇒ 这条自检在"洞很远"时会把一点点不一致读成"uBhView 口径错了"。
                //   现在：连续 BH_VIEW_BAD_FRAMES 帧都超标才降级（真口径错会一直超标，瞬移/bob 只有一两帧）。
                if (++bhViewBadFrames >= BH_VIEW_BAD_FRAMES) {
                    g.degradeTo(BlackHoleFallbackGuard.LEVEL_QUAD, "bh_view_bad:bn=" + f2(bnAtCenter));
                }
                if (!bhViewBadWarned) {
                    bhViewBadWarned = true;
                    GTDishanhaiMod.LOGGER.warn("{} bh_view_bad 中心处 bn={} ≥ b_crit={}（uBhView 口径错了？"
                                    + " bhView={},{},{} dist={} ⇒ 连续 {} 帧不达标才降级，本帧只跳过一级）",
                            PREFIX, f2(bnAtCenter), f2(BlackHoleShaderProbe.B_CRIT),
                            f2(f.bhView[0]), f2(f.bhView[1]), f2(f.bhView[2]), f1(f.dist),
                            BH_VIEW_BAD_FRAMES);
                }
                return false;
            }
            if (bhViewBadFrames != 0) {
                bhViewBadFrames = 0;                    // 连续超标断了 ⇒ 清零（下一次重新计数）
                bhViewBadWarned = false;
            }
            shader.safeGetUniform("uProjMat").set(new Matrix4f().set(f.proj));
            shader.safeGetUniform("uBhView").set(f.bhView[0], f.bhView[1], f.bhView[2]);
            shader.safeGetUniform("uDiskNormal").set(nx / nlen, ny / nlen, nz / nlen);
            shader.safeGetUniform("uScreenSize").set((float) f.screenW, (float) f.screenH);
            // 🔴 2026-10-25：uBgRect 用【真正拷进来的整数矩形】（见 Frame.copyX0 的注释）。
            //   改前给的是浮点 rectX0/rectW ⇒ 与贴图 texel 半像素错位 ⇒ "warp=0 时逐像素等于原画面"
            //   其实不成立；现在同一组整数 ⇒ 无弯折区与已画好的背景真正逐像素相同。
            shader.safeGetUniform("uBgRect").set((float) f.copyX0, (float) f.copyY0,
                    (float) f.copyW, (float) f.copyH);
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
            // ---- 🔴 2026-10-25 采样侧深度门：只有比黑洞更远的像素才参与弯折
            //   gate = 深度可用（depthOk）且深度拷贝真的一次都没失败过 且【用户没把 A/B 开关关掉】。
            //   gate=0 时着色器里那一整段被跳过 ⇒ 行为与 2026-10-25 之前逐字一致（可回退、可对照）。
            final boolean gate = depthTexOk && !depthGateDisabled && depthTexId != -1
                    && BlackHoleTuning.depthGate();
            shader.safeGetUniform("uDepthGate").set(gate ? 1.0F : 0.0F);
            // 🔴 2026-10-25 第四改：uHoleDepth **重新参与判定**（fsh 里是 `refDepth = max(dSelf, uHoleDepth)`），
            //   它现在是"参照深度的下界"，不再只是写入门/日志/契约的占位。
            //   ⚠️ 为什么这一步必须"写得进 + shader 里读得到"：只声明不读的 uniform 会被 GLSL 编译器整条删掉 ⇒
            //     这里 getUniform 拿到 null ⇒ safeGetUniform 给的是 DUMMY（写入无害、静默不生效），同时
            //     上层的"必需 uniform 缺失"检查会把整条一级透镜打成二级（实机 reason=uniform_missing:uHoleDepth）。
            //   ⇒ 这一行与 fsh 的 max 必须成对存在；离线断言见 temp/bh-gate2/check-uniforms.ps1（B1/C1）。
            //   注：safeGetUniform 在取不到时返回 DUMMY（memory: safeGetUniform → getUniform()==null → DUMMY_UNIFORM），
            //   所以哪怕真取不到，这里也**不会抛异常**、不会把这一帧打断 —— 失败形态只剩"门的参照少一个下界"。
            shader.safeGetUniform("uHoleDepth").set((float) f.holeDepthWin);
            // 容差单独走 uGateEps（与 uHoleDepth 解耦：一个是参照，一个是容差）
            shader.safeGetUniform("uGateEps").set((float) f.gateEps);
            if (gate) {
                try {
                    // 与 Sampler0 同一套机制（见 bindTextureForShader 的注释）：apply() 会把
                    // Sampler1 的 uniform 设成它的单元号(1) 并把这张纹理绑到 GL_TEXTURE1。
                    shader.setSampler("Sampler1", depthTexId);
                } catch (Throwable ignored) {
                    // 取不到就只是"门吃掉的都是同一张纹理"⇒ 由下面的日志判据暴露，不会静默
                }
            }

            // 背景贴图绑到 0 号纹理单元（见 bindTextureForShader 的注释：setShaderTexture 只"记账"不绑）
            bindTextureForShader(shader, bgTexId);
            drawLensQuad(f, depthOk, gate);
            return true;
        } catch (Throwable t) {
            g.degradeTo(BlackHoleFallbackGuard.LEVEL_QUAD, "lens_draw:" + t.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 画一个 NDC 矩形（顶点已经是裁剪空间坐标；z 用黑洞中心平面的 NDC 深度 ⇒ 深度测试天然正确）。
     *
     * <h2>🔴 2026-10-25 新增：<b>实测</b>深度态 + {@code lens_gate} 判据行</h2>
     * 用户报「黑洞前面的物体也会被引力透镜影响」，而<b>写入门（GL depth test）从 2026-10-24 起
     * 就一直在</b>（实机日志 447/447 行都是 {@code depth=ok depthPreserved=true}）。
     * 所以这一段做两件事：
     * <ol>
     *   <li>不依赖 {@code RenderType} 的 DrawState：<b>自己</b>把深度态显式设一遍
     *       （{@code enableDepthTest / depthFunc(LEQUAL) / depthMask(false)}）；</li>
     *   <li>每 {@link #LENS_GATE_LOG_MS} 毫秒向驱动<b>问一次真实状态</b>
     *       （{@code glIsEnabled(GL_DEPTH_TEST)} / {@code glGetInteger(GL_DEPTH_FUNC)} /
     *       {@code glGetBoolean(GL_DEPTH_WRITEMASK)}，在 {@code setupRenderState()} 之后问
     *       = 就是绘制那一刻的状态），打一行 {@code lens_gate}。
     *       ⇒「深度测试到底开没开、写不写深度、quad 的 z 是多少」<b>进游戏就能判</b>，
     *       不用再靠读代码猜。</li>
     * </ol>
     *
     * @param depthOk false = 深度缓冲不是本帧世界（Iris 合成掉了）⇒ 换成 {@code always} 深度测试的
     *                <b>无遮挡透镜版</b>（用户口径：「不要为此伪造遮挡」，这里只是"不假装有遮挡"）
     * @param gate    采样侧深度门是否真的启用（{@code uDepthGate}），只进日志
     */
    private static void drawLensQuad(Frame f, boolean depthOk, boolean gate) {
        final float x0 = (float) (f.rectX0 / f.screenW * 2.0D - 1.0D);
        final float x1 = (float) ((f.rectX0 + f.rectW) / f.screenW * 2.0D - 1.0D);
        final float y0 = (float) (f.rectY0 / f.screenH * 2.0D - 1.0D);
        final float y1 = (float) ((f.rectY0 + f.rectH) / f.screenH * 2.0D - 1.0D);
        final float z = (float) f.holeScreen[2];
        final RenderType type = lensType(depthOk);
        // ① 显式把写入门设一遍（幂等；不依赖 RenderType 的 DrawState 有没有被别处改掉）
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(depthOk ? GL_LEQUAL : GL_ALWAYS);
        RenderSystem.depthMask(false);
        // ② 限流实测（把这条 RenderType 的状态装一遍再问驱动；setupRenderState 是 public 且幂等）
        final long now = System.currentTimeMillis();
        if (now - lastLensGateLogMs >= LENS_GATE_LOG_MS || lensGateTag == null) {
            lastLensGateLogMs = now;
            boolean depthTestOn = false;
            boolean depthWriteOn = true;
            int depthFunc = -1;
            try {
                type.setupRenderState();                // 应用 DrawState（画的时候 endBatch 会再应用一次）
                depthTestOn = GL11.glIsEnabled(GL_DEPTH_TEST);
                depthWriteOn = GL11.glGetBoolean(GL_DEPTH_WRITEMASK);
                depthFunc = GL11.glGetInteger(GL_DEPTH_FUNC);
            } catch (Throwable ignored) {
                // 量不到就如实写 -1 / 默认值，绝不编一个好看的数字
            }
            final String depthTexState = depthTexId == -1 ? "none"
                    : (depthGateDisabled ? "fail" : "ok");
            lensGateTag = depthOk + "/" + gate + "/" + depthTestOn + "/" + depthFunc + "/" + depthWriteOn
                    + "/" + depthTexState + "/" + depthProbeVerdict + "/" + fe(f.gateEps);
            // 🔴 2026-10-25：容差这两个数必须按【本帧】现算着打出来（用户要看"换算成几格"）。
            //   gateEpsBlocks = gateEps / depthEpsPerBlock，分母是"洞心距离处每 1 格的窗口深度差"
            //   ⇒ 它是按<b>当前洞距</b>换算出来的真实格数，**不是常量**（常量只有 GATE_EPS_MIN=1e-5
            //   与 GATE_EPS_BLOCKS=0.75，两个都在同一行里打出来，方便一眼对上）。
            //   gateEpsSrc = max() 里哪一项胜出：blocks（物理项 0.75 格）/ min（下限项）。
            final boolean epsMeasurable = Double.isFinite(f.depthEpsPerBlock) && f.depthEpsPerBlock > 0.0D;
            final boolean epsFromBlocks = epsMeasurable
                    && GATE_EPS_BLOCKS * f.depthEpsPerBlock > GATE_EPS_MIN;
            final String gateEpsBlocksStr = epsMeasurable
                    ? f2(f.gateEps / f.depthEpsPerBlock) : "nan";
            GTDishanhaiMod.LOGGER.info(
                    "{} lens_gate depthOk={} gate={} depthTest={} depthFunc=0x{}({}) depthMask={} "
                            + "quadNdcZ={} holeCenterNdcZ={} holeWinZ={} depthTex={} depthTexSide={} "
                            + "gateEps={} gateEpsBlocks={} depthEpsPerBlock={} gateEpsMin={} gateEpsCfgBlocks={} "
                            + "gateEpsSrc={} depthProbe={}/{} depthProbeVerdict={} "
                            + "rect=[{},{} {}x{}] note=gateEps=max(gateEpsMin, gateEpsCfgBlocks×depthEpsPerBlock)，"
                            + "三项都是窗口深度单位；gateEpsBlocks 是按【本帧洞距】现算的格数"
                            + "（=gateEps/depthEpsPerBlock，随距离变，不是常量）；"
                            + "写入门用 GL depth test（quad 的 z = 洞心平面）；"
                            + "采样门用 Sampler1 的深度拷贝，判据是「采样点比 refDepth=max(本像素深度,洞心平面深度) "
                            + "更近就退回自采样」（正常情形 refDepth=本像素深度，与逐像素自比等价；"
                            + "洞面只当 dSelf 的下界 ⇒ 门不押在常数上，同时 uHoleDepth 因此被真的读到、不会被优化掉）",
                    PREFIX, depthOk, gate ? "on" : "off",
                    depthTestOn ? "on" : "off", Integer.toHexString(depthFunc),
                    depthFunc == GL_LEQUAL ? "lequal" : (depthFunc == GL_ALWAYS ? "always" : "?"),
                    depthWriteOn ? "true" : "false",
                    fd(z), fd(f.holeScreen[2]), fd(f.holeDepthWin),
                    depthTexState, depthTexSide,
                    fe(f.gateEps), gateEpsBlocksStr, fe(f.depthEpsPerBlock), fe(GATE_EPS_MIN),
                    f2(GATE_EPS_BLOCKS), epsFromBlocks ? "blocks" : "min",
                    fd(depthProbeMin), fd(depthProbeMax), depthProbeVerdict,
                    f1(f.rectX0), f1(f.rectY0), f1(f.rectW), f1(f.rectH));
        }
        final MultiBufferSource.BufferSource buffers =
                MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        try {
            final VertexConsumer vc = buffers.getBuffer(type);
            vc.vertex(x0, y0, z).endVertex();
            vc.vertex(x1, y0, z).endVertex();
            vc.vertex(x1, y1, z).endVertex();
            vc.vertex(x0, y1, z).endVertex();
        } finally {
            buffers.endBatch(type);                     // end() 里会 setupRenderState → draw → clearRenderState
            // clearRenderState 已把深度态还原成原版默认（enableDepthTest + LEQUAL + depthMask true）；
            // 这里再显式写一遍，是因为我们自己上面改过 depthMask/depthFunc（双保险，代价为零）
            RenderSystem.depthMask(true);
            RenderSystem.depthFunc(GL_LEQUAL);
            RenderSystem.enableDepthTest();
        }
    }

    /** {@code lens_gate} 判据行的节流（毫秒）：2 秒一行，够回读又不刷屏。 */
    public static final long LENS_GATE_LOG_MS = 2_000L;

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
        // ---- 🔴 2026-10-25 传送 bug：这条四边形从"世界空间公告板"改成【屏幕空间四边形】
        //   改前的写法（相机相对世界坐标 + ±camUp·half + ±camLeft·half）依赖
        //   `RenderSystem.getModelViewMatrix()` 等于"相机旋转矩阵"；
        //   而它真正的绘制时机在 GameRenderer.renderLevel 里 RenderSystem.clear(256) 之前，
        //   = LevelRenderer.renderLevel **返回之后**，那时 LevelRenderer 已经在方法尾执行过
        //   `posestack.popPose(); RenderSystem.applyModelViewMatrix();`（本地反编译
        //   LevelRenderer.java 第 1420-1421 行；相机旋转是在 1385-1387 行 push 进去的）
        //   ⇒ 此刻 modelview = 【单位阵】。于是那张四边形的屏幕位置跟"洞在哪"无关，
        //   只跟"洞相对于世界坐标轴的方位"有关 ⇒ 传送前恰好盖住洞、传送后掉到右下角。
        //   现在：矩形直接用**当帧** capture() 算出的 holeScreen / lensHalfPx / rect（一级画的同一块），
        //   顶点写 NDC，绘制期间临时把投影与 modelview 都当单位阵（vanilla GUI 用的同一套办法），
        //   画完在 finally 里原样还原。uv 按"矩形被屏幕裁掉多少"重算，黑洞贴图不会跟着矩形挤扁。
        final BlackHoleFallbackQuad.Plan plan = BlackHoleFallbackQuad.plan(
                f.holeScreen[0], f.holeScreen[1], f.lensHalfPx,
                f.rectX0, f.rectY0, f.rectW, f.rectH,
                f.screenW, f.screenH, f.holeScreen[2]);
        if (plan == null) {
            lastQuadSkip = "bad_plan";
            return false;
        }
        final RenderType type = quadType(fallbackTex);
        final Matrix4f identity = new Matrix4f();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(identity, VertexSorting.DISTANCE_TO_ORIGIN);
        final PoseStack mvs = RenderSystem.getModelViewStack();
        mvs.pushPose();
        mvs.last().pose().identity();
        RenderSystem.applyModelViewMatrix();
        try {
            final MultiBufferSource.BufferSource buffers =
                    MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
            final VertexConsumer vc = buffers.getBuffer(type);
            // 左下 → 右下 → 右上 → 左上（贴图 v 原点在上 ⇒ 底边用 vBottom、顶边用 vTop）
            // 顶点是【裁剪空间】：投影与 modelview 都已临时当单位阵，于是 Position 就是 NDC，
            // z = 洞心所在平面的 NDC 深度 ⇒ `lequal` 深度测试照旧让前面的方块挡住它（与一级同口径）。
            vc.vertex((float) plan.x0Ndc, (float) plan.y0Ndc, (float) plan.ndcZ)
                    .uv((float) plan.u0, (float) plan.vBottom).color(255, 255, 255, 255).endVertex();
            vc.vertex((float) plan.x1Ndc, (float) plan.y0Ndc, (float) plan.ndcZ)
                    .uv((float) plan.u1, (float) plan.vBottom).color(255, 255, 255, 255).endVertex();
            vc.vertex((float) plan.x1Ndc, (float) plan.y1Ndc, (float) plan.ndcZ)
                    .uv((float) plan.u1, (float) plan.vTop).color(255, 255, 255, 255).endVertex();
            vc.vertex((float) plan.x0Ndc, (float) plan.y1Ndc, (float) plan.ndcZ)
                    .uv((float) plan.u0, (float) plan.vTop).color(255, 255, 255, 255).endVertex();
            buffers.endBatch(type);
        } catch (Throwable t) {
            lastQuadSkip = "draw:" + t.getClass().getSimpleName();
            return false;                               // 画到一半炸了：finally 仍会把矩阵还原
        } finally {
            mvs.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
        lastQuadSkip = "none";
        if (!fallbackDrewLogged) {
            fallbackDrewLogged = true;
            GTDishanhaiMod.LOGGER.info("{} fallback_quad_ok（兜底：NativeImage+TextureManager 贴图，"
                            + "屏幕空间四边形 rect={} uv={} 半边长={}px tex={}）",
                    PREFIX, plan.rectText(), plan.uvText(), f1(plan.half), fallbackTex);
        }
        if (!fallbackPosLogged) {
            fallbackPosLogged = true;
            // 这一行是"二级到底画在哪"的判据：rect 必须与一级同一块、且必须落在屏幕里。
            GTDishanhaiMod.LOGGER.info("{} fallback_quad_pos hole=[{},{}] half={}px rect={} ndc=[{},{}..{},{}]"
                            + " screen={}x{} note=二级四边形与一级同矩形（屏幕空间），不再依赖 modelview",
                    PREFIX, f1(plan.holeX), f1(plan.holeY), f1(plan.half), plan.rectText(),
                    f2(plan.x0Ndc), f2(plan.y0Ndc), f2(plan.x1Ndc), f2(plan.y1Ndc),
                    f.screenW, f.screenH);
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
            // 🔴 2026-10-25 第四改：先给「可选但取不到」的那几项各打一行 INFO（不降级），
            //   再把它们并进 shader_ok 那一行 —— 这样"uHoleDepth 到底被 GLSL 优化掉了没有"
            //   在日志里是**可读的**（optional_missing=[] 就是"读到了"）。
            final java.util.List<String> optionalMissing = noteOptionalUniforms(sh);
            GTDishanhaiMod.LOGGER.info("{} shader_ok id={} uniforms_checked={} optional={} optional_missing={}",
                    PREFIX, SHADER_ID, REQUIRED_UNIFORMS.length, OPTIONAL_UNIFORMS.length, optionalMissing);
            return sh;
        } catch (Throwable t) {
            g.degradeTo(BlackHoleFallbackGuard.LEVEL_QUAD, "shader_load:" + t.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 必需清单里第一个"取不到"的 uniform；全都在 ⇒ {@code null}。
     *
     * <p>🔴 2026-10-25 第四改：<b>可选集合里的项一律跳过</b>（不构成降级理由）。
     * 这条 skip 是<b>双保险</b>：{@code uHoleDepth} 已经从 {@link #REQUIRED_UNIFORMS} 里移出去了，
     * 所以正常情况下这里根本轮不到它；但万一以后有人把某个可选项又写回上面那张表，
     * 也不许因此把整条一级透镜打成二级 —— <b>"着色器里没人读它"不是"着色器坏了"</b>。
     */
    private static String firstMissingUniform(ShaderInstance sh) {
        for (String name : REQUIRED_UNIFORMS) {
            if (sh.getUniform(name) != null) {
                continue;
            }
            if (BlackHoleLensSpec.isOptionalUniform(name)) {
                continue;                       // 可选项取不到 ⇒ 只记日志，不降级（见 noteOptionalUniforms）
            }
            return name;
        }
        return null;
    }

    /**
     * 给「可选但这份程序里没有」的 uniform 各打一行 INFO，并返回这次确实缺的那些名字。
     *
     * <p>🔴 判据（写在这里，免得下一个人再踩）：
     * <b>「着色器里没人读它」和「着色器坏了」是两件事</b>。
     * GLSL 编译器会把只声明、从不读取的 uniform 整条删掉（{@code getUniform} 返回 null，
     * vanilla 自己也会打一行 {@code could not find uniform named …}），这是<b>正常</b>的优化结果
     * ⇒ 必须只降级使用（少一个 uniform 的默认值），<b>不许降级整条链路</b>。
     * 而"不在可选集合里却取不到"才是真坏了（名字打错 / 链接失败 / 驱动问题），那条路仍是
     * {@link #firstMissingUniform} 的硬失败。
     *
     * <p>节流：<b>一面会话一次</b>（按名字去重，见 {@link #OPTIONAL_MISSING_LOGGED}）——
     * 降级后的退避重试每次都会重新 new ShaderInstance，不去重就会每 1~60 秒刷一行。
     */
    private static java.util.List<String> noteOptionalUniforms(ShaderInstance sh) {
        final java.util.List<String> missing = new java.util.ArrayList<>();
        for (String name : OPTIONAL_UNIFORMS) {
            if (sh.getUniform(name) != null) {
                continue;                       // 在 = 这次真读到了（正常情形：fsh 里那句 max 就是读它）
            }
            missing.add(name);
            if (OPTIONAL_MISSING_LOGGED.add(name)) {
                GTDishanhaiMod.LOGGER.info(BlackHoleLensSpec.optionalMissingLine(name));
            }
        }
        return missing;
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
     * 建立 / 复用<b>深度拷贝贴图</b>（边长 = {@link #bgTexSide}，与颜色拷贝共用同一套 uv）。
     *
     * <p>为什么用 NEAREST：深度纹理不能线性过滤（比较模式关闭时按普通纹理采，但驱动普遍只保证
     * NEAREST；而且这里要的就是"这个像素本来多深"，插值反而错）。
     *
     * <p>任何一步失败 {@link #depthGateDisabled} 一置位就<b>再也不试</b>：
     * 拿不到真深度时，门必须诚实地关掉（日志 {@code gate=off depthTex=fail}），
     * 而不是拿一张脏纹理把画面整块涂掉。
     */
    private static boolean ensureDepthTexture(BlackHoleFallbackGuard g) {
        if (depthGateDisabled) {
            return false;
        }
        if (depthTexId != -1 && depthTexSide == bgTexSide) {
            return true;
        }
        try {
            final int id = GlStateManager._genTexture();
            GlStateManager._bindTexture(id);
            GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            // 只分配存储（data = null）；内容由每帧的 glCopyTexSubImage2D 从深度缓冲灌进来
            // 🔴 2026-10-25 修 bug：type 必须是 GL_UNSIGNED_INT（不是 GL_FLOAT）。
            //   GL 4.6 表 8.2：DEPTH_COMPONENT24 × DEPTH_COMPONENT 只允许 UNSIGNED_INT。
            //   这条错误【不会抛异常】，只有 glGetError 能看到 ⇒ 下面这一段自检就是为它写的。
            drainGlErrors();                             // 先把历史错误排干，之后读到的才归因于这一次分配
            GlStateManager._texImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, bgTexSide, bgTexSide, 0,
                    GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, (IntBuffer) null);
            final int allocErr = GlStateManager._getError();   // ← 必须【紧跟着】读，别被后面的调用污染
            if (!depthAllocLogged) {
                depthAllocLogged = true;
                if (allocErr == 0) {
                    GTDishanhaiMod.LOGGER.info("{} depth_tex_alloc gl=0 ok internalformat=GL_DEPTH_COMPONENT24"
                                    + " format=GL_DEPTH_COMPONENT type=GL_UNSIGNED_INT side={}",
                            PREFIX, bgTexSide);
                } else {
                    GTDishanhaiMod.LOGGER.warn("{} depth_tex_alloc gl={} fail internalformat=GL_DEPTH_COMPONENT24"
                                    + " format=GL_DEPTH_COMPONENT type=GL_UNSIGNED_INT side={} ⇒ 采样门关闭"
                                    + "（这张贴图没有存储，采样只会读到 0 —— 那样的门会把整屏都判成前景）",
                            PREFIX, allocErr, bgTexSide);
                }
            }
            GlStateManager._bindTexture(0);
            if (allocErr != 0) {
                // 分配失败 ⇒ 立刻关掉门并回收这张空纹理（绝不拿"恒 0"的深度去拦画面）
                GlStateManager._deleteTexture(id);
                depthGateDisabled = true;
                return false;
            }
            if (depthTexId != -1) {
                GlStateManager._deleteTexture(depthTexId);
            }
            depthTexId = id;
            depthTexSide = bgTexSide;
            depthCopyVerified = false;
            depthGateDisabled = false;
            return true;
        } catch (Throwable t) {
            depthGateDisabled = true;
            if (!depthTexLogged) {
                depthTexLogged = true;
                GTDishanhaiMod.LOGGER.warn("{} lens_gate depth_tex_failed err={} ⇒ 采样侧深度门关闭"
                                + "（背景照旧弯折，但前景颜色会被搬到别处；这一条是诚实的降级，不是沉默失败）",
                        PREFIX, t.getClass().getSimpleName());
            }
            return false;
        }
    }

    /**
     * 把主渲染目标那块矩形的<b>深度</b>拷进 {@link #depthTexId}（与颜色拷贝同一个源矩形、同一套 uv）。
     *
     * <p>⚠️ 它与 {@link #copyBackground} 必须用<b>同一组</b> {@code x/y/w/h}：着色器里
     * {@code Sampler0} 与 {@code Sampler1} 共用 {@code uBgRect}/{@code uBgTexel}，
     * 差一格就会"颜色说那是背景、深度说那是前景"，门会乱拦。
     */
    private static void copyDepthRect(int x, int y, int w, int h) {
        if (depthTexId == -1 || depthGateDisabled) {
            return;
        }
        try {
            GlStateManager._bindTexture(depthTexId);
            GlStateManager._glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, x, y, w, h);
            GlStateManager._bindTexture(0);
            if (!depthCopyVerified) {
                depthCopyVerified = true;
                drainGlErrors();                        // 历史错误排干（同 copyBackground 的手法）
                GlStateManager._bindTexture(depthTexId);
                GlStateManager._glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, x, y, w, h);
                GlStateManager._bindTexture(0);
                final int err = GlStateManager._getError();
                if (err != 0) {
                    depthGateDisabled = true;
                    if (!depthTexLogged) {
                        depthTexLogged = true;
                        GTDishanhaiMod.LOGGER.warn("{} lens_gate depth_copy_failed gl={} ⇒ 采样侧深度门关闭"
                                + "（这条通路在你的驱动上不能拷深度；画面退回 2026-10-25 之前）", PREFIX, err);
                    }
                } else if (!depthTexLogged) {
                    depthTexLogged = true;
                    GTDishanhaiMod.LOGGER.info("{} depth_copy_ok kind=glCopyTexSubImage2D(depth→tex{}) side={} "
                                    + "note=这是「只有黑洞后面的东西才参与弯折」的数据来源", PREFIX, depthTexId, depthTexSide);
                }
            }
            // 🔴 2026-10-25：拷完之后【把贴图里的数读出来看一眼】—— 一面会话只做一次。
            //   这是决定性的证据：`depth_copy_ok` 只证明"拷贝没报错"，不证明"贴图里有数"。
            probeDepthOnce(x, y, w, h);
        } catch (Throwable t) {
            depthGateDisabled = true;
            if (!depthTexLogged) {
                depthTexLogged = true;
                GTDishanhaiMod.LOGGER.warn("{} lens_gate depth_copy_exception err={} ⇒ 采样侧深度门关闭",
                        PREFIX, t.getClass().getSimpleName());
            }
        }
    }

    /** 探针贴图边长（{@code depth_probe} 用；32² = 1024 个 float，回读+统计可忽略）。 */
    static final int DEPTH_PROBE_SIDE = 32;

    /**
     * 🔴 2026-10-25：<b>深度贴图实测回读探针</b>（一面会话只做一次，跑在第一次深度拷贝之后）。
     *
     * <h2>为什么非做不可</h2>
     * 实机日志给出的是 {@code depth_copy_ok}（拷贝 {@code glGetError()==0}）＋
     * {@code gate=on}，而画面<b>一点都不弯折</b>。这两个事实只能在两种病因里选一个：
     * <ol>
     *   <li><b>贴图里根本没数</b>（分配非法 / 拷贝被驱动忽略）⇒ 采样恒 0 ⇒ 门把整屏判成前景；</li>
     *   <li>贴图里有数，但门的阈值/判据错 ⇒ 也是全拦或全放。</li>
     * </ol>
     * 日志分不开，代码也分不开 —— 只有"把贴图里的 float 读出来"能分开。
     *
     * <h2>做法（三道保险，任何一道失败都如实写进日志）</h2>
     * <ol>
     *   <li>建一张 {@code DEPTH_PROBE_SIDE}² 的 depth24 探针贴图（type = {@code GL_UNSIGNED_INT}，与主贴图同口径）；</li>
     *   <li>先用 {@code glTexSubImage2D} 填 0 当<b>哨兵</b>：这样"回读全是 0"就直接证明
     *       <b>拷贝没写进去</b>，而不是"读到未初始化显存里的垃圾"（垃圾会给出一个假的 ok）；</li>
     *   <li>从当前帧 rect <b>中心</b>拷 32×32 进探针贴图 →
     *       {@code glGetTexImage(GL_DEPTH_COMPONENT, GL_FLOAT)} 回读 1024 个 float →
     *       min/max/mean/p05/p95。</li>
     * </ol>
     *
     * <p>判据：{@code max == 0}（全 0）⇒ 贴图没内容 ⇒ 立刻 {@link #depthGateDisabled} 并 WARN。
     * 回读这一步本身报错 ⇒ {@code verdict=failed}，<b>不</b>据此关掉门（那不是"贴图为空"的证据）。
     *
     * <p>状态恢复：探针贴图 {@code _deleteTexture}，绑定回 0（不留下任何一张贴图挂在 unit 上）。
     */
    private static void probeDepthOnce(int x, int y, int w, int h) {
        if (depthProbeDone || depthGateDisabled || depthTexId == -1) {
            return;
        }
        final int side = Math.min(DEPTH_PROBE_SIDE, Math.min(w, h));
        if (side < 8) {
            return;                                      // 这一帧矩形太小：下一帧再试（不置 depthProbeDone）
        }
        depthProbeDone = true;
        final int px = x + (w - side) / 2;               // rect 中心（不取角：角上常常是天空/无几何）
        final int py = y + (h - side) / 2;
        int probeTex = -1;
        try {
            drainGlErrors();
            probeTex = GlStateManager._genTexture();
            GlStateManager._bindTexture(probeTex);
            GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            GlStateManager._texImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, side, side, 0,
                    GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, (IntBuffer) null);
            // 哨兵：0 = 窗口深度 0.0（= 近平面）。真实几何的深度不可能全 0。
            final IntBuffer zeros = org.lwjgl.BufferUtils.createIntBuffer(side * side);
            GL11.glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, side, side,
                    GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, zeros);
            GlStateManager._glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, px, py, side, side);
            final FloatBuffer back = org.lwjgl.BufferUtils.createFloatBuffer(side * side);
            GL11.glGetTexImage(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT, GL_FLOAT, back);
            final int err = GlStateManager._getError();
            if (err != 0) {
                depthProbeVerdict = "failed";
                GTDishanhaiMod.LOGGER.warn("{} depth_probe src=[{},{} {}x{}] verdict=failed gl={} "
                                + "note=回读这一步在本机不可用 ⇒ 「贴图里到底有没有数」这一条【没有拿到证据】；"
                                + "门本身按逐像素自比工作：贴图为空时它会自然退回「全都弯折」，不会变成「一点都不弯折」",
                        PREFIX, px, py, side, side, err);
                return;
            }
            float min = Float.POSITIVE_INFINITY;
            float max = Float.NEGATIVE_INFINITY;
            double sum = 0.0D;
            int finite = 0;
            final int n = side * side;
            final float[] vals = new float[n];
            for (int i = 0; i < n; i++) {
                final float v = back.get(i);
                vals[i] = v;
                if (!Float.isFinite(v)) {
                    continue;
                }
                if (v < min) {
                    min = v;
                }
                if (v > max) {
                    max = v;
                }
                sum += v;
                finite++;
            }
            if (finite == 0) {
                min = Float.NaN;
                max = Float.NaN;
            }
            final float[] sorted = vals.clone();
            java.util.Arrays.sort(sorted);
            final float p05 = sorted[Math.min(n - 1, (int) Math.floor(n * 0.05D))];
            final float p95 = sorted[Math.min(n - 1, (int) Math.floor(n * 0.95D))];
            final float mean = finite == 0 ? Float.NaN : (float) (sum / finite);
            // 判据：max 必须严格大于 0（全 0 = 哨兵没被覆盖 = 拷贝没写进这张贴图）
            final boolean ok = finite > 0 && max > 0.0F;
            depthProbeVerdict = ok ? "ok" : "empty";
            depthProbeMin = min;
            depthProbeMax = max;
            GTDishanhaiMod.LOGGER.info("{} depth_probe src=[{},{} {}x{}] min={} max={} mean={} p05={} p95={} "
                            + "verdict={} note={}",
                    PREFIX, px, py, side, side, fd(min), fd(max), fd(mean), fd(p05), fd(p95),
                    depthProbeVerdict,
                    ok ? "深度贴图里确实有本帧世界的深度值（采样门的数据来源成立）"
                            : "深度贴图里【读不到任何深度值】（全 0 = 哨兵没被覆盖）⇒ 采样门没有数据来源");
            if (!ok) {
                // 🔴 这就是用户这次那个 bug 的形态：贴图没内容 ⇒ 旧判据（和洞心常数比）会把整屏判成前景。
                //   现在关掉门 = 退回 2026-10-25 之前的"全都弯折"（前景会被搬动，但背景一定在弯）。
                depthGateDisabled = true;
                GTDishanhaiMod.LOGGER.warn("{} lens_gate depth_probe_empty max=0 min={} ⇒ 采样门关闭"
                                + "（退回改前行为：背景照旧弯折、前景颜色可能被搬动；"
                                + "这一条是诚实的降级 —— 拿不到深度就不许拿它拦画面）",
                        PREFIX, fd(min));
            }
        } catch (Throwable t) {
            depthProbeVerdict = "failed";
            GTDishanhaiMod.LOGGER.warn("{} depth_probe src=[{},{} {}x{}] verdict=failed err={} "
                            + "note=探针本身抛异常 ⇒ 「贴图里有没有数」没有证据；门按逐像素自比工作，安全",
                    PREFIX, px, py, side, side, t.getClass().getSimpleName());
        } finally {
            GlStateManager._bindTexture(0);               // 恢复绑定状态（不留贴图挂在 unit 上）
            if (probeTex != -1) {
                GlStateManager._deleteTexture(probeTex);  // 临时贴图必须回收
            }
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
            if (f.inside) {
                BlackHoleInsideProbe.logOnce(BlackHoleInsideProbe.STAGE_COPY,
                        "src=[" + x + "," + y + " " + w + "x" + h + "] tex=" + bgTexId
                                + " side=" + bgTexSide + " main=[" + main.width + "x" + main.height + "]"
                                + " result=fail why=src_rect_out_of_main note=源矩形越出主目标"
                                + "（screenR=0 的洞内帧不该走到这里，rect 是屏幕中心正方形）");
                BlackHoleInsideProbe.markBreak(BlackHoleInsideProbe.BREAK_COPY, "src_rect_out_of_main");
            }
            return false;
        }
        try {
            // GL_FRAMEBUFFER（36160）同时绑读/写 ⇒ bindWrite 之后 READ framebuffer 就是主目标
            main.bindWrite(false);
            GlStateManager._bindTexture(bgTexId);
            GlStateManager._glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, x, y, w, h);
            GlStateManager._bindTexture(0);
            // 🔴 把"真正拷进贴图的整数矩形"记进这一帧：着色器 uBgRect 用它（见 Frame.copyX0 的注释）
            f.copyX0 = x;
            f.copyY0 = y;
            f.copyW = w;
            f.copyH = h;
            // 🔴 2026-10-25：颜色拷完之后紧跟着拷同一块矩形的深度（采样侧深度门的数据来源）。
            //   必须用同一组 x/y/w/h —— 两个采样器共用 uBgRect/uBgTexel。
            copyDepthRect(x, y, w, h);

            // ---- 探针②（洞内专属的一次性实测）：源矩形 / 目标纹理 / 此刻绑定的读+写 FBO / glGetError ----
            //   为什么还要再排干+再拷一次：上面的那次拷贝可能继承到别的 pass 留下的历史错误，
            //   那样"拷成功没有"就不是实测结论。一次会话只做一遍，成本可忽略。
            if (f.inside && !BlackHoleInsideProbe.isLogged(BlackHoleInsideProbe.STAGE_COPY)) {
                drainGlErrors();
                GlStateManager._bindTexture(bgTexId);
                GlStateManager._glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, x, y, w, h);
                GlStateManager._bindTexture(0);
                final int copyErr = GlStateManager._getError();
                BlackHoleInsideProbe.logOnce(BlackHoleInsideProbe.STAGE_COPY,
                        "src=[" + x + "," + y + " " + w + "x" + h + "] tex=" + bgTexId
                                + " side=" + bgTexSide + " main=[" + main.width + "x" + main.height + "]"
                                + " fb_read=" + GlStateManager._getInteger(GL_READ_FRAMEBUFFER_BINDING)
                                + " fb_draw=" + GlStateManager._getInteger(GL_DRAW_FRAMEBUFFER_BINDING)
                                + " result=" + (copyErr == 0 ? "ok" : "fail") + " gl=" + copyErr
                                + " note=洞内这一帧真的拷了这么多像素；这是采样数据的唯一来源");
                if (copyErr != 0) {
                    BlackHoleInsideProbe.markBreak(BlackHoleInsideProbe.BREAK_COPY, "copy_gl_" + copyErr);
                    return false;
                }
            }
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

    /**
     * 扔掉"待取走的那一帧读数"。
     *
     * <p>为什么单独一个方法：{@link #pending} 里装的正是<b>相机世界坐标 / 投影矩阵 / 屏幕尺寸 / 屏幕矩形</b>
     * 这一整份东西，而它是<b>跨帧</b>的（AFTER_WEATHER 采、合成之后取走）。任何一处清得不齐，
     * 就会出现"用上一帧的相机画这一帧"。改前 {@code capture()} 返回 null 时这里<b>根本没清</b>。
     */
    private static void clearPending() {
        pending = null;
        pendingSerial = -1;
    }

    /**
     * 🔴 <b>相机瞬移 / 换维度 ⇒ 整份跨帧缓存作废</b>（任务书第 2 条）。
     *
     * <h2>本件里"缓存了相机世界坐标 / 投影矩阵 / 屏幕尺寸"的字段（逐条列全）</h2>
     * <ol>
     *   <li>{@link #pending}（一个 {@link Frame}）—— {@code camPos} / {@code view} / {@code proj} /
     *       {@code screenW,H} / {@code holeScreen} / {@code rect*} / {@code camUp,camLeft}。
     *       <b>这是唯一一份跨帧的相机快照</b>；改前它会活过 capture 失败的那些帧（现在由
     *       帧号 {@link #pendingSerial} + {@link #clearPending()} 双保险）。</li>
     *   <li>{@link #copyVerified} —— 一次性自检旗标（验过就不再验），它假设"屏幕/目标没换过"。</li>
     *   <li>{@link #lastDrawnRenderTick} —— 防重复绘制，跟渲染刻绑定。</li>
     *   <li>{@link #fallbackPosLogged} —— 只打一次的诊断行（换环境后要能再打一次）。</li>
     *   <li>{@link #bgTexId} / {@link #bgTexSide} —— 显存里的拷贝贴图尺寸（按 {@code need} 增长，
     *       <b>不依赖相机</b>，所以不作废；它由 {@code ensureBgTexture} 自己按需重建）。</li>
     *   <li>{@link #centerCache} / {@link #centerRawCache} —— 黑洞<b>世界</b>坐标（配置来的），
     *       <b>不是相机量</b>，传送不影响它；它由配置字符串变化驱动。</li>
     * </ol>
     * 其余（{@code lensType} / {@code quadType} / {@code insideType} / {@code lensShader} / {@code guard}）
     * 都与相机无关。
     *
     * <h2>触发后做什么</h2>
     * 清跨帧快照 + 重做一次性自检 + 重探 Ops 状态 + <b>允许立刻重试一级</b>
     * （"条件重新满足"就包括"换了个场景"）。日志一行
     * {@code [SHANHAI-BH] camera_rebased from=…@dim to=…@dim moved=… why=teleport|dimension}。
     */
    private static void noteCameraRebase() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.gameRenderer == null) {
            CAMERA_GUARD.forget();
            clearPending();
            return;
        }
        final Camera cam = mc.gameRenderer.getMainCamera();
        if (cam == null) {
            CAMERA_GUARD.forget();
            return;
        }
        final Vec3 p = cam.getPosition();
        final String why = CAMERA_GUARD.update(p.x, p.y, p.z,
                mc.level.dimension().location().toString());
        if (why == null) {
            return;
        }
        clearPending();
        copyVerified = false;
        lastDrawnRenderTick = Integer.MIN_VALUE;
        fallbackPosLogged = false;
        BlackHoleIris.invalidate();
        LEVEL_RETRY.forceNext();                        // 瞬移/换维度 = "条件重新满足" ⇒ 立刻试一级
        GTDishanhaiMod.LOGGER.info(CAMERA_GUARD.line()
                + " note=跨帧相机缓存整份作废（相机世界坐标/投影/屏幕尺寸/一次性自检），"
                + "并放开一次一级重试");
    }

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

    /**
     * 这一帧真正用的视界半径（格）= 常数 × 运行期倍率；<b>主机接管优先</b>。
     *
     * <h2>🔴 2026-10-25 第二十改：归墟视界枢核的机器字段接管半径</h2>
     * 机器上那个 {@code hostBlackHoleRadiusBlocks} 是<b>玩家口径的可见半径（格）</b>，
     * 而本方法是 {@code rsBlocks}（视界半径）—— 两者差一个 {@code B_CRIT}，
     * 所以接管时走 {@link BlackHoleLensMath#rsBlocksForHoleRadius(double)} 反算，
     * <b>不动</b> {@link BlackHoleTuning#scale()}（命令设的倍率原样保留，主机一走就回来）。
     * <p>⚠️ 这一句是"屏幕上黑盘多大"的唯一入口：一级透镜（{@code uRs} / 矩形缩放）、
     * 二级静态贴图、以及 {@code HostFxBlackHole} 的兜底半径全部从它派生 ⇒ 只改这一处就三条路一起生效。
     */
    public static double rsBlocks() {
        ensureTuningInit();
        if (BlackHoleTuning.hasHostRadiusBlocks()) {
            return BlackHoleLensMath.rsBlocksForHoleRadius(BlackHoleTuning.hostRadiusBlocks());
        }
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
        closeInsideShaderQuietly();
        shaderTried = false;
        insideTried = false;
        insideBroken = false;
        insideLogged = false;
        copyVerified = false;
        lensOnLogged = false;
        lensEnvLogged = null;
        // 采样侧深度门：贴图与纹理都还在（跨重载有效），只把"打过哪一行"的节流作废 ⇒
        // 重载之后 lens_gate 立刻再打一条，回读时不会看到上一轮的状态。
        lensGateTag = null;
        lastLensGateLogMs = 0L;
        BlackHoleIris.invalidate();          // Oculus 的 pipeline 也可能被重载掉 ⇒ 立刻重探
        if (guard != null) {
            guard.reset();
        }
    }

    /**
     * 关掉洞内着色器（资源重载 / {@code /shanhai bh reload} 之后允许重试）。
     *
     * <p>同时作废这一轮的<b>探针记录与断点</b>：重载之后着色器会重新加载，
     * 旧断点留着只会让读数行一直显示上一轮的病灶 —— 而"这一轮断没断"才是要看的。
     */
    private static void closeInsideShaderQuietly() {
        try {
            if (insideShader != null) {
                insideShader.close();
            }
        } catch (Throwable ignored) {
            // 关不掉也不影响：重新 new 一个
        }
        insideShader = null;
        insideUniformMissing = new String[0];
        insideUniformPresent = 0;
        insideSkippedUniforms.clear();
        insideSkipLogged = false;
        BlackHoleInsideProbe.reset();
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
                // 🔴 第二十改：报的是**真正生效**的档位（主机接管时 = 机器字段），
                //   否则 /shanhai bh status 会写 lens=auto 而画面其实被机器关了 —— 那正是"日志骗人"。
                g.level(), g.reason(), BlackHoleTuning.effectiveLensMode().id(),
                enabled(), BlackHoleTuning.scale(), BlackHoleTuning.strength(), BlackHoleTuning.disk(),
                BlackHoleFrameStats.lastSkip(), center, rsBlocks(), BlackHoleFrameStats.lastDist(),
                BlackHoleFrameStats.lastRectW(), BlackHoleFrameStats.lastRectH(),
                BlackHoleFrameStats.lastScreenFraction(), BlackHoleFrameStats.lastTanHalfFov(),
                BlackHoleFrameStats.lastShaderpack());
    }

    /**
     * 采样侧深度门（"只有黑洞后面的东西才参与弯折"）的读数 —— 给 {@code /shanhai bh status} 用。
     *
     * <p>三个取值一次说清，不猜：
     * <ul>
     *   <li>{@code depthTex=ok} —— 深度拷贝成功，{@code Sampler1} 里是本帧世界的深度；
     *       {@code gate=on} 表示着色器里那道采样门真的在生效；</li>
     *   <li>{@code depthTex=fail} —— 你的驱动上拷不出深度（或建纹理失败）⇒ 门<b>诚实关掉</b>，
     *       画面退回 2026-10-25 之前（前景颜色会被搬动）；</li>
     *   <li>{@code depthTex=none} —— 还没画过（或深度缓冲不是本帧世界：光影生效时就是这样）。</li>
     * </ul>
     * 逐帧的真实 GL 深度态（depthTest/depthFunc/depthMask/quad 的 z）在 {@code lens_gate} 那行日志里，
     * 2 秒一条。
     */
    public static String lensGateLine() {
        final String tex = depthTexId == -1 ? "none" : (depthGateDisabled ? "fail" : "ok");
        final boolean on = depthTexId != -1 && !depthGateDisabled && BlackHoleTuning.depthGate();
        return "lens_gate gate=" + (on ? "on" : "off") + " depthTex=" + tex
                + " side=" + depthTexSide
                + " depthProbe=" + fd(depthProbeMin) + "/" + fd(depthProbeMax)
                + " depthProbeVerdict=" + depthProbeVerdict
                + " abSwitch=" + (BlackHoleTuning.depthGate() ? "on" : "off")
                + (depthGateDisabled
                        ? " note=深度拷不出来 / 贴图里读不到数 ⇒ 采样门已关闭"
                        + "（退回改前行为：背景照旧弯折，前景颜色可能被搬动）"
                        : "")
                + (BlackHoleTuning.depthGate() ? "" : " note=A/B 开关 depthgate=off ⇒ uDepthGate=0");
    }

    /** {@code /shanhai bh status} 里的洞内 uniform 读数（"缺哪个"点名，不猜）。 */
    public static String insideUniformLine() {        // 三种状态分开写：没加载过 / 加载失败 / 加载成功（present 才有意义）。
        // 混成一句 "present=0/13 missing=[]" 会让人误读成"13 个里一个都没取到"。
        if (!insideTried) {
            return "inside_uniforms state=not_loaded_yet（还没进过洞内分支）";
        }
        if (insideShader == null) {
            return "inside_uniforms state=load_failed（断点在 program，见 inside_break）";
        }
        return "inside_uniforms present=" + insideUniformPresent + "/"
                + insideRequiredUniforms().length
                + " missing=" + java.util.Arrays.toString(insideUniformMissing);
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
        closeInsideShaderQuietly();
        shaderTried = false;
        insideTried = false;
        insideBroken = false;
        insideLogged = false;
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
        closeInsideShaderQuietly();
        shaderTried = false;
        insideTried = false;
        insideBroken = false;
        insideLogged = false;
        copyVerified = false;
        lensOnLogged = false;
        lensEnvLogged = null;
        BlackHoleIris.invalidate();
        guard().logRestored();
        guard().reset();
        GTDishanhaiMod.LOGGER.info("{} reload_requested note=下一帧重新加载 shader（blackhole + blackhole_inside，"
                + "可重试一级与洞内）", PREFIX);
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
     * 窗口深度/容差用的格式化：<b>6 位小数</b>。
     *
     * <p>为什么不是 2 位：深度值普遍落在 {@code 0.9999xx}，2 位小数会把
     * 「洞心平面 0.999939」和「背景 0.999945」打成同一个 {@code 1.00} ——
     * 这正是上一轮日志「holeWinZ=1.00」看不出问题的原因。{@code %.6f} 让 1 个 24 位深度步长
     * （≈6e-8）在同一行里能分辨出来。
     */
    private static String fd(double v) {
        if (Double.isNaN(v)) {
            return "nan";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "inf" : "-inf";
        }
        return String.format(Locale.ROOT, "%.6f", v);
    }

    /**
     * 容差/每格深度用的格式化：<b>科学计数法 3 位小数</b>（{@code 1.218e-05}）。
     *
     * <p>为什么不复用 {@link #fd}：容差现在落在 {@code 1e-5} 量级，{@code %.6f} 只剩 1 位有效数字
     * （{@code 0.000012}）—— "0.75 格那一项胜出还是 1e-5 下限胜出"这件事会看不出来。
     * 深度读数（{@code 0.999xxx}）继续用 {@link #fd}，两套格式各管各的量级。
     */
    private static String fe(double v) {
        if (Double.isNaN(v)) {
            return "nan";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "inf" : "-inf";
        }
        return String.format(Locale.ROOT, "%.3e", v);
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

    /** 夹到 0..1（洞内深度与各处比例共用；与 {@link BlackHoleInsideSpec} 里那份同语义）。 */
    private static double clamp01(double v) {
        return v < 0.0D ? 0.0D : (v > 1.0D ? 1.0D : v);
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
