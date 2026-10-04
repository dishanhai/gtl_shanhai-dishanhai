package com.shanhai.client.render.halo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 一组「位移 + 旋转 + 缩放」，逐值抄自用户上一个 mod
 * （{@code ysm_render_compat 1.0.6} 的 {@code GoetyLayerRenderer.HaloOffsets}）的<b>同一套语义</b>：
 *
 * <pre>
 * poseStack.translate(x / 16, y / 16, z / 16);   // 像素 → 格
 * if (xRot != 0) poseStack.mulPose(Axis.XP.rotationDegrees(xRot));
 * if (yRot != 0) poseStack.mulPose(Axis.YP.rotationDegrees(yRot));
 * if (zRot != 0) poseStack.mulPose(Axis.ZP.rotationDegrees(zRot));
 * if (scale != 1) poseStack.scale(scale, scale, scale);
 * </pre>
 *
 * <h2>🔴 顺序是「位移 → 旋转 → 缩放」，缩放必须放最后</h2>
 * 这是用户上一个 mod 定的口径（其 README 原文：「缩放应用于位置偏移与旋转<b>之后</b>，
 * 因此原有的 X/Y/Z 偏移量含义不变」）。若把 scale 放前面，用户已经调好的像素位移含义会变，
 * 他得把所有数重调一遍。
 *
 * <h2>为什么三处旋转都带 {@code != 0} 短路</h2>
 * 与参考实现逐字一致 —— 不是为了性能，是为了<b>默认值（全 0 / 1.0）时姿势栈上一条指令都不多</b>，
 * 从而"默认 = 完全等价于启示录原版"这句话可以被字节码指令序列直接证明（见对拍脚本）。
 */
@OnlyIn(Dist.CLIENT)
public final class HaloPose {

    /** 什么都不加 = 原版（{@code OdamaneHaloLayer} 在 Curios 路径下的行为）。 */
    public static final HaloPose IDENTITY = new HaloPose(0, 0, 0, 0, 0, 0, 1);

    private final double xPixels;
    private final double yPixels;
    private final double zPixels;
    private final double xRotDeg;
    private final double yRotDeg;
    private final double zRotDeg;
    private final double scale;

    public HaloPose(double xPixels, double yPixels, double zPixels,
                    double xRotDeg, double yRotDeg, double zRotDeg, double scale) {
        this.xPixels = xPixels;
        this.yPixels = yPixels;
        this.zPixels = zPixels;
        this.xRotDeg = xRotDeg;
        this.yRotDeg = yRotDeg;
        this.zRotDeg = zRotDeg;
        this.scale = scale;
    }

    /** 从 {@code [x, y, z, xRot, yRot, zRot, scale]} 七元组构造（{@code ShanhaiClientConfig} 的返回格式）。 */
    public static HaloPose of(double[] v) {
        if (v == null || v.length != 7) {
            return IDENTITY;
        }
        return new HaloPose(v[0], v[1], v[2], v[3], v[4], v[5], v[6]);
    }

    public void applyTo(PoseStack poseStack) {
        poseStack.translate(xPixels / 16.0D, yPixels / 16.0D, zPixels / 16.0D);
        if (xRotDeg != 0.0D) {
            poseStack.mulPose(Axis.XP.rotationDegrees((float) xRotDeg));
        }
        if (yRotDeg != 0.0D) {
            poseStack.mulPose(Axis.YP.rotationDegrees((float) yRotDeg));
        }
        if (zRotDeg != 0.0D) {
            poseStack.mulPose(Axis.ZP.rotationDegrees((float) zRotDeg));
        }
        if (scale != 1.0D) {
            poseStack.scale((float) scale, (float) scale, (float) scale);
        }
    }

    /** 可判据的一行文本（日志用）：全 0/1 时会明确写出 {@code IDENTITY}。 */
    public String describe() {
        if (this == IDENTITY
                || (xPixels == 0 && yPixels == 0 && zPixels == 0
                && xRotDeg == 0 && yRotDeg == 0 && zRotDeg == 0 && scale == 1.0D)) {
            return "IDENTITY(0,0,0 / 0,0,0 / 1.0)";
        }
        return String.format("pos(%.3f,%.3f,%.3f)px rot(%.3f,%.3f,%.3f)deg scale(%.3f)",
                xPixels, yPixels, zPixels, xRotDeg, yRotDeg, zRotDeg, scale);
    }
}
