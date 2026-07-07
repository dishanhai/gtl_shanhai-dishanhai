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
 * 原始终焉引擎环形轨道顶点缓冲
 * 与终焉矩阵相同的环形状，但使用蒸汽时代方块映射
 */
public class PrimordialOmegaEngineRingBuffer {

    private static VertexBuffer[] ringBuffers;
    private static boolean ringBuildAttempted;

    public static VertexBuffer[] getRingBuffers() {
        if (ringBuffers == null && !ringBuildAttempted) {
            ringBuildAttempted = true;
            ringBuffers = buildAllBuffers();
        }
        return ringBuffers;
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
