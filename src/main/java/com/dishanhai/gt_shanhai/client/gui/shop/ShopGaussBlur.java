package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.ScreenEvent;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

/**
 * 商店内容区的局部高斯模糊。一帧最多两块矩形，共用同一条 {@link PostChain}，不另分配渲染目标。
 *
 * <p>实现路线按 {@code docs/blur-forge-高斯模糊技术分析-2026-10-19.md} 的结论收敛：</p>
 * <ul>
 *     <li>用原版 {@link PostChain} 做 2 趟可分离高斯（H → V），最后一趟落回 {@code minecraft:main}；</li>
 *     <li>默认 5 次宽间距采样，给卡片飞行；文字层走 {@link #requestSmooth} 的密采样高斯，避免字形残影；</li>
 *     <li>加载中的商品格走 {@link #requestSmoothCells}，一趟里最多 8 块，不另开渲染目标。</li>
 *     <li>{@code Rect} 把模糊限制在调用方给出的商店内矩形，{@code Hole} 把飞行卡片或浮层挖掉保持清晰；</li>
 *     <li>{@code Progress} 由调用方驱动：飞行晚于位移淡入，浮层跟 150ms 弹入走。</li>
 * </ul>
 *
 * <p>执行时机是 {@link ScreenEvent.Render.Post}：它在 {@code Screen.render} 之后触发，
 * 先 flush GUI 批次，此时 {@code minecraft:main} 里已经是“世界 + 当前界面”，
 * 再对它做局部后处理。不能放在 {@code RenderGuiEvent.Post}——那是 HUD，
 * 触发时机在 Screen 之前，商店界面还没画进主画面。</p>
 */
public final class ShopGaussBlur {

    /** 模糊半径（虚拟 GUI 单位）；实际像素半径按 ScaledScreen × 窗口 GUI scale 换算。 */
    public static final float BLUR_RADIUS_VIRTUAL = 5.0f;
    /** 同一趟后处理能挖开的加载格矩形数。多出来的格子只画占位，不再各跑一趟。 */
    public static final int MAX_CELL_RECTS = 8;
    private static final long RETRY_MS = 5_000L;

    /**
     * PostChain#passes 是私有的。开发环境字段名是 passes，生产环境是 SRG 的
     * f_110009_；这里按两个名字依次解析，避免额外引入 mixin refmap 依赖。
     */
    private static final Field POST_CHAIN_PASSES = resolvePassesField();

    private static PostChain chain;
    private static int chainWidth = -1;
    private static int chainHeight = -1;
    private static boolean failed;
    private static long failedAtMs;
    private static boolean reflectionWarned;
    private static boolean uniformFailed;

    private static boolean requested;
    private static boolean requestSmooth;
    private static float requestStrength;
    private static float requestRadius = BLUR_RADIUS_VIRTUAL;
    private static float[] requestRect;
    private static float[] requestHole;
    /** 同一帧的第二块矩形。飞行时用来糊源卡片；新的主请求会把它清掉。 */
    private static boolean extraRequested;
    private static float extraStrength;
    private static float extraRadius = BLUR_RADIUS_VIRTUAL;
    private static float[] extraRect;
    private static float[] extraHole;
    /** 加载格的 UV 矩形，最多 {@link #MAX_CELL_RECTS} 块，每块 4 个分量。0 表示只用主矩形。 */
    private static int cellCount;
    private static final float[] cellUv = new float[MAX_CELL_RECTS * 4];
    private static final float[] cellUvSnapshot = new float[MAX_CELL_RECTS * 4];
    private static final float[] NO_CELLS = new float[MAX_CELL_RECTS * 4];
    /** 不带波：{u, v, radius, thickness, refract, glow}。 */
    private static final float[] NO_WAVE = new float[6];

    /**
     * 点击波的专用槽位，跟主请求互不覆盖。
     *
     * <p>点商品格必定同时开卡片飞行，飞行会占掉主请求；如果波挤在主请求里，就只能整段让路，
     * 玩家在格子上点永远看不到波。这里给波单独一条矩形，帧末排在主请求之后再跑一趟，
     * 波就能盖在模糊之上。</p>
     */
    private static boolean waveRequested;
    private static float waveStrength;
    private static float waveKernelPx;
    private static float waveRadiusPx;
    private static float waveBandPx;
    private static float waveRefractPx;
    private static float waveGlowAmount;
    private static float waveOriginU;
    private static float waveOriginV;
    private static float[] waveRect;

    private ShopGaussBlur() {
    }

    /**
     * 商店打开时预热，避免第一次点商品才编译 shader 造成卡顿。
     * 失败时静默降级：调用方会继续使用原来的局部磨砂。
     */
    public static void warmUp() {
        // 每次打开商店都给 uniform 一次重试机会；真失败会在首帧渲染时再次置位并回退磨砂。
        uniformFailed = false;
        PostChain post = ensureChain(Minecraft.getInstance());
        if (post != null) {
            // 提前确认 Rect/Hole/Progress 这三个动态 uniform 真的存在，
            // 避免第一帧 isReady() 误报可用而闪烁一次。
            try {
                uniformFailed = !applyUniforms(post, 1f, BLUR_RADIUS_VIRTUAL,
                        new float[]{0f, 0f, 1f, 1f}, new float[]{0f, 0f, 0f, 0f}, false, 0, NO_CELLS,
                        NO_WAVE);
            } catch (Exception e) {
                uniformFailed = true;
                GTDishanhaiMod.LOGGER.error("商店高斯模糊 uniform 预检失败，已回退为局部磨砂", e);
            }
        }
    }

    /** 后处理链是否可用；不可用时 ShopScreen 回退到局部磨砂。 */
    public static boolean isReady() {
        return chain != null && !uniformFailed;
    }

    /**
     * 登记本帧的模糊请求。坐标全部是 framebuffer 像素，原点在左上角。
     *
     * @param hole 正在飞行的卡片矩形；宽高 <= 0 表示不挖洞
     */
    public static void request(int x, int y, int w, int h, float strength,
                               float radiusPixels,
                               int holeX, int holeY, int holeW, int holeH) {
        // 新的主矩形取代上一块，避免飞行模糊和浮层模糊在同一帧叠两次。
        extraRequested = false;
        extraRect = null;
        extraHole = null;
        requestSmooth = false;
        cellCount = 0;
        if (strength <= 0.01f || w <= 0 || h <= 0) {
            clear();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getMainRenderTarget() == null) return;
        int fbW = mc.getMainRenderTarget().width;
        int fbH = mc.getMainRenderTarget().height;
        requestRect = toUvRect(x, y, w, h, fbW, fbH);
        requestHole = holeW > 0 && holeH > 0
                ? toUvRect(holeX, holeY, holeW, holeH, fbW, fbH)
                : new float[]{0f, 0f, 0f, 0f};
        requestStrength = clamp01(strength);
        requestRadius = radiusPixels > 0f ? radiusPixels : BLUR_RADIUS_VIRTUAL;
        requested = true;
    }

    /**
     * 同 {@link #request}，但走密采样高斯。描述详情和分组切页用这条，宽间距 5-tap 会把文字打成残影。
     */
    public static void requestSmooth(int x, int y, int w, int h, float strength,
                                     float radiusPixels,
                                     int holeX, int holeY, int holeW, int holeH) {
        request(x, y, w, h, strength, radiusPixels, holeX, holeY, holeW, holeH);
        if (requested) requestSmooth = true;
    }

    /**
     * 同一趟密采样高斯里糊多块加载格。{@code xywh} 是 framebuffer 像素，每块 4 个分量（x,y,w,h），
     * 原点在左上角。主矩形取这些块的并集，着色器再把并集里已经加载好的格子挖掉。
     * 只有一块时退回 {@link #requestSmooth}，少做逐像素判断。
     */
    public static void requestSmoothCells(int[] xywh, int count, float strength, float radiusPixels) {
        if (xywh == null || count <= 0 || strength <= 0.01f) return;
        int n = Math.min(count, MAX_CELL_RECTS);
        int x0 = Integer.MAX_VALUE;
        int y0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE;
        int y1 = Integer.MIN_VALUE;
        int filled = 0;
        for (int i = 0; i < n; i++) {
            int o = i * 4;
            if (o + 3 >= xywh.length) break;
            int w = xywh[o + 2];
            int h = xywh[o + 3];
            if (w <= 0 || h <= 0) continue;
            int x = xywh[o];
            int y = xywh[o + 1];
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            x1 = Math.max(x1, x + w);
            y1 = Math.max(y1, y + h);
            filled++;
        }
        if (filled <= 0 || x1 <= x0 || y1 <= y0) return;
        requestSmooth(x0, y0, x1 - x0, y1 - y0, strength, radiusPixels, 0, 0, 0, 0);
        if (!requested || filled <= 1) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getMainRenderTarget() == null) return;
        int fbW = mc.getMainRenderTarget().width;
        int fbH = mc.getMainRenderTarget().height;
        int written = 0;
        for (int i = 0; i < n && written < MAX_CELL_RECTS; i++) {
            int o = i * 4;
            if (o + 3 >= xywh.length) break;
            int w = xywh[o + 2];
            int h = xywh[o + 3];
            if (w <= 0 || h <= 0) continue;
            float[] uv = toUvRect(xywh[o], xywh[o + 1], w, h, fbW, fbH);
            int d = written * 4;
            cellUv[d] = uv[0];
            cellUv[d + 1] = uv[1];
            cellUv[d + 2] = uv[2];
            cellUv[d + 3] = uv[3];
            written++;
        }
        for (int i = written * 4; i < cellUv.length; i++) cellUv[i] = 0f;
        cellCount = written > 1 ? written : 0;
    }

    /**
     * 登记一次点击波，坐标是 framebuffer 像素、原点在左上角。
     *
     * <p>独立于 {@link #request}：同一帧的飞行/浮层模糊照常跑，波在它们之后再叠一趟。
     * 每帧都要重新登记，没登记就等于没有波。</p>
     *
     * @param radiusPixels    波峰所在半径；<= 0.5 视为取消
     * @param thicknessPixels 波峰厚度
     * @param refractPixels   波峰处径向采样位移，正数把画面向外推开
     * @param glow            波峰高光强度 0..1
     */
    public static void requestWave(int x, int y, int w, int h, float strength,
                                   float kernelPixels,
                                   float radiusPixels, float thicknessPixels,
                                   float refractPixels, float glow) {
        waveRequested = false;
        waveRect = null;
        if (strength <= 0.01f || w <= 0 || h <= 0 || radiusPixels <= 0.5f) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getMainRenderTarget() == null) return;
        int fbW = mc.getMainRenderTarget().width;
        int fbH = mc.getMainRenderTarget().height;
        if (fbW <= 0 || fbH <= 0) return;
        float[] uv = toUvRect(x, y, w, h, fbW, fbH);
        if (uv[2] <= uv[0] || uv[3] <= uv[1]) return;
        waveRect = uv;
        waveStrength = clamp01(strength);
        waveKernelPx = kernelPixels > 0f ? kernelPixels : BLUR_RADIUS_VIRTUAL;
        waveRadiusPx = radiusPixels;
        waveBandPx = Math.max(1f, thicknessPixels);
        waveRefractPx = refractPixels;
        waveGlowAmount = clamp01(glow);
        waveOriginU = clamp01((x + w * 0.5f) / (float) fbW);
        waveOriginV = clamp01(1f - (y + h * 0.5f) / (float) fbH);
        waveRequested = true;
    }

    /** 点击波自己的圆心（UV），由 {@link #requestWave} 之后再覆盖成真实点击点。 */
    public static void setWaveOrigin(float originU, float originV) {
        if (!waveRequested) return;
        waveOriginU = clamp01(originU);
        waveOriginV = clamp01(originV);
    }

    /** 本帧是否已经排了点击波。 */
    public static boolean hasWaveRequest() {
        return waveRequested && waveRect != null;
    }

    /**
     * 同一帧的第二块矩形。不清除主请求。宽高小于等于 0 或强度过低时忽略。
     * 坐标同样是 framebuffer 像素，原点在左上角。
     */    public static void requestExtra(int x, int y, int w, int h, float strength,
                                    float radiusPixels,
                                    int holeX, int holeY, int holeW, int holeH) {
        if (strength <= 0.01f || w <= 0 || h <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getMainRenderTarget() == null) return;
        int fbW = mc.getMainRenderTarget().width;
        int fbH = mc.getMainRenderTarget().height;
        extraRect = toUvRect(x, y, w, h, fbW, fbH);
        extraHole = holeW > 0 && holeH > 0
                ? toUvRect(holeX, holeY, holeW, holeH, fbW, fbH)
                : new float[]{0f, 0f, 0f, 0f};
        extraStrength = clamp01(strength);
        extraRadius = radiusPixels > 0f ? radiusPixels : BLUR_RADIUS_VIRTUAL;
        extraRequested = true;
    }

    public static void clear() {
        requested = false;
        requestSmooth = false;
        requestStrength = 0f;
        requestRadius = BLUR_RADIUS_VIRTUAL;
        requestRect = null;
        requestHole = null;
        extraRequested = false;
        extraStrength = 0f;
        extraRadius = BLUR_RADIUS_VIRTUAL;
        extraRect = null;
        extraHole = null;
        cellCount = 0;
        waveRequested = false;
        waveRect = null;
        waveStrength = 0f;
        waveKernelPx = 0f;
        waveRadiusPx = 0f;
        waveBandPx = 0f;
        waveRefractPx = 0f;
        waveGlowAmount = 0f;
    }

    /**
     * 界面帧末执行。必须先 flush，否则商店最后一批 quad 还在 GuiGraphics 的缓冲里，
     * 后处理读到的是不完整的画面。
     */
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        boolean hadRequest = requested;
        boolean smooth = requestSmooth;
        float strength = requestStrength;
        float radius = requestRadius;
        float[] rect = requestRect;
        float[] hole = requestHole;
        boolean hadExtra = extraRequested;
        float strength2 = extraStrength;
        float radius2 = extraRadius;
        float[] rect2 = extraRect;
        float[] hole2 = extraHole;
        int cells = cellCount;
        boolean hadWave = waveRequested && waveRect != null && waveStrength > 0.01f;
        float[] wave = {waveOriginU, waveOriginV, waveRadiusPx, waveBandPx, waveRefractPx, waveGlowAmount};
        float waveStrength = ShopGaussBlur.waveStrength;
        float waveRadius = waveKernelPx;
        float[] waveRectCopy = waveRect;
        System.arraycopy(cellUv, 0, cellUvSnapshot, 0, cellUv.length);
        clear();

        boolean primary = hadRequest && rect != null && strength > 0.01f;
        boolean extra = hadExtra && rect2 != null && strength2 > 0.01f;
        if (!primary && !extra && !hadWave) return;
        if (!(event.getScreen() instanceof ShopScreen)) return;
        if (event.getGuiGraphics() != null) {
            event.getGuiGraphics().flush();
        }
        float partialTick = event.getPartialTick();
        Minecraft mc = Minecraft.getInstance();
        if (primary) {
            render(mc, strength, radius, rect,
                    hole == null ? new float[]{0f, 0f, 0f, 0f} : hole, smooth, cells, cellUvSnapshot,
                    NO_WAVE, partialTick);
        }
        if (extra && isReady()) {
            render(mc, strength2, radius2, rect2,
                    hole2 == null ? new float[]{0f, 0f, 0f, 0f} : hole2, false, 0, NO_CELLS,
                    NO_WAVE, partialTick);
        }
        // 波放最后一趟：它要盖在飞行/浮层的模糊之上，否则点在商品格上会被飞行请求吃掉。
        if (hadWave && isReady()) {
            render(mc, waveStrength, waveRadius, waveRectCopy,
                    new float[]{0f, 0f, 0f, 0f}, true, 0, NO_CELLS, wave, partialTick);
        }
    }

    /**
     * 窗口像素矩形（左上原点）→ shader 的 UV 矩形（左下原点）。
     * PostChain 的投影是 setOrtho(0, w, 0, h)，所以 v 需要翻转。
     */
    static float[] toUvRect(int x, int y, int w, int h, int fbW, int fbH) {
        if (fbW <= 0 || fbH <= 0 || w <= 0 || h <= 0) {
            return new float[]{0f, 0f, 0f, 0f};
        }
        float u0 = clamp01((float) x / (float) fbW);
        float u1 = clamp01((float) (x + w) / (float) fbW);
        float v0 = clamp01(1.0f - (float) (y + h) / (float) fbH);
        float v1 = clamp01(1.0f - (float) y / (float) fbH);
        return new float[]{u0, v0, u1, v1};
    }

    private static void render(Minecraft mc, float strength, float radius,
                               float[] rect, float[] hole, boolean smooth,
                               int cells, float[] cellData,
                               float[] wave, float partialTick) {
        PostChain post = ensureChain(mc);
        if (post == null) return;
        if (mc.getMainRenderTarget() == null) return;

        int fbW = mc.getMainRenderTarget().width;
        int fbH = mc.getMainRenderTarget().height;
        if (fbW != chainWidth || fbH != chainHeight) {
            post.resize(fbW, fbH);
            chainWidth = fbW;
            chainHeight = fbH;
        }
        // 反射/着色器 uniform 任一不可用就整个跳过，绝不让 Rect 落回默认的全屏值。
        if (!applyUniforms(post, strength, radius, rect, hole, smooth, cells, cellData, wave)) {
            uniformFailed = true;
            return;
        }
        uniformFailed = false;

        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        try {
            post.process(partialTick);
            failed = false;
        } catch (Exception e) {
            // 不让渲染异常冒到游戏主循环；下一帧开始回退到磨砂。
            chain = null;
            failed = true;
            failedAtMs = System.currentTimeMillis();
            GTDishanhaiMod.LOGGER.error("商店高斯模糊执行失败，已回退为局部磨砂", e);
        } finally {
            // PostPass 解绑后会把默认 framebuffer 设回来；恢复 main，保证后续 GUI/最终 flush 仍写主画面。
            if (mc.getMainRenderTarget() != null) {
                mc.getMainRenderTarget().bindWrite(false);
                RenderSystem.viewport(0, 0, fbW, fbH);
            }
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        }
    }

    private static boolean applyUniforms(PostChain post, float strength, float radius,
                                         float[] rect, float[] hole, boolean smooth,
                                         int cells, float[] cellData, float[] wave) {
        List<PostPass> passes = passesOf(post);
        if (passes.isEmpty()) return false;
        boolean allReady = true;
        for (PostPass pass : passes) {
            Uniform rectUniform = pass.getEffect().getUniform("Rect");
            Uniform holeUniform = pass.getEffect().getUniform("Hole");
            Uniform progressUniform = pass.getEffect().getUniform("Progress");
            Uniform smoothUniform = pass.getEffect().getUniform("Smooth");
            Uniform cellCountUniform = pass.getEffect().getUniform("CellCount");
            Uniform cellRectsUniform = pass.getEffect().getUniform("CellRects");
            Uniform waveOriginUniform = pass.getEffect().getUniform("WaveOrigin");
            Uniform waveRadiusUniform = pass.getEffect().getUniform("WaveRadius");
            Uniform waveThickUniform = pass.getEffect().getUniform("WaveThickness");
            Uniform waveRefractUniform = pass.getEffect().getUniform("WaveRefract");
            Uniform waveGlowUniform = pass.getEffect().getUniform("WaveGlow");
            if (rectUniform == null || holeUniform == null || progressUniform == null || smoothUniform == null
                    || cellCountUniform == null || cellRectsUniform == null
                    || waveOriginUniform == null || waveRadiusUniform == null
                    || waveThickUniform == null || waveRefractUniform == null || waveGlowUniform == null) {
                allReady = false;
                continue;
            }
            rectUniform.set(rect[0], rect[1], rect[2], rect[3]);
            holeUniform.set(hole[0], hole[1], hole[2], hole[3]);
            progressUniform.set(strength);
            smoothUniform.set(smooth ? 1f : 0f);
            waveOriginUniform.set(wave[0], wave[1]);
            waveRadiusUniform.set(wave[2]);
            waveThickUniform.set(wave[3]);
            waveRefractUniform.set(wave[4]);
            waveGlowUniform.set(wave[5]);
            int n = cells > 1 && cellData != null ? Math.min(cells, MAX_CELL_RECTS) : 0;
            cellCountUniform.set(n);
            cellRectsUniform.set(n > 0 ? cellData : NO_CELLS);
            Uniform radiusUniform = pass.getEffect().getUniform("Radius");
            if (radiusUniform != null) {
                radiusUniform.set(radius);
            }
        }
        return allReady;
    }

    @SuppressWarnings("unchecked")
    private static List<PostPass> passesOf(PostChain post) {
        if (POST_CHAIN_PASSES == null) return Collections.emptyList();
        try {
            return (List<PostPass>) POST_CHAIN_PASSES.get(post);
        } catch (IllegalAccessException | RuntimeException e) {
            if (!reflectionWarned) {
                reflectionWarned = true;
                GTDishanhaiMod.LOGGER.error("商店高斯模糊无法访问 PostChain.passes，已回退为局部磨砂", e);
            }
            return Collections.emptyList();
        }
    }

    private static Field resolvePassesField() {
        for (String name : new String[]{"passes", "f_110009_"}) {
            try {
                Field field = PostChain.class.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException | RuntimeException ignored) {
                // 开发环境 / 生产环境字段名不同，或反射被限制；继续试下一个。
            }
        }
        return null;
    }

    private static PostChain ensureChain(Minecraft mc) {
        if (chain != null) return chain;
        if (mc.getMainRenderTarget() == null) return null;
        long now = System.currentTimeMillis();
        if (failed && now - failedAtMs < RETRY_MS) return null;
        try {
            int fbW = mc.getMainRenderTarget().width;
            int fbH = mc.getMainRenderTarget().height;
            PostChain created = new PostChain(
                    mc.getTextureManager(),
                    mc.getResourceManager(),
                    mc.getMainRenderTarget(),
                    new ResourceLocation(GTDishanhaiMod.MOD_ID, "shaders/post/shop_gauss_blur.json"));
            created.resize(fbW, fbH);
            chain = created;
            chainWidth = fbW;
            chainHeight = fbH;
            failed = false;
            uniformFailed = false;
            return chain;
        } catch (Exception e) {
            failed = true;
            failedAtMs = now;
            GTDishanhaiMod.LOGGER.error("商店高斯模糊加载失败，已回退为局部磨砂", e);
            return null;
        }
    }

    private static float clamp01(float value) {
        if (value < 0f) return 0f;
        if (value > 1f) return 1f;
        return value;
    }
}
