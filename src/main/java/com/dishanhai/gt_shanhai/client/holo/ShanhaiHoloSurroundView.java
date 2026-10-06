package com.dishanhai.gt_shanhai.client.holo;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import org.joml.Matrix4f;

/**
 * 山海重构 · <b>环绕层唯一的"世界 → 相机"变换</b>。
 *
 * <h2>1. 🔴 为什么需要它（2026-10-06 用户实测第 ① 条：环绕层飘在天上）</h2>
 * 环绕层的顶点<b>全是世界坐标</b>（见 {@link ShanhaiHoloSurround.Frame#playerX} 那一组字段：
 * 环心 = 玩家、点场壳层 = 玩家上方 1.15 格 ⇒ 半径 2.55~3.30）。
 * 要把它画到玩家身上，顶点必须再经过一次 <b>{@code 世界 → 相机}</b> 的变换。
 *
 * <p>而 {@code RenderLevelStageEvent#getPoseStack()} 给的矩阵<b>只有相机旋转</b>，<b>没有</b>
 * {@code −camPos} 的平移。这一条是 {@code javap -c} 取证出来的，不是推断：
 * <pre>
 *   GameRenderer.renderLevel(float,long,PoseStack)   ← 只对 poseStack 施加旋转
 *     : 124  PoseStack.mulPoseMatrix(Matrix4f)        （视角晃动）
 *     : 131  bobHurt(PoseStack,float)                 （内部 translate 一正一负、净零）
 *     : 160  bobView(PoseStack,float)                 （同上）
 *     : 491/509/531  Axis.ZP/XP/YP rotationDegrees + PoseStack.mulPose
 *     : 602  LevelRenderer.prepareCullFrustum(PoseStack, Vec3, Matrix4f)
 *            ← 相机坐标是【另外传】的 ⇒ 说明 poseStack 里没有它
 *     : 627  LevelRenderer.renderLevel(PoseStack, …, Camera, …, Matrix4f)
 *   LevelRenderer.renderLevel  的 translate 全部成对 push/pop（1354/1541、1615/1688、2005/2144）
 *     : 2604/2743  ForgeHooksClient.dispatchRenderStage(AFTER_PARTICLES, …, aload_1 = 同一个 poseStack, …)
 *   com.mojang.blaze3d.vertex.VertexConsumer.vertex(Matrix4f,float,float,float)
 *     : 0  aload_1 → new Vector4f(x,y,z,1) → Matrix4f.transform(Vector4f)
 *          ← 仿射变换（含平移），与 Matrix4f（相机旋转）相乘时平移分量恒为 0
 * </pre>
 * ⇒ <b>顶点只过旋转、不过平移，于是在世界原点附近"按世界坐标"落地</b>：
 * 玩家离世界原点有多远，这团光就飘多远（用户的截图：一团光网挂在几百格外的天上，
 * 而贴着身的数据环一个都看不见）。实测方向也吻合 —— 顶点落在相机前方的方向
 * 恰好是"玩家世界坐标"那个方向、距离 ≈ |玩家世界坐标|。
 *
 * <h2>2. 对照：那 5 块板为什么是对的</h2>
 * {@link ShanhaiHoloMenuRenderer} 自己把 {@code −camPos} 补进了 {@code PoseStack}
 * （{@code poseStack.translate(ox − camera.x, …)}）⇒ 板的顶点是<b>相机相对坐标</b>，
 * 所以它一直画在玩家面前。环绕层缺的正是这一行 —— 本类把它补上，并且<b>只此一处</b>：
 * {@code MVP}（做近平面/屏幕兜底判据）与<b>顶点本身</b>用的是同一把矩阵，
 * 两者不可能再各自漂一套。
 *
 * <h2>3. 它不是"又一个工具类"，它是判据</h2>
 * 离线自检台（{@code temp/holo-verify}）会拿<b>同一个</b> {@link #composeView} 做正/负对照：
 * <pre>
 *   正：composeView(R, cam) 把世界点送到 R·(p − cam) ⇒ 与玩家的相机空间距离 ≤ 5 格 ✓
 *   负：只给 R（= 修复前那一行）⇒ 同一个顶点跑到几十~几百格外 ⇒ 判据必须报红 ✓
 * </pre>
 */
@OnlyIn(Dist.CLIENT)
public final class ShanhaiHoloSurroundView {

    private ShanhaiHoloSurroundView() {}

    /**
     * 合成<b>真正的</b>视图矩阵：{@code out = cameraPose · T(−camPos)}。
     *
     * <p>JOML 的 {@code Matrix4f.translate(x,y,z)} 是<b>右乘</b>一个平移矩阵
     * （{@code this = this · T}）⇒ 点先被平移，再被相机姿态作用，正是"世界 → 相机"。
     *
     * @param out        出口（复用同一把静态矩阵，不每帧 new）
     * @param cameraPose 事件给的 poseStack 矩阵（<b>只有旋转</b>）
     * @param camX       这一帧的相机世界坐标（{@code camera.getPosition()}，含 partialTick 插值）
     */
    public static void composeView(Matrix4f out, Matrix4f cameraPose,
                                   double camX, double camY, double camZ) {
        out.set(cameraPose);
        out.translate((float) (-camX), (float) (-camY), (float) (-camZ));
    }
}
