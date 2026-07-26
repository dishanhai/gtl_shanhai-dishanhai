package com.dishanhai.gt_shanhai.client.renderer.item;

import com.dishanhai.gt_shanhai.api.HaloSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Avaritia 风格光环包装模型：在原物品 quad 之前插入底暈/冕环/星芒三类特效面。
 * <p>
 * 关键事实（已从 Forge 1.20.1 反编译源确认）：
 * <ul>
 *   <li>Forge 补丁的 {@code ItemRenderer.renderQuadList} 走
 *       {@code putBulkData(..., readExistingColor=true)}，烘焙顶点色 RGB+Alpha 均生效
 *       （原版 vanilla 才忽略顶点色）——动画颜色直接写顶点即可，无需 tintIndex。</li>
 *   <li>BakedQuad 位置为 0..1 方块空间（FaceBakery 烘焙除以 16），不是 0..16。
 *       这里对被包装模型实测边界，自适应中心/尺寸/深度。</li>
 *   <li>烘焙 lightmap 与传入光照取 max（applyBakedLighting），写 0xF000F0 即全亮。</li>
 *   <li>物品渲染类型为半透明系（translucentCull），贴图 alpha 有效；黑色光环
 *       依赖普通 alpha 混合而非加法混合，因此可行。</li>
 * </ul>
 * 每帧重建特效 quad 实现旋转/脉冲/彩虹动画；每物品仅 ≤6 个特效面，开销可忽略。
 * 特效quad 同时生成正反两个绕序，保证 GUI（y 翻转）与地面实体两种上下文各有一面通过背面剔除。
 */
public class HaloBakedModel implements BakedModel {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("gt_shanhai/halo");
    /** 全局一次性：首次成功解析贴图时打 INFO，便于在日志里确认渲染链路已就绪 */
    private static final java.util.concurrent.atomic.AtomicBoolean LOGGED_READY =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static final int VERTEX_SIZE = 8;
    private static final int FULL_BRIGHT = 0xF000F0;

    private static final ResourceLocation SPRITE_HALO = new ResourceLocation("gt_shanhai", "render_fx/halo");
    private static final ResourceLocation SPRITE_RING = new ResourceLocation("gt_shanhai", "render_fx/halo_ring");
    private static final ResourceLocation SPRITE_RAYS = new ResourceLocation("gt_shanhai", "render_fx/halo_rays");

    private final BakedModel wrapped;
    private final HaloSettings settings;
    /** 相位偏移，避免整排物品同步呼吸 */
    private final long phase;

    private TextureAtlasSprite haloSprite;
    private TextureAtlasSprite ringSprite;
    private TextureAtlasSprite raysSprite;
    private boolean spritesResolved;

    public HaloBakedModel(BakedModel wrapped, HaloSettings settings, long seed) {
        this.wrapped = wrapped;
        this.settings = settings;
        this.phase = (seed % 100000L + 100000L) % 100000L;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state,
                                    @Nullable Direction direction,
                                    RandomSource random) {
        List<BakedQuad> base = wrapped.getQuads(state, direction, random);
        if (state != null || direction != null || base.isEmpty()) return base;

        resolveSprites();
        if (haloSprite == null && ringSprite == null && raysSprite == null) return base;

        // 实测被包装模型边界，自适应任意坐标空间
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (BakedQuad quad : base) {
            int[] v = quad.getVertices();
            for (int i = 0; i + 2 < v.length; i += VERTEX_SIZE) {
                float x = Float.intBitsToFloat(v[i]);
                float y = Float.intBitsToFloat(v[i + 1]);
                float z = Float.intBitsToFloat(v[i + 2]);
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                if (z < minZ) minZ = z;
                if (z > maxZ) maxZ = z;
            }
        }
        float size = Math.max(maxX - minX, maxY - minY);
        if (!(size > 0.0001F)) return base;
        float cx = (minX + maxX) * 0.5F;
        float cy = (minY + maxY) * 0.5F;
        float eps = size * 0.03F;

        long t = System.currentTimeMillis() + phase;
        // ⚠ 所有相位必须先对周期取模再乘 2π：epoch 毫秒 ~1.8e12，直接乘系数后
        //   float ulp 达数十弧度、每帧增量远小于 ulp，旋转/呼吸会完全冻结（sin 为 2π 周期，取模后跨界无缝）
        double pulsePhase = phaseOf(t, settings.pulsePeriodMs);
        float scalePulse = settings.pulse ? 1.0F + 0.05F * (float) Math.sin(pulsePhase) : 1.0F;
        float alphaPulse = settings.pulse ? 0.82F + 0.18F * (float) Math.sin(pulsePhase + 1.3) : 1.0F;

        int core = settings.coreColor;
        int rim = settings.rimColor;
        if (settings.hueCore || settings.hueRim) {
            float hue = (t % 6000L) / 6000.0F;
            if (settings.hueCore) {
                core = (core & 0xFF000000) | (Mth.hsvToRgb(hue, 0.85F, 1.0F) & 0xFFFFFF);
            }
            if (settings.hueRim) {
                rim = (rim & 0xFF000000) | (Mth.hsvToRgb((hue + 0.18F) % 1.0F, 0.75F, 1.0F) & 0xFFFFFF);
            }
        }

        float half = size * 0.5F * settings.scale;
        List<BakedQuad> out = new ArrayList<>(base.size() + 6);

        if ((settings.style & HaloSettings.STYLE_HALO) != 0 && haloSprite != null) {
            addEffectQuads(out, haloSprite, cx, cy, minZ - 3 * eps, maxZ + 3 * eps,
                    half * scalePulse, 0.0F, withAlpha(core, alphaPulse));
        }
        if ((settings.style & HaloSettings.STYLE_RAYS) != 0 && raysSprite != null) {
            float rot = 0.0F;
            if (settings.rotateSpeed != 0.0F) {
                double periodMs = 1000.0 / Math.abs(settings.rotateSpeed);
                long p = Math.max(1L, (long) periodMs);
                rot = (float) (2.0 * Math.PI * ((t % p) / periodMs) * Math.signum(settings.rotateSpeed));
            }
            addEffectQuads(out, raysSprite, cx, cy, minZ - 2 * eps, maxZ + 2 * eps,
                    half * 1.05F * scalePulse, rot, withAlpha(rim, alphaPulse));
        }
        if ((settings.style & HaloSettings.STYLE_RING) != 0 && ringSprite != null) {
            // 冕环用错频闪烁，与底暈呼吸相位脱开，模拟日冕的不安定感
            float flicker = settings.pulse
                    ? 0.78F + 0.22F * (float) Math.sin(phaseOf(t, (int) (settings.pulsePeriodMs / 1.64)) + 2.1)
                    : 1.0F;
            addEffectQuads(out, ringSprite, cx, cy, minZ - eps, maxZ + eps,
                    half * 0.92F, 0.0F, withAlpha(rim, flicker));
        }

        out.addAll(base);
        return out;
    }

    /**
     * ⚠ 只在拿到至少一张有效贴图时才锁存缓存。
     * 资源重载期间（atlas 上传前）可能有模组提前调 getQuads——此时 getSprite 只会
     * 返回 missing sprite；若在这里无条件置 spritesResolved=true，光环会在整局游戏里
     * 静默隐形（首次部署时实际发生过）。失败就下帧重试，代价仅是几次 map 查询。
     */
    private void resolveSprites() {
        if (spritesResolved) return;
        try {
            TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
            haloSprite = validOrNull(atlas.getSprite(SPRITE_HALO));
            ringSprite = validOrNull(atlas.getSprite(SPRITE_RING));
            raysSprite = validOrNull(atlas.getSprite(SPRITE_RAYS));
        } catch (Exception e) {
            haloSprite = ringSprite = raysSprite = null;
        }
        if (haloSprite != null || ringSprite != null || raysSprite != null) {
            spritesResolved = true;
            if (LOGGED_READY.compareAndSet(false, true)) {
                LOGGER.info("[Halo] 特效贴图解析成功 halo={} ring={} rays={}",
                        haloSprite != null, ringSprite != null, raysSprite != null);
            }
        }
    }

    private static TextureAtlasSprite validOrNull(TextureAtlasSprite sprite) {
        if (sprite == null) return null;
        return MissingTextureAtlasSprite.getLocation().equals(sprite.contents().name()) ? null : sprite;
    }

    /** 相位 = 2π·(t mod 周期)/周期；先取模再乘 2π，避免 epoch 级大数吃掉每帧增量 */
    private static double phaseOf(long t, int periodMs) {
        long p = Math.max(1, periodMs);
        return 2.0 * Math.PI * (t % p) / (double) p;
    }

    private static int withAlpha(int argb, float alphaMul) {
        int a = (int) (((argb >>> 24) & 0xFF) * Mth.clamp(alphaMul, 0.0F, 1.0F));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /**
     * 生成一对正反绕序的特效面（rot 弧度绕中心旋转，贴图随面旋转）。
     * 正绕序面放在物品背面侧（zFront = minZ 后方），反绕序面放在物品正面侧
     * （zBack = maxZ 前方）——各自只在「光环位于物品之后」的视角下通过背面剔除，
     * 否则地面掉落物旋转到背面朝向镜头的半圈里，光环会盖住物品本体。
     */
    private static void addEffectQuads(List<BakedQuad> out, TextureAtlasSprite sprite,
                                       float cx, float cy, float zFront, float zBack,
                                       float half, float rot, int argb) {
        if (((argb >>> 24) & 0xFF) == 0) return;
        float cos = (float) Math.cos(rot), sin = (float) Math.sin(rot);
        // 四角：左下、左上、右上、右下（旋转前）
        float[][] corners = {{-half, -half}, {-half, half}, {half, half}, {half, -half}};
        float[] xs = new float[4], ys = new float[4];
        for (int i = 0; i < 4; i++) {
            xs[i] = cx + corners[i][0] * cos - corners[i][1] * sin;
            ys[i] = cy + corners[i][0] * sin + corners[i][1] * cos;
        }
        float u0 = sprite.getU0(), u1 = sprite.getU1();
        float v0 = sprite.getV0(), v1 = sprite.getV1();
        float[] us = {u0, u0, u1, u1};
        float[] vs = {v1, v0, v0, v1};

        int abgr = toAbgr(argb);
        int[] front = new int[VERTEX_SIZE * 4];
        int[] back = new int[VERTEX_SIZE * 4];
        for (int i = 0; i < 4; i++) {
            putVertex(front, i, xs[i], ys[i], zFront, us[i], vs[i], abgr, 127);
            int j = 3 - i;
            putVertex(back, i, xs[j], ys[j], zBack, us[j], vs[j], abgr, -127);
        }
        out.add(new BakedQuad(front, -1, Direction.SOUTH, sprite, false));
        out.add(new BakedQuad(back, -1, Direction.NORTH, sprite, false));
    }

    private static int toAbgr(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static void putVertex(int[] v, int idx, float x, float y, float z,
                                  float u, float tv, int abgr, int normalZ) {
        int o = idx * VERTEX_SIZE;
        v[o] = Float.floatToRawIntBits(x);
        v[o + 1] = Float.floatToRawIntBits(y);
        v[o + 2] = Float.floatToRawIntBits(z);
        v[o + 3] = abgr;
        v[o + 4] = Float.floatToRawIntBits(u);
        v[o + 5] = Float.floatToRawIntBits(tv);
        v[o + 6] = FULL_BRIGHT;
        v[o + 7] = (normalZ & 0xFF) << 16;
    }

    // ===== 委托 BakedModel / Forge 扩展 =====

    @Override public boolean useAmbientOcclusion() { return wrapped.useAmbientOcclusion(); }
    @Override public boolean isGui3d() { return wrapped.isGui3d(); }
    @Override public boolean usesBlockLight() { return wrapped.usesBlockLight(); }
    @Override public boolean isCustomRenderer() { return wrapped.isCustomRenderer(); }
    @Override public TextureAtlasSprite getParticleIcon() { return wrapped.getParticleIcon(); }
    @Override public ItemTransforms getTransforms() { return wrapped.getTransforms(); }
    @Override public ItemOverrides getOverrides() { return wrapped.getOverrides(); }

    /** 渲染类型沿用被包装模型（半透明系，贴图与顶点 alpha 均有效）；
     *  getRenderPasses 保持默认（返回 this），否则光环 quad 会被绕过 */
    @Override
    public List<RenderType> getRenderTypes(ItemStack itemStack, boolean fabulous) {
        return wrapped.getRenderTypes(itemStack, fabulous);
    }
}
