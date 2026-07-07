package com.dishanhai.gt_shanhai.client.renderer.quantum;

import com.google.common.collect.Lists;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.IDynamicBakedModel;
import net.minecraftforge.client.model.QuadTransformers;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;
import net.minecraftforge.client.model.pipeline.QuadBakingVertexConsumer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class QuantumConnectedBakedModel implements IDynamicBakedModel {

    private static final int LU = 0;
    private static final int RU = 1;
    private static final int LD = 2;
    private static final int SPLIT = 3;
    private static final int RD = 4;
    private static final ModelProperty<ConnectionState> CONNECT_STATE = new ModelProperty<ConnectionState>();
    private static final EnumMap<Direction, List<Vector3f>> FACE_VERTICES = createFaceVertices();
    private static final Map<FaceCorner, List<Vector3f>> CORNER_VERTICES = createCornerVertices();
    // getModelData 的 27 次邻居偏移复用，避免临时 BlockPos 分配；chunk builder 多线程调用故 ThreadLocal。
    private static final ThreadLocal<BlockPos.MutableBlockPos> MUTABLE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private final ChunkRenderTypeSet renderTypes;
    private final TextureAtlasSprite face;
    private final TextureAtlasSprite sides;
    private final TextureAtlasSprite poweredSides;
    private Map<Direction, TextureAtlasSprite> faceAnimations;
    private RenderType faceRenderType;
    private RenderType sideRenderType;
    private boolean renderOppositeSide;
    private boolean faceEmissive;
    private boolean sideEmissive;
    private boolean faceAnimationEmissive;

    protected QuantumConnectedBakedModel(RenderType renderType, TextureAtlasSprite face, TextureAtlasSprite sides,
            TextureAtlasSprite poweredSides) {
        this(renderType, renderType, face, sides, poweredSides);
    }

    protected QuantumConnectedBakedModel(RenderType faceRenderType, RenderType sideRenderType,
            TextureAtlasSprite face, TextureAtlasSprite sides, TextureAtlasSprite poweredSides) {
        this.renderTypes = ChunkRenderTypeSet.of(faceRenderType, sideRenderType);
        this.faceRenderType = faceRenderType;
        this.sideRenderType = sideRenderType;
        this.face = face;
        this.sides = sides;
        this.poweredSides = poweredSides;
    }

    protected abstract boolean shouldConnect(Block block);

    protected abstract boolean shouldBeEmissive(BlockState state);

    protected void setFaceEmissive(boolean faceEmissive) {
        this.faceEmissive = faceEmissive;
    }

    protected void setSideEmissive(boolean sideEmissive) {
        this.sideEmissive = sideEmissive;
    }

    protected void setFaceAnimation(Map<Direction, TextureAtlasSprite> faceAnimations, boolean emissive) {
        this.faceAnimations = faceAnimations;
        this.faceAnimationEmissive = emissive;
    }

    protected void setRenderOppositeSide(boolean renderOppositeSide) {
        this.renderOppositeSide = renderOppositeSide;
    }

    @Override
    public @NotNull ModelData getModelData(@NotNull BlockAndTintGetter world, @NotNull BlockPos pos,
            @NotNull BlockState state, @NotNull ModelData modelData) {
        // ConnectionState 会随 ModelData 返回并被缓存，无法复用；但 27 次邻居偏移的 BlockPos
        // 可用 ThreadLocal 的 MutableBlockPos 复用，省掉每次 27 个临时 BlockPos 分配。
        // getModelData 会被多个 chunk builder 线程并发调用，故必须 ThreadLocal。
        ConnectionState connect = new ConnectionState();
        connect.init(pos);
        BlockPos.MutableBlockPos offset = MUTABLE_POS.get();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    offset.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                    Block block = world.getBlockState(offset)
                            .getAppearance(world, offset, Direction.NORTH, state, pos)
                            .getBlock();
                    if (shouldConnect(block)) {
                        connect.set(x, y, z);
                    }
                }
            }
        }
        return modelData.derive().with(CONNECT_STATE, connect).build();
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
            @NotNull RandomSource random, @NotNull ModelData modelData, @Nullable RenderType renderType) {
        ConnectionState connect;
        boolean powered;
        List<BakedQuad> quads;
        if (side == null) {
            return Collections.emptyList();
        }

        connect = modelData.get(CONNECT_STATE);
        if (connect == null) {
            return Collections.emptyList();
        }

        powered = state != null && shouldBeEmissive(state);
        quads = new ArrayList<BakedQuad>();
        if (renderType == null || this.renderTypes.contains(renderType)) {
            if (renderType == this.faceRenderType) {
                addFaceQuad(quads, side, connect.getFace(side), powered);
            }
            if (this.sides != null && renderType == this.sideRenderType) {
                addSideQuads(quads, connect, side, powered, false);
                if (this.renderOppositeSide) {
                    addSideQuads(quads, connect, side.getOpposite(), powered, true);
                }
            }
        }
        return quads;
    }

    @Override
    public @NotNull ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        return this.renderTypes;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random) {
        return Collections.emptyList();
    }

    @Override
    public boolean useAmbientOcclusion() {
        return false;
    }

    @Override
    public boolean isGui3d() {
        return false;
    }

    @Override
    public boolean usesBlockLight() {
        return false;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return this.sides;
    }

    @Override
    public ItemTransforms getTransforms() {
        return ItemTransforms.NO_TRANSFORMS;
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

    private void addFaceQuad(List<BakedQuad> quads, Direction side, int faceIndex, boolean powered) {
        List<Vector3f> vertices;
        Vec3i normal;
        Vector3f step;
        Vector3f c1;
        Vector3f c2;
        Vector3f c3;
        Vector3f c4;
        if (faceIndex < 0) {
            return;
        }

        vertices = FACE_VERTICES.get(side);
        normal = side.getNormal();
        step = getNormalStep(normal, 1.0f);
        c1 = sub(vertices.get(0), step);
        c2 = sub(vertices.get(1), step);
        c3 = sub(vertices.get(2), step);
        c4 = sub(vertices.get(3), step);
        addQuad(quads, side, this.face, normal, c1, c2, c3, c4, 0.0f, 0.0f, 16.0f, 16.0f);
        if (this.faceEmissive && powered) {
            QuadTransformers.settingMaxEmissivity().processInPlace(quads.get(quads.size() - 1));
        }

        if (powered && this.faceAnimations != null && this.faceAnimations.get(side) != null) {
            TextureAtlasSprite animation = this.faceAnimations.get(side);
            addQuad(quads, side, animation, normal, c1, c2, c3, c4, 0.0f, 0.0f, 16.0f, 16.0f);
            if (this.faceAnimationEmissive) {
                QuadTransformers.settingMaxEmissivity().processInPlace(quads.get(quads.size() - 1));
            }
        }
    }

    private void addSideQuads(List<BakedQuad> quads, ConnectionState connect, Direction side, boolean powered,
            boolean renderOpposite) {
        addSideQuad(quads, side, connect.getIndex(side, LU), LU, powered, renderOpposite);
        addSideQuad(quads, side, connect.getIndex(side, RU), RU, powered, renderOpposite);
        addSideQuad(quads, side, connect.getIndex(side, LD), LD, powered, renderOpposite);
        addSideQuad(quads, side, connect.getIndex(side, RD), RD, powered, renderOpposite);
    }

    private void addSideQuad(List<BakedQuad> quads, Direction side, int index, int corner, boolean powered,
            boolean renderOpposite) {
        List<Vector3f> vertices;
        TextureAtlasSprite sprite;
        Vector3f c1;
        Vector3f c2;
        Vector3f c3;
        Vector3f c4;
        float u0;
        float u1;
        float v0;
        float v1;
        if (index < 0) {
            return;
        }

        vertices = CORNER_VERTICES.get(new FaceCorner(side, corner));
        sprite = powered ? this.poweredSides : this.sides;
        c1 = renderOpposite ? vertices.get(3) : vertices.get(0);
        c2 = renderOpposite ? vertices.get(2) : vertices.get(1);
        c3 = renderOpposite ? vertices.get(1) : vertices.get(2);
        c4 = renderOpposite ? vertices.get(0) : vertices.get(3);
        if (renderOpposite) {
            Vector3f step = getNormalStep(side.getNormal(), 2.0f);
            c1 = sub(c1, step);
            c2 = sub(c2, step);
            c3 = sub(c3, step);
            c4 = sub(c4, step);
        }

        u0 = renderOpposite ? getU1(index) : getU0(index);
        u1 = renderOpposite ? getU0(index) : getU1(index);
        v0 = getV0(index);
        v1 = getV1(index);

        if (corner == LU) {
            addQuad(quads, side, sprite, side.getNormal(), c1, c2, c3, c4, u0, v0, u1, v1);
        } else if (corner == RU) {
            addQuad(quads, side, sprite, side.getNormal(), c1, c2, c3, c4, u1, v0, u0, v1);
        } else if (corner == LD) {
            addQuad(quads, side, sprite, side.getNormal(), c1, c2, c3, c4, u0, v1, u1, v0);
        } else if (corner == RD) {
            addQuad(quads, side, sprite, side.getNormal(), c1, c2, c3, c4, u1, v1, u0, v0);
        }

        if (this.sideEmissive && powered) {
            QuadTransformers.settingMaxEmissivity().processInPlace(quads.get(quads.size() - 1));
        }
    }

    private static void addQuad(List<BakedQuad> quads, Direction side, TextureAtlasSprite sprite, Vec3i normal,
            Vector3f c1, Vector3f c2, Vector3f c3, Vector3f c4,
            float u0, float v0, float u1, float v1) {
        QuadBakingVertexConsumer builder = new QuadBakingVertexConsumer(quads::add);
        builder.setSprite(sprite);
        builder.setDirection(side);
        builder.setShade(true);
        putVertex(builder, sprite, normal, c1.x(), c1.y(), c1.z(), u0, v0);
        putVertex(builder, sprite, normal, c2.x(), c2.y(), c2.z(), u0, v1);
        putVertex(builder, sprite, normal, c3.x(), c3.y(), c3.z(), u1, v1);
        putVertex(builder, sprite, normal, c4.x(), c4.y(), c4.z(), u1, v0);
    }

    private static void putVertex(QuadBakingVertexConsumer builder, TextureAtlasSprite sprite, Vec3i normal,
            float x, float y, float z, float u, float v) {
        builder.vertex(x, y, z);
        builder.color(1.0f, 1.0f, 1.0f, 1.0f);
        builder.normal(normal.getX(), normal.getY(), normal.getZ());
        builder.uv(sprite.getU(u), sprite.getV(v));
        builder.endVertex();
    }

    private static float getU0(int index) {
        if (index == RU || index == SPLIT) {
            return 8.0f;
        }
        return 0.0f;
    }

    private static float getU1(int index) {
        if (index == RU || index == SPLIT) {
            return 16.0f;
        }
        return 8.0f;
    }

    private static float getV0(int index) {
        if (index == LD || index == SPLIT) {
            return 8.0f;
        }
        return 0.0f;
    }

    private static float getV1(int index) {
        if (index == LD || index == SPLIT) {
            return 16.0f;
        }
        return 8.0f;
    }

    private static Vector3f getNormalStep(Vec3i normal, float multiplier) {
        return new Vector3f(getNormalStep(normal.getX(), multiplier), getNormalStep(normal.getY(), multiplier), getNormalStep(normal.getZ(), multiplier));
    }

    private static float getNormalStep(int step, float multiplier) {
        if (step > 0) {
            return 0.002f * multiplier;
        }
        if (step < 0) {
            return -0.002f * multiplier;
        }
        return 0.0f;
    }

    private static Vector3f sub(Vector3f value, Vector3f step) {
        return new Vector3f(value).sub(step);
    }

    private static EnumMap<Direction, List<Vector3f>> createFaceVertices() {
        EnumMap<Direction, List<Vector3f>> map = new EnumMap<Direction, List<Vector3f>>(Direction.class);
        map.put(Direction.EAST, vertices(new Vector3f(1.0f, 1.0f, 1.0f), new Vector3f(1.0f, 0.0f, 1.0f), new Vector3f(1.0f, 0.0f, 0.0f), new Vector3f(1.0f, 1.0f, 0.0f)));
        map.put(Direction.WEST, Lists.reverse(vertices(new Vector3f(0.0f, 1.0f, 1.0f), new Vector3f(0.0f, 0.0f, 1.0f), new Vector3f(0.0f, 0.0f, 0.0f), new Vector3f(0.0f, 1.0f, 0.0f))));
        map.put(Direction.UP, vertices(new Vector3f(1.0f, 1.0f, 1.0f), new Vector3f(1.0f, 1.0f, 0.0f), new Vector3f(0.0f, 1.0f, 0.0f), new Vector3f(0.0f, 1.0f, 1.0f)));
        map.put(Direction.DOWN, Lists.reverse(vertices(new Vector3f(1.0f, 0.0f, 1.0f), new Vector3f(1.0f, 0.0f, 0.0f), new Vector3f(0.0f, 0.0f, 0.0f), new Vector3f(0.0f, 0.0f, 1.0f))));
        map.put(Direction.SOUTH, vertices(new Vector3f(0.0f, 1.0f, 1.0f), new Vector3f(0.0f, 0.0f, 1.0f), new Vector3f(1.0f, 0.0f, 1.0f), new Vector3f(1.0f, 1.0f, 1.0f)));
        map.put(Direction.NORTH, Lists.reverse(vertices(new Vector3f(0.0f, 1.0f, 0.0f), new Vector3f(0.0f, 0.0f, 0.0f), new Vector3f(1.0f, 0.0f, 0.0f), new Vector3f(1.0f, 1.0f, 0.0f))));
        return map;
    }

    private static Map<FaceCorner, List<Vector3f>> createCornerVertices() {
        Map<FaceCorner, List<Vector3f>> map = new HashMap<FaceCorner, List<Vector3f>>();
        putCorner(map, Direction.EAST, LU, 1.0f, 1.0f, 1.0f, 1.0f, 0.5f, 1.0f, 1.0f, 0.5f, 0.5f, 1.0f, 1.0f, 0.5f);
        putCorner(map, Direction.EAST, RU, 1.0f, 1.0f, 0.5f, 1.0f, 0.5f, 0.5f, 1.0f, 0.5f, 0.0f, 1.0f, 1.0f, 0.0f);
        putCorner(map, Direction.EAST, LD, 1.0f, 0.5f, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.5f, 1.0f, 0.5f, 0.5f);
        putCorner(map, Direction.EAST, RD, 1.0f, 0.5f, 0.5f, 1.0f, 0.0f, 0.5f, 1.0f, 0.0f, 0.0f, 1.0f, 0.5f, 0.0f);
        putCorner(map, Direction.WEST, LU, 0.0f, 1.0f, 0.0f, 0.0f, 0.5f, 0.0f, 0.0f, 0.5f, 0.5f, 0.0f, 1.0f, 0.5f);
        putCorner(map, Direction.WEST, RU, 0.0f, 1.0f, 0.5f, 0.0f, 0.5f, 0.5f, 0.0f, 0.5f, 1.0f, 0.0f, 1.0f, 1.0f);
        putCorner(map, Direction.WEST, LD, 0.0f, 0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.5f, 0.0f, 0.5f, 0.5f);
        putCorner(map, Direction.WEST, RD, 0.0f, 0.5f, 0.5f, 0.0f, 0.0f, 0.5f, 0.0f, 0.0f, 1.0f, 0.0f, 0.5f, 1.0f);
        putCorner(map, Direction.SOUTH, LU, 0.0f, 1.0f, 1.0f, 0.0f, 0.5f, 1.0f, 0.5f, 0.5f, 1.0f, 0.5f, 1.0f, 1.0f);
        putCorner(map, Direction.SOUTH, RU, 0.5f, 1.0f, 1.0f, 0.5f, 0.5f, 1.0f, 1.0f, 0.5f, 1.0f, 1.0f, 1.0f, 1.0f);
        putCorner(map, Direction.SOUTH, LD, 0.0f, 0.5f, 1.0f, 0.0f, 0.0f, 1.0f, 0.5f, 0.0f, 1.0f, 0.5f, 0.5f, 1.0f);
        putCorner(map, Direction.SOUTH, RD, 0.5f, 0.5f, 1.0f, 0.5f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.5f, 1.0f);
        putCorner(map, Direction.NORTH, LU, 1.0f, 1.0f, 0.0f, 1.0f, 0.5f, 0.0f, 0.5f, 0.5f, 0.0f, 0.5f, 1.0f, 0.0f);
        putCorner(map, Direction.NORTH, RU, 0.5f, 1.0f, 0.0f, 0.5f, 0.5f, 0.0f, 0.0f, 0.5f, 0.0f, 0.0f, 1.0f, 0.0f);
        putCorner(map, Direction.NORTH, LD, 1.0f, 0.5f, 0.0f, 1.0f, 0.0f, 0.0f, 0.5f, 0.0f, 0.0f, 0.5f, 0.5f, 0.0f);
        putCorner(map, Direction.NORTH, RD, 0.5f, 0.5f, 0.0f, 0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.5f, 0.0f);
        putCorner(map, Direction.UP, LU, 0.0f, 1.0f, 1.0f, 0.5f, 1.0f, 1.0f, 0.5f, 1.0f, 0.5f, 0.0f, 1.0f, 0.5f);
        putCorner(map, Direction.UP, RU, 0.0f, 1.0f, 0.5f, 0.5f, 1.0f, 0.5f, 0.5f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        putCorner(map, Direction.UP, LD, 0.5f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.5f, 0.5f, 1.0f, 0.5f);
        putCorner(map, Direction.UP, RD, 0.5f, 1.0f, 0.5f, 1.0f, 1.0f, 0.5f, 1.0f, 1.0f, 0.0f, 0.5f, 1.0f, 0.0f);
        putCorner(map, Direction.DOWN, LU, 1.0f, 0.0f, 1.0f, 0.5f, 0.0f, 1.0f, 0.5f, 0.0f, 0.5f, 1.0f, 0.0f, 0.5f);
        putCorner(map, Direction.DOWN, RU, 1.0f, 0.0f, 0.5f, 0.5f, 0.0f, 0.5f, 0.5f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f);
        putCorner(map, Direction.DOWN, LD, 0.5f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.5f, 0.5f, 0.0f, 0.5f);
        putCorner(map, Direction.DOWN, RD, 0.5f, 0.0f, 0.5f, 0.0f, 0.0f, 0.5f, 0.0f, 0.0f, 0.0f, 0.5f, 0.0f, 0.0f);
        return map;
    }

    private static void putCorner(Map<FaceCorner, List<Vector3f>> map, Direction side, int corner,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            float x4, float y4, float z4) {
        map.put(new FaceCorner(side, corner), vertices(new Vector3f(x1, y1, z1), new Vector3f(x2, y2, z2), new Vector3f(x3, y3, z3), new Vector3f(x4, y4, z4)));
    }

    private static List<Vector3f> vertices(Vector3f a, Vector3f b, Vector3f c, Vector3f d) {
        List<Vector3f> list = new ArrayList<Vector3f>(4);
        list.add(a);
        list.add(b);
        list.add(c);
        list.add(d);
        return list;
    }

    private static final class FaceCorner {
        private final Direction face;
        private final int corner;

        private FaceCorner(Direction face, int corner) {
            this.face = face;
            this.corner = corner;
        }

        @Override
        public boolean equals(Object obj) {
            FaceCorner other;
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof FaceCorner)) {
                return false;
            }
            other = (FaceCorner) obj;
            return this.face == other.face && this.corner == other.corner;
        }

        @Override
        public int hashCode() {
            return this.face.hashCode() * 31 + this.corner;
        }
    }

    private static final class ConnectionState {
        private final boolean[][][] connects = new boolean[3][3][3];
        private int face;

        private void init(BlockPos pos) {
            this.face = Math.abs((pos.getX() ^ pos.getY() ^ pos.getZ()) % 3);
        }

        private void set(int x, int y, int z) {
            this.connects[x + 1][y + 1][z + 1] = true;
        }

        private int getFace(Direction side) {
            if (blocked(side)) {
                return -1;
            }
            return this.face;
        }

        private int getIndex(Direction side, int corner) {
            if (blocked(side)) {
                return -1;
            }
            if (side == Direction.WEST || side == Direction.EAST) {
                return getIndexX(side, corner);
            }
            if (side == Direction.DOWN || side == Direction.UP) {
                return getIndexY(side, corner);
            }
            return getIndexZ(side, corner);
        }

        private boolean blocked(Direction side) {
            Vec3i pos = side.getNormal().offset(1, 1, 1);
            return this.connects[pos.getX()][pos.getY()][pos.getZ()];
        }

        private int getIndexX(Direction side, int corner) {
            int x = side.getStepX();
            if (corner == LU) {
                return getIndex(this.connects[1][1][1 + x], this.connects[1][2][1], this.connects[1][2][1 + x]);
            }
            if (corner == RU) {
                return getIndex(this.connects[1][1][1 - x], this.connects[1][2][1], this.connects[1][2][1 - x]);
            }
            if (corner == LD) {
                return getIndex(this.connects[1][1][1 + x], this.connects[1][0][1], this.connects[1][0][1 + x]);
            }
            if (corner == RD) {
                return getIndex(this.connects[1][1][1 - x], this.connects[1][0][1], this.connects[1][0][1 - x]);
            }
            return -1;
        }

        private int getIndexY(Direction side, int corner) {
            int y = side.getStepY();
            if (corner == LU) {
                return getIndex(this.connects[1][1][2], this.connects[1 - y][1][1], this.connects[1 - y][1][2]);
            }
            if (corner == RU) {
                return getIndex(this.connects[1][1][0], this.connects[1 - y][1][1], this.connects[1 - y][1][0]);
            }
            if (corner == LD) {
                return getIndex(this.connects[1][1][2], this.connects[1 + y][1][1], this.connects[1 + y][1][2]);
            }
            if (corner == RD) {
                return getIndex(this.connects[1][1][0], this.connects[1 + y][1][1], this.connects[1 + y][1][0]);
            }
            return -1;
        }

        private int getIndexZ(Direction side, int corner) {
            int z = side.getStepZ();
            if (corner == LU) {
                return getIndex(this.connects[1 - z][1][1], this.connects[1][2][1], this.connects[1 - z][2][1]);
            }
            if (corner == RU) {
                return getIndex(this.connects[1 + z][1][1], this.connects[1][2][1], this.connects[1 + z][2][1]);
            }
            if (corner == LD) {
                return getIndex(this.connects[1 - z][1][1], this.connects[1][0][1], this.connects[1 - z][0][1]);
            }
            if (corner == RD) {
                return getIndex(this.connects[1 + z][1][1], this.connects[1][0][1], this.connects[1 + z][0][1]);
            }
            return -1;
        }

        private int getIndex(boolean a, boolean b, boolean c) {
            if (!a && !b) {
                return LU;
            }
            if (a && b && !c) {
                return RU;
            }
            if (!a && b) {
                return LD;
            }
            if (a && !b) {
                return SPLIT;
            }
            return -1;
        }
    }
}
