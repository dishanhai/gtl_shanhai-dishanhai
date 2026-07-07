package com.dishanhai.gt_shanhai.client.renderer.machine;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** GTNL 永恒格雷工坊 extra.mb 结构环顶点缓冲。 */
public final class EternalGregTechWorkshopRingBuffer {

    private static final ResourceLocation EXTRA_PATTERN = new ResourceLocation(
            "gt_shanhai", "multiblock/eternal_gregtech_workshop/extra.mb");

    private static VertexBuffer ringBuffer;
    private static boolean buildAttempted;

    private static final Map<Character, Block> BLOCK_MAPPER = createBlockMapper();

    public static VertexBuffer getRingBuffer() {
        if (ringBuffer == null && !buildAttempted) {
            buildAttempted = true;
            ringBuffer = buildBuffer();
        }
        return ringBuffer;
    }

    private static VertexBuffer buildBuffer() {
        try {
            String[][] pattern = loadPattern();
            if (pattern == null || pattern.length == 0) {
                return null;
            }
            return buildRingBuffer(pattern);
        } catch (Exception ignored) {
            buildAttempted = false;
            return null;
        }
    }

    private static String[][] loadPattern() throws Exception {
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(EXTRA_PATTERN);
        if (resource.isEmpty()) {
            return null;
        }

        List<String[]> layers = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.get().open(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                layers.add(line.split(",", -1));
            }
        }
        return layers.toArray(new String[0][]);
    }

    private static Map<Character, Block> createBlockMapper() {
        Map<Character, Block> map = new HashMap<>();
        map.put('C', getBlock("gtlcore:dimension_injection_casing"));
        map.put('E', getBlock("gtladditions:extreme_density_casing"));
        map.put('H', getBlock("gtlcore:dimension_connection_casing"));
        map.put('L', getBlock("dishanhai:omni_purpose_infinity_fused_glass"));
        map.put('P', getBlock("dishanhai:reinforced_temporal_structure_casing"));
        map.put('Q', getBlock("gtladditions:central_graviton_flow_regulator"));
        map.put('T', getBlock("gtladditions:gravity_stabilization_casing"));
        map.put('V', getBlock("dishanhai:gallifreyan_time_dilation_field_generator"));
        map.put('W', getBlock("dishanhai:reinforced_spatial_structure_casing"));
        return map;
    }

    private static Block getBlock(String id) {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
        return block != null ? block : Blocks.AIR;
    }

    private static VertexBuffer buildRingBuffer(String[][] pattern) {
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);

        PoseStack poseStack = new PoseStack();
        RandomSource random = RandomSource.create();

        double centerX = pattern.length / 2.0;
        double centerY = pattern[0].length / 2.0;
        double centerZ = pattern[0][0].length() / 2.0;

        for (int x = 0; x < pattern.length; x++) {
            String[] layer = pattern[x];
            for (int y = 0; y < layer.length; y++) {
                String row = layer[y];
                for (int z = 0; z < row.length(); z++) {
                    char ch = row.charAt(z);
                    if (ch == ' ') {
                        continue;
                    }

                    Block block = BLOCK_MAPPER.get(ch);
                    if (block == null || block == Blocks.AIR) {
                        continue;
                    }

                    List<Direction> visibleFaces = getVisibleFaces(pattern, x, y, z);
                    if (visibleFaces.isEmpty()) {
                        continue;
                    }

                    BlockState state = block.defaultBlockState();
                    BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
                    BakedModel model = dispatcher.getBlockModel(state);
                    if (model == null) {
                        continue;
                    }

                    poseStack.pushPose();
                    poseStack.translate(x - centerX, y - centerY, z - centerZ);
                    int lightUV = LightTexture.pack(
                            block.getLightEmission(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO), 15);
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

    private static List<Direction> getVisibleFaces(String[][] pattern, int x, int y, int z) {
        List<Direction> faces = new ArrayList<>();
        char current = pattern[x][y].charAt(z);
        checkFace(pattern, x, y, z, -1, 0, 0, current, Direction.WEST, faces);
        checkFace(pattern, x, y, z, 1, 0, 0, current, Direction.EAST, faces);
        checkFace(pattern, x, y, z, 0, -1, 0, current, Direction.DOWN, faces);
        checkFace(pattern, x, y, z, 0, 1, 0, current, Direction.UP, faces);
        checkFace(pattern, x, y, z, 0, 0, -1, current, Direction.NORTH, faces);
        checkFace(pattern, x, y, z, 0, 0, 1, current, Direction.SOUTH, faces);
        return faces;
    }

    private static void checkFace(String[][] pattern, int x, int y, int z,
                                  int dx, int dy, int dz, char current,
                                  Direction direction, List<Direction> faces) {
        int nx = x + dx;
        int ny = y + dy;
        int nz = z + dz;

        if (nx < 0 || nx >= pattern.length ||
                ny < 0 || ny >= pattern[nx].length ||
                nz < 0 || nz >= pattern[nx][ny].length()) {
            faces.add(direction);
            return;
        }

        char neighbor = pattern[nx][ny].charAt(nz);
        if (!shouldCullFace(current, neighbor)) {
            faces.add(direction);
        }
    }

    private static boolean shouldCullFace(char current, char neighbor) {
        if (neighbor == ' ' || current != neighbor) {
            return false;
        }

        Block neighborBlock = BLOCK_MAPPER.get(neighbor);
        if (neighborBlock == null || neighborBlock == Blocks.AIR) {
            return false;
        }

        BlockState state = neighborBlock.defaultBlockState();
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        return state.isSolidRender(level, BlockPos.ZERO);
    }

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

        for (Direction direction : directions) {
            random.setSeed(42L);
            quads = model.getQuads(state, direction, random, modelData, null);
            for (BakedQuad quad : quads) {
                builder.putBulkData(pose, quad, 1.0f, 1.0f, 1.0f, 1.0f, lightUV, overlay, true);
            }
        }
    }

    private EternalGregTechWorkshopRingBuffer() {}
}
