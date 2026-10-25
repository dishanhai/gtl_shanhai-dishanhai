package com.shanhai.client.render.hostfx;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;

import com.shanhai.ShanhaiMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import org.joml.Matrix4f;

/**
 * 归墟视界枢核 · <b>内外环之间那圈"伪神光柱环"（torus）</b> —— 用户要求 ①。
 *
 * <p>用户原话：「我希望可以把<b>伪神的那个光柱变粗</b>，然后<b>围绕在外环和内环中间</b>，
 * 就是在外环和内环中间用<b>伪神的光柱画一个环</b>，<b>要很粗，直径得有 5-7 格</b>」。
 *
 * <h2>尺寸与位置（一串数字，全部有真值来源）</h2>
 * <table border="1">
 *   <tr><th>项</th><th>值</th><th>为什么是这个数</th></tr>
 *   <tr><td>中心线半径</td><td><b>54.00 格</b></td>
 *       <td>自由环带 [49.50（内环带最外表面）, 58.50（飞船带最内表面）] 的正中；
 *           实测见 {@link HostFxBeamRingGeom} 类注释与 {@code temp/host-fx/ringar/spokes.mjs}</td></tr>
 *   <tr><td>管半径 / 管径</td><td><b>3.00 / 6.00 格</b></td>
 *       <td>用户硬线 5~7；取正中偏上，内沿 51.00 与 49.50 让 1.50、外沿 57.00 与 58.50 让 1.50（左右等距）</td></tr>
 *   <tr><td>段数 × 面数</td><td><b>64 × 16</b></td>
 *       <td>16 面 = 伪神主光柱的 {@code PILLAR_QUADS}（同一套观感）；64 段 ⇒ 拱高 0.065 格，肉眼无棱</td></tr>
 *   <tr><td>自转</td><td><b>12.0 °/s</b>（= {@code HostFxGeometry.RING_DEG_PER_SEC}）</td>
 *       <td>4 座解压机跟着内环带一起转 ⇒ 光柱的 4 个"暗窗"必须<b>钉在机器上</b>，只能同角速度</td></tr>
 *   <tr><td>贴图流速</td><td><b>21.33 格/秒</b></td>
 *       <td>{@code u = 弧长/256 + tick/240} ⇒ 12 秒流完 256 格（与中央主光柱逐字同一个式子）</td></tr>
 *   <tr><td>两遍</td><td>Intensity <b>2.0</b>（外层）/ <b>4.0</b>（内芯 ×0.45）</td>
 *       <td>= {@code HostFxPillarBeam} 的 soft / intense 两遍，颜色固定白、外层用 {@code BeamColor}</td></tr>
 * </table>
 *
 * <h2>为什么必须自建一个着色器（而不是直接复用 {@code shanhai_host_beam}）</h2>
 * 伪神那套 fsh 的 rim 光照是这么算的：
 * <pre>
 *   localPosition = Position.xy                       ← 顶点在"光束本地"的横截面坐标
 *   viewEffect    = dot(normalize(CameraPosition.xy), normalize(localPosition))
 * </pre>
 * 这要求<b>整根光束共享一个本地坐标系</b>（轴 = 本地 +Z）。管环的轴<b>沿环周一直在拐弯</b>
 * ⇒ 一个 buffer / 一个 ModelViewMat 里根本不存在这样一个坐标系：要么把环切成 N 段分别画
 * （N 次 {@code VertexBuffer} 上传 + N 次 draw，64 段 × 2 遍 = 128 次/帧，且贴图只能沿弦流），
 * 要么把 "横截面坐标" 换成<b>每个顶点自带的面法线</b>。
 * <p>这里选后者：新着色器 {@code shanhai_host_beam_ring} 是伪神那对的<b>逐行移植</b>，
 * 只把 {@code localPosition.xy} 换成 {@code Normal} 属性，rim 变成
 * {@code viewEffect = dot(normalize(Normal), normalize(CameraPosition − Position))}
 * —— 对圆柱/圆环在数学上是<b>同一个量</b>（法线与切线垂直 ⇒ 三维视线向量在横截面上的投影即原式），
 * 所以像素观感与伪神一致，而<b>贴图可以沿环周长映射</b>（用户要的"沿环流动"）、
 * <b>整环一次 draw</b>。贴图、顶点格式（多一个 Normal）、uniform、两遍 Intensity、混合、深度策略
 * 全部与 {@code shanhai_host_beam} 相同。
 *
 * <h2>回落（不许出现"环整圈不见了"）</h2>
 * 着色器建不起来（缺 json / 编译不过 / 无 GL）⇒ {@link #draw} 返回 0 ⇒ 调用方走
 * {@link #drawFallback}：同一份几何、CPU 端算同样的 rim 公式、写进 {@code HostFxDraw.beamLayer()}
 * 那条加性 RenderType。<b>代价</b>：淡出窗/rim 是逐顶点算的（管面 16 面上会有轻微几段感），
 * 且贴图用不上（纯顶点色）—— 但"环在不在"这件事不会翻转。
 *
 * <h2>资源重载</h2>
 * {@code F3+T} 由 {@link HostFxPillarBeamReloadHook} 同时通知本类（{@link #onResourceReload()}），
 * 与主光柱一样"只作废实例 + 打判据行"，真正的 {@code close()} 延到渲染线程下一次 {@link #shader()}。
 */
public final class HostFxBeamRing {

    private HostFxBeamRing() {}

    /** 着色器 id（{@code assets/shanhai/shaders/core/shanhai_host_beam_ring.json}）。 */
    public static final ResourceLocation SHADER_ID =
            new ResourceLocation("shanhai", "shanhai_host_beam_ring");

    /** 贴图：与伪神之锻炉那张 {@code space_layer.png} <b>同一份字节级副本</b>（复用主光柱那个常量）。 */
    public static final ResourceLocation TEXTURE = HostFxPillarBeam.TEXTURE;

    /**
     * 顶点格式：{@code Position + UV0 + UV1 + Normal}。
     *
     * <p>前三个与伪神 {@code BEAM_VERTEX_FORMAT} <b>逐项相同</b>（{@code UV1} 仍装 alpha×10000）；
     * 只多一个 {@code Normal}（{@code DefaultVertexFormat.ELEMENT_NORMAL}，byte×3）——
     * 它在 vsh 里被 {@code normalize()} 过，所以 byte 归一化与否都不影响方向。
     * <p>⚠️ <b>顺序就是协议</b>：{@code ShaderInstance} 按 {@code getElementAttributeNames()} 的顺序
     * 逐个 {@code glBindAttribLocation}（已 javap 核对：{@code ShaderInstance.<init>} 里
     * {@code VertexFormat.getElementAttributeNames()} → {@code Uniform.glBindAttribLocation}），
     * 而 {@code getElementAttributeNames()} 返回的就是这个 map 的 keySet。所以
     * <b>写顶点的顺序必须与 map 顺序一致</b>：{@code vertex → uv → overlayCoords → normal → endVertex}。
     */
    public static final VertexFormat BEAM_RING_VERTEX_FORMAT = new VertexFormat(ImmutableMap.of(
            "Position", DefaultVertexFormat.ELEMENT_POSITION,
            "UV0", DefaultVertexFormat.ELEMENT_UV0,
            "UV1", DefaultVertexFormat.ELEMENT_UV1,
            "Normal", DefaultVertexFormat.ELEMENT_NORMAL));

    /** alpha 打包比例（= 伪神 {@code ALPHA_PACK_SCALE}，直接复用主光柱那个常量）。 */
    public static final float ALPHA_PACK_SCALE = HostFxPillarBeam.ALPHA_PACK_SCALE;

    /** 着色器持有器（首帧懒建 + 重载后重建，语义全在 {@link HostFxLazyHandle}）。 */
    private static final HostFxLazyHandle<ShaderInstance> HANDLE =
            new HostFxLazyHandle<>(HostFxBeamRing::createShader);

    /**
     * <b>rim 取法的分支名</b>（2026-10-25 §5 的判据：日志里要能一眼看出跑的是哪一套）。
     *
     * <table border="1">
     *   <tr><th>值</th><th>含义</th></tr>
     *   <tr><td>{@value #RIM_AXIAL_PROJECTED}</td>
     *       <td><b>当前</b>：视线先减掉"沿管轴（环切线）"分量再与法线点积 —— 与伪神
     *           {@code normalize(CameraPosition.xy)} 等价，<b>无距离衰减</b>。</td></tr>
     *   <tr><td>{@value #RIM_PLAIN_3D}</td>
     *       <td>旧写法（bug）：三维都留 ⇒ 侧向段整片黑、越远黑得越多。只在"复现旧行为"时才用。</td></tr>
     * </table>
     */
    public static final String RIM_AXIAL_PROJECTED = "axial-projected（伪神 .xy 等价，无距离衰减）";
    /** 旧写法（有 bug）：三维直接点积，保留切线分量。 */
    public static final String RIM_PLAIN_3D = "plain-3d（旧写法，会随距离变黑）";
    /** 当前生效的 rim 分支（日志直接打这个字符串）。 */
    public static final String RIM_BRANCH = RIM_AXIAL_PROJECTED;

    /** 重载后待 close 的旧实例（GL 操作只许在渲染线程做）。 */
    private static ShaderInstance pendingClose;

    private static VertexBuffer buffer;

    /** 上一帧成功提交的遍数（正常 = 2：soft + intense；0 = 回落）。 */
    private static int lastPasses;
    /** 上一帧回落路径提交的四边形数（正常 = 0）。 */
    private static int lastFallbackQuads;
    /** 上一次打过 {@code ring_shader_ok} 的 build 序号。 */
    private static int loggedBuilds = -1;
    /** 上一次打过 {@code ring_shader_failed} 的 build 序号。 */
    private static int warnedBuilds = -1;
    /** 上一次打印"尺寸自检读数"的 build 序号（只打一次，不刷屏）。 */
    private static int loggedSelfCheck = -1;

    /** 上一帧提交的遍数（0 = shader 没起来；2 = soft+intense）。 */
    public static int passes() {
        return lastPasses;
    }

    /** 上一帧回落路径的四边形数（0 = 走的是新着色器）。 */
    public static int fallbackQuads() {
        return lastFallbackQuads;
    }

    /**
     * 记一笔"这一帧<b>故意</b>没画光柱环"（降级态整圈跳过时由 {@code HostFxDraw} 调）。
     *
     * <p>与 {@link HostFxPillarBeam#markMdBeamsSkipped()} 同一个理由：{@link #draw} 的开头才清零，
     * 跳过时不调它 ⇒ {@code stats()} 里的 {@code passes=/2} 会停在上一次正常帧的 2，日志就成了假读数。
     */
    public static void markSkipped() {
        lastPasses = 0;
        lastFallbackQuads = 0;
    }

    /** 着色器是否就绪。 */
    public static boolean ready() {
        return shader() != null;
    }

    /** 一行摘要（{@code HostFxDraw.stats()} 里带上）。 */
    public static String stats() {
        return HostFxBeamRingGeom.stats()
                + " 本帧 passes=" + lastPasses + "/2"
                + (lastFallbackQuads > 0 ? ("（回落自绘 " + lastFallbackQuads + " 四边形）") : "")
                + " shader=" + SHADER_ID.getPath()
                + " 实例 identity=" + Integer.toHexString(HANDLE.identityHash())
                + " 建过 " + HANDLE.builds() + " 次";
    }

    /**
     * 资源重载信号（由 {@link HostFxPillarBeamReloadHook} 调）。
     *
     * @return 是否真的作废了一个在用的旧实例
     */
    public static boolean onResourceReload() {
        final ShaderInstance old = HANDLE.invalidate();
        if (old != null) {
            pendingClose = old;
        }
        loggedBuilds = -1;
        warnedBuilds = -1;
        ShanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] ring_shader_reload rebuilt=1 oldIdentity={} builds={} "
                        + "hadOld={}（下一次 draw 重建；判据：F3+T 后光柱环跟着变）",
                old == null ? "none" : Integer.toHexString(System.identityHashCode(old)),
                HANDLE.builds(), old != null);
        return old != null;
    }

    /** 换维度时丢弃。 */
    public static void reset() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
        final ShaderInstance old = HANDLE.invalidate();
        if (old != null) {
            old.close();
        }
        pendingClose = null;
        loggedBuilds = -1;
        warnedBuilds = -1;
        loggedSelfCheck = -1;
        lastPasses = 0;
        lastFallbackQuads = 0;
    }

    /** 工厂：<b>全类唯一的</b> {@code new ShaderInstance(...)}（资源管理器没起来时返回 null = 下次再试）。 */
    private static ShaderInstance createShader() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getResourceManager() == null) {
            return null;
        }
        try {
            return new ShaderInstance(mc.getResourceManager(), SHADER_ID, BEAM_RING_VERTEX_FORMAT);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("ShaderInstance 建不起来：" + SHADER_ID
                    + "（缺 assets/shanhai/shaders/core/shanhai_host_beam_ring.json？）", e);
        }
    }

    /** 每帧取一次实例；顺带做"重载后旧实例的延后 close"与两条探针日志。 */
    private static ShaderInstance shader() {
        final ShaderInstance dead = pendingClose;
        if (dead != null) {
            pendingClose = null;
            dead.close();
        }
        final ShaderInstance s = HANDLE.get();
        if (s != null && loggedBuilds != HANDLE.builds()) {
            loggedBuilds = HANDLE.builds();
            ShanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] ring_shader_ok id={} 顶点格式={} identity={} 第 {} 次建"
                            + " | ring_dist_fade=off（rim 已按伪神 normalize(...xy) 口径投影掉管轴分量）"
                            + " | ring_gaps={} | ring_rim_mode={}+floor({})",
                    SHADER_ID, BEAM_RING_VERTEX_FORMAT,
                    Integer.toHexString(System.identityHashCode(s)), HANDLE.builds(),
                    HostFxBeamRingGeom.FADE_AT_MACHINES ? "on" : "off", RIM_BRANCH,
                    String.format(java.util.Locale.ROOT, "%.2f", HostFxBeamRingGeom.RIM_FLOOR));
        }
        if (s != null && loggedSelfCheck != HANDLE.builds()) {
            loggedSelfCheck = HANDLE.builds();
            // 判据：管径必须在 5~7；内沿 > 内环带最外表面；外沿 < 飞船带最内表面；
            //      与辐条/解压机相交的格必须全在淡出窗里
            final String bad = HostFxBeamRingGeom.selfCheck();
            if (bad == null) {
                ShanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] ring_size_ok 判据：{} | {}",
                        HostFxBeamRingGeom.stats(), HostFxBeamRingGeom.selfCheckReading());
            } else {
                ShanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] ring_size_mismatch {} | {} | {}",
                        bad, HostFxBeamRingGeom.stats(), HostFxBeamRingGeom.selfCheckReading());
            }
        }
        if (s == null && HANDLE.failed() && warnedBuilds != HANDLE.builds()) {
            warnedBuilds = HANDLE.builds();
            ShanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] ring_shader_failed id=" + SHADER_ID
                    + "（回落到 CPU rim 自绘，光柱环不会消失）", HANDLE.lastError());
        }
        return s;
    }

    private static VertexBuffer buffer() {
        if (buffer == null) {
            buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        }
        return buffer;
    }

    /**
     * 画管环（soft + intense 两遍，一次 draw 一遍）。
     *
     * <p>⚠️ 调用方必须已经套好 <b>环心枢轴 + 内环带自转角</b>（{@link HostFxRingTransforms#pivot} /
     * {@link HostFxRingTransforms#spin}），并且 {@code camLocalX/Y/Z} 必须是<b>同一帧、同一自转后</b>
     * 的相机坐标（{@code HostFxGeometry.rotateYDeg(-spinDeg, …)}）—— 否则 {@code CameraPosition}
     * 差一个自转角，rim 会随自转"呼吸"。
     *
     * @param seconds      世界时间（秒）⇒ {@code tick = seconds × 20} 进 UV
     * @param spinDeg      本帧的自转角（度）—— 淡出窗要跟着它转（钉在解压机上）
     * @param camX/Y/Z     相机在<b>自转后的环心本地帧</b>里的位置
     * @param glowRgb      外层发光色（{@link HostFxBeamRingGeom#GLOW_RGB}）
     * @return 提交的遍数（2 = 正常；0 = 着色器没起来，调用方应用 {@link #drawFallback}）
     */
    public static int draw(PoseStack poseStack, float seconds, float spinDeg,
                           float camX, float camY, float camZ, float[] glowRgb) {
        lastPasses = 0;
        lastFallbackQuads = 0;
        final ShaderInstance s = shader();
        if (s == null) {
            return 0;
        }
        final float tick = seconds * 20.0F;
        final Matrix4f model = poseStack.last().pose();
        RenderSystem.enableBlend();
        // 伪神 JSON 的 blend = add / srcalpha / 1-srcalpha，这里显式再来一次（shader.apply 也会套）
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();                 // 空心管：双面都要（近端不破洞）
        RenderSystem.setShaderTexture(0, TEXTURE);
        try {
            // ① soft 遍：外层，颜色 = BeamColor
            setUniform(s, "CameraPosition", camX, camY, camZ);
            setUniform(s, "BeamColor", glowRgb[0], glowRgb[1], glowRgb[2]);
            setUniform(s, "Intensity", HostFxBeamRingGeom.INTENSITY_SOFT);
            setUniform(s, "RimFloor", HostFxBeamRingGeom.RIM_FLOOR);
            upload(s, HostFxBeamRingGeom.OUTER_R_SCALE, HostFxBeamRingGeom.OUTER_ALPHA, tick, spinDeg);
            buffer().bind();
            buffer().drawWithShader(model, RenderSystem.getProjectionMatrix(), s);
            lastPasses = 1;

            // ② intense 遍：内芯（半径 ×0.45），颜色固定白（伪神传 1,1,1）
            setUniform(s, "BeamColor", 1.0F, 1.0F, 1.0F);
            setUniform(s, "Intensity", HostFxBeamRingGeom.INTENSITY_INTENSE);
            setUniform(s, "RimFloor", HostFxBeamRingGeom.RIM_FLOOR);
            upload(s, HostFxBeamRingGeom.INNER_R_SCALE, HostFxBeamRingGeom.INNER_ALPHA, tick, spinDeg);
            buffer().bind();
            buffer().drawWithShader(model, RenderSystem.getProjectionMatrix(), s);
            lastPasses = 2;
        } finally {
            VertexBuffer.unbind();
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
        }
        return lastPasses;
    }

    /** 建顶点并上传（每遍一次）。 */
    private static void upload(ShaderInstance shader, float radiusScale, float alpha,
                               float tick, float spinDeg) {
        final BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(Mode.TRIANGLES, BEAM_RING_VERTEX_FORMAT);
        // 顺序必须与 BEAM_RING_VERTEX_FORMAT 的 key 顺序一致：Position → UV0 → UV1 → Normal
        // 🔴 UV1 = (alpha×10000, 方位角×10)：vsh 用后者重建管轴切线（§5 距离衰减修复的关键数据）
        HostFxBeamRingGeom.emitTube((x, y, z, nx, ny, nz, u, v, a, azDeg) -> builder
                .vertex(x, y, z)
                .uv(u, v)
                .overlayCoords(packAlpha(a), packAzimuthDeg(azDeg))
                .normal(nx, ny, nz)
                .endVertex(), radiusScale, alpha, tick, spinDeg);
        buffer().bind();
        buffer().upload(builder.end());
    }

    /** 方位角（度）打包成 {@code UV1.y}：×10 ⇒ 0.1° 精度，0..3599 落在 short 里。 */
    private static int packAzimuthDeg(float azimuthDeg) {
        int v = Math.round(((azimuthDeg % 360.0F) + 360.0F) % 360.0F * 10.0F);
        if (v >= 3600) {
            v -= 3600;
        }
        return v & 0xFFFF;
    }

    /**
     * 回落：同一份几何 + <b>CPU 端算同样的 rim 公式</b>，写进加性 RenderType。
     *
     * <p>rim 逐顶点算：{@code alpha = clamp(pow(max((dot(N, normalize(cam−P)) − 0.5) × 2, 0), 2), 0, 1)}
     * —— 与 fsh 的 {@code angleAlpha} 同一个式子（Intensity 取 soft 遍的 2.0）。
     * 代价：管面 16 等分上逐顶点 ⇒ 横向会有轻微条纹感；没有贴图（纯顶点色）。
     *
     * @return 提交的四边形数
     */
    public static int drawFallback(PoseStack poseStack, float seconds, float spinDeg,
                                   float camX, float camY, float camZ, float[] glowRgb) {
        lastFallbackQuads = 0;
        final float tick = seconds * 20.0F;
        final Matrix4f m = poseStack.last().pose();
        final net.minecraft.client.renderer.RenderType layer = HostFxDraw.beamLayer();
        final net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers =
                net.minecraft.client.renderer.MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        final VertexConsumer vc = buffers.getBuffer(layer);
        final int[] written = {0};
        // 外层：颜色 = glowRgb
        HostFxBeamRingGeom.emitTube(new RimSink(vc, m, glowRgb[0], glowRgb[1], glowRgb[2],
                        camX, camY, camZ, written),
                HostFxBeamRingGeom.OUTER_R_SCALE, HostFxBeamRingGeom.OUTER_ALPHA, tick, spinDeg);
        // 内芯：固定白（与伪神 intense 遍一致）
        HostFxBeamRingGeom.emitTube(new RimSink(vc, m, 1.0F, 1.0F, 1.0F,
                        camX, camY, camZ, written),
                HostFxBeamRingGeom.INNER_R_SCALE, HostFxBeamRingGeom.INNER_ALPHA, tick, spinDeg);
        buffers.endBatch(layer);
        lastFallbackQuads = written[0];
        return lastFallbackQuads;
    }

    /**
     * 回落路径的顶点收集器：每 6 个顶点（= 一个管面的 2 个三角形）写成<b>两个四边形</b>。
     *
     * <p>为什么要凑成四边形：{@code HostFxDraw.beamLayer()} 是 {@code Mode.QUADS}
     * （全项目的光束层都是它）。把三角形写成"第 4 个顶点重复第 3 个"的四边形 ⇒
     * 第二个三角形面积 0，光栅化阶段直接丢弃，像素结果与直接画三角形<b>逐点相同</b>，
     * 但不需要为回落路径再建一条 RenderType。
     */
    private static final class RimSink implements HostFxBeamRingGeom.Sink {
        private final VertexConsumer vc;
        private final Matrix4f m;
        private final float r;
        private final float g;
        private final float b;
        private final float camX;
        private final float camY;
        private final float camZ;
        private final int[] written;
        private final float[] px = new float[6];
        private final float[] py = new float[6];
        private final float[] pz = new float[6];
        private final float[] pa = new float[6];
        private int n;

        RimSink(VertexConsumer vc, Matrix4f m, float r, float g, float b,
                float camX, float camY, float camZ, int[] written) {
            this.vc = vc;
            this.m = m;
            this.r = r;
            this.g = g;
            this.b = b;
            this.camX = camX;
            this.camY = camY;
            this.camZ = camZ;
            this.written = written;
        }

        @Override
        public void vertex(float x, float y, float z, float nx, float ny, float nz,
                           float u, float v, float alpha, float azimuthDeg) {
            px[n] = x;
            py[n] = y;
            pz[n] = z;
            pa[n] = rimAlpha(x, y, z, nx, ny, nz, camX, camY, camZ, azimuthDeg) * alpha;
            n++;
            if (n == 6) {
                quadOf3(0, 1, 2);
                quadOf3(3, 4, 5);
                written[0] += 2;
                n = 0;
            }
        }

        /** 三角形 (i,j,k) 写成一个"第 4 顶点重复"的四边形。 */
        private void quadOf3(int i, int j, int k) {
            final float a = pa[i];
            vc.vertex(m, px[i], py[i], pz[i]).color(r, g, b, a).endVertex();
            vc.vertex(m, px[j], py[j], pz[j]).color(r, g, b, a).endVertex();
            vc.vertex(m, px[k], py[k], pz[k]).color(r, g, b, a).endVertex();
            vc.vertex(m, px[k], py[k], pz[k]).color(r, g, b, a).endVertex();
        }
    }

    /**
     * fsh 里 {@code angleAlpha} 的 <b>CPU 版</b>（Intensity 取 soft 遍 2.0）。
     *
     * <p>🔴 2026-10-25 §5：这里必须和 fsh 一样<b>先把视线的切线分量减掉</b>再点积 ——
     * 第一版直接 {@code dot(N, normalize(cam−P))}，侧向那两段会拿到负值 ⇒ alpha=0
     * （"远段整片黑"的真因）。判据：{@code RingRimProbe} 里 NEW 那一列。
     */
    private static float rimAlpha(float x, float y, float z, float nx, float ny, float nz,
                                  float camX, float camY, float camZ, float azimuthDeg) {
        float vx = camX - x;
        float vy = camY - y;
        float vz = camZ - z;
        // 切线 = (−sinθ, 0, cosθ)
        final float th = (float) Math.toRadians(azimuthDeg);
        final float tanX = -(float) Math.sin(th);
        final float tanZ = (float) Math.cos(th);
        final float axial = vx * tanX + vz * tanZ;
        vx -= axial * tanX;
        vz -= axial * tanZ;
        float len = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (len < 1.0E-4F) {
            // 退化：正对管口 ⇒ 用切线当视线（与 fsh 的兜底一致）
            vx = tanX;
            vy = 0.0F;
            vz = tanZ;
            len = 1.0F;
        }
        vx /= len;
        vy /= len;
        vz /= len;
        final float nlen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nlen < 1.0E-4F) {
            return 0.0F;
        }
        final float d = Math.abs((nx * vx + ny * vy + nz * vz) / nlen);
        final float a = Math.max((d - 0.5F) * 2.0F, 0.0F);
        final float rim = (float) Math.pow(a, HostFxBeamRingGeom.INTENSITY_SOFT);
        // 与 fsh 同一个下限：任何角度都不许塌到 0
        return HostFxBeamRingGeom.RIM_FLOOR + (1.0F - HostFxBeamRingGeom.RIM_FLOOR) * rim;
    }




    private static void setUniform(ShaderInstance shader, String name, float... values) {
        final Uniform u = shader.getUniform(name);
        if (u == null) {
            return;
        }
        switch (values.length) {
            case 1 -> u.set(values[0]);
            case 2 -> u.set(values[0], values[1]);
            case 3 -> u.set(values[0], values[1], values[2]);
            default -> u.set(values[0], values[1], values[2], values[3]);
        }
    }

    /** {@code clamp(round(alpha × 10000), -32768, 32767) & 0xFFFF}（伪神 {@code packAlpha}）。 */
    private static int packAlpha(float alpha) {
        final int v = Math.round(alpha * ALPHA_PACK_SCALE);
        return Math.max(-32768, Math.min(32767, v)) & 0xFFFF;
    }
}
