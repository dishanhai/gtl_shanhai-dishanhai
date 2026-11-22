package com.shanhai.client.render.droplet;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.droplet.ShanhaiDropletEntity;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

/**
 * 强相互作用水滴的<b>本体渲染器</b>（2026-11-24 起只画本体）。
 *
 * <h2>〇、类名为什么还叫 {@code Debug}</h2>
 * 历史名。原先这里只有 12 条线的占位线框，任务是「<b>保留文件、替换实现</b>」。
 * 所以：<b>类名 = 历史名，实现 = 真实外观</b>。
 *
 * <h2>一、这里只剩本体；尾迹/推进环/穿墙蓝框都搬走了</h2>
 * <ul>
 *   <li><b>本体</b>（{@link DropletMesh#BODY}）：v3 那条解析回转体，48 环 × 24 经线 = <b>1152 四边形</b>，
 *       顶点在本体坐标系里预烘一次，每帧只做"基变换 + 着色"。</li>
 *   <li><b>尾迹 / 推进环</b>：它们是<b>世界空间的痕迹</b>（走过的路不会因为水滴转头而转向），
 *       必须在水滴本体被视锥剔除时也画得出来 ⇒ 搬到 {@link ShanhaiDropletWorldFx} 的全局阶段，
 *       数据来自 {@link ShanhaiDropletTrail}（每 client tick 采样一次世界坐标）。</li>
 *   <li><b>穿墙蓝框</b>：同样在 {@link ShanhaiDropletWorldFx}（和视锥剔除无关），几何来自
 *       {@link DropletBoxFx} —— 实体碰撞箱的 <b>12 条棱</b>，<b>屏幕空间恒定线宽</b>。
 *       2026-11-24 之前这里是"随形高亮外壳两遍"，用户否掉了：「改成蓝色的框，就和驾驶的框一样」。</li>
 * </ul>
 *
 * <h2>二、朝向：按 {@code partialTick} 插值，头朝运动方向</h2>
 * 实体每 tick 把 {@code yaw/pitch} 写成速度方向，并且写了 {@code yRotO/xRotO}
 * ⇒ 这里用 {@code Mth.lerp(partialTick, yRotO, getYRot())} 拿到本帧朝向。
 * <p>🔴 <b>不拼欧拉角</b>：直接按实体自己 {@code makeBoundingBox()} 的三根基向量算
 * （{@code forward=局部+Z}、{@code right=局部+X}、{@code up=forward×right}），
 * 再把预烘顶点从本体坐标系<b>线性组合</b>到世界方向。这样"渲染的盒子"与"碰撞的盒子"
 * 用的是同一套公式 —— 两者对不上是这类实体最经典的 bug。
 *
 * <h2>三、每帧不 new 东西</h2>
 * 三个 {@code float[]} 静态暂存（颜色 / 顶点流）在类加载时按容量一次性分配；每帧只写、不建。
 */
public class ShanhaiDropletDebugRenderer extends EntityRenderer<ShanhaiDropletEntity> {

    /** 占位贴图：顶点色渲染器不绑贴图，这里只是 {@link EntityRenderer} 的必需返回值。 */
    private static final ResourceLocation PLACEHOLDER_TEXTURE =
            new ResourceLocation(ShanhaiMod.MOD_ID, "textures/entity/droplet_debug_placeholder.png");

    private static final String PREFIX = "[SHANHAI-DROPLET-FX]";

    /** 基向量暂存：{@code [0..2]=right、[3..5]=up、[6..8]=forward}。 */
    private static final float[] BASIS = new float[9];

    /** 本体每帧的顶点色暂存（容量 = 网格顶点数）。 */
    private static final float[] BODY_COL = new float[DropletMesh.BODY.vertexCount() * 3];

    private static final float[] SHADE_RGB = new float[3];

    /** 只在第一次出图时打一行判据（证明"真的进过提交"）。 */
    private static boolean loggedFirstFrame;

    public ShanhaiDropletDebugRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(ShanhaiDropletEntity entity) {
        return PLACEHOLDER_TEXTURE;
    }

    @Override
    public void render(ShanhaiDropletEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        // 朝向：实体每 tick 写 yaw/pitch 与它们的 o 值 ⇒ 必须插值，否则高速时会"跳格"
        final float yaw = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());
        final float pitch = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());
        buildBasis(yaw, pitch, BASIS);

        // 相机 → 表面 的方向 V（整颗水滴用同一个：见 DropletLook#shade 的说明）
        final Vec3 cam = this.entityRenderDispatcher.camera.getPosition();
        final double ex = Mth.lerp(partialTick, entity.xo, entity.getX());
        final double ey = Mth.lerp(partialTick, entity.yo, entity.getY());
        final double ez = Mth.lerp(partialTick, entity.zo, entity.getZ());
        float vx = (float) (cam.x - ex);
        float vy = (float) (cam.y - ey);
        float vz = (float) (cam.z - ez);
        final float vl = Math.max(1.0E-4F, (float) Math.sqrt(vx * vx + vy * vy + vz * vz));
        vx /= vl;
        vy /= vl;
        vz /= vl;

        try {
            // 本体（不透明，写深度）—— 独立一批：尾迹/高亮要读它的深度
            poseStack.pushPose();
            try {
                final Matrix4f m = poseStack.last().pose();
                final MultiBufferSource.BufferSource buffers =
                        MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
                emitBody(buffers.getBuffer(DropletLayers.body()), m, BASIS, 0.0D, 0.0D, 0.0D, vx, vy, vz);
                buffers.endBatch(DropletLayers.body());
            } finally {
                poseStack.popPose();
            }
            if (!loggedFirstFrame) {
                loggedFirstFrame = true;
                ShanhaiMod.LOGGER.info("{} first_frame pos={} yaw={} pitch={} body_quads={}",
                        PREFIX, entity.blockPosition(), Float.valueOf(yaw), Float.valueOf(pitch),
                        Integer.valueOf(DropletMesh.BODY.quadCount()));
            }
        } catch (RuntimeException e) {
            // 渲染线程抛异常 = 直接崩客户端。宁可这一帧少画，也不要打断存档会话。
            ShanhaiMod.LOGGER.error("{} render_failed uuid={}（这一帧未画完，游戏继续）",
                    PREFIX, entity.getUUID(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════
    //  共用：基变换 / 顶点发射（世界层那一趟也走这里，保证两边几何逐字相同）
    // ══════════════════════════════════════════════════════════════════════════════════

    /**
     * 由实体 {@code yaw/pitch} 算三根基向量 —— <b>与 {@code ShanhaiDropletEntity#makeBoundingBox()}
     * 逐字相同</b>，实现落在 {@link DropletMesh#basis}（零 MC 依赖，世界空间的推进环也要用它）。
     *
     * @param out {@code [0..2]=right、[3..5]=up、[6..8]=forward}
     */
    public static void buildBasis(float yaw, float pitch, float[] out) {
        DropletMesh.basis(yaw, pitch, out);
    }

    /**
     * 发射本体（逐顶点着色；顶点色按顶点算一次，四边形共享）。
     *
     * @param origin 本体坐标系原点在 pose 空间里的位置（实体渲染器传 0；穿墙那一趟传实体世界坐标 − 相机）
     */
    public static void emitBody(VertexConsumer vc, Matrix4f m, float[] basis,
                                double originX, double originY, double originZ,
                                float vx, float vy, float vz) {
        final DropletMesh.Baked mesh = DropletMesh.BODY;
        final int verts = mesh.vertexCount();
        // ① 先把每个顶点的"世界法线 → 颜色"算一遍（顶点被 4 个四边形共享，算 4 次是浪费）
        for (int i = 0; i < verts; i++) {
            final float nx = mesh.nrm[i * 3];
            final float ny = mesh.nrm[i * 3 + 1];
            final float nz = mesh.nrm[i * 3 + 2];
            final float wnx = basis[0] * nx + basis[3] * ny + basis[6] * nz;
            final float wny = basis[1] * nx + basis[4] * ny + basis[7] * nz;
            final float wnz = basis[2] * nx + basis[5] * ny + basis[8] * nz;
            DropletLook.shade(wnx, wny, wnz, vx, vy, vz, SHADE_RGB);
            BODY_COL[i * 3] = SHADE_RGB[0];
            BODY_COL[i * 3 + 1] = SHADE_RGB[1];
            BODY_COL[i * 3 + 2] = SHADE_RGB[2];
        }
        // ② 逐四边形发射（逆时针绕序，配 cull BACK）
        for (int q = 0; q < mesh.quadCount(); q++) {
            final int i0 = mesh.quad[q * 4];
            final int i1 = mesh.quad[q * 4 + 1];
            final int i2 = mesh.quad[q * 4 + 2];
            final int i3 = mesh.quad[q * 4 + 3];
            vertex(vc, m, basis, originX, originY, originZ, mesh.pos, i0);
            vertex(vc, m, basis, originX, originY, originZ, mesh.pos, i1);
            vertex(vc, m, basis, originX, originY, originZ, mesh.pos, i2);
            vertex(vc, m, basis, originX, originY, originZ, mesh.pos, i3);
        }
    }


    /**
     * 发射一条<b>世界坐标</b>顶点流（尾迹 / 推进环）：{@code x,y,z,r,g,b,a} × 顶点数。
     *
     * <p>与实体渲染器的基变换不同：这里的坐标已经<b>是世界坐标</b>，不做任何旋转
     * （推进环/尾迹属于世界空间，不能跟着水滴当前的 yaw/pitch 转）。
     */
    public static void emitWorldStream(VertexConsumer vc, Matrix4f m, float[] stream, int verts) {
        for (int i = 0; i < verts; i++) {
            final int o = i * DropletMesh.STREAM_STRIDE;
            vc.vertex(m, stream[o], stream[o + 1], stream[o + 2])
                    .color(stream[o + 3], stream[o + 4], stream[o + 5], stream[o + 6])
                    .endVertex();
        }
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, float[] basis,
                               double originX, double originY, double originZ,
                               float[] pos, int index) {
        final float px = pos[index * 3];
        final float py = pos[index * 3 + 1];
        final float pz = pos[index * 3 + 2];
        final float wx = basis[0] * px + basis[3] * py + basis[6] * pz;
        final float wy = basis[1] * px + basis[4] * py + basis[7] * pz;
        final float wz = basis[2] * px + basis[5] * py + basis[8] * pz;
        vc.vertex(m, (float) (originX + wx), (float) (originY + wy), (float) (originZ + wz))
                .color(BODY_COL[index * 3], BODY_COL[index * 3 + 1], BODY_COL[index * 3 + 2], 1.0F)
                .endVertex();
    }

}