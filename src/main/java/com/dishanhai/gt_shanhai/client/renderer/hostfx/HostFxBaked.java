package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 归墟视界枢核 · <b>烘焙器</b>：把"被藏起来的真方块"烘成静态 {@code VertexBuffer}。
 *
 * <h2>为什么这么做（照抄伪神之锻炉）</h2>
 * 用户口径：「内环和飞船的渲染都可以是直接让方块开始转动，就像伪神的那三个环的渲染一样」。
 * 伪神的做法（gtladditions {@code RingStructureVertexBuffer}，取证见
 * {@code temp/host-fx/报告-伪神渲染取证.md} §3）：
 * <ol>
 *   <li>{@code prepareRing}：逐格取 {@link BakedModel}，按"图案邻居"剔面，按 RenderType 分桶；</li>
 *   <li>{@code uploadTerrainRing}：每 RenderType 一个 {@code BufferBuilder}，逐块
 *       {@code translate(本地坐标)} + {@code putBulkData}，装进 {@code VertexBuffer(STATIC)} 缓存；</li>
 *   <li>{@code renderUploadedTerrainRing}：每帧 {@code setupRenderState → drawWithShader → clearRenderState}。</li>
 * </ol>
 * 本类的 ①②③ 与它一一对应，差别只有两处（都是我们有意的）：
 * <ul>
 *   <li><b>方块来源</b>：它用编译期图案 {@code RingStructure.RINGS}；我们用 {@link HostFxHide#ledger}
 *       的<b>同一份台账</b>（藏了哪些格，就烘哪些格，绝不"藏 A 画 B"）；</li>
 *   <li><b>面剔除</b>：它按图案字符 + {@code skipRendering} 判定；我们直接看"邻居格是否也在台账里"
 *       （我们这个集合是一个薄环 + 小飞船，邻居在集合里 ⇒ 那一面一定在体内，剔掉不会漏）。</li>
 * </ul>
 *
 * <h2>坐标</h2>
 * 顶点烘在 <b>环心本地坐标</b>（{@link HostFxGeometry#CELL_CENTER} 口径：环心那一格的<b>中心</b> = 本地 (0,0,0)，
 * 每格占 [n−0.5, n+0.5]³）。每帧由 {@link HostFxRingTransforms} 先平移到"环心格中心"、
 * 再套朝向 yaw、再套自转角 —— 这个顺序（平移 → yaw → 自转）就是"绕那条竖轴刚体旋转"。
 *
 * <h2>⚠️ 只能在渲染线程调用</h2>
 * {@code VertexBuffer.upload} 是 GL 上传；{@link Minecraft#getBlockRenderer()} 也要在主线程。
 */
public final class HostFxBaked implements AutoCloseable {

    /**
     * 一个待烘焙的方块。
     *
     * @param gx,gy,gz 本地整数网格坐标（面剔除的邻居判定用）
     * @param lx,ly,lz 本地浮点坐标（顶点平移用；本实现里与整数格一一对应）
     */
    public record Cell(int gx, int gy, int gz, float lx, float ly, float lz, BlockState state) {}

    /** RenderType → 顶点缓冲（一个集合里通常只有 solid / cutout / translucent 两三种）。 */
    private final Map<RenderType, VertexBuffer> buffers = new LinkedHashMap<>();
    private String name = "未命名";
    private int blocks;
    private int quads;
    private int skippedTypes;

    private HostFxBaked() {}

    public String name() {
        return name;
    }

    public int blocks() {
        return blocks;
    }

    public int quads() {
        return quads;
    }

    public int bufferCount() {
        return buffers.size();
    }

    /** 因为顶点格式不是 BLOCK 而被跳过的材质数（正常应为 0；日志里能看到）。 */
    public int skippedTypes() {
        return skippedTypes;
    }

    public boolean isEmpty() {
        return buffers.isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  ① 台账 → 本地格
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * 把 {@link HostFxHide} 的台账（世界坐标 → 原方块状态）换成环心本地坐标的方块表。
     *
     * <p>⚠️ 这里就是"藏一份、烘一份"的接头处：台账里有什么，就烘什么；
     * 台账是空的（还没藏过）⇒ 返回空表，调用方不要画（否则会和真方块叠成两层）。
     */
    public static List<Cell> cells(Map<Long, BlockState> ledger, BlockPos controllerPos, Direction facing) {
        final List<Cell> out = new ArrayList<>(ledger.size());
        for (Map.Entry<Long, BlockState> e : ledger.entrySet()) {
            final BlockPos world = BlockPos.of(e.getKey());
            final float[] l = HostFxGeometry.worldToLocal(controllerPos, facing, world);
            // 本地坐标是"格中心在整数上"（HostFxGeometry.CELL_CENTER）⇒ 格子索引 = round(l + 0.5)；
            // 顶点平移用 l 本身（一格占 [l, l+1]… 即 [n−0.5, n+0.5]），这是 putBulkData 的角点口径。
            out.add(new Cell(Math.round(l[0] + HostFxGeometry.CELL_CENTER),
                    Math.round(l[1] + HostFxGeometry.CELL_CENTER),
                    Math.round(l[2] + HostFxGeometry.CELL_CENTER),
                    l[0], l[1], l[2], e.getValue()));
        }
        return out;
    }

    /** 台账逐格的世界坐标往返自检（"本地坐标能不能还原成原来那一格"），返回第一处不一致，null = 全对。 */
    public static String roundTripMismatch(List<Cell> cells, BlockPos controllerPos, Direction facing) {
        for (Cell c : cells) {
            final double[] w = HostFxGeometry.localToWorld(controllerPos, facing, c.lx(), c.ly(), c.lz());
            final BlockPos back = BlockPos.containing(w[0], w[1], w[2]);
            final float[] again = HostFxGeometry.worldToLocal(controllerPos, facing, back);
            if (Math.abs(again[0] - c.lx()) > 1.0e-3F
                    || Math.abs(again[1] - c.ly()) > 1.0e-3F
                    || Math.abs(again[2] - c.lz()) > 1.0e-3F) {
                return c.gx() + "," + c.gy() + "," + c.gz() + " → " + back;
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  ② 烘焙
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /** 烘焙一组方块（同一组共用一套 RenderType → VertexBuffer）。 */
    public static HostFxBaked bake(String name, List<Cell> cells) {
        final HostFxBaked baked = new HostFxBaked();
        baked.name = name;
        if (cells.isEmpty()) {
            return baked;
        }
        final Set<Long> occupied = new HashSet<>(cells.size() * 2);
        for (Cell c : cells) {
            occupied.add(key(c.gx(), c.gy(), c.gz()));
        }
        final Map<RenderType, BufferBuilder> builders = new LinkedHashMap<>();
        final RandomSource random = RandomSource.create(42L);
        final PoseStack poseStack = new PoseStack();
        final var dispatcher = Minecraft.getInstance().getBlockRenderer();
        final List<Direction> faces = new ArrayList<>(6);
        final List<BakedQuad> quads = new ArrayList<>(32);
        for (Cell c : cells) {
            final BlockState state = c.state();
            final BakedModel model;
            try {
                model = dispatcher.getBlockModel(state);
            } catch (Throwable t) {
                continue;                       // 单个方块模型坏了不该拖垮整组
            }
            final int light = LightTexture.pack(
                    state.getBlock().getLightEmission(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO), 13);
            for (RenderType type : model.getRenderTypes(state, random, ModelData.EMPTY)) {
                if (type.format() != DefaultVertexFormat.BLOCK) {
                    baked.skippedTypes++;
                    continue;
                }
                // 面剔除：邻居也在本组里 ⇒ 那一面在体内，不用画
                faces.clear();
                for (Direction dir : Direction.values()) {
                    final long nk = key(c.gx() + dir.getStepX(), c.gy() + dir.getStepY(),
                            c.gz() + dir.getStepZ());
                    if (!occupied.contains(nk)) {
                        faces.add(dir);
                    }
                }
                quads.clear();
                random.setSeed(42L);
                quads.addAll(model.getQuads(state, null, random, ModelData.EMPTY, type));
                for (Direction dir : faces) {
                    random.setSeed(42L);
                    quads.addAll(model.getQuads(state, dir, random, ModelData.EMPTY, type));
                }
                if (quads.isEmpty()) {
                    continue;
                }
                BufferBuilder builder = builders.get(type);
                if (builder == null) {
                    builder = new BufferBuilder(type.bufferSize());
                    builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
                    builders.put(type, builder);
                }
                poseStack.pushPose();
                poseStack.translate(c.lx(), c.ly(), c.lz());
                final PoseStack.Pose pose = poseStack.last();
                for (BakedQuad quad : quads) {
                    builder.putBulkData(pose, quad, 1.0F, 1.0F, 1.0F, 1.0F, light,
                            OverlayTexture.NO_OVERLAY, true);
                }
                poseStack.popPose();
                baked.quads += quads.size();
            }
            baked.blocks++;
        }
        for (Map.Entry<RenderType, BufferBuilder> e : builders.entrySet()) {
            final BufferBuilder builder = e.getValue();
            if (builder.building()) {
                final VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
                vb.bind();
                vb.upload(builder.end());
                VertexBuffer.unbind();
                baked.buffers.put(e.getKey(), vb);
            }
        }
        return baked;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  ③ 每帧绘制
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * 把本组画一遍（调用方已经把 pose 摆成"环心格中心 + 朝向 + 自转角"）。
     *
     * <p>逐 RenderType：{@code setupRenderState} → {@code drawWithShader} → {@code clearRenderState}，
     * 与伪神 {@code renderUploadedTerrainRing} 逐句一致；状态由 RenderType 自己负责开关，
     * 所以外层的混合/深度设置不会被我们污染。
     *
     * <p>🔴 <b>为什么要乘一个 {@code RenderSystem.getModelViewMatrix()}</b>：
     * 顶点缓冲里的顶点是"环心本地坐标"，真正要交给 shader 的 ModelViewMat 必须是
     * <b>本地 → 相机</b> 的完整变换。而 BER 的 PoseStack 与 {@code RenderSystem} 的 modelview
     * 谁含"相机旋转"取决于渲染阶段（1.20.1 里两者互补，合起来才是完整变换）。
     * 写成 {@code modelview · pose} 就在两种情形下都恒等于"完整变换"：
     * <ul>
     *   <li>若 modelview = 单位阵（BER pose 已含相机旋转）⇒ 结果 = pose；</li>
     *   <li>若 modelview = 相机旋转（BER pose 只有平移）⇒ 结果 = 旋转 · 平移。</li>
     * </ul>
     * 这也正好是 {@code BufferUploader}/{@code MultiBufferSource} 那条路对本几何做的事
     * （它对 {@code pose} 变换后的顶点再乘一次 modelview）⇒ 烘焙方块与同一帧里的四边形/光束<b>不会错位</b>。
     */
    public void render(PoseStack poseStack) {
        if (buffers.isEmpty()) {
            return;
        }
        final Matrix4f pose = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(poseStack.last().pose());
        for (Map.Entry<RenderType, VertexBuffer> e : buffers.entrySet()) {
            final RenderType type = e.getKey();
            type.setupRenderState();
            try {
                final ShaderInstance shader = RenderSystem.getShader();
                if (shader != null) {
                    final VertexBuffer vb = e.getValue();
                    vb.bind();
                    vb.drawWithShader(pose, RenderSystem.getProjectionMatrix(), shader);
                    VertexBuffer.unbind();
                }
            } finally {
                type.clearRenderState();
            }
        }
    }

    @Override
    public void close() {
        for (VertexBuffer vb : buffers.values()) {
            try {
                vb.close();
            } catch (Throwable ignored) {
                // 换维度/重启期关缓冲失败不影响后续（下一次 bake 会重新建）
            }
        }
        buffers.clear();
        blocks = 0;
        quads = 0;
    }

    /** 一组的摘要（日志用）。 */
    public String summary() {
        return name + " " + blocks + " 格/" + quads + " 面×4顶点/" + buffers.size() + " 缓冲";
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (long) (z & 0x1FFFFF);
    }
}
