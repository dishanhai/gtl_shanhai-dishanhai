package com.dishanhai.gt_shanhai.client.renderer.item;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class HypercubeBakedModel implements BakedModel {

    private static final int VERTEX_SIZE = 8;
    private static final int BLACK_ABGR = 0xFF000000;
    private static final float LINE_WIDTH = 0.45F;

    private final BakedModel wrapped;

    public HypercubeBakedModel(BakedModel wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction, RandomSource random) {
        List<BakedQuad> base = wrapped.getQuads(state, direction, random);
        if (state != null || direction != null) {
            return base;
        }
        TextureAtlasSprite sprite = findSprite(base);
        if (sprite == null) {
            return base;
        }

        List<BakedQuad> quads = new ArrayList<>(18);
        float z = findFrontZ(base);

        addLine(quads, sprite, 4.0F, 4.0F, 13.0F, 4.0F, z);
        addLine(quads, sprite, 13.0F, 4.0F, 13.0F, 13.0F, z);
        addLine(quads, sprite, 13.0F, 13.0F, 4.0F, 13.0F, z);
        addLine(quads, sprite, 4.0F, 13.0F, 4.0F, 4.0F, z);

        addLine(quads, sprite, 2.0F, 2.0F, 11.0F, 2.0F, z);
        addLine(quads, sprite, 11.0F, 2.0F, 11.0F, 11.0F, z);
        addLine(quads, sprite, 11.0F, 11.0F, 2.0F, 11.0F, z);
        addLine(quads, sprite, 2.0F, 11.0F, 2.0F, 2.0F, z);

        addLine(quads, sprite, 2.0F, 2.0F, 4.0F, 4.0F, z);
        addLine(quads, sprite, 11.0F, 2.0F, 13.0F, 4.0F, z);
        addLine(quads, sprite, 11.0F, 11.0F, 13.0F, 13.0F, z);
        addLine(quads, sprite, 2.0F, 11.0F, 4.0F, 13.0F, z);

        addLine(quads, sprite, 4.0F, 4.0F, 11.0F, 11.0F, z);
        addLine(quads, sprite, 13.0F, 4.0F, 2.0F, 11.0F, z);
        return quads;
    }

    private static void addLine(List<BakedQuad> quads, TextureAtlasSprite sprite,
                                float x0, float y0, float x1, float y1, float z) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length <= 0.0001F) {
            return;
        }
        float nx = -dy / length * LINE_WIDTH;
        float ny = dx / length * LINE_WIDTH;
        quads.add(buildQuad(
                x0 + nx, y0 + ny,
                x0 - nx, y0 - ny,
                x1 - nx, y1 - ny,
                x1 + nx, y1 + ny,
                z, sprite));
    }

    private static BakedQuad buildQuad(float x0, float y0, float x1, float y1,
                                       float x2, float y2, float x3, float y3,
                                       float z, TextureAtlasSprite sprite) {
        int[] vertices = new int[VERTEX_SIZE * 4];
        putVertex(vertices, 0, x0, y0, z, sprite.getU(7), sprite.getV(7));
        putVertex(vertices, 1, x1, y1, z, sprite.getU(7), sprite.getV(9));
        putVertex(vertices, 2, x2, y2, z, sprite.getU(9), sprite.getV(9));
        putVertex(vertices, 3, x3, y3, z, sprite.getU(9), sprite.getV(7));
        return new BakedQuad(vertices, -1, Direction.SOUTH, sprite, false);
    }

    private static void putVertex(int[] vertices, int index, float x, float y, float z, float u, float v) {
        int offset = index * VERTEX_SIZE;
        vertices[offset] = Float.floatToRawIntBits(x);
        vertices[offset + 1] = Float.floatToRawIntBits(y);
        vertices[offset + 2] = Float.floatToRawIntBits(z);
        vertices[offset + 3] = BLACK_ABGR;
        vertices[offset + 4] = Float.floatToRawIntBits(u);
        vertices[offset + 5] = Float.floatToRawIntBits(v);
        vertices[offset + 6] = 0xF000F0;
        vertices[offset + 7] = 0 | (0 << 8) | (127 << 16) | 0;
    }

    private TextureAtlasSprite findSprite(List<BakedQuad> quads) {
        for (BakedQuad quad : quads) {
            if (quad.getSprite() != null) {
                return quad.getSprite();
            }
        }
        return wrapped.getParticleIcon();
    }

    private static float findFrontZ(List<BakedQuad> quads) {
        if (quads.isEmpty()) {
            return 7.5F;
        }
        int[] vertices = quads.get(0).getVertices();
        if (vertices.length < 3) {
            return 7.5F;
        }
        return Float.intBitsToFloat(vertices[2]) + 0.03F;
    }

    @Override public boolean useAmbientOcclusion() { return false; }
    @Override public boolean isGui3d() { return false; }
    @Override public boolean usesBlockLight() { return false; }
    @Override public boolean isCustomRenderer() { return false; }
    @Override public TextureAtlasSprite getParticleIcon() { return wrapped.getParticleIcon(); }
    @Override public ItemTransforms getTransforms() { return wrapped.getTransforms(); }
    @Override public ItemOverrides getOverrides() { return wrapped.getOverrides(); }
}
