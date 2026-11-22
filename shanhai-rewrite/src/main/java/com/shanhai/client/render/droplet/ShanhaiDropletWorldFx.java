package com.shanhai.client.render.droplet;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.droplet.ShanhaiDropletEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.List;

/**
 * 水滴的<b>世界层附加 FX</b>：穿墙高亮 + 世界空间尾迹 / 推进环 + 给 GUI 用的投影矩阵捕获。
 *
 * <h2>一、为什么这些"被视锥剔除也能画"</h2>
 * 因为它们<b>不在实体渲染器里</b>。流程是：
 * <ol>
 *   <li>{@link ShanhaiDropletOwnership} 每客户端 tick 扫一遍世界，缓存"我名下"的水滴清单；</li>
 *   <li>{@link ShanhaiDropletTrail} 每客户端 tick 采样<b>世界坐标路点</b>（轨迹与推进环的唯一真源）；</li>
 *   <li>本类在 {@link RenderLevelStageEvent}（<b>全局阶段事件</b>，每帧必发、与实体是否可见无关）
 *       里遍历这些清单，自己画。</li>
 * </ol>
 * 原版实体渲染是 {@code EntityRenderDispatcher#render}：它先做 {@code shouldRender}
 * （视锥 {@code Frustum#isVisible} + 距离）再调渲染器 ⇒ <b>水滴一旦出了屏幕边缘，
 * 渲染器根本不会被调用</b>。
 *
 * <h2>二、🔴 挂点为什么是 {@code AFTER_ENTITIES}（2026-11-24 改）</h2>
 * 老实现挂在 {@code Stage.AFTER_TRANSLUCENT_BLOCKS}。查 Forge 47.4.16 的字节码
 * （{@code forge-1.20.1-47.4.16_mapped_parchment_*.jar}，
 * {@code RenderLevelStageEvent$Stage} 的静态初始化偏移 70-78）可以确认：
 * 那个 Stage 是<b>按 RenderType 注册</b>的（{@code register("after_translucent_blocks", RenderType.translucent())}），
 * 它<b>只</b>在 {@code LevelRenderer.renderChunkLayer} 的尾部、经由
 * {@code ForgeHooksClient.dispatchRenderStage(RenderType,...)} → {@code Stage.fromRenderType} 派发
 * （同一份 jar 的 {@code LevelRenderer} 偏移 766-789），而且派发发生在
 * {@code renderType.clearRenderState()} <b>之前</b>（偏移 792）—— 也就是说那一趟是在
 * "半透明地形这一层的渲染状态还没撤掉"的中间态里跑外部代码。多一层间接就多一层不确定性。
 *
 * <p>相对地，{@code AFTER_ENTITIES} 是 {@code LevelRenderer.renderLevel} 里<b>显式派发的固定阶段</b>
 * （偏移 1213，{@code getstatic Stage.AFTER_ENTITIES}），每帧必到；此时
 * {@code RenderSystem.getModelViewMatrix()} 还是<b>单位阵</b>（实体/地形都是"相机相对坐标"），
 * 和本类"CPU 侧已经把顶点乘成相机空间"的做法天然一致。
 * ⇒ 既不会因为渲染状态中间态出问题，也不会吃到"别人把模型视图矩阵换成视图矩阵"的二次变换。
 *
 * <p>代价（如实记录）：{@code AFTER_ENTITIES} 之后才画的<b>半透明方块</b>（水/彩色玻璃）会盖在
 * 高亮上面。实心地形（用户截图里那种）在这之前就画完了，不受影响。
 *
 * <h2>三、坐标系</h2>
 * {@code RenderLevelStageEvent} 的 PoseStack <b>已经是相机视图矩阵</b>
 * ⇒ 这里 {@code translate(-cam)} 一次，之后全程用<b>世界坐标</b>。
 * 矩阵本身由 {@link DropletLayers} 里的 {@code MAIN_TARGET_IDENTITY_MV} 钉成单位阵（并在画完还原）。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiDropletWorldFx {

    private static final String PREFIX = "[SHANHAI-DROPLET-FX]";

    /** 投影矩阵 / 视图矩阵（每帧覆盖；GUI 那趟只读）。 */
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static final Matrix4f VIEW = new Matrix4f();
    /** 相机位置（世界）。 */
    private static double camX;
    private static double camY;
    private static double camZ;
    /** 本帧的 MVP 是否有效（第一帧之前 / 不在世界里时 false）。 */
    private static boolean mvpValid;
    /** 最近一次捕获的时间戳（毫秒）——GUI 用它对"陈旧矩阵"兜底。 */
    private static long mvpAtMillis;

    /** 线框盒的颜色（蓝，和水滴同色系）—— <b>#4FA8FF</b>。 */
    private static final float BOX_R = 0.31F;      // 0x4F / 255
    private static final float BOX_G = 0.66F;      // 0xA8 / 255
    private static final float BOX_B = 1.00F;      // 0xFF / 255
    /** 线框盒的 alpha（用户要求 ~0.85；再乘一个 0.72~1.0 的极轻微呼吸）。 */
    private static final float BOX_ALPHA = 0.85F;

    /** 复用：盒子 6 个 double、相机三根基（世界）、视图矩阵 16 个 float。 */
    private static final double[] BOX = new double[6];
    private static final double[] CAM_RIGHT = new double[3];
    private static final double[] CAM_UP = new double[3];
    private static final double[] CAM_BACK = new double[3];
    private static final float[] VIEW_FLOATS = new float[16];

    /**
     * 焦距（像素）= {@code 投影矩阵 P11 × (帧缓冲高度/2)}。
     *
     * <p>这是"像素 ↔ 世界"的唯一换算因子：深度 {@code d} 处 1 世界单位 = {@code fPx/d} 像素
     * ⇒ 要让线框在任何距离都是同一个像素宽，就得按 {@code d/fPx} 把像素宽度缩回世界单位。
     * 每帧从投影矩阵现取（改 FOV / 改窗口大小都自动对）。
     */
    private static double focalPx = 771.0D;

    /** 投影用的复用向量（每帧零分配）。 */
    private static final Vector4f SCRATCH = new Vector4f();


    /**
     * 本类专用的顶点缓冲。
     *
     * <p>为什么不借 {@code Tesselator.getInstance().getBuilder()}：那个是<b>全局共享</b>的
     * {@code BufferBuilder}，谁在同一个阶段里正开着批次就会撞车（{@code begin()} 会抛
     * "Already building"）。自己拿一个 256 KB 的 Builder，每帧 {@code immediate(...)} + {@code endBatch}
     * 用完就收，互不干扰。
     */
    private static final BufferBuilder OWN_BUFFER = new BufferBuilder(262144);

    // ------------------------------------------------------------------ 诊断（5 秒 1 行）

    private static final long DIAG_INTERVAL_MILLIS = 5000L;
    private static long lastDiagMillis;

    private ShanhaiDropletWorldFx() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // 所有实体之后（见类注释 §二：显式派发、模型视图矩阵 = 单位阵）
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        if (level == null) {
            mvpValid = false;
            return;
        }
        // ① 捕获 MVP（给 GUI 那趟的准星拉线用）
        PROJECTION.set(event.getProjectionMatrix());
        VIEW.set(event.getPoseStack().last().pose());
        final Vec3 cam = event.getCamera().getPosition();
        camX = cam.x;
        camY = cam.y;
        camZ = cam.z;
        mvpValid = true;
        mvpAtMillis = System.currentTimeMillis();
        // 相机三根基（世界）+ 焦距：线框盒的"屏幕空间恒定线宽"靠这两个换算
        VIEW.get(VIEW_FLOATS);
        DropletBoxFx.cameraAxes(VIEW_FLOATS, CAM_RIGHT, CAM_UP, CAM_BACK);
        focalPx = PROJECTION.m11() * (mc.getWindow().getHeight() * 0.5D);

        // ② 收集：轨迹/推进环对所有水滴，高亮只对"我名下"
        final List<ShanhaiDropletEntity> all = ShanhaiDropletTrail.allDroplets();
        final List<ShanhaiDropletEntity> owned = ShanhaiDropletOwnership.ownedInLevel();
        if (all.isEmpty() && owned.isEmpty()) {
            return;
        }
        final PoseStack poseStack = event.getPoseStack();
        final float breath = 0.72F + 0.28F
                * (0.5F + 0.5F * Mth.sin(((float) (mvpAtMillis % 2000L)) * 0.0031415927F));
        final float partialTick = mc.getFrameTime();
        int drawn = 0;
        int glowQuads = 0;
        int boxQuads = 0;
        try {
            poseStack.pushPose();
            try {
                poseStack.translate(-camX, -camY, -camZ);
                final Matrix4f m = poseStack.last().pose();

                // ②-a 尾迹 + 推进环（加性、LEQUAL、不写深度；对**所有**水滴）
                final MultiBufferSource.BufferSource glowBuffers = MultiBufferSource.immediate(OWN_BUFFER);
                final VertexConsumer glow = glowBuffers.getBuffer(DropletLayers.glow());
                for (int i = 0; i < all.size(); i++) {
                    final ShanhaiDropletEntity droplet = all.get(i);
                    if (!droplet.isAlive() || droplet.level() != level) {
                        continue;
                    }
                    final int trailVerts = ShanhaiDropletTrail.trailStream(droplet, camX, camY, camZ);
                    if (trailVerts > 0) {
                        ShanhaiDropletDebugRenderer.emitWorldStream(glow, m,
                                DropletMesh.TRAIL_SCRATCH, trailVerts);
                        glowQuads += trailVerts / 4;
                    }
                    final int ringVerts = ShanhaiDropletTrail.ringStream(droplet, partialTick);
                    if (ringVerts > 0) {
                        ShanhaiDropletDebugRenderer.emitWorldStream(glow, m,
                                DropletMesh.RING_SCRATCH, ringVerts);
                        glowQuads += ringVerts / 4;
                    }
                }
                glowBuffers.endBatch(DropletLayers.glow());

                // ②-b 穿墙蓝框（用户 2026-11-24：「改成蓝色的框，就和驾驶的框一样」）
                //     几何 = 实体碰撞箱的 12 条棱；线宽是**屏幕像素**恒定（见 DropletBoxFx）。
                if (!owned.isEmpty()) {
                    final MultiBufferSource.BufferSource boxBuffers = MultiBufferSource.immediate(OWN_BUFFER);
                    final VertexConsumer box = boxBuffers.getBuffer(DropletLayers.box());
                    for (int i = 0; i < owned.size(); i++) {
                        final ShanhaiDropletEntity droplet = owned.get(i);
                        if (!droplet.isAlive() || droplet.level() != level) {
                            continue;
                        }
                        final AABB aabb = droplet.getBoundingBox();
                        BOX[0] = aabb.minX;
                        BOX[1] = aabb.minY;
                        BOX[2] = aabb.minZ;
                        BOX[3] = aabb.maxX;
                        BOX[4] = aabb.maxY;
                        BOX[5] = aabb.maxZ;
                        final int verts = DropletBoxFx.buildBox(BOX, camX, camY, camZ,
                                CAM_RIGHT, CAM_UP, CAM_BACK, focalPx,
                                DropletBoxFx.LINE_WIDTH_PX * 0.5F,
                                BOX_R, BOX_G, BOX_B, BOX_ALPHA * breath);
                        if (verts > 0) {
                            ShanhaiDropletDebugRenderer.emitWorldStream(box, m,
                                    DropletBoxFx.SCRATCH, verts);
                            boxQuads += verts / 4;
                            drawn++;
                        }
                    }
                    boxBuffers.endBatch(DropletLayers.box());
                }
            } finally {
                poseStack.popPose();
            }
        } catch (RuntimeException e) {
            ShanhaiMod.LOGGER.error("{} droplet_fx_failed（这一帧未画完，游戏继续）", PREFIX, e);
            return;
        }
        logDiagnostics(all.size(), owned.size(), drawn, glowQuads, boxQuads);
    }

    /**
     * 诊断行（5 秒 1 行）。口径见用户 2026-11-24 的要求。
     *
     * <pre>[SHANHAI-DROPLET-FX] xray boxes=N drawn=M quads=Q ownOk=true …</pre>
     *
     * <ul>
     *   <li>{@code boxes}：世界里看到的<b>全部</b>水滴数（本行同时是"这一趟真的跑过"的证据）；</li>
     *   <li>{@code drawn}：这一帧真的提交了蓝框的个数（应等于 {@code owned}）；</li>
     *   <li>{@code quads}：蓝框那一趟的四边形数 = <b>12 棱 × 1 四边形 × boxes</b>；0 就是没画；</li>
     *   <li>{@code ownOk}：{@code owned} 是否非空 —— 服务端 {@code ownerUuid} 不参与同步，客户端靠三条降级判据
     *       （见 {@link ShanhaiDropletOwnership}）。这一格是 false 时，"看不见框"的原因在归属判定，不在绘制。</li>
     * </ul>
     */
    private static void logDiagnostics(int boxes, int owned, int drawn, int glowQuads, int boxQuads) {
        final long now = System.currentTimeMillis();
        if (now - lastDiagMillis < DIAG_INTERVAL_MILLIS) {
            return;
        }
        lastDiagMillis = now;
        ShanhaiMod.LOGGER.info(
                "{} xray boxes={} drawn={} quads={} ownOk={} owned={} glow_quads={} line_px={} dim={}",
                PREFIX, Integer.valueOf(boxes), Integer.valueOf(drawn), Integer.valueOf(boxQuads),
                Boolean.valueOf(owned > 0), Integer.valueOf(owned), Integer.valueOf(glowQuads),
                Float.valueOf(DropletBoxFx.LINE_WIDTH_PX),
                Minecraft.getInstance().level == null ? "?" : Minecraft.getInstance().level.dimension().location());
    }

    // ══════════════════════════════════════════════════════════════════════════════════
    //  给 GUI 用的世界 → 屏幕投影（复用上面捕获的 MVP）
    // ══════════════════════════════════════════════════════════════════════════════════

    /** MVP 是否可用（GUI 那趟先问这个，避免用上一局的矩阵乱画）。 */
    public static boolean projectionReady() {
        return mvpValid && System.currentTimeMillis() - mvpAtMillis < 1000L;
    }

    /**
     * 世界坐标 → <b>GUI 缩放后</b>的屏幕坐标。
     *
     * <p>算法：{@code clip = P·V·(p−cam)} → 透视除法得 NDC → 映射到 GUI 空间
     * （{@code x=(ndc.x+1)/2·guiW}、{@code y=(1−ndc.y)/2·guiH}）。
     *
     * @param out       长度 ≥3：{@code [0]=guiX, [1]=guiY, [2]=是否在相机前方(1/0)}
     * @param guiW,guiH 当前 GUI 缩放尺寸
     */
    public static void projectToGui(double x, double y, double z, int guiW, int guiH, float[] out) {
        if (!projectionReady() || guiW <= 0 || guiH <= 0) {
            out[0] = 0.0F;
            out[1] = 0.0F;
            out[2] = 0.0F;
            return;
        }
        SCRATCH.set(x - camX, y - camY, z - camZ, 1.0D);
        VIEW.transform(SCRATCH);
        PROJECTION.transform(SCRATCH);
        final float w = SCRATCH.w;
        if (w <= 1.0E-4F) {
            // 在相机平面后方：投出来的点是"镜像"的（没有意义）⇒ 明确报不可见，让调用方跳过
            out[0] = 0.0F;
            out[1] = 0.0F;
            out[2] = 0.0F;
            return;
        }
        final float nx = SCRATCH.x / w;
        final float ny = SCRATCH.y / w;
        out[0] = (nx * 0.5F + 0.5F) * guiW;
        out[1] = (0.5F - ny * 0.5F) * guiH;
        out[2] = 1.0F;
    }
}
