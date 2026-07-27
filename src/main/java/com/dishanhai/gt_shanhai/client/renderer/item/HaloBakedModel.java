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
import net.minecraftforge.client.model.IDynamicBakedModel;
import net.minecraftforge.client.model.data.ModelData;
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
 * 另支持不稳定抖动（逐帧烘进 quad 顶点）与 RGB 色差残影（本体主面复制错位），见下方分节。
 */
public class HaloBakedModel implements IDynamicBakedModel {

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

    /**
     * 动态模型入口（IDynamicBakedModel）。实现该接口的关键作用：JEI 的
     * ForgeLimitedQuadItemModel.wrap 对 IDynamicBakedModel 直接跳过包装（免 quad 缓存、
     * 免 SOUTH 单面过滤），JEI 批渲染因此每帧走到这里——光环动画/抖动/色差在 JEI 列表全部活动。
     * 抖动直接烘进 quad 顶点而非注入 applyTransform：下游包装壳的 applyTransform
     * 转发链字节码虽完整但整合包实测冻结，"每帧重建 quad"才是已验证的可靠动画通道。
     */
    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state,
                                    @Nullable Direction direction,
                                    RandomSource random,
                                    ModelData modelData,
                                    @Nullable RenderType renderType) {
        List<BakedQuad> base = wrapped.getQuads(state, direction, random, modelData, renderType);
        if (state != null || direction != null || base.isEmpty()) return base;

        boolean wantHalo = settings.style != 0;
        if (wantHalo) resolveSprites();
        boolean haveSprite = haloSprite != null || ringSprite != null || raysSprite != null;
        boolean wantChroma = settings.chromaAmp > 0;
        boolean wantShake = settings.shakeMode != HaloSettings.SHAKE_NONE && settings.shakeAmp > 0;
        if (!(wantHalo && haveSprite) && !wantChroma && !wantShake) return base;

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
        // 抖动向量烘进本帧全部 quad；glitch 失稳尖峰同帧联动光环胀大，"整体炸开"
        float jx = 0.0F, jy = 0.0F, roll = 0.0F, unstable = 1.0F;
        if (wantShake) {
            float[] j = computeJitter(t);
            jx = j[0] * size;
            jy = j[1] * size;
            roll = j[2];
            unstable = j[3];
        }
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
            addEffectQuads(out, haloSprite, cx + jx, cy + jy, minZ - 3 * eps, maxZ + 3 * eps,
                    half * scalePulse * unstable, 0.0F, withAlpha(core, alphaPulse));
        }
        if ((settings.style & HaloSettings.STYLE_RAYS) != 0 && raysSprite != null) {
            float rot = 0.0F;
            if (settings.rotateSpeed != 0.0F) {
                double periodMs = 1000.0 / Math.abs(settings.rotateSpeed);
                long p = Math.max(1L, (long) periodMs);
                rot = (float) (2.0 * Math.PI * ((t % p) / periodMs) * Math.signum(settings.rotateSpeed));
            }
            addEffectQuads(out, raysSprite, cx + jx, cy + jy, minZ - 2 * eps, maxZ + 2 * eps,
                    half * 1.05F * scalePulse * unstable, rot, withAlpha(rim, alphaPulse));
        }
        if ((settings.style & HaloSettings.STYLE_RING) != 0 && ringSprite != null) {
            // 冕环用错频闪烁，与底暈呼吸相位脱开，模拟日冕的不安定感
            float flicker = settings.pulse
                    ? 0.78F + 0.22F * (float) Math.sin(phaseOf(t, (int) (settings.pulsePeriodMs / 1.64)) + 2.1)
                    : 1.0F;
            addEffectQuads(out, ringSprite, cx + jx, cy + jy, minZ - eps, maxZ + eps,
                    half * 0.92F, 0.0F, withAlpha(rim, flicker));
        }

        // 色差残影在本体之前提交：重叠区被后绘的本体覆盖，只在轮廓边缘露出红/青错位描边
        if (wantChroma) addChromaGhosts(out, base, t, size, minZ, maxZ, jx, jy);
        if (wantShake) {
            // 本体 quad 平移/滚转副本（顶点数组与被包装模型共享，不可原地改）
            for (BakedQuad quad : base) out.add(shiftedQuad(quad, jx, jy, roll, cx, cy));
        } else {
            out.addAll(base);
        }
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
        // ⚠ 绕序以原版 FaceInfo.SOUTH（左上→左下→右下→右上，几何法线 +z）为准：
        //   corners 数组是 左下,左上,右上,右下 —— 逆序遍历(3-i)恰好等价该 CCW 绕序。
        //   JEI 物品列表只保留标记 SOUTH 的 quad 再交给 GPU 背面剔除，
        //   绕序若与标记不符，光环在 JEI 中会被整张剔除（普通 GUI 因正反成对而侥幸可见）。
        for (int i = 0; i < 4; i++) {
            int j = 3 - i;
            putVertex(front, i, xs[j], ys[j], zFront, us[j], vs[j], abgr, 127);
            putVertex(back, i, xs[i], ys[i], zBack, us[i], vs[i], abgr, -127);
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

    // ===== 不稳定抖动（烘进 quad 顶点，所有逐帧取 quad 的渲染路径生效） =====

    /**
     * 本体 quad 的抖动副本：整体平移 (ox, oy)，roll != 0 时绕物品中心 (cx, cy) 滚转。
     * ⚠ 曾用 applyTransform 位姿注入实现——字节码上 JEI 批渲染每帧应转发到位，
     *   但整合包实测冻结（断点未定位），故改烘 quad：与光环共用同一条已验证的动画通道。
     * 顶点法线不随滚转重算（≤3.5°，视觉无感）。
     */
    private static BakedQuad shiftedQuad(BakedQuad src, float ox, float oy, float roll, float cx, float cy) {
        int[] v = src.getVertices().clone();
        float cos = roll == 0.0F ? 1.0F : (float) Math.cos(roll);
        float sin = roll == 0.0F ? 0.0F : (float) Math.sin(roll);
        for (int i = 0; i + 7 < v.length; i += VERTEX_SIZE) {
            float x = Float.intBitsToFloat(v[i]);
            float y = Float.intBitsToFloat(v[i + 1]);
            if (roll != 0.0F) {
                float dx = x - cx, dy = y - cy;
                x = cx + dx * cos - dy * sin;
                y = cy + dx * sin + dy * cos;
            }
            v[i] = Float.floatToRawIntBits(x + ox);
            v[i + 1] = Float.floatToRawIntBits(y + oy);
        }
        return new BakedQuad(v, src.getTintIndex(), src.getDirection(), src.getSprite(), src.isShade());
    }

    /**
     * 抖动向量 {dx, dy, roll弧度, 光环尺寸系数}。
     * quiver：双正弦叠频（周期互相错开），x/y 独立相位，平滑 Lissajous 式颤动；
     * glitch：按保持间隔离散取哈希伪随机位移，1/8 概率失稳尖峰（位移×2.6+急促滚转+光环×1.18）。
     * 全部相位先取模再入三角函数（epoch 大数 float 精度教训，同 phaseOf）。
     */
    private float[] computeJitter(long t) {
        float amp = settings.shakeAmp;
        if (settings.shakeMode == HaloSettings.SHAKE_GLITCH) {
            long hold = Math.max(30L, settings.shakePeriodMs);
            long h = hash(t / hold);
            boolean spike = (h & 0x7L) == 0L;
            float k = spike ? 2.6F : 1.0F;
            return new float[]{
                    amp * k * unit(h >>> 8),
                    amp * k * unit(h >>> 24),
                    spike ? 0.06F * unit(h >>> 40) : 0.0F,
                    spike ? 1.18F : 1.0F};
        }
        int p = Math.max(30, settings.shakePeriodMs);
        float dx = amp * (float) (0.62 * Math.sin(phaseOf(t, p))
                + 0.38 * Math.sin(phaseOf(t, (int) (p * 1.618) + 7) + 1.7));
        float dy = amp * (float) (0.62 * Math.sin(phaseOf(t, (int) (p * 0.83) + 3) + 0.9)
                + 0.38 * Math.sin(phaseOf(t, (int) (p * 2.09) + 11) + 2.6));
        return new float[]{dx, dy, 0.0F, 1.0F};
    }

    /** splitmix64 变体，混入实例相位使同类物品不同步跳位 */
    private long hash(long x) {
        long h = x * 0x9E3779B97F4A7C15L + phase * 0x632BE59BD9B4E019L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    /** 取 16 位映射到 [-1, 1] */
    private static float unit(long bits) {
        return ((bits & 0xFFFFL) / 32767.5F) - 1.0F;
    }

    // ===== RGB 色差残影 =====

    /**
     * 把本体 SOUTH/NORTH 主面各复制红/青两张，反向错位、深度推到本体面之后：
     * 重叠区被本体覆盖（残影先绘、本体后绘），只在轮廓边缘露出红/青撕裂描边。
     * 1px 侧棱不复制。物品渲染是半透明系 + alpha<0.1 discard，本体透明像素
     * 不写深度，描边不会被空像素挡住。SOUTH 残影沿用原标签，可通过 JEI 的
     * 单面过滤（JEI 缓存 quad，残影错位量在列表里定格，属预期）。
     */
    private void addChromaGhosts(List<BakedQuad> out, List<BakedQuad> base,
                                 long t, float size, float minZ, float maxZ, float jx, float jy) {
        float[] sep = chromaOffset(t);
        float sx = sep[0] * size, sy = sep[1] * size;
        if (Math.abs(sx) + Math.abs(sy) < size * 0.0015F) return;
        float gz = Math.max((maxZ - minZ) * 0.25F, size * 0.004F);
        int red = toAbgr(0xB4FF3232);
        int cyan = toAbgr(0xB432E0FF);
        for (BakedQuad quad : base) {
            Direction dir = quad.getDirection();
            if (dir != Direction.SOUTH && dir != Direction.NORTH) continue;
            // SOUTH 面朝 +z，残影往 -z 退到本体之后；NORTH 相反。红青深度错开防互相 z-fight
            // (jx, jy) 是本体抖动位移：残影跟随本体、错位量叠加其上
            float dz = dir == Direction.SOUTH ? -gz : gz;
            out.add(ghostOf(quad, jx + sx, jy + sy, dz, red));
            out.add(ghostOf(quad, jx - sx, jy - sy, dz * 2.0F, cyan));
        }
    }

    /** 残影错位向量：抖动开启时与抖动同向联动（幅值换 chromaAmp），否则做慢速圆周漂移 */
    private float[] chromaOffset(long t) {
        if (settings.shakeMode != HaloSettings.SHAKE_NONE && settings.shakeAmp > 0) {
            float[] j = computeJitter(t);
            float k = settings.chromaAmp / settings.shakeAmp;
            return new float[]{j[0] * k, j[1] * k};
        }
        double a = phaseOf(t, 2400);
        return new float[]{settings.chromaAmp * (float) Math.cos(a),
                settings.chromaAmp * (float) Math.sin(a)};
    }

    /** 复制 quad：平移顶点、覆写顶点色为残影色、拉满亮度；tintIndex 置 -1 防物品染色器二次上色 */
    private static BakedQuad ghostOf(BakedQuad src, float ox, float oy, float oz, int abgr) {
        int[] v = src.getVertices().clone();
        for (int i = 0; i + 7 < v.length; i += VERTEX_SIZE) {
            v[i] = Float.floatToRawIntBits(Float.intBitsToFloat(v[i]) + ox);
            v[i + 1] = Float.floatToRawIntBits(Float.intBitsToFloat(v[i + 1]) + oy);
            v[i + 2] = Float.floatToRawIntBits(Float.intBitsToFloat(v[i + 2]) + oz);
            v[i + 3] = abgr;
            v[i + 6] = FULL_BRIGHT;
        }
        return new BakedQuad(v, -1, src.getDirection(), src.getSprite(), src.isShade());
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
