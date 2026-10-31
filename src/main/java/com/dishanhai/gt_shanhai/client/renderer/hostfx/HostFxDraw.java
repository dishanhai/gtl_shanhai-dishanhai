package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * 归墟视界枢核 · <b>唯一保留的自绘特效</b>：12 条细紫引导光束（每座解压机 3 条）＋ 4 条解压机主喷口光柱
 * ＋ 中央上下 2 根主光柱（后两组走伪神同款 shader，见 {@link HostFxPillarBeam}）。
 *
 * <h2>内环与飞船去哪了</h2>
 * 已经不在这里画了 —— 按用户口径改成"真方块跟着转"（{@link HostFxBaked} + {@link HostFxRingGroups}）。
 * 本类只负责光束的<b>提交</b>；细紫光束几何在 {@link HostFxGeometry#buildBeamQuads}
 * （纯数学，离线探针共用），伪神同款那 6 根在 {@link HostFxPillarBeam}。
 *
 * <h2>2026-10-25 第七改（本轮）：细光束改绕"每座解压机"、4 条主喷口光柱换伪神同款</h2>
 * <ul>
 *   <li><b>12 条细紫光束</b>：从"绕中央主光柱一圈"改成<b>每座解压机 3 条</b>（绕本座喷口轴
 *       r=1.90、互成 120°、方向与本座主柱夹角 0°）—— 用户：「我说的小光束是围绕这内环上面
 *       4 个物质解压机的啊，每个物质解压机围绕 3 个……不是围绕上下两条光束的」；</li>
 *   <li><b>4 条主喷口光柱</b>：从"自绘管壳 + 几何粒子"整根换成 {@link HostFxPillarBeam#drawMdBeams}
 *       （与中央两根同一套 shader/贴图/uniform）—— 用户：「还有 4 个物质解压机的主光束也换成
 *       伪神同款的啊」；</li>
 *   <li>⇒ 正常路径的<b>自绘四边形只剩 1728 片</b>（12 条细紫 × (18×4 + 12×6)），粒子 0；
 *       6 根光柱的流动全部来自 space_layer 贴图 UV 沿轴滚（tick/240）。回落路径见
 *       {@link HostFxGeometry#buildMdBeamQuads} / {@link HostFxGeometry#buildPillarQuads}。</li>
 * </ul>
 *
 * <h2>2026-10-24 第二次改：从"手写 GL 状态"改成"一个 RenderType + 每批 endBatch()"</h2>
 * 上一版是 {@code Tesselator.getBuilder()} 写顶点、再交给 {@code BufferUploader} 的 {@code drawWithShader}
 * 提交，状态靠 {@code GL11.glIsEnabled/glGetBoolean} 手工存取恢复。这一版改成 MC 的正规批处理：
 * <pre>
 *   RenderType = POSITION_COLOR + 加性混合 + 不写深度 + 不剔除 + 无贴图
 *   BufferSource buffers = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder())
 *   vc = buffers.getBuffer(LAYER) → 写细紫光束的顶点 → buffers.endBatch(LAYER)   ← 每批末尾一次
 * </pre>
 * 好处有三个，都是可验证的：
 * <ol>
 *   <li><b>状态由 RenderType 自己 setup/clear</b>（{@code type.draw} 内部一对 setupRenderState/clearRenderState），
 *       不用手工保存恢复，也就不会把"混合/深度写/剔除"泄漏给后面的渲染器；</li>
 *   <li><b>{@code endBatch()} 是"这一批真的提交了"的唯一判据</b> —— 本类把它记进
 *       {@link #batchFlushes()}（正常情况下 1；主柱回落时各 +1），渲染器的 {@code draw} 日志会打出来；</li>
 *   <li>少一次 {@code BufferUploader} 的直接调用 ⇒ 不再依赖"当前 shader 是谁"这种隐式状态。</li>
 * </ol>
 *
 * <h2>参数（逐条对应用户口径，离线探针逐条量）</h2>
 * <table border="1">
 *   <tr><th>项</th><th>值</th><th>落在哪</th></tr>
 *   <tr><td>条数</td><td>18（4 主喷口 + 12 细紫 + 2 中央主光柱；条数不变，"谁画"变了 2 处）</td>
 *       <td>{@link HostFxGeometry#buildBeamQuads} / {@link HostFxPillarBeam}</td></tr>
 *   <tr><td>细紫 12 条</td><td><b>每座 3 条</b>，绕本座喷口轴 r=1.90、互成 120°、与本座主柱夹角 0°</td>
 *       <td>{@link HostFxGeometry#thinBeamSegment}</td></tr>
 *   <tr><td>主喷口 4 条</td><td>伪神同款：外层 1.25→1.55 格、内芯 ×0.45、Intensity 2.0/4.0、颜色可配</td>
 *       <td>{@link HostFxPillarBeam#drawMdBeams}</td></tr>
 *   <tr><td>中央主光柱 2 条</td><td>伪神同款：外层 6.20→3.80 格、内芯 ×0.45</td>
 *       <td>{@link HostFxPillarBeam#draw}</td></tr>
 *   <tr><td>细分</td><td>细紫 18×4 / 发光壳 12×6（主柱是 16 片三角形/遍）</td>
 *       <td>{@code THIN_BEAM_*} / {@code PILLAR_QUADS}</td></tr>
 *   <tr><td>半径</td><td>细紫峰值 0.10（引导用，细）</td><td>{@code THIN_BEAM_PEAK_RADIUS}</td></tr>
 *   <tr><td>粒子</td><td>正常路径 0（流动来自贴图 UV 滚动）；仅回落路径 4×16 颗</td>
 *       <td>{@link HostFxGeometry#buildParticleQuads}</td></tr>
 *   <tr><td>贴图</td><td>细紫：无（纯顶点色）／主柱：直接引用 gtladditions 的 space_layer.png</td>
 *       <td>{@code THIN_BEAM_COLOR} / {@code HostFxPillarBeam.TEXTURE}</td></tr>
 *   <tr><td>混合</td><td>细紫：加性（SRC_ALPHA, ONE）／主柱：伪神 JSON 里的 add + SRC_ALPHA/ONE_MINUS</td>
 *       <td>{@link #ADDITIVE} / {@code HostFxPillarBeam}</td></tr>
 *   <tr><td>深度</td><td>关深度写（测试照常）</td><td>{@code WriteMaskStateShard(true, false)}</td></tr>
 *   <tr><td>提交</td><td>细紫每帧一次 {@code endBatch()}</td><td>{@link #drawBeams} / {@link #drawMatterDecompressors}</td></tr>
 * </table>
 */
public final class HostFxDraw {

    private HostFxDraw() {}

    /** 山海：保留 master 的運行狀態亮度，供 shader 與 CPU 回落共用。 */
    private static float beamBrightness = 1.0F;

    public static float beamBrightness() {
        return beamBrightness;
    }

    /** 光束着色器：原版 {@code position_color}（顶点色直接当片元色，不采样任何贴图）。 */
    private static final RenderStateShard.ShaderStateShard BEAM_SHADER =
            new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader);

    /**
     * <b>加性</b>混合：{@code src × SRC_ALPHA + dst × ONE}。
     *
     * <p>顶点 alpha 就是亮度包络 ⇒ 包络直接当权重生效（这也是为什么必须用 {@code SRC_ALPHA, ONE}
     * 而不是原版 {@code ADDITIVE_TRANSPARENCY} 的 {@code ONE, ONE}：后者会把 alpha 丢掉，包络就白算了）。
     * 加性混合与提交顺序无关 ⇒ 不需要排序（见 {@link #beamLayer()} 的 {@code sortOnUpload=false}）。
     */
    private static final RenderStateShard.TransparencyStateShard ADDITIVE =
            new RenderStateShard.TransparencyStateShard("shanhai_hostfx_beam_additive",
                    () -> {
                        RenderSystem.enableBlend();
                        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                                GlStateManager.DestFactor.ONE);
                    },
                    () -> {
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                    });

    /** 懒建的批处理层（{@code RenderType.create} 一帧只跑一次）。 */
    private static RenderType beamLayer;
    private static RenderType nebulaLayer;

    /** 上一帧提交的四边形数（日志/自检用；每帧应 = {@link HostFxGeometry#beamQuadCount()}）。 */
    private static int lastQuads;
    /** 上一帧 {@code endBatch()} 的次数（每帧应恰好 1；主光柱回落时 +1）。 */
    private static int lastBatches;
    /** 上一帧的流动粒子四边形数（应 = {@link HostFxGeometry#particleQuadCount()}）。 */
    private static int lastParticles;
    /** 上一帧中央主光柱真正画出去的遍数（应 = 4：上下各 soft+intense）。 */
    private static int lastPillarPasses;
    /** 上一帧 4 座解压机主柱真正画出去的遍数（应 = 8：4 根各 soft+intense）。 */
    private static int lastMdBeamPasses;
    /** 上一帧主光柱<b>回落</b>的自绘四边形数（0 = 走的是伪神 shader，正常就该是 0）。 */
    private static int lastFallbackPillarQuads;
    /** 上一帧 <b>4 条解压机主柱</b>回落的四边形数（管壳 + 粒子；0 = 走的是伪神 shader，正常就该是 0）。 */
    private static int lastFallbackMdBeamQuads;
    /** 上一幀外緣吸積流提交遍數（正常 1，shader 未就緒 0）。 */
    private static int lastRingPasses;
    /** 上一帧光柱环回落的自绘四边形数（正常 = 0）。 */
    private static int lastFallbackRingQuads;

    /** 上一帧提交的四边形数（{@code draw} 日志用）。 */
    public static int quads() {
        return lastQuads;
    }

    /** 上一帧的粒子数（{@code draw} 日志用）。 */
    public static int particles() {
        return lastParticles;
    }

    /** 上一帧 {@code endBatch()} 次数（{@code draw} 日志用；0 = 这批根本没提交）。 */
    public static int batchFlushes() {
        return lastBatches;
    }

    /** 一行摘要（渲染器 {@code draw} 日志里打这一行）。 */
    public static String stats() {
        return "beamQuads=" + lastQuads + "/" + HostFxGeometry.beamQuadCount()
                + "（细紫管壳 " + HostFxGeometry.tubeQuadCount() + " + 回落粒子 " + lastParticles
                + "）"
                + " endBatch=" + lastBatches
                // 🔴 2026-10-24 傍晚：飞船改真方块烘焙 ⇒ 这里报"自绘飞船方盒 = 0"（判据：没有回退）
                + " | shipSelfBoxes=0"
                + " | mdBoxes=" + lastMdBoxes + "/" + HostFxGeometry.mdBoxTotal()
                + " mdBatch=" + lastMdBatches
                // 🔴 2026-10-25 第五改：中央主光柱改成伪神同款（shader + space_layer 贴图），
                //    判据：passes 恒 4；回落自绘四边形恒 0
                // 🔴 2026-10-25 第七改：4 座解压机的主喷口光柱也是同一套 ⇒ mdPasses 恒 8、回落恒 0
                + " | 光柱 " + HostFxPillarBeam.stats()
                + " 本帧 passes=" + lastPillarPasses + "/4（中央）+ " + lastMdBeamPasses
                + "/8（4 座解压机）回落自绘=" + lastFallbackPillarQuads
                + "+" + lastFallbackMdBeamQuads
                // 🔴 第八改：内外环之间的那圈粗光柱环（用户要求 ①）
                + " | 外緣吸積流 " + HostFxBeamRing.stats()
                + " 本帧 passes=" + lastRingPasses + "/1（回落自绘 " + lastFallbackRingQuads + " 四边形）"
                + " | " + HostFxGeometry.THIN_BEAM_COUNT + " 细紫（<b>每座 3 条</b>，绕<b>本座</b>喷口轴 r="
                + HostFxGeometry.THIN_BEAM_RING_R + " 均布、互成 120°、与本座主柱夹角 0°，峰值半径 "
                + HostFxGeometry.THIN_BEAM_PEAK_RADIUS
                + " 色 " + HostFxGeometry.rgbText(HostFxGeometry.THIN_BEAM_COLOR) + "）"
                + " + 中央主光柱发光色 " + HostFxGeometry.rgbText(HostFxGeometry.jetGlowRgb())
                + " / 解压机主柱发光色 " + HostFxGeometry.rgbText(HostFxGeometry.mdBeamGlowRgb())
                // 🔴 2026-10-25 第二十改：开关语义的读数（降级态：md 主柱 0/4、光柱环关、中央两束红）
                + " | 开关 降级=" + (HostFxSwitch.degraded() ? "是" : "否")
                + "（md主柱 " + HostFxSwitch.mdMainBeamCount() + "/" + HostFxGeometry.MD_COUNT
                + "、小束 " + HostFxSwitch.thinBeamCount() + "、光柱环 "
                + (HostFxSwitch.beamRingOn() ? "开" : "关") + "、中央两束 "
                + HostFxGeometry.rgbText(HostFxSwitch.centralBeamRgb()) + "）"
                + " blackholeFallback=" + HostFxBlackHole.lastState();
    }

    /** 当前光束层的名字（日志/静态自检用；null = 还没建过）。 */
    public static String layerName() {
        return beamLayer == null ? "未建" : "shanhai_hostfx_beam";
    }

    /**
     * 光束批处理层：{@code POSITION_COLOR}、加性、<b>不写深度</b>、不剔除、无贴图。
     *
     * <p>和同项目 {@code ShanhaiHoloSurroundRenderer.ADDITIVE_BLEND} 是同一套写法
     * （同一对 {@code RenderSystem.blendFunc(SRC_ALPHA, ONE)}），所以两处"加性"的观感一致。
     * {@code sortOnUpload = false}：加性混合与顺序无关，每帧 {@link HostFxGeometry#beamQuadCount()}
     * 个四边形（2026-10-25 起 3480：管壳 3360 + 粒子 120）不值得排一次序。
     *
     * <p>🔴 2026-10-25 第八改：从 {@code private} 改成 {@code public} ——
     * {@link HostFxBeamRing#drawFallback} 在"光柱环的着色器建不起来"时要用<b>同一条</b>加性层回落
     * （不另建 RenderType：同一条层上画的加性四边形与原路径像素可叠加，不会出现两套混合参数打架）。
     */
    public static RenderType beamLayer() {
        if (beamLayer == null) {
            beamLayer = RenderType.create(
                    "shanhai_hostfx_beam",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    8192,                                    // 2026-10-25：6 条→18 条，初始容量翻倍
                    false,                                   // affectsCrumbling
                    false,                                   // sortOnUpload（加性 ⇒ 不必排序）
                    RenderType.CompositeState.builder()
                            .setShaderState(BEAM_SHADER)
                            .setTransparencyState(ADDITIVE)
                            .setDepthTestState(new RenderStateShard.DepthTestStateShard("lequal", GL11.GL_LEQUAL))
                            // 只写颜色、不写深度（"关深度写"就是这一个参数）
                            .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, false))
                            // 管是空心圆柱壳，双面都要（否则近端会破洞）
                            .setCullState(new RenderStateShard.CullStateShard(false))
                            .createCompositeState(false));
        }
        return beamLayer;
    }

    /** 山海：動態雲氣材質；資源載入失敗時使用原生貼圖 shader。 */
    public static RenderType nebulaLayer() {
        if (nebulaLayer == null) {
            nebulaLayer = RenderType.create("shanhai_guixu_nebula", GuixuNebulaShader.FORMAT,
                    VertexFormat.Mode.QUADS, 8192, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(new RenderStateShard.ShaderStateShard(GuixuNebulaShader::shader))
                            .setTextureState(new RenderStateShard.TextureStateShard(
                                    new ResourceLocation("gt_shanhai", "textures/effect/guixu_nebula.png"), true, false))
                            .setTransparencyState(ADDITIVE)
                            .setDepthTestState(new RenderStateShard.DepthTestStateShard("lequal", GL11.GL_LEQUAL))
                            .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, false))
                            .setCullState(new RenderStateShard.CullStateShard(false))
                            .createCompositeState(false));
        }
        return nebulaLayer;
    }

    /**
     * 画全部光束（<b>18 条</b>：12 条细紫 + 4 条解压机主喷口光柱 + 2 根中央主光柱）。
     *
     * <p>🔴 2026-10-25 第七改：这个方法现在<b>分三段</b>画（顺序 = 提交顺序）：
     * <ol>
     *   <li><b>12 条细紫</b>：自绘加性管壳（一次 {@code endBatch}），套 pivot + spin；</li>
     *   <li><b>2 根中央主光柱</b>：{@link HostFxPillarBeam#draw}（伪神 shader，套 pivot、<b>不套 spin</b>）；</li>
     *   <li><b>4 条解压机主柱</b>：{@link HostFxPillarBeam#drawMdBeams}（同一套 shader，套 pivot + <b>spin</b>，
     *       相机用自转后的 {@code camSpin}）。</li>
     * </ol>
     * 后两组在 shader 建不起来时各自回落到自绘管壳（{@link HostFxGeometry#buildPillarQuads} /
     * {@link HostFxGeometry#buildMdBeamQuads} + {@link HostFxGeometry#buildParticleQuads}）——
     * <b>绝不出现"光柱整根不见了"</b>。
     *
     * @param cx,cy,cz        环心相对控制器的坐标（与三组烘焙共用同一个枢轴；见 {@link HostFxRingTransforms#pivot}）
     * @param controllerPos   控制器方块坐标（把相机换算到环心本地帧要用它）
     * @param camWorldX/Y/Z   相机世界坐标
     * @param seconds         世界时间（秒）—— 贴图 UV 滚动的相位（{@code tick/240}）；暂停时不动
     */
    public static void drawBeams(PoseStack poseStack, float cx, float cy, float cz, Direction facing,
                                 BlockPos controllerPos,
                                 double camWorldX, double camWorldY, double camWorldZ,
                                 float holeSurfaceR, float spinDeg, float seconds, float brightness) {
        beamBrightness = clamp(brightness);
        try {
            drawBeamsInternal(poseStack, cx, cy, cz, facing, controllerPos,
                    camWorldX, camWorldY, camWorldZ, holeSurfaceR, spinDeg, seconds);
        } finally {
            beamBrightness = 1.0F;
        }
    }

    private static void drawBeamsInternal(PoseStack poseStack, float cx, float cy, float cz, Direction facing,
                                 BlockPos controllerPos,
                                 double camWorldX, double camWorldY, double camWorldZ,
                                 float holeSurfaceR, float spinDeg, float seconds) {
        lastQuads = 0;
        lastBatches = 0;
        lastParticles = 0;
        lastPillarPasses = 0;
        lastMdBeamPasses = 0;
        lastFallbackPillarQuads = 0;
        lastFallbackMdBeamQuads = 0;
        lastRingPasses = 0;
        lastFallbackRingQuads = 0;
        // 相机换算到"环心本地"帧：先反旋转回生成器方向，再减掉环心
        final float[] cam = HostFxGeometry.cameraInRingLocal(facing,
                camWorldX - controllerPos.getX(),
                camWorldY - controllerPos.getY(),
                camWorldZ - controllerPos.getZ());
        // 🔴 光束与"内环 4 个解压机"刚性绑定（机器是烘好的转块）⇒ 几何画在【自转后的帧】里，
        //    相机也要转到同一帧（绕 Y 反旋 −spinDeg），否则横截面的"朝向相机的起始角"会差一个 spin。
        final float[] camSpin = HostFxGeometry.rotateYDeg(-spinDeg, cam[0], cam[1], cam[2]);
        // ① 自绘管壳：只剩 12 条细紫光束（每座解压机 3 条，绕本座喷口轴 120° 均布）
        final List<HostFxGeometry.Quad> quads =
                HostFxGeometry.buildBeamQuads(camSpin[0], camSpin[1], camSpin[2], holeSurfaceR, seconds);
        if (!quads.isEmpty()) {
            final RenderType layer = beamLayer();
            poseStack.pushPose();
            try {
                HostFxRingTransforms.pivot(poseStack, cx, cy, cz, facing);
                HostFxRingTransforms.spin(poseStack, spinDeg);      // 与内环同一个自转角
                final Matrix4f m = poseStack.last().pose();
                // 立即模式：所有顶点进同一个 builder，末尾一次 endBatch 提交（状态由 RenderType 自己开关）
                final MultiBufferSource.BufferSource buffers =
                        MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
                final VertexConsumer vc = buffers.getBuffer(layer);
                emitQuads(vc, m, quads);
                // 🔴 每批末尾 endBatch()：这一次调用 = 这一批顶点真的提交并画出去
                buffers.endBatch(layer);
                lastQuads = quads.size();
                lastBatches = 1;
            } finally {
                poseStack.popPose();
            }
        }

        // ═══════════════════════════════════════════════════════════════════════════════════
        //  🔴 2026-10-25 第五改：中央主光柱（上下两根）= 伪神之锻炉那套【1:1】
        // ═══════════════════════════════════════════════════════════════════════════════════
        //  ⚠️ 它【不能】走上面那个 RenderType：那条路的顶点会被 PoseStack 预乘进缓冲，
        //     而 fsh 里 `localPosition = Position.xy` 必须拿到"光束本地横截面坐标"，
        //     所以只能用 VertexBuffer + drawWithShader（顶点保持本地坐标、pose 当 ModelViewMat）。
        //  ⚠️ 也【不套 spin】：光柱在竖轴上，自转只会让贴图沿角向漂移（伪神那边贴图是"沿轴流"）。
        //  着色器建不起来时回落到 buildPillarQuads（上一版的自绘管壳），主光柱不会消失。
        poseStack.pushPose();
        try {
            HostFxRingTransforms.pivot(poseStack, cx, cy, cz, facing);
            final float tipR = HostFxGeometry.tipRadius(holeSurfaceR);
            lastPillarPasses = HostFxPillarBeam.draw(poseStack, tipR, seconds,
                    cam[0], cam[1], cam[2], HostFxGeometry.jetGlowRgb());
            if (lastPillarPasses == 0) {
                final List<HostFxGeometry.Quad> pillar = HostFxGeometry.buildPillarQuads(
                        cam[0], cam[1], cam[2], holeSurfaceR, seconds);
                final RenderType layer = beamLayer();
                final MultiBufferSource.BufferSource buffers =
                        MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
                final VertexConsumer vc = buffers.getBuffer(layer);
                emitQuads(vc, poseStack.last().pose(), pillar);
                buffers.endBatch(layer);
                lastFallbackPillarQuads = pillar.size();
                lastQuads += pillar.size();
                lastBatches++;
            }
        } finally {
            poseStack.popPose();
        }

        // ═══════════════════════════════════════════════════════════════════════════════════
        //  🔴 2026-10-25 第七改：4 座解压机的【主喷口光柱】也换成伪神同款（与上面那 2 根同一套）
        // ═══════════════════════════════════════════════════════════════════════════════════
        //  用户原话：「还有 4 个物质解压机的主光束也换成伪神同款的啊」。
        //  ⚠️ 与中央两根的唯一差别：**必须套 spin** —— 这 4 根长在会自转的解压机上；
        //     相机也要用自转后的 camSpin（否则 CameraPosition 差一个自转角，可见宽度会随自转呼吸）。
        //  着色器建不起来时回落到上一版的自绘管壳 + 粒子（buildMdBeamQuads + buildParticleQuads）。
        //
        //  🔴 2026-10-25 第二十改：【降级态整段不提交】（机器 hostFxEnabled=false）。
        //     用户原话：「周围物质解压机的小光束还在，主光束消失」—— 这里的"主光束"就是这 4 根。
        //     ⚠️ 刻意写成 `if` 跳段，**不是**"把颜色改成 alpha=0"：那种擦边写法照样会提交顶点、
        //        照样把 lastMdBeamPasses 记成 8，日志与顶点数一起骗人（本工程的红线）。
        if (HostFxSwitch.mdMainBeamsOn()) {
            poseStack.pushPose();
            try {
                HostFxRingTransforms.pivot(poseStack, cx, cy, cz, facing);
                HostFxRingTransforms.spin(poseStack, spinDeg);
                final float tipR = HostFxGeometry.tipRadius(holeSurfaceR);
                lastMdBeamPasses = HostFxPillarBeam.drawMdBeams(poseStack, tipR, seconds,
                        camSpin[0], camSpin[1], camSpin[2], HostFxGeometry.mdBeamGlowRgb());
                if (lastMdBeamPasses == 0) {
                    final List<HostFxGeometry.Quad> tubes = HostFxGeometry.buildMdBeamQuads(
                            camSpin[0], camSpin[1], camSpin[2], holeSurfaceR);
                    final List<HostFxGeometry.Quad> dots = HostFxGeometry.buildParticleQuads(
                            camSpin[0], camSpin[1], camSpin[2], seconds, holeSurfaceR);
                    final RenderType layer = beamLayer();
                    final MultiBufferSource.BufferSource buffers =
                            MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
                    final VertexConsumer vc = buffers.getBuffer(layer);
                    emitQuads(vc, poseStack.last().pose(), tubes);
                    emitQuads(vc, poseStack.last().pose(), dots);
                    buffers.endBatch(layer);
                    lastFallbackMdBeamQuads = tubes.size() + dots.size();
                    lastParticles = dots.size();
                    lastQuads += lastFallbackMdBeamQuads;
                    lastBatches++;
                }
            } finally {
                poseStack.popPose();
            }
        } else {
            // 降级态：这 4 条主柱（含 shader 两遍 + 自绘回落 + 粒子）一条都不提交。
            // 顺带把 HostFxPillarBeam 的 mdPasses 记 0 —— 否则 stats() 会打上一帧残留的 8/8。
            HostFxPillarBeam.markMdBeamsSkipped();
        }

        // 山海：原藍色光柱管環已重製為單遍暖白吸積流；共用既有 pivot、自轉與凍結時鐘。
        // shader 失敗時使用同輪廓與材質公式回落，保留降級態不提交外環的既有契約。
        if (HostFxSwitch.beamRingOn()) {
            poseStack.pushPose();
            try {
                HostFxRingTransforms.pivot(poseStack, cx, cy, cz, facing);
                HostFxRingTransforms.spin(poseStack, spinDeg);
                lastRingPasses = HostFxBeamRing.draw(poseStack, seconds, spinDeg,
                        camSpin[0], camSpin[1], camSpin[2],
                        HostFxSwitch.beamRingGlowRgb(HostFxBeamRingGeom.GLOW_RGB));
                if (lastRingPasses == 0) {
                    lastFallbackRingQuads = HostFxBeamRing.drawFallback(poseStack, seconds, spinDeg,
                            camSpin[0], camSpin[1], camSpin[2],
                            HostFxSwitch.beamRingGlowRgb(HostFxBeamRingGeom.GLOW_RGB));
                    lastQuads += lastFallbackRingQuads;
                    lastBatches++;
                }
            } finally {
                poseStack.popPose();
            }
        } else {
            // 降級態不提交外緣吸積流，清除上一幀計數。
            HostFxBeamRing.markSkipped();
        }
    }

    /** 把一批四边形按 {@code POSITION_COLOR} 写进顶点缓冲（顶点色 = 加性亮度）。 */
    private static void emitQuads(VertexConsumer vc, Matrix4f m, List<HostFxGeometry.Quad> quads) {
        for (int i = 0; i < quads.size(); i++) {
            final HostFxGeometry.Quad q = quads.get(i);
            final float[] p = q.xyz();
            final float r = clamp(q.r());
            final float g = clamp(q.g());
            final float b = clamp(q.b());
            final float a = clamp(q.a() * beamBrightness);
            vc.vertex(m, p[0], p[1], p[2]).color(r, g, b, a).endVertex();
            vc.vertex(m, p[3], p[4], p[5]).color(r, g, b, a).endVertex();
            vc.vertex(m, p[6], p[7], p[8]).color(r, g, b, a).endVertex();
            vc.vertex(m, p[9], p[10], p[11]).color(r, g, b, a).endVertex();
        }
    }

    private static float clamp(float v) {
        return v < 0.0F ? 0.0F : (v > 1.0F ? 1.0F : v);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  8 艘飞船：🔴 2026-10-24 傍晚【自绘模型已整批撤掉】—— 改为真方块烘焙
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /*
     * 用户原话：「**8 艘飞船不需要你自己画，就用原来的方块就行**」。
     *
     * 所以这里原来的 `drawShips(...)`（8 艘 × 21 件、≤3×2×2 的自绘方盒）连同
     * `lastShipBoxes / lastShipBatches / shipBoxes() / shipBatches() / resetShipCounters()`
     * 一起删除 —— 现在飞船由 `HostFxRingGroups` 把台账里的 888 格真方块烘成
     * `HostFxBaked` 再每帧套自转角（与内环带同构）。
     *
     * ⚠️ 仍然保留 `shipLayer` 这个名字：它是「不透明、写深度、不剔除、无贴图」的
     * **实体小件层**，环附加件（加厚箍/配重脊）与 4 座解压机都在用它。
     */

    /** 「实体小件」层：{@code POSITION_COLOR}、<b>不透明</b>（不混合）、写深度、不剔除、无贴图。 */
    private static RenderType shipLayer;

    private static RenderType shipLayer() {
        if (shipLayer == null) {
            shipLayer = RenderType.create(
                    "shanhai_hostfx_ship",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    8192,
                    false,                                   // affectsCrumbling
                    false,                                   // sortOnUpload
                    RenderType.CompositeState.builder()
                            .setShaderState(BEAM_SHADER)     // 同一个 position_color：不采样贴图
                            .setTransparencyState(noTransparency())
                            .setDepthTestState(new RenderStateShard.DepthTestStateShard("lequal", GL11.GL_LEQUAL))
                            // 写深度：实体小件，挡住后面的东西才对
                            .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, true))
                            // 方盒是封闭体，但要防"面向内"的分件被剔掉 ⇒ 关剔除
                            .setCullState(new RenderStateShard.CullStateShard(false))
                            .createCompositeState(false));
        }
        return shipLayer;
    }

    /**
     * 内环的「加厚箍 + 配重脊」（纯渲染件；**调用方已套好环的自转角**）。
     *
     * <p>结构 {@code .bin} 一格不动，靠这两圈箍把环带的视觉厚度从 3 层顶到 ≈5 层
     * （伪神三环的实测基准是 3–4 格厚 / 5 层高）。与飞船共用同一个不透明层。
     *
     * @return 提交的方盒数
     */
    public static int drawRingExtras(PoseStack poseStack) {
        final RenderType layer = shipLayer();
        final MultiBufferSource.BufferSource buffers =
                MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        final VertexConsumer vc = buffers.getBuffer(layer);
        final Matrix4f m = poseStack.last().pose();
        int boxes = 0;
        for (HostFxGeometry.Box b : HostFxGeometry.ringGirdleBoxes()) {
            emitBox(vc, m, b);
            boxes++;
        }
        for (HostFxGeometry.Box b : HostFxGeometry.ringSpineBoxes()) {
            emitBox(vc, m, b);
            boxes++;
        }
        buffers.endBatch(layer);
        return boxes;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  4 座「物质解压机」（替换 4 处辐条方块堆）—— 用户 2026-10-25 口径
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /** 上一帧提交的解压机方盒数与批次数（日志/自检用）。 */
    private static int lastMdBoxes;
    private static int lastMdBatches;

    /** 解压机方盒数（{@code draw} 日志用；应为 4 × {@link HostFxGeometry#mdBoxCount()}）。 */
    public static int mdBoxes() {
        return lastMdBoxes;
    }

    /** 解压机批次（应为 1）。 */
    public static int mdBatches() {
        return lastMdBatches;
    }

    /**
     * 画 4 座物质解压机（<b>调用方已经套好"环心枢轴 + 环自转角"</b>）。
     *
     * <p>每座：{@code translate(轴线落点) → yaw(方位) → tilt(内倾)}，然后逐件写
     * {@link HostFxGeometry#matterDecompressorBoxes()} 的轴对齐小方盒。
     * 与飞船/环共用同一个不透明层（{@link #shipLayer}），也共用<b>同一个自转角</b>
     * ⇒ 「4 座跟着所在辐条一起转」是定义上成立的（同一句 {@code spin}，见 {@code HostFxRingGroups}）。
     *
     * @return 实际提交的方盒数（应为 {@link HostFxGeometry#mdBoxTotal()}）
     */
    public static int drawMatterDecompressors(PoseStack poseStack) {
        lastMdBoxes = 0;
        lastMdBatches = 0;
        final RenderType layer = shipLayer();
        final MultiBufferSource.BufferSource buffers =
                MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        final VertexConsumer vc = buffers.getBuffer(layer);
        final List<HostFxGeometry.MdBox> parts = HostFxGeometry.matterDecompressorBoxes();
        int boxes = 0;
        for (int i = 0; i < HostFxGeometry.MD_COUNT; i++) {
            final float[] c = HostFxGeometry.mdCenter(i);
            poseStack.pushPose();
            try {
                poseStack.translate(c[0], c[1], c[2]);
                poseStack.mulPose(Axis.YP.rotationDegrees(HostFxGeometry.mdYawDeg(i)));
                // 姿态改编：顶部朝环心（本地 −Z）倾 —— 底座因此压在辐条端面上
                poseStack.mulPose(Axis.XP.rotationDegrees(HostFxGeometry.MD_TILT_DEG));
                for (HostFxGeometry.MdBox b : parts) {
                    poseStack.pushPose();
                    try {
                        // 每件先平移到自己的位置、再绕 Y 偏航到"切向"，最后画一个以原点为中心的方盒
                        poseStack.translate(b.x(), b.y(), b.z());
                        poseStack.mulPose(Axis.YP.rotationDegrees(b.yawDeg()));
                        emitCenteredBox(vc, poseStack.last().pose(), b);
                        boxes++;
                    } finally {
                        poseStack.popPose();
                    }
                }
            } finally {
                poseStack.popPose();
            }
        }
        buffers.endBatch(layer);
        lastMdBoxes = boxes;
        lastMdBatches = 1;
        return boxes;
    }

    /** 一个<b>以原点为中心</b>的方盒（分件已经平移到自己的位置）→ 6 个四边形。 */
    private static void emitCenteredBox(VertexConsumer vc, Matrix4f m, HostFxGeometry.MdBox b) {
        final float x0 = -b.hx();
        final float x1 = b.hx();
        final float y0 = -b.hy();
        final float y1 = b.hy();
        final float z0 = -b.hz();
        final float z1 = b.hz();
        final float r = clamp(b.red());
        final float g = clamp(b.green());
        final float bl = clamp(b.blue());
        quad(vc, m, r, g, bl, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0);
        quad(vc, m, r, g, bl, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
        quad(vc, m, r, g, bl, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1);
        quad(vc, m, r, g, bl, x1, y0, z1, x1, y1, z1, x1, y1, z0, x1, y0, z0);
        quad(vc, m, r, g, bl, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        quad(vc, m, r, g, bl, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
    }

    /** 一个轴对齐方盒 → 6 个四边形。 */
    private static void emitBox(VertexConsumer vc, Matrix4f m, HostFxGeometry.Box b) {        final float x0 = b.x() - b.hx();
        final float x1 = b.x() + b.hx();
        final float y0 = b.y() - b.hy();
        final float y1 = b.y() + b.hy();
        final float z0 = b.z() - b.hz();
        final float z1 = b.z() + b.hz();
        final float r = clamp(b.red());
        final float g = clamp(b.green());
        final float bl = clamp(b.blue());
        // -Y / +Y
        quad(vc, m, r, g, bl, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0);
        quad(vc, m, r, g, bl, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
        // -X / +X
        quad(vc, m, r, g, bl, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1);
        quad(vc, m, r, g, bl, x1, y0, z1, x1, y1, z1, x1, y1, z0, x1, y0, z0);
        // -Z / +Z
        quad(vc, m, r, g, bl, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        quad(vc, m, r, g, bl, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float r, float g, float b,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx2, float cy2, float cz2, float dx2, float dy2, float dz2) {
        vc.vertex(m, ax, ay, az).color(r, g, b, 1.0F).endVertex();
        vc.vertex(m, bx, by, bz).color(r, g, b, 1.0F).endVertex();
        vc.vertex(m, cx2, cy2, cz2).color(r, g, b, 1.0F).endVertex();
        vc.vertex(m, dx2, dy2, dz2).color(r, g, b, 1.0F).endVertex();
    }

    /**
     * "不混合"的透明状态。
     *
     * <p>⚠️ 原版那个 {@code RenderStateShard.NO_TRANSPARENCY} 是 {@code protected}，外部取不到
     * （2026-10-25 编译报 "NO_TRANSPARENCY 在 RenderStateShard 中是 protected 访问控制"）。
     * 这里自带一个等价的：进入关混合、退出也关混合（对比 {@link #ADDITIVE} 的退出复位）。
     */
    private static RenderStateShard.TransparencyStateShard noTransparency() {
        return new RenderStateShard.TransparencyStateShard("shanhai_hostfx_opaque",
                RenderSystem::disableBlend, RenderSystem::disableBlend);
    }
}
