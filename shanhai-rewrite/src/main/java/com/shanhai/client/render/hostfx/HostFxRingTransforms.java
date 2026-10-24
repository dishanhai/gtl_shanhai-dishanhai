package com.shanhai.client.render.hostfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.core.Direction;

/**
 * 归墟视界枢核 · <b>旋转枢轴与朝向</b>（照伪神 {@code AntichristRingTransforms} 写，只换轴）。
 *
 * <h2>伪神原文（取证：{@code temp/host-fx/报告-伪神渲染取证.md} §3 第 ④ 段）</h2>
 * <pre>
 *   translate(starPos) → 朝向 yaw(NORTH 270 / SOUTH 90 / WEST 0 / EAST 180)
 *   → 每个环 mulPose(fromAxisAngleDeg(1,0,0, (tick*(0.4+i*0.4)*2*(i==1?-1:1) + i*120) % 360))
 * </pre>
 * 两处必须不一样：
 * <ol>
 *   <li><b>轴</b>：伪神的三个环是正交球壳环 ⇒ 绕 <b>X</b> 轴；我们的内环是水平圆环 ⇒ 绕 <b>Y</b> 轴；</li>
 *   <li><b>正位</b>：伪神的 {@code BASE_DIRECTION} 经字节码核实是 <b>EAST</b>
 *       （{@code AntichristRenderProfile.<clinit>} 偏移 11/14：{@code getstatic Direction.EAST → putstatic BASE_DIRECTION}），
 *       我们的是 <b>WEST</b>（{@code GuixuHostGeometry.CONTROLLER_FACING}）⇒
 *       这里只用 {@link HostFxGeometry#yawDegrees} 给的那一套（WEST 0 / NORTH 270 / SOUTH 90 / EAST 180），
 *       <b>不要</b>照抄伪神那边可能存在的 EAST 常量。</li>
 * </ol>
 *
 * <h2>为什么朝向能在这里转、不必逐顶点转</h2>
 * 旋转是线性的：{@code rotate(朝向, 环心 + 本地点) = rotate(朝向, 环心) + rotate(朝向, 本地点)}。
 * 所以"平移 + 一次 yaw"与"每个顶点各转一次"完全等价（离线探针 §3 会在整数点逐点验这一点），
 * 而前者只要 1 次矩阵乘法 —— 这正是伪神能把整环当刚体转的原因。
 */
public final class HostFxRingTransforms {

    private HostFxRingTransforms() {}

    /**
     * 摆好枢轴：平移到环心（控制器相对坐标），再套朝向 yaw。
     *
     * @param cx,cy,cz 环心相对控制器的坐标（TESR 的 PoseStack 已经在控制器原点）
     */
    public static void pivot(PoseStack poseStack, float cx, float cy, float cz, Direction facing) {
        poseStack.translate(cx, cy, cz);
        final float yaw = HostFxGeometry.yawDegrees(facing);
        if (yaw != 0.0F) {
            poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
        }
    }

    /** 绕 Y 轴自转（顺时针为正，与成环方向一致；调用方给"度"）。 */
    public static void spin(PoseStack poseStack, float angleDeg) {
        poseStack.mulPose(Axis.YP.rotationDegrees(angleDeg % 360.0F));
    }
}
