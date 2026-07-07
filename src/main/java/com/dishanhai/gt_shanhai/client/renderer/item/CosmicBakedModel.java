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

/**
 * 宇宙边框包装模型。
 * getQuads() 返回原始物品 quad + 四边窄边框（带脉冲颜色特效）。
 */
public class CosmicBakedModel implements BakedModel {

    private static final int VERTEX_SIZE = 8;

    private final BakedModel wrapped;
    private final long seed;

    public CosmicBakedModel(BakedModel wrapped, long seed) {
        this.wrapped = wrapped;
        this.seed = seed;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state,
                                    @Nullable Direction direction,
                                    RandomSource random) {
        List<BakedQuad> base = wrapped.getQuads(state, direction, random);
        if (base.isEmpty()) return base;

        if (state != null || direction != null) return base;

        TextureAtlasSprite sprite = findSprite(base);
        if (sprite == null) return base;

        long time = System.currentTimeMillis() + seed;
        List<BakedQuad> out = new ArrayList<>(base.size() + 4);
        out.addAll(base);

        // 从第一个 base quad 提取 Z 坐标和尺寸
        BakedQuad firstQuad = base.get(0);
        float z = Float.intBitsToFloat(firstQuad.getVertices()[2]);

        // 四条闪烁边框 (ABGR: 蓝紫→白脉冲)
        double pulse = Math.sin(time / 600.0) * 0.5 + 0.5;
        pulse = 0.15 + pulse * 0.85;

        int alpha = (int)(200 * pulse);
        int red   = (int)(100 + 80 * Math.sin(time / 2500.0));
        int green = (int)(100 + 80 * Math.sin(time / 3100.0 + 2));
        int blue  = (int)(180 + 75 * Math.sin(time / 3700.0 + 4));
        int color = (alpha << 24) | (blue << 16) | (green << 8) | red;

        float bw = 0.4f; // 边框宽度
        float[][] strips = {
            {-8f - bw, -8f, 8f + bw, -8f},
            {-8f - bw, 8f, 8f + bw, 8f},
            {-8f - bw, -8f, -8f, 8f},
            {8f, -8f, 8f + bw, 8f},
        };

        for (float[] s : strips) {
            out.add(buildStrip(s[0], s[1], s[2], s[3], z, color, sprite));
        }

        return out;
    }

    // 从 quads 里找可用 sprite
    private TextureAtlasSprite findSprite(List<BakedQuad> quads) {
        for (BakedQuad q : quads) {
            TextureAtlasSprite sp = q.getSprite();
            if (sp != null) return sp;
        }
        return wrapped.getParticleIcon();
    }

    // ===== 边框 strip 创建 =====

    private static BakedQuad buildStrip(float x0, float y0, float x1, float y1,
                                        float z, int color, TextureAtlasSprite sprite) {
        int[] v = new int[VERTEX_SIZE * 4];
        putPos(v, 0, x0, y0, z); putColor(v, 0, color);
        putUV(v, 0, sprite.getU(4), sprite.getV(4)); putLight(v, 0); putNormal(v, 0);
        putPos(v, 1, x0, y1, z); putColor(v, 1, color);
        putUV(v, 1, sprite.getU(4), sprite.getV(12)); putLight(v, 1); putNormal(v, 1);
        putPos(v, 2, x1, y1, z); putColor(v, 2, color);
        putUV(v, 2, sprite.getU(12), sprite.getV(12)); putLight(v, 2); putNormal(v, 2);
        putPos(v, 3, x1, y0, z); putColor(v, 3, color);
        putUV(v, 3, sprite.getU(12), sprite.getV(4)); putLight(v, 3); putNormal(v, 3);
        return new BakedQuad(v, -1, Direction.SOUTH, sprite, false);
    }

    private static void putPos(int[] v, int idx, float x, float y, float z) {
        int o = idx * VERTEX_SIZE;
        v[o]     = Float.floatToRawIntBits(x);
        v[o + 1] = Float.floatToRawIntBits(y);
        v[o + 2] = Float.floatToRawIntBits(z);
    }
    private static void putColor(int[] v, int idx, int abgr) { v[idx * VERTEX_SIZE + 3] = abgr; }
    private static void putUV(int[] v, int idx, float u, float vt) {
        int o = idx * VERTEX_SIZE + 4;
        v[o]     = Float.floatToRawIntBits(u);
        v[o + 1] = Float.floatToRawIntBits(vt);
    }
    private static void putLight(int[] v, int idx) { v[idx * VERTEX_SIZE + 6] = 0xF000F0; }
    private static void putNormal(int[] v, int idx) { v[idx * VERTEX_SIZE + 7] = 0 | (0 << 8) | (127 << 16) | 0; }

    // ===== BakedModel 接口 =====

    @Override public boolean useAmbientOcclusion() { return false; }
    @Override public boolean isGui3d()              { return false; }
    @Override public boolean usesBlockLight()       { return false; }
    @Override public boolean isCustomRenderer()     { return false; }
    @Override public TextureAtlasSprite getParticleIcon() { return wrapped.getParticleIcon(); }
    @Override public ItemTransforms getTransforms() { return wrapped.getTransforms(); }
    @Override public ItemOverrides getOverrides()   { return wrapped.getOverrides(); }
}
