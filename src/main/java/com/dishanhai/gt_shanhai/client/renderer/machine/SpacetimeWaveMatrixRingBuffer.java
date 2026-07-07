package com.dishanhai.gt_shanhai.client.renderer.machine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
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

import com.gtladd.gtladditions.common.machine.multiblock.structure.RingStructure;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 终焉超时空波动矩阵的自定义环结构顶点缓冲
 * 使用与神锻相同的环形状，但映射到自定义方块
 */
public class SpacetimeWaveMatrixRingBuffer {

    private static VertexBuffer[] ringBuffers;

    public static VertexBuffer[] getRingBuffers() {
        if (ringBuffers == null) {
            ringBuffers = buildAllBuffers();
        }
        return ringBuffers;
    }

    private static VertexBuffer[] buildAllBuffers() {
        try {
            String[][][] rings = RingStructure.INSTANCE.getRINGS();
            VertexBuffer[] buffers = new VertexBuffer[rings.length];
            for (int i = 0; i < rings.length; i++) {
                buffers[i] = buildRingBuffer(rings[i]);
            }
            return buffers;
        } catch (Exception e) {
            // 静默降级：返回空缓冲
            VertexBuffer[] buffers = new VertexBuffer[3];
            for (int i = 0; i < 3; i++) {
                buffers[i] = new VertexBuffer(VertexBuffer.Usage.STATIC);
            }
            return buffers;
        }
    }

    // ========== 方块映射 ==========

    private static final Map<Character, Block> BLOCK_MAPPER = createBlockMapper();

    private static Map<Character, Block> createBlockMapper() {
        Map<Character, Block> map = new HashMap<>();
        // 与 SpacetimeWaveMatrixStructure 保持一致
        map.put('C', getBlock("gtlcore", "manipulator"));                        // 结构 C
        map.put('D', getBlock("gtladditions", "extreme_density_casing"));         // 极密外壳
        map.put('E', getBlock("gtladditions", "quantum_glass"));                 // 结构 E
        map.put('F', getBlock("gtlcore", "qft_coil"));                           // 结构 F
        map.put('G', getBlock("gtladditions", "remote_graviton_flow_regulator"));
        map.put('H', getBlock("gtladditions", "spatially_transcendent_gravitational_lens"));
        map.put('I', getBlock("gtladditions", "central_graviton_flow_regulator"));
        map.put('J', getBlock("gtladditions", "extreme_density_casing"));        // 极密外壳
        map.put('K', getBlock("gtladditions", "mediary_graviton_flow_regulator"));
        return map;
    }

    private static Block getBlock(String namespace, String path) {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(namespace, path));
        return block != null ? block : Blocks.AIR;
    }

    // ========== 单环构建 ==========

    private static VertexBuffer buildRingBuffer(String[][] pattern) {
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
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

        // 6 个方向：WEST, EAST, UP, DOWN, NORTH, SOUTH
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

        // 边界外 → 面可见
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
        // 空格 → 不剔除
        if (neighbor == ' ') return false;

        Block neighborBlock = BLOCK_MAPPER.get(neighbor);
        if (neighborBlock == null || neighborBlock == Blocks.AIR) return false;

        // 不同方块类型 → 不剔除
        if (current != neighbor) return false;

        // 相同方块 → 是实体方块则剔除面，透明方块则不剔除
        BlockState state = neighborBlock.defaultBlockState();
        var level = Minecraft.getInstance().level;
        return level != null && state.isSolidRender(level, BlockPos.ZERO);
    }

    // ========== 模型面渲染 ==========

    private static void renderBlockModelFaces(BakedModel model, BlockState state,
                                               List<Direction> directions, int lightUV,
                                               PoseStack poseStack, BufferBuilder builder,
                                               RandomSource random) {
        PoseStack.Pose pose = poseStack.last();
        ModelData modelData = ModelData.EMPTY;
        int overlay = OverlayTexture.NO_OVERLAY;

        // 无方向面（粒子效果等）
        random.setSeed(42L);
        List<BakedQuad> quads = model.getQuads(state, null, random, modelData, null);
        for (BakedQuad quad : quads) {
            builder.putBulkData(pose, quad, 1.0f, 1.0f, 1.0f, 1.0f, lightUV, overlay, true);
        }

        // 有方向面
        for (Direction dir : directions) {
            random.setSeed(42L);
            quads = model.getQuads(state, dir, random, modelData, null);
            for (BakedQuad quad : quads) {
                builder.putBulkData(pose, quad, 1.0f, 1.0f, 1.0f, 1.0f, lightUV, overlay, true);
            }
        }
    }
}
