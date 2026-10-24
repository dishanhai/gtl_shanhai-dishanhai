package com.shanhai.client.render.hostfx;

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

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * 归墟视界枢核 · <b>唯一保留的自绘特效</b>：6 条光束（4 条提取 + 黑洞上下 2 条）。
 *
 * <h2>内环与飞船去哪了</h2>
 * 已经不在这里画了 —— 按用户口径改成"真方块跟着转"（{@link HostFxBaked} + {@link HostFxRingGroups}）。
 * 本类只负责那 6 条光束的<b>提交</b>；几何在 {@link HostFxGeometry#buildBeamQuads}（纯数学，离线探针共用）。
 *
 * <h2>2026-10-24 第二次改：从"手写 GL 状态"改成"一个 RenderType + 每批 endBatch()"</h2>
 * 上一版是 {@code Tesselator.getBuilder()} 写顶点、再交给 {@code BufferUploader} 的 {@code drawWithShader}
 * 提交，状态靠 {@code GL11.glIsEnabled/glGetBoolean} 手工存取恢复。这一版改成 MC 的正规批处理：
 * <pre>
 *   RenderType = POSITION_COLOR + 加性混合 + 不写深度 + 不剔除 + 无贴图
 *   BufferSource buffers = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder())
 *   vc = buffers.getBuffer(LAYER) → 写 6 条管的全部顶点 → buffers.endBatch(LAYER)   ← 每批末尾一次
 * </pre>
 * 好处有三个，都是可验证的：
 * <ol>
 *   <li><b>状态由 RenderType 自己 setup/clear</b>（{@code type.draw} 内部一对 setupRenderState/clearRenderState），
 *       不用手工保存恢复，也就不会把"混合/深度写/剔除"泄漏给后面的渲染器；</li>
 *   <li><b>{@code endBatch()} 是"这一批真的提交了"的唯一判据</b> —— 本类把它记进
 *       {@link #batchFlushes()}（每帧应当恰好 1），渲染器的 {@code draw} 日志会打出来；</li>
 *   <li>少一次 {@code BufferUploader} 的直接调用 ⇒ 不再依赖"当前 shader 是谁"这种隐式状态。</li>
 * </ol>
 *
 * <h2>参数（逐条对应用户口径，离线探针 §5 逐条量）</h2>
 * <table border="1">
 *   <tr><th>项</th><th>值</th><th>落在哪</th></tr>
 *   <tr><td>条数</td><td>6（4 条径向 + 黑洞上下 2 条）</td><td>{@link HostFxGeometry#buildBeamQuads}</td></tr>
 *   <tr><td>径向 4 条</td><td>臂根 r={@link HostFxGeometry#ARM_ROOT_R} → 黑洞表面（默认 2.6 格）</td><td>{@code buildBeamQuads}</td></tr>
 *   <tr><td>极轴 2 条</td><td>黑洞表面 → 往外 12 格</td><td>{@link HostFxGeometry#POLAR_LENGTH}</td></tr>
 *   <tr><td>细分</td><td>32 段长 × 12 段圆</td><td>{@code BEAM_SEGMENTS} / {@code BEAM_FACETS}</td></tr>
 *   <tr><td>半径峰值</td><td>0.25 格（直径 0.5）</td><td>{@code BEAM_PEAK_RADIUS} / {@code taperRadius}</td></tr>
 *   <tr><td>贴图</td><td>无（纯顶点色 180,220,255）</td><td>{@code BEAM_COLOR} / 本类的 {@code POSITION_COLOR}</td></tr>
 *   <tr><td>亮度包络</td><td>{@code 1 − 0.4·|2t−1|}（两端 0.6、中间 1.0）</td><td>{@code taperBrightness} → 顶点 alpha</td></tr>
 *   <tr><td>混合</td><td>加性（SRC_ALPHA, ONE）</td><td>{@link #ADDITIVE}</td></tr>
 *   <tr><td>深度</td><td>关深度写（测试照常）</td><td>{@code WriteMaskStateShard(true, false)}</td></tr>
 *   <tr><td>提交</td><td>每批末尾 {@code endBatch()}</td><td>{@link #drawBeams} / {@link #drawMatterDecompressors}</td></tr>
 * </table>
 */
public final class HostFxDraw {

    private HostFxDraw() {}

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

    /** 上一帧提交的四边形数（日志/自检用；每帧应 = {@link HostFxGeometry#beamQuadCount()}）。 */
    private static int lastQuads;
    /** 上一帧 {@code endBatch()} 的次数（每帧应恰好 1）。 */
    private static int lastBatches;

    /** 上一帧提交的四边形数（{@code draw} 日志用）。 */
    public static int quads() {
        return lastQuads;
    }

    /** 上一帧 {@code endBatch()} 次数（{@code draw} 日志用；0 = 这批根本没提交）。 */
    public static int batchFlushes() {
        return lastBatches;
    }

    /** 一行摘要（渲染器 {@code draw} 日志里打这一行）。 */
    public static String stats() {
        return "beamQuads=" + lastQuads + "/" + HostFxGeometry.beamQuadCount()
                + " endBatch=" + lastBatches
                // 🔴 2026-10-24 傍晚：飞船改真方块烘焙 ⇒ 这里报"自绘飞船方盒 = 0"（判据：没有回退）
                + " | shipSelfBoxes=0"
                + " | mdBoxes=" + lastMdBoxes + "/" + HostFxGeometry.mdBoxTotal()
                + " mdBatch=" + lastMdBatches
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
     * {@code sortOnUpload = false}：加性混合与顺序无关，每帧 2304 个 quad 不值得排一次序。
     */
    private static RenderType beamLayer() {
        if (beamLayer == null) {
            beamLayer = RenderType.create(
                    "shanhai_hostfx_beam",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    4096,
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

    /**
     * 画 6 条光束。
     *
     * @param cx,cy,cz        环心相对控制器的坐标（与三组烘焙共用同一个枢轴；见 {@link HostFxRingTransforms#pivot}）
     * @param controllerPos   控制器方块坐标（把相机换算到环心本地帧要用它）
     * @param camWorldX/Y/Z   相机世界坐标（光束横截面"跟着相机转"，让细管任何角度看都有厚度）
     */
    public static void drawBeams(PoseStack poseStack, float cx, float cy, float cz, Direction facing,
                                 BlockPos controllerPos,
                                 double camWorldX, double camWorldY, double camWorldZ,
                                 float holeSurfaceR, float spinDeg) {
        lastQuads = 0;
        lastBatches = 0;
        // 相机换算到"环心本地"帧：先反旋转回生成器方向，再减掉环心
        final float[] cam = HostFxGeometry.cameraInRingLocal(facing,
                camWorldX - controllerPos.getX(),
                camWorldY - controllerPos.getY(),
                camWorldZ - controllerPos.getZ());
        // 🔴 光束与"内环 4 个臂根"刚性绑定（臂根本身是烘好的转块）⇒ 几何画在【自转后的帧】里，
        //    相机也要转到同一帧（绕 Y 反旋 −spinDeg），否则横截面的"朝向相机的起始角"会差一个 spin。
        final float[] camSpin = HostFxGeometry.rotateYDeg(-spinDeg, cam[0], cam[1], cam[2]);
        final List<HostFxGeometry.Quad> quads =
                HostFxGeometry.buildBeamQuads(camSpin[0], camSpin[1], camSpin[2], holeSurfaceR);
        if (quads.isEmpty()) {
            return;
        }
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
            for (int i = 0; i < quads.size(); i++) {
                final HostFxGeometry.Quad q = quads.get(i);
                final float[] p = q.xyz();
                final float r = clamp(q.r());
                final float g = clamp(q.g());
                final float b = clamp(q.b());
                final float a = clamp(q.a());
                vc.vertex(m, p[0], p[1], p[2]).color(r, g, b, a).endVertex();
                vc.vertex(m, p[3], p[4], p[5]).color(r, g, b, a).endVertex();
                vc.vertex(m, p[6], p[7], p[8]).color(r, g, b, a).endVertex();
                vc.vertex(m, p[9], p[10], p[11]).color(r, g, b, a).endVertex();
            }
            // 🔴 每批末尾 endBatch()：这一次调用 = 这一批顶点真的提交并画出去（本类只此一处）
            buffers.endBatch(layer);
            lastQuads = quads.size();
            lastBatches = 1;
        } finally {
            poseStack.popPose();
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
