package com.shanhai.client.renderer.machine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.registries.ForgeRegistries;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 原始终焉引擎环形轨道顶点缓冲
 * 与终焉矩阵相同的环形状，但使用蒸汽时代方块映射
 *
 * <h2>🔴 2026-10-04 修复：资源包重载（load 一次材质包）后贴图全乱、必须重启才正常</h2>
 *
 * <h3>根因（代码级，可复核）</h3>
 * 本类的顶点缓冲是<b>照着一份 {@link BakedQuad} 烘出来的</b>，而 {@link BakedQuad} 里的 UV 是
 * <b>方块图集的绝对坐标</b>（`textures/atlas/blocks.png` 整张图上的 0~1 值；取证：
 * `javap -p net.minecraft.client.renderer.block.model.BakedQuad` 的字段是
 * `int[] vertices`（UV 就编在里面）+ `TextureAtlasSprite sprite`，
 * 而 `builder.putBulkData(pose, quad, …)` 写进 {@code DefaultVertexFormat.BLOCK} 的就是这对 UV）。
 * <p>
 * 资源包一重载，客户端会**整张重建方块图集**（日志原文，每次 load 都有：
 * {@code [Render thread/INFO] [TextureAtlas/]: Created: 16384x16384x4 minecraft:textures/atlas/blocks.png-atlas}）
 * 并把 {@code ModelManager} 换成新实例（{@code Minecraft.reloadResourcePacks()} 里
 * {@code new ModelManager(...) → putfield modelManager}）⇒ 图集里每个 sprite 的落点都可能变。
 * <p>
 * 🔴 而旧代码<b>一辈子只烘一次</b>（`ringBuffers == null && !ringBuildAttempted`，
 * 见 2026-10-04 前 `javap -c getRingBuffers()` 的 `ifnonnull` / `ifne` 两条短路）
 * ⇒ 重载之后，这组 VBO 里的 UV 仍然指着<b>旧图集的坐标</b>，于是每张面都去采到
 * <b>别的方块（甚至别的模组）的 sprite</b>：用户实测现象 =「多方块结构件/控制器的面上出现了
 * 不属于它的贴图（绿色网格、蓝色同心圈）」；重启游戏会重新烘一次 ⇒ 恢复正常。
 *
 * <h3>修法（两处，都在这一个方法里，改动最小）</h3>
 * <ol>
 *   <li><b>自愈判据</b>：记住烘这批 VBO 时用的是哪一个 {@link ModelManager}；下一次渲染时若
 *       它与 {@code Minecraft.getModelManager()} 已经不是同一个实例 ⇒ 说明重载过了
 *       ⇒ {@link #invalidate()} 后重烘。<b>不依赖任何事件注册成功</b>（注册失败也照样会修）。</li>
 *   <li>另有显式入口 {@link #invalidate()}，由 {@code ShanhaiClientReloadInvalidator} 在
 *       {@code RegisterClientReloadListenersEvent} 的资源重载回调里调用（那次回调跑在
 *       **渲染线程**上 —— 判据就是上面那行 {@code [Render thread/…] TextureAtlas: Created}），
 *       保证"重载后第一帧就是新图集"，而不是等下一帧。</li>
 * </ol>
 * <p>
 * ⚠️ <b>烘制不再用共享 {@code Tesselator} 的 builder</b>（原来 `Tesselator.getInstance().getBuilder()`
 * 是全局共享缓冲：在渲染中途 {@code begin()/end()} 会把它正在用的内容冲掉）。改为与兄弟类
 * {@link PrimordialOmegaEngineModelBuffers} 同款的自建 {@link BufferBuilder}（已实测可用的写法）。
 */
public class PrimordialOmegaEngineRingBuffer {

    private static VertexBuffer[] ringBuffers;
    private static boolean ringBuildAttempted;

    /**
     * 当前这批 VBO 是照着<b>哪一个</b> {@link ModelManager}（= 哪一张方块图集）烘的。
     * 资源重载会把它整个换新 ⇒ 实例身份变了 ⇒ 旧 VBO 全部作废（见类注释）。
     */
    private static ModelManager builtAgainstModelManager;

    public static VertexBuffer[] getRingBuffers() {
        // 🔴 自愈判据：本方法每次渲染都会被调用，O(1) 取一次 ModelManager 实例比一下身份。
        //    它独立于事件注册，所以「重载监听器没装上也照样修」（两条路互为保险）。
        ModelManager current = currentModelManager();
        if (current != null && current != builtAgainstModelManager) {
            invalidate();
            builtAgainstModelManager = current;
        }
        if (ringBuffers == null && !ringBuildAttempted) {
            ringBuildAttempted = true;
            ringBuffers = buildAllBuffers();
        }
        return ringBuffers;
    }

    private static ModelManager currentModelManager() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? null : minecraft.getModelManager();
    }

    /**
     * 作废本组 VBO（下一次 {@link #getRingBuffers()} 会用<b>当前</b>图集重烘）。
     *
     * <p>只在渲染线程真正 close（{@code glDeleteBuffers} 必须在渲染线程）；
     * 非渲染线程走到这里就只丢引用（正常路径到不了 —— 资源重载的 apply 跑在渲染线程）。
     */
    public static void invalidate() {
        VertexBuffer[] old = ringBuffers;
        ringBuffers = null;
        ringBuildAttempted = false;
        if (old == null) {
            return;
        }
        if (!RenderSystem.isOnRenderThread()) {
            com.shanhai.ShanhaiMod.LOGGER.warn(
                    "[SHANHAI-RELOAD] ring VBO 在非渲染线程被作废 ⇒ 只丢引用、不 close（本来不该发生；"
                            + "GL 对象交给 GC，下一次渲染会用新图集重烘）");
            return;
        }
        for (VertexBuffer buffer : old) {
            if (buffer != null) {
                buffer.close();
            }
        }
    }

    private static VertexBuffer[] buildAllBuffers() {
        try {
            // 优先反射调用 RingStructure（与终焉矩阵相同数据），失败则回退内联图案
            String[][][] rings = loadAntichristRingData();
            if (rings == null) rings = createFallbackPatterns();

            int count = rings.length;
            VertexBuffer[] buffers = new VertexBuffer[count];
            for (int i = 0; i < count; i++) {
                buffers[i] = buildRingBuffer(rings[i]);
            }
            return buffers;
        } catch (Exception e) {
            ringBuildAttempted = false;
            return null;
        }
    }

    // ========== 通过反射获取神锻环数据（无编译时依赖） ==========

    private static String[][][] loadAntichristRingData() {
        try {
            Class<?> rsClass = Class.forName(
                    "com.gtladd.gtladditions.common.machine.multiblock.structure.RingStructure");
            Object instance = rsClass.getField("INSTANCE").get(null);
            Object ringsObj = rsClass.getMethod("getRINGS").invoke(instance);
            return (String[][][]) ringsObj;
        } catch (Exception ignored) {
            return null; // 回退内联
        }
    }

    // ========== 回退内联环形图案 ==========

    private static String[][][] createFallbackPatterns() {
        return new String[][][] {
            buildFallbackRing('G', 'C', 10, 18, 5, 40),
            buildFallbackRing('D', 'E', 16, 26, 5, 28),
            buildFallbackRing('F', 'H', 22, 36, 5, 16)
        };
    }

    private static String[][] buildFallbackRing(char outerBlock, char innerBlock, int innerR, int outerR, int thickness, int pad) {
        int ringSize = outerR * 2 + 1;
        String[] slice = new String[ringSize];
        for (int z = 0; z < ringSize; z++) {
            StringBuilder sb = new StringBuilder();
            for (int p = 0; p < pad; p++) sb.append(' ');
            for (int x = 0; x < ringSize; x++) {
                double dist = Math.sqrt(Math.pow(z - outerR, 2) + Math.pow(x - outerR, 2));
                boolean inRing = dist >= innerR && dist <= outerR;
                sb.append(inRing ? (dist < (innerR + outerR) / 2.0 ? innerBlock : outerBlock) : ' ');
            }
            slice[z] = sb.toString();
        }
        String[][] pattern = new String[thickness][];
        for (int t = 0; t < thickness; t++) pattern[t] = slice;
        return pattern;
    }

    // ========== 蒸汽时代方块映射 ==========

    private static final Map<Character, Block> BLOCK_MAPPER = createBlockMapper();

    private static Map<Character, Block> createBlockMapper() {
        Map<Character, Block> map = new HashMap<>();
        // 与 PrimordialOmegaEngineStructure 保持一致的主题
        map.put('C', getBlock("gtceu", "bronze_pipe_casing"));
        map.put('D', getBlock("gtceu", "firebricks"));
        map.put('E', getBlock("gtceu", "industrial_steam_casing"));
        map.put('F', getBlock("gtceu", "coke_oven_bricks"));
        map.put('G', getBlock("gtceu", "bronze_machine_casing"));
        map.put('H', getBlock("gtceu", "steam_machine_casing"));
        map.put('I', getBlock("gtceu", "bronze_pipe_casing"));
        map.put('J', getBlock("gtceu", "firebricks"));
        map.put('K', getBlock("gtceu", "industrial_steam_casing"));
        return map;
    }

    private static Block getBlock(String namespace, String path) {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(namespace, path));
        return block != null ? block : Blocks.AIR;
    }

    // ========== 单环构建 ==========

    private static VertexBuffer buildRingBuffer(String[][] pattern) {
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        // 🔴 自建 BufferBuilder（不用 Tesselator 的全局共享那个）：本方法在渲染中途被调用，
        //    共享缓冲里可能正装着别处的内容，begin()/end() 会把它冲掉。
        //    写法与 PrimordialOmegaEngineModelBuffers.buildBuffer 逐行同款（那条路已实测可用）。
        BufferBuilder builder = new BufferBuilder(1 << 20);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);

        PoseStack poseStack = new PoseStack();
        RandomSource random = RandomSource.create();

        double centerY = pattern.length / 2.0;
        double centerZ = pattern[0].length / 2.0;
        double centerX = pattern[0][0].length() / 2.0;

        for (int y = 0; y < pattern.length; y++) {
            String[] layer = pattern[y];
            for (int z = 0; z < layer.length; z++) {
                String row = layer[z];
                for (int x = 0; x < row.length(); x++) {
                    char c = row.charAt(x);
                    if (c == ' ') continue;

                    Block block = BLOCK_MAPPER.get(c);
                    if (block == null || block == Blocks.AIR) continue;

                    List<Direction> visibleFaces = getVisibleFaces(pattern, y, z, x);
                    if (visibleFaces.isEmpty()) continue;

                    BlockState state = block.defaultBlockState();
                    BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
                    BakedModel model = dispatcher.getBlockModel(state);
                    if (model == null) continue;

                    poseStack.pushPose();
                    poseStack.translate(
                            y - centerY,
                            z - centerZ,
                            x - centerX
                    );

                    int lightUV = LightTexture.pack(
                            block.getLightEmission(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO),
                            13
                    );

                    renderBlockModelFaces(model, state, visibleFaces, lightUV, poseStack, builder, random);

                    poseStack.popPose();
                }
            }
        }

        buffer.bind();
        buffer.upload(builder.end());
        VertexBuffer.unbind();
        return buffer;
    }

    // ========== 可见面计算 ==========

    private static List<Direction> getVisibleFaces(String[][] pattern, int y, int z, int x) {
        List<Direction> faces = new ArrayList<>();
        char current = pattern[y][z].charAt(x);

        checkFace(pattern, y, z, x, -1, 0, 0, current, Direction.WEST, faces);
        checkFace(pattern, y, z, x, 1, 0, 0, current, Direction.EAST, faces);
        checkFace(pattern, y, z, x, 0, -1, 0, current, Direction.UP, faces);
        checkFace(pattern, y, z, x, 0, 1, 0, current, Direction.DOWN, faces);
        checkFace(pattern, y, z, x, 0, 0, -1, current, Direction.NORTH, faces);
        checkFace(pattern, y, z, x, 0, 0, 1, current, Direction.SOUTH, faces);

        return faces;
    }

    private static void checkFace(String[][] pattern, int y, int z, int x,
                                   int dy, int dz, int dx, char current,
                                   Direction direction, List<Direction> faces) {
        int ny = y + dy;
        int nz = z + dz;
        int nx = x + dx;

        if (ny < 0 || ny >= pattern.length ||
            nz < 0 || nz >= pattern[ny].length ||
            nx < 0 || nx >= pattern[ny][nz].length()) {
            faces.add(direction);
            return;
        }

        char neighbor = pattern[ny][nz].charAt(nx);
        if (!shouldCullFace(current, neighbor)) {
            faces.add(direction);
        }
    }

    private static boolean shouldCullFace(char current, char neighbor) {
        if (neighbor == ' ') return false;

        Block neighborBlock = BLOCK_MAPPER.get(neighbor);
        if (neighborBlock == null || neighborBlock == Blocks.AIR) return false;

        if (current != neighbor) return false;

        BlockState state = neighborBlock.defaultBlockState();
        // level 可能为空（渲染初始化阶段），此时不剔除面
        var level = Minecraft.getInstance().level;
        if (level == null) return false;
        return state.isSolidRender(level, BlockPos.ZERO);
    }

    // ========== 模型面渲染 ==========

    private static void renderBlockModelFaces(BakedModel model, BlockState state,
                                               List<Direction> directions, int lightUV,
                                               PoseStack poseStack, BufferBuilder builder,
                                               RandomSource random) {
        PoseStack.Pose pose = poseStack.last();
        ModelData modelData = ModelData.EMPTY;
        int overlay = OverlayTexture.NO_OVERLAY;

        random.setSeed(42L);
        List<BakedQuad> quads = model.getQuads(state, null, random, modelData, null);
        for (BakedQuad quad : quads) {
            builder.putBulkData(pose, quad, 1.0f, 1.0f, 1.0f, 1.0f, lightUV, overlay, true);
        }

        for (Direction dir : directions) {
            random.setSeed(42L);
            quads = model.getQuads(state, dir, random, modelData, null);
            for (BakedQuad quad : quads) {
                builder.putBulkData(pose, quad, 1.0f, 1.0f, 1.0f, 1.0f, lightUV, overlay, true);
            }
        }
    }
}
