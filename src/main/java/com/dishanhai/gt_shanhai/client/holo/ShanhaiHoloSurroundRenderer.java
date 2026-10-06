package com.dishanhai.gt_shanhai.client.holo;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * 山海重构 · <b>全息环绕层（数据环 + 星尘网格）的绘制</b>。
 *
 * <h2>0. 它与 {@link ShanhaiHoloSurround} 的分工</h2>
 * 几何（环上的点 / 壳层里的点 / 哪条连线该连）全在 {@link ShanhaiHoloSurround} 里，是纯算术、离线可跑。
 * 本类只做三件事：
 * <ol>
 *   <li>把"这一帧的相机与投影"翻译成 {@link ShanhaiHoloSurround.Proj}（一个 4×4 矩阵 + 屏幕半宽半高）；</li>
 *   <li>把"这一帧那 5 块板在哪"填进 {@link ShanhaiHoloSurround.Frame}；</li>
 *   <li>实现 {@link ShanhaiHoloSurround.Sink}：<b>把两条线段/光点变成顶点</b>。</li>
 * </ol>
 * ⇒ 于是"每帧画了多少顶点"这件事在构建机上就能数出来（见 {@code ShanhaiHoloSurround.CountingSink}），
 * 而游戏里走的是同一份几何代码。
 *
 * <h2>0.5 🔴 坐标口径（2026-10-06 用户实测第 ① 条，栽过一次）</h2>
 * <pre>
 *   几何核吐出来的顶点 = 【世界坐标】（环心 = 玩家的世界坐标）
 *   事件给的 poseStack = 只有相机旋转，没有 −camPos
 *   ⇒ 本类必须自己把平移补齐：VIEW_FULL = poseStack · T(−camPos)（ShanhaiHoloSurroundView）
 *   顶点与 MVP 都用 VIEW_FULL ⇒ "画在哪"与"判据算在哪"不可能各自漂一套
 * </pre>
 * 修复前的症状（用户截图）：贴身的环一个都看不见，天上多出一团发光的光网 ——
 * 那团光与相机的距离恰好 ≈ |玩家世界坐标|，方向就是"玩家世界坐标"那个方向。
 * 现场判据：日志里的 {@code surround_probe … eye_check=…}（见 {@link #logProbe}）。
 *
 * <h2>1. 🔴 两种图元怎么变成顶点（为什么不是 GL_LINES）</h2>
 * <pre>
 *   线段：MC 跑的是 core profile，{@code glLineWidth > 1} 在多数驱动上会被夹回 1.0
 *        ⇒ 用 GL 线画的话，环体 1.3 / 刻度 1.8 / 辉光 4.6 / 拖尾 2.2 这四档线宽<b>全都变成一样粗</b>，
 *          而那几档粗细正是这条美术的层次。改用【面向相机的四边形】：
 *          沿线段方向 × 视线方向叉乘得到"屏幕平面内的粗细方向"，四角 = 两端各 ±半宽。
 *          ⇒ 4 个顶点一条线，线宽随透视自然收敛，与网页里的 stroke 是同一件事。
 *   光点：网页里是一张 64×64 的径向渐变精灵（中心 1.0、0.18 处 0.85、0.42 处 0.28、边缘 0）。
 *        本工程<b>不碰任何贴图图集</b>（那条老坑见 {@code PrimordialOmegaEngineRingBuffer} 的注释）
 *        ⇒ 用【菱形风扇】代替：中心顶点不透明、四个边缘顶点全透明，三角形内插值出来的
 *          正是一个线性衰减的光晕。4 个三角形在 QUADS 模式下各用一个
 *          "第 4 顶点与第 3 顶点重合"的四边形表示（那一个三角形面积为零 ⇒ 不产生像素）
 *          ⇒ 16 个顶点一个光点。所以<b>不引入 Mode.TRIANGLES、也不引入任何贴图</b>。
 * </pre>
 *
 * <h2>2. 🔴 每帧的顶点账（判据，不是估算）</h2>
 * 见 {@link ShanhaiHoloSurround#selfTest()} 的 C 段：构建机上用同一个 Sink 跑一遍就有原始读数。
 * 本类<b>没有</b>改 {@code PER_FRAME_BUFFER_CAPACITY}（仍是 {@code 1<<14} = 98304 字节），
 * 因为按图元种类切 {@code RenderType} 会把上一批冲掉 ⇒ 单批峰值远小于容量（读数见报告）。
 *
 * <h2>3. 🔴 任何异常都不许崩客户端</h2>
 * 整个 {@link #render} 包在 try/catch 里：一旦抛异常就<b>永久关掉这一层</b>并打一行日志，
 * 那 5 块板与面板<b>一个字都不受影响</b>（同 {@code ShanhaiRecipeStats#onServerStarted} 的口径）。
 */
@OnlyIn(Dist.CLIENT)
public final class ShanhaiHoloSurroundRenderer {

    private ShanhaiHoloSurroundRenderer() {}

    // ------------------------------------------------------------------ 状态（只建一次）

    private static final ShanhaiHoloSurround.Frame FRAME = ShanhaiHoloSurround.newFrame();
    private static final ShanhaiHoloSurround.Proj PROJ = new ShanhaiHoloSurround.Proj();
    private static final Matrix4f MVP = new Matrix4f();
    private static final float[] MVP_ARR = new float[16];

    /**
     * 🆕 2026-10-06（用户实测第 ① 条）：<b>真正的视图矩阵</b> =
     * {@code 事件给的 poseStack（只有旋转） · T(−相机世界坐标)}。
     * <p>🔴 顶点与 {@link #MVP} 都用它 ⇒ "画在哪"与"判据算在哪"不可能各自漂一套。
     * <p>取证（为什么必须补 −camPos 这一项）见 {@link ShanhaiHoloSurroundView} 的类注释。
     */
    private static final Matrix4f VIEW_FULL = new Matrix4f();

    /** 复用的一支向量：把"玩家自己"送进 {@link #VIEW_FULL} 做体检（见 {@link #logProbe}）。 */
    private static final Vector4f PROBE_POINT = new Vector4f();

    /** 出过事就把这一层关掉（宁可少画一层，也不许每帧抛异常把帧率拖垮）。 */
    private static boolean disabled;
    private static boolean loggedOnce;

    /** 累加器：从上一帧到这一帧的秒数（由 {@code tSec} 差分得到，已夹过）。 */
    private static float lastTSec = Float.NaN;

    private static RenderType lineType;
    private static RenderType dotType;

    // ------------------------------------------------------------------ 两个 RenderType

    /** {@code POSITION_COLOR} 着色器（与 {@link ShanhaiHoloMenuRenderer} 用的是同一个）。 */
    private static final RenderStateShard.ShaderStateShard POSITION_COLOR_SHADER =
            new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader);

    /** <b>加法</b>混合 —— 环绕层整层都是叠加发光（与网页里每一条 {@code additive=true} 对应）。 */
    private static final RenderStateShard.TransparencyStateShard ADDITIVE_BLEND =
            new RenderStateShard.TransparencyStateShard(
                    "shanhai_holo_surround_additive",
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

    /**
     * 线段层：加法、<b>不写深度</b>、深度测试 {@code lequal}、不剔除。
     * <p>不写深度是刻意的：环绕层是"光"，它叠加在世界上，但不该挡住后面的东西。
     * <p>与 {@link ShanhaiHoloMenuRenderer} 的 {@code glowType()} 状态完全相同，
     * 但<b>是另一个 RenderType 对象</b> —— 这样"切类型 ⇒ 冲掉上一批"这条行为会把
     * 环绕层与那 5 块板的发光层分成两批（每批的顶点数才有意义，见报告的顶点账）。
     */
    private static RenderType lineType() {
        if (lineType == null) {
            lineType = RenderType.create(
                    "shanhai_holo_surround_line",
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
        return lineType;
    }

    /** 光点层：状态与 {@link #lineType()} 相同，但<b>是另一个对象</b> ⇒ 光点与线各成一批。 */
    private static RenderType dotType() {
        if (dotType == null) {
            dotType = RenderType.create(
                    "shanhai_holo_surround_dot",
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
        return dotType;
    }

    // ------------------------------------------------------------------ 入口

    /**
     * 画这一帧的环绕层。
     *
     * <p>⚠️ <b>调用位置有讲究</b>：必须夹在"第 1 遍底板（<b>写深度</b>）"与"第 2 遍发光（加法）"之间。
     * 这样那 5 块板已经占好了深度 ⇒ 环绕层里跑到板背后的部分<b>会被真的挡住</b>
     * （与网页里 {@code DL.sort} 的意图一致，但这里是深度缓冲做的，更准）；
     * 而第 2 遍的板发光与第 3 遍的文字又都叠在环绕层之上 ⇒ "环与点永不压到板上的字"这条要求
     * 有<b>第三道</b>保险（前两道是规则 ③ 的两个判据）。
     *
     * @param viewMatrix 事件给的那份矩阵（{@code poseStack.last().pose()} 在<b>任何 push 之前</b>的
     *                   一份拷贝）—— 它<b>只有相机旋转、没有 −camPos</b>（取证见
     *                   {@link ShanhaiHoloSurroundView}），本类会用
     *                   {@link ShanhaiHoloSurroundView#composeView} 把平移补齐
     * @param animSpinDeg 展开动画的自转（度），会与框偏航一起作用在板位姿上
     * @param boards     本帧的板表（菜单 5 块 / 面板只喂【画出来的】那几行）
     * @param boardCount {@code boards} 里有效的块数（面板模式下可能少于数组长度：
     *                   命令面板只画输入框与返回框两行 ⇒ 只按这两块算板面禁区）
     * @return {@code true} = 这一帧真的画了；{@code false} = 关掉了/出过错/参数为 0
     */
    public static boolean render(RenderLevelStageEvent event,
                                 MultiBufferSource.BufferSource buffers,
                                 Matrix4f viewMatrix,
                                 double playerX, double playerY, double playerZ,
                                 double originX, double originY, double originZ,
                                 float frameYawDeg, float animSpinDeg, double animScale,
                                 ShanhaiHoloMenuLayout.Board[] boards, int boardCount,
                                 float tSec, float globalAlpha) {
        if (disabled) {
            return false;
        }
        // 🔴 2026-10-06（第二轮）：「关」档 = 这一帧【一个顶点都不发】。
        //    这是"环绕特效 = 关"那句话在渲染侧的唯一落点（几何核里另有一条同样的短路，
        //    两条独立：即便有人绕开渲染器直接调几何核，也是 0 顶点）。
        if (!ShanhaiHoloSurroundTuning.drawEnabled()) {
            return false;
        }
        try {
            return doRender(event, buffers, viewMatrix,
                    playerX, playerY, playerZ,
                    originX, originY, originZ,
                    frameYawDeg, animSpinDeg, animScale, boards, boardCount, tSec, globalAlpha);
        } catch (RuntimeException e) {
            disabled = true;
            com.dishanhai.gt_shanhai.GTDishanhaiMod.LOGGER.error(
                    "[SHANHAI-HOLO] surround disabled after a failure (那 5 块板不受影响；"
                            + "这一层以后不再画)", e);
            return false;
        }
    }

    private static boolean doRender(RenderLevelStageEvent event,
                                    MultiBufferSource.BufferSource buffers,
                                    Matrix4f viewMatrix,
                                    double playerX, double playerY, double playerZ,
                                    double originX, double originY, double originZ,
                                    float frameYawDeg, float animSpinDeg, double animScale,
                                    ShanhaiHoloMenuLayout.Board[] boards, int boardCount,
                                    float tSec, float globalAlpha) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return false;
        }
        final int fbW = minecraft.getWindow().getWidth();
        final int fbH = minecraft.getWindow().getHeight();
        if (fbW <= 0 || fbH <= 0) {
            return false;
        }

        // ---- 1. 相机（视图矩阵要用它，所以先取）----
        final net.minecraft.client.Camera camera = event.getCamera();
        final net.minecraft.world.phys.Vec3 camPos = camera.getPosition();
        final Vector3f look = camera.getLookVector();
        final Vector3f up = camera.getUpVector();
        final Vector3f left = camera.getLeftVector();

        // ---- 2. 🔴 真正的视图矩阵 = 相机姿态 · T(−相机世界坐标) ----
        //   事件给的 poseStack【只有旋转】（javap 取证链见 ShanhaiHoloSurroundView 的类注释），
        //   而本层的顶点是世界坐标 ⇒ 平移这一项必须自己补。
        //   ⚠️ 少了它，每个顶点都会落在"世界原点那一带"：玩家离原点越远，这团光飘得越远
        //      —— 正是用户截图里"贴身的环看不见、天上多了一团发光的光网"。
        //   同一把矩阵既用来画顶点、也算 MVP ⇒ 两者不可能再各自漂一套（本轮就是栽在这里）。
        ShanhaiHoloSurroundView.composeView(VIEW_FULL, viewMatrix, camPos.x, camPos.y, camPos.z);
        MVP.set(event.getProjectionMatrix()).mul(VIEW_FULL);
        MVP.get(MVP_ARR);
        System.arraycopy(MVP_ARR, 0, PROJ.m, 0, 16);
        PROJ.halfW = fbW * 0.5f;
        PROJ.halfH = fbH * 0.5f;

        // ---- 3. 相机基向量（做面向相机的广告牌用）----
        final Geom geom = GEOM;
        geom.buffers = buffers;
        geom.view = VIEW_FULL;
        geom.mvp = MVP;
        geom.halfW = PROJ.halfW;
        geom.halfH = PROJ.halfH;
        geom.fx = look.x();
        geom.fy = look.y();
        geom.fz = look.z();
        geom.ux = up.x();
        geom.uy = up.y();
        geom.uz = up.z();
        // 相机右方向 = −左方向（原版 Camera 给的是 left/up/look 三个基）
        geom.rx = -left.x();
        geom.ry = -left.y();
        geom.rz = -left.z();
        geom.camX = camPos.x;
        geom.camY = camPos.y;
        geom.camZ = camPos.z;

        // ---- 4. 这一帧的帧长（秒）----
        float dt;
        if (Float.isNaN(lastTSec)) {
            dt = 1.0f / 60.0f;
        } else {
            dt = tSec - lastTSec;
            if (dt < 0.0f) {
                dt = 0.0f;
            } else if (dt > ShanhaiHoloMenuTuning.MAX_FRAME_DT_SEC) {
                dt = ShanhaiHoloMenuTuning.MAX_FRAME_DT_SEC;
            }
        }
        lastTSec = tSec;

        // ---- 5. 帧输入 ----
        FRAME.playerX = playerX;
        FRAME.playerY = playerY;
        FRAME.playerZ = playerZ;
        FRAME.camX = geom.camX;
        FRAME.camY = geom.camY;
        FRAME.camZ = geom.camZ;
        FRAME.tSec = tSec;
        FRAME.dtSec = dt;
        FRAME.globalAlpha = globalAlpha;
        FRAME.proj = PROJ;
        FRAME.originX = originX;
        FRAME.originY = originY;
        FRAME.originZ = originZ;
        FRAME.frameYawRad = Math.toRadians(frameYawDeg + animSpinDeg);
        FRAME.animScale = animScale;
        final int n = Math.min(boardCount, ShanhaiHoloSurround.MAX_BOARDS);
        FRAME.boardCount = n;
        for (int i = 0; i < n; i++) {
            final ShanhaiHoloMenuLayout.Board b = boards[i];
            FRAME.boardX[i] = b.x();
            FRAME.boardY[i] = b.y();
            FRAME.boardZ[i] = b.z();
            FRAME.boardRotY[i] = b.rotY();
            FRAME.boardRotX[i] = b.rotX();
            FRAME.boardScale[i] = b.scale();
        }

        // ---- 6. 交给几何核，出口是本类的 Geom ----
        geom.beginFrame(playerX, playerY, playerZ);
        ShanhaiHoloSurround.render(FRAME, geom);

        if (!loggedOnce) {
            loggedOnce = true;
            com.dishanhai.gt_shanhai.GTDishanhaiMod.LOGGER.info(
                    "[SHANHAI-HOLO] surround on: {} | this frame: rings_seg={} links={} dots={}",
                    ShanhaiHoloSurroundTuning.summary(),
                    FRAME.ringSegments, FRAME.fieldLinks, FRAME.fieldDots);
        }
        // ---- 7. 🆕 顶点体检（机器判据，见 logProbe 的注释）：每 5 秒一行 ----
        if (tSec - lastProbeSec >= PROBE_PERIOD_SEC || tSec < lastProbeSec) {
            lastProbeSec = tSec;
            logProbe(geom, playerX, playerY, playerZ, camPos);
        }
        return true;
    }

    /** 体检的打点周期（秒）—— 每帧一行会把日志刷爆，5 秒一行足够看出问题。 */
    private static final float PROBE_PERIOD_SEC = 5.0f;

    /** 上一次体检的时刻（秒）。初值取一个很小的数（<b>不是 NaN</b>：NaN 参与比较恒为 false，
     *  那样第一次永远打不出这一行 —— 自检代码自己也会犯"判据从不响"的错）。 */
    private static float lastProbeSec = -1.0e9f;

    /**
     * 🆕 2026-10-06（用户实测第 ① 条）<b>环绕层的顶点体检</b> —— 把"画在哪"变成一行可读的读数。
     *
     * <pre>
     *   ① verts         : 这一帧真的写了多少个顶点（0 = 这一层什么都没画）
     *   ② radial_min/max: 顶点到【玩家脚底】的距离范围（格）
     *                     期望 ≈ 1.4 ~ 4.45（环 ≤1.75；点场壳层 2.55~3.30、圆心在脚上 1.15）
     *   ③ horiz_max     : 顶点相对玩家的【水平】距离上限（格）—— 期望 ≈ 3.30
     *   ④ eye_check     : 把【玩家自己】的世界坐标送进这一帧真正的视图矩阵 {@link #VIEW_FULL}，
     *                     得到的相机空间长度。几何上它必须 ≈ 眼睛到脚底的距离（第一人称 1.62、
     *                     第三人称更大）⇒ 期望 0.2 ~ 8.0 格。
     *                     🔴 这一条才是抓"坐标原点算错"的判据：漏掉 −camPos 那一项时，
     *                     得到的是 |玩家世界坐标|（几十~几百格）⇒ 一次就能看出来。
     *   ⑤ 体位不对时额外打一行 ERROR（改坏了会在日志里自己喊）
     * </pre>
     * 全部是标量累加（{@link Geom#put} 里只比平方、不 sqrt），不新建任何对象。
     */
    private static void logProbe(Geom geom, double playerX, double playerY, double playerZ,
                                 net.minecraft.world.phys.Vec3 camPos) {
        final int verts = geom.probeVerts;
        double eyeCheck = -1.0;
        // 单点体检：玩家（脚底）自己过一遍视图矩阵
        PROBE_POINT.set((float) playerX, (float) playerY, (float) playerZ, 1.0f);
        VIEW_FULL.transform(PROBE_POINT);
        eyeCheck = Math.sqrt((double) PROBE_POINT.x * PROBE_POINT.x
                + (double) PROBE_POINT.y * PROBE_POINT.y
                + (double) PROBE_POINT.z * PROBE_POINT.z);
        final boolean eyeOk = eyeCheck >= PROBE_EYE_MIN && eyeCheck <= PROBE_EYE_MAX;
        final String line = String.format(java.util.Locale.ROOT,
                "[SHANHAI-HOLO] surround_probe verts=%d radial_min=%.3f radial_max=%.3f "
                        + "horiz_max=%.3f centroid_xz=(%.3f,%.3f) eye_check=%.3f expect_eye=%.1f~%.1f "
                        + "player=(%.2f,%.2f,%.2f) cam=(%.2f,%.2f,%.2f) ok=%s",
                verts,
                verts > 0 ? Math.sqrt(geom.probeMin2) : -1.0,
                verts > 0 ? Math.sqrt(geom.probeMax2) : -1.0,
                verts > 0 ? Math.sqrt(geom.probeHorizMax2) : -1.0,
                verts > 0 ? geom.probeSumX / verts : 0.0,
                verts > 0 ? geom.probeSumZ / verts : 0.0,
                eyeCheck, PROBE_EYE_MIN, PROBE_EYE_MAX,
                playerX, playerY, playerZ, camPos.x, camPos.y, camPos.z, eyeOk);
        if (eyeOk) {
            com.dishanhai.gt_shanhai.GTDishanhaiMod.LOGGER.info(line);
        } else {
            com.dishanhai.gt_shanhai.GTDishanhaiMod.LOGGER.error(line
                    + " | 🔴 环绕层体位不对：把玩家自己送进视图矩阵之后离相机 "
                    + String.format(java.util.Locale.ROOT, "%.3f", eyeCheck)
                    + " 格（期望 %.1f~%.1f）—— 大概率是 −camPos 那一项丢了"
                    + "（见 ShanhaiHoloSurroundView 的类注释）", PROBE_EYE_MIN, PROBE_EYE_MAX);
        }
    }

    /** 体检④的合格带（格）：第一人称 ≈1.62、第三人称 ≈5；两头都留了余量。 */
    private static final double PROBE_EYE_MIN = 0.2;
    private static final double PROBE_EYE_MAX = 8.0;

    // ------------------------------------------------------------------ 出口实现

    /** 复用的那一份（渲染线程单线程，不需要每次新建）。 */
    private static final Geom GEOM = new Geom();

    /**
     * {@link ShanhaiHoloSurround.Sink} 的游戏侧实现：几何核说"画这条线/这个点"，这里就写顶点。
     */
    private static final class Geom implements ShanhaiHoloSurround.Sink {

        MultiBufferSource.BufferSource buffers;
        Matrix4f view;
        Matrix4f mvp;
        float halfW, halfH;

        /** 相机基向量（世界坐标）。 */
        double fx, fy, fz, rx, ry, rz, ux, uy, uz, camX, camY, camZ;

        /** 当前这一批是"线"（0）还是"点"（1）；{@code -1} = 还没开始。 */
        private int kind = -1;
        private VertexConsumer current;

        // 诊断读数（打进日志用）
        int drawnSegments, drawnDots, blocked;

        // 🆕 顶点体检（见 ShanhaiHoloSurroundRenderer#logProbe）：只累加标量、只比平方，不 sqrt 不建对象
        /** 这一帧写出去的顶点相对【玩家脚底】的平方距离范围。 */
        double probeMin2 = Double.MAX_VALUE;
        double probeMax2 = 0.0;
        /** 水平方向（x/z）的平方距离上限 —— 判"有没有跑到玩家身后/侧面太远"。 */
        double probeHorizMax2 = 0.0;
        /** 水平位移的累加（除以顶点数 = 质心的水平偏移）。 */
        double probeSumX, probeSumZ;
        int probeVerts;
        private double probePlayerX, probePlayerY, probePlayerZ;

        private final Vector4f clipA = new Vector4f();
        private final Vector4f clipB = new Vector4f();

        void beginFrame(double playerX, double playerY, double playerZ) {
            kind = -1;
            current = null;
            drawnSegments = 0;
            drawnDots = 0;
            blocked = 0;
            probeMin2 = Double.MAX_VALUE;
            probeMax2 = 0.0;
            probeHorizMax2 = 0.0;
            probeSumX = 0.0;
            probeSumZ = 0.0;
            probeVerts = 0;
            probePlayerX = playerX;
            probePlayerY = playerY;
            probePlayerZ = playerZ;
        }

        /**
         * 取当前图元种类该用的顶点消费者。
         * <p>⚠️ <b>必须按种类显式切</b>：MC 的 {@code BufferSource#getBuffer} 在类型变化时会把
         * 上一批 end 掉 ⇒ 这一行既是"拿缓冲区"，也是"这批画到哪为止"的<b>唯一</b>分界。
         */
        private VertexConsumer consumer(int want) {
            if (kind != want) {
                kind = want;
                current = buffers.getBuffer(want == 0 ? lineType() : dotType());
            }
            return current;
        }

        // -------------------------------------------------------------- 线段

        @Override
        public void segment(double ax, double ay, double az,
                            double bx, double by, double bz,
                            int rgb, float widthHtmlPx, float alpha) {
            // 规则 ③-①：三维板面禁区
            final float fade = Math.min(ShanhaiHoloSurround.boardFade(FRAME, ax, ay, az),
                    ShanhaiHoloSurround.boardFade(FRAME, bx, by, bz));
            if (fade < ShanhaiHoloSurroundTuning.CULL_FADE) {
                blocked++;
                return;
            }

            // 近平面裁剪（环绕层是围着玩家的 ⇒ 第一视角下大量线段跨过近平面）
            clipA.set((float) ax, (float) ay, (float) az, 1.0f);
            mvp.transform(clipA);
            clipB.set((float) bx, (float) by, (float) bz, 1.0f);
            mvp.transform(clipB);
            final float near = ShanhaiHoloSurroundTuning.NEAR_DEPTH_BLOCKS;
            if (clipA.w < near && clipB.w < near) {
                blocked++;
                return;
            }
            double wax = ax;
            double way = ay;
            double waz = az;
            double wbx = bx;
            double wby = by;
            double wbz = bz;
            float cax = clipA.x;
            float cay = clipA.y;
            float caw = clipA.w;
            float cbx = clipB.x;
            float cby = clipB.y;
            float cbw = clipB.w;
            if (clipA.w < near) {
                final float t = (near - clipA.w) / (clipB.w - clipA.w);
                wax += (bx - ax) * t;
                way += (by - ay) * t;
                waz += (bz - az) * t;
                cax += (clipB.x - clipA.x) * t;
                cay += (clipB.y - clipA.y) * t;
                caw = near;
            } else if (clipB.w < near) {
                final float t = (near - clipA.w) / (clipB.w - clipA.w);
                wbx = ax + (bx - ax) * t;
                wby = ay + (by - ay) * t;
                wbz = az + (bz - az) * t;
                cbx = clipA.x + (clipB.x - clipA.x) * t;
                cby = clipA.y + (clipB.y - clipA.y) * t;
                cbw = near;
            }

            // 规则 ③-②：屏幕投影兜底
            final float pad = ShanhaiHoloSurroundTuning.LINE_SCREEN_PAD_FRAC * 2.0f * halfH;
            final double sax = cax / caw * halfW;
            final double say = -cay / caw * halfH;
            final double sbx = cbx / cbw * halfW;
            final double sby = -cby / cbw * halfH;
            final float fq = Math.min(ShanhaiHoloSurround.screenFade(FRAME, sax, say, pad),
                    ShanhaiHoloSurround.screenFade(FRAME, sbx, sby, pad));
            if (fq < ShanhaiHoloSurroundTuning.CULL_FADE) {
                blocked++;
                return;
            }

            final float a = clamp01(alpha * fade * fq);
            if (a < 1.0f / 255.0f) {
                blocked++;
                return;
            }

            // 线宽：HTML 参考像素 → 弧度 → 该深度处的世界宽度（与分辨率、视场角都无关）
            final double depth = (caw + cbw) * 0.5;
            final double half = widthHtmlPx / ShanhaiHoloSurroundTuning.HTML_REF_FOCAL_PX * depth * 0.5;

            // 屏幕平面内的"粗细方向" = 线段方向 × 视线方向
            double dx = wbx - wax;
            double dy = wby - way;
            double dz = wbz - waz;
            final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0e-9) {
                return;
            }
            dx /= len;
            dy /= len;
            dz /= len;
            double vx = (wax + wbx) * 0.5 - camX;
            double vy = (way + wby) * 0.5 - camY;
            double vz = (waz + wbz) * 0.5 - camZ;
            final double vl = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (vl < 1.0e-9) {
                return;
            }
            vx /= vl;
            vy /= vl;
            vz /= vl;
            double px = dy * vz - dz * vy;
            double py = dz * vx - dx * vz;
            double pz = dx * vy - dy * vx;
            double pl = Math.sqrt(px * px + py * py + pz * pz);
            if (pl < 1.0e-6) {
                // 线段几乎正对镜头 ⇒ 与视线叉乘退化，改用"相机右 × 线段方向"
                px = ry * dz - rz * dy;
                py = rz * dx - rx * dz;
                pz = rx * dy - ry * dx;
                pl = Math.sqrt(px * px + py * py + pz * pz);
                if (pl < 1.0e-6) {
                    px = rx;
                    py = ry;
                    pz = rz;
                    pl = 1.0;
                }
            }
            px = px / pl * half;
            py = py / pl * half;
            pz = pz / pl * half;

            final VertexConsumer vc = consumer(0);
            put(vc, wax + px, way + py, waz + pz, rgb, a);
            put(vc, wbx + px, wby + py, wbz + pz, rgb, a);
            put(vc, wbx - px, wby - py, wbz - pz, rgb, a);
            put(vc, wax - px, way - py, waz - pz, rgb, a);
            drawnSegments++;
        }

        // -------------------------------------------------------------- 光点

        @Override
        public void dot(double x, double y, double z, float radiusBlocks, int rgb, float alpha) {
            final float fade = ShanhaiHoloSurround.boardFade(FRAME, x, y, z);
            if (fade < ShanhaiHoloSurroundTuning.CULL_FADE) {
                blocked++;
                return;
            }
            clipA.set((float) x, (float) y, (float) z, 1.0f);
            mvp.transform(clipA);
            if (clipA.w < ShanhaiHoloSurroundTuning.NEAR_DEPTH_BLOCKS) {
                blocked++;
                return;
            }
            final float pad = ShanhaiHoloSurroundTuning.DOT_SCREEN_PAD_FRAC * 2.0f * halfH;
            final double sx = clipA.x / clipA.w * halfW;
            final double sy = -clipA.y / clipA.w * halfH;
            final float fq = ShanhaiHoloSurround.screenFade(FRAME, sx, sy, pad);
            if (fq < ShanhaiHoloSurroundTuning.CULL_FADE) {
                blocked++;
                return;
            }
            final float a = clamp01(alpha * fade * fq);
            if (a < ShanhaiHoloSurroundTuning.DOT_MIN_ALPHA) {
                blocked++;
                return;
            }

            final float rad = radiusBlocks * ShanhaiHoloSurroundTuning.DOT_SPRITE_RADIUS_MUL;
            final double ex = rx * rad;
            final double ey = ry * rad;
            final double ez = rz * rad;
            final double wx = ux * rad;
            final double wy = uy * rad;
            final double wz = uz * rad;

            final VertexConsumer vc = consumer(1);
            // 四个三角形拼成一个菱形风扇：
            //   (中心, +右, +上) (中心, +上, −右) (中心, −右, −上) (中心, −上, +右)
            fanTri(vc, x, y, z, x + ex, y + ey, z + ez, x + wx, y + wy, z + wz, rgb, a);
            fanTri(vc, x, y, z, x + wx, y + wy, z + wz, x - ex, y - ey, z - ez, rgb, a);
            fanTri(vc, x, y, z, x - ex, y - ey, z - ez, x - wx, y - wy, z - wz, rgb, a);
            fanTri(vc, x, y, z, x - wx, y - wy, z - wz, x + ex, y + ey, z + ez, rgb, a);
            drawnDots++;
        }

        // -------------------------------------------------------------- 顶点小工具

        /** 一个三角形，用"第 4 个顶点与第 3 个重合"的四边形表示（QUADS 模式下那一个三角形面积为零）。 */
        private void fanTri(VertexConsumer vc,
                            double cx, double cy, double cz,
                            double ax, double ay, double az,
                            double bx, double by, double bz,
                            int rgb, float alpha) {
            put(vc, cx, cy, cz, rgb, alpha);
            put(vc, ax, ay, az, rgb, 0.0f);
            put(vc, bx, by, bz, rgb, 0.0f);
            put(vc, bx, by, bz, rgb, 0.0f);
        }

        private void put(VertexConsumer vc, double x, double y, double z, int rgb, float alpha) {
            // 顶点体检：相对玩家的径向/水平距离（只比平方 ⇒ 每顶点一个 sqrt 都不做）
            final double pdx = x - probePlayerX;
            final double pdy = y - probePlayerY;
            final double pdz = z - probePlayerZ;
            final double d2 = pdx * pdx + pdy * pdy + pdz * pdz;
            if (d2 < probeMin2) {
                probeMin2 = d2;
            }
            if (d2 > probeMax2) {
                probeMax2 = d2;
            }
            final double h2 = pdx * pdx + pdz * pdz;
            if (h2 > probeHorizMax2) {
                probeHorizMax2 = h2;
            }
            probeSumX += pdx;
            probeSumZ += pdz;
            probeVerts++;

            vc.vertex(view, (float) x, (float) y, (float) z);
            vc.color((rgb >>> 16) & 0xFF, (rgb >>> 8) & 0xFF, rgb & 0xFF, (int) (alpha * 255.0f + 0.5f));
            vc.endVertex();
        }

        private static float clamp01(float v) {
            return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
        }
    }

    /** 供日志/自检：这一层是不是已经因为出错被关掉了。 */
    public static boolean isDisabled() {
        return disabled;
    }

    /** 供离线判读：把"上一帧实际画了多少"读出来（渲染线程写、外部读，只做诊断用）。 */
    public static int lastDrawnSegments() {
        return GEOM.drawnSegments;
    }

    /** 供离线判读：上一帧实际画了多少个光点。 */
    public static int lastDrawnDots() {
        return GEOM.drawnDots;
    }

    /** 供离线判读：上一帧被规则 ③ 挡掉了多少次。 */
    public static int lastBlocked() {
        return GEOM.blocked;
    }
}
