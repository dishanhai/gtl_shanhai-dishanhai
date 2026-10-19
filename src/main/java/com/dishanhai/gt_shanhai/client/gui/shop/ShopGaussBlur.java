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
 * 商店右侧详情面板的局部高斯模糊。
 *
 * <p>实现路线按 {@code docs/blur-forge-高斯模糊技术分析-2026-10-19.md} 的结论收敛：</p>
 * <ul>
 *     <li>用原版 {@link PostChain} 做 2 趟可分离高斯（H → V），最后一趟落回 {@code minecraft:main}；</li>
 *     <li>5 次双线性采样近似 9-tap 高斯，权重 0.227 / 0.316 / 0.070，成本约 10 taps/px；</li>
 *     <li>{@code Rect} 把模糊限制在右侧商品面板，{@code Hole} 把正在飞的卡片挖掉保持清晰；</li>
 *     <li>{@code Progress} 随卡片落位从 1 淡到 0，模糊半径同步收缩。</li>
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
    private static float requestStrength;
    private static float requestRadius = BLUR_RADIUS_VIRTUAL;
    private static float[] requestRect;
    private static float[] requestHole;

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
                        new float[]{0f, 0f, 1f, 1f}, new float[]{0f, 0f, 0f, 0f});
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

    public static void clear() {
        requested = false;
        requestStrength = 0f;
        requestRadius = BLUR_RADIUS_VIRTUAL;
        requestRect = null;
        requestHole = null;
    }

    /**
     * 界面帧末执行。必须先 flush，否则商店最后一批 quad 还在 GuiGraphics 的缓冲里，
     * 后处理读到的是不完整的画面。
     */
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        boolean hadRequest = requested;
        float strength = requestStrength;
        float radius = requestRadius;
        float[] rect = requestRect;
        float[] hole = requestHole;
        clear();

        if (!hadRequest || rect == null || strength <= 0.01f) return;
        if (!(event.getScreen() instanceof ShopScreen)) return;
        if (event.getGuiGraphics() != null) {
            event.getGuiGraphics().flush();
        }
        render(Minecraft.getInstance(), strength, radius, rect,
                hole == null ? new float[]{0f, 0f, 0f, 0f} : hole, event.getPartialTick());
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
                               float[] rect, float[] hole, float partialTick) {
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
        if (!applyUniforms(post, strength, radius, rect, hole)) {
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
                                         float[] rect, float[] hole) {
        List<PostPass> passes = passesOf(post);
        if (passes.isEmpty()) return false;
        boolean allReady = true;
        for (PostPass pass : passes) {
            Uniform rectUniform = pass.getEffect().getUniform("Rect");
            Uniform holeUniform = pass.getEffect().getUniform("Hole");
            Uniform progressUniform = pass.getEffect().getUniform("Progress");
            if (rectUniform == null || holeUniform == null || progressUniform == null) {
                allReady = false;
                continue;
            }
            rectUniform.set(rect[0], rect[1], rect[2], rect[3]);
            holeUniform.set(hole[0], hole[1], hole[2], hole[3]);
            progressUniform.set(strength);
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
