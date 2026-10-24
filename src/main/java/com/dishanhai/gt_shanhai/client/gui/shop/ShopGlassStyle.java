package com.dishanhai.gt_shanhai.client.gui.shop;

/**
 * 商店模糊的「液态玻璃」样式：色调、饱和度、颗粒、提亮、内缘亮边、边缘折射、浮层外缘阴影。
 *
 * <p>只描述数值，不碰 GL，所以可以直接单测。长度类字段（折射、亮边宽、阴影半径）用虚拟 GUI 单位，
 * 写入 uniform 时乘 {@code scale} 换成 framebuffer 像素；其余是无量纲比例，不随缩放。
 * {@code scale = radiusPixels / ShopGaussBlur.BLUR_RADIUS_VIRTUAL}，复用模糊半径的换算。</p>
 */
public final class ShopGlassStyle {

    /** 大图层、右键菜单：全套玻璃。 */
    public static final ShopGlassStyle OVERLAY = new ShopGlassStyle(
            0.78f, 0.90f, 1.0f, 0.10f, 0.20f, 0.035f, 0.025f, 0.90f, 5f, 7f, 10f, 0.50f);
    /** 卡片飞行：略轻，飞行时间短，不要抢走卡片本身。 */
    public static final ShopGlassStyle FLIGHT = new ShopGlassStyle(
            0.78f, 0.90f, 1.0f, 0.08f, 0.15f, 0.025f, 0.020f, 0.60f, 4f, 6f, 8f, 0.40f);
    /** 下拉/历史弹层的外圈：不要折射和内缘亮边（外圈的矩形边不是玻璃的边），只留色调、轻颗粒和弹层外缘的光晕与柔影。 */
    public static final ShopGlassStyle POPUP = new ShopGlassStyle(
            0.78f, 0.90f, 1.0f, 0.08f, 0.15f, 0.020f, 0.015f, 0.60f, 0f, 7f, 10f, 0.45f);
    /** 分组切页：只上色加一点颗粒，不要亮边，否则网格边缘每次切页都闪一圈。 */
    public static final ShopGlassStyle SWAP = new ShopGlassStyle(
            0.78f, 0.90f, 1.0f, 0.05f, 0.10f, 0.020f, 0f, 0f, 0f, 0f, 0f, 0f);
    /** 加载占位：呼吸式强度变化下只保留颗粒和一点色调。 */
    public static final ShopGlassStyle LOADING = new ShopGlassStyle(
            0.78f, 0.90f, 1.0f, 0.06f, 0f, 0.030f, 0.010f, 0f, 0f, 0f, 0f, 0f);
    /** 商店玻璃主题的毛玻璃底：只糊面板后面的世界，叠一点冷色调与提亮，亮边由面板自己画。 */
    public static final ShopGlassStyle BACKDROP = new ShopGlassStyle(
            0.70f, 0.85f, 1.0f, 0.12f, 0.25f, 0.020f, 0.030f, 0f, 0f, 0f, 0f, 0f);
    /**
     * 玻璃主题的区域分隔（余额栏、网格区、详情区）：在根板毛玻璃之上再糊一层，色调压向深海军蓝、去饱和，
     * 区域比根板暗一档，分区边界一眼可见，区域里的浅色字也更清楚。
     */
    public static final ShopGlassStyle REGION = new ShopGlassStyle(
            0.05f, 0.09f, 0.15f, 0.30f, -0.30f, 0.015f, 0f, 0f, 0f, 0f, 0f, 0f);
    /** 玻璃主题的按钮/页签底：压得比区域更深、几乎去色，背后再花的画面也不会和按钮字抢对比度。 */
    public static final ShopGlassStyle CONTROL = new ShopGlassStyle(
            0.04f, 0.07f, 0.12f, 0.50f, -0.45f, 0.012f, 0f, 0f, 0f, 0f, 0f, 0f);
    /** 完全不加玻璃层，等价于旧的纯高斯。点击波用它，波自己带折射和高光。 */
    public static final ShopGlassStyle NONE = new ShopGlassStyle(
            0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);

    private final float tintR;
    private final float tintG;
    private final float tintB;
    private final float tintAmount;
    private final float saturation;
    private final float grain;
    private final float lift;
    private final float rim;
    private final float refractV;
    private final float rimWidthV;
    private final float shadowV;
    private final float shadowStrength;

    private ShopGlassStyle(float tintR, float tintG, float tintB, float tintAmount,
                           float saturation, float grain, float lift, float rim,
                           float refractV, float rimWidthV, float shadowV, float shadowStrength) {
        this.tintR = tintR;
        this.tintG = tintG;
        this.tintB = tintB;
        this.tintAmount = tintAmount;
        this.saturation = saturation;
        this.grain = grain;
        this.lift = lift;
        this.rim = rim;
        this.refractV = refractV;
        this.rimWidthV = rimWidthV;
        this.shadowV = shadowV;
        this.shadowStrength = shadowStrength;
    }

    /**
     * 写出三个 vec4 uniform 的值，每个数组至少 4 个元素。
     * tint = {r, g, b, 混合量}；params = {饱和度增量, 颗粒, 提亮, 亮边强度}；
     * edge = {折射 px, 亮边宽 px, 阴影半径 px, 阴影强度}。
     */
    public void write(float scale, float[] tint, float[] params, float[] edge) {
        tint[0] = tintR;
        tint[1] = tintG;
        tint[2] = tintB;
        tint[3] = tintAmount;
        params[0] = saturation;
        params[1] = grain;
        params[2] = lift;
        params[3] = rim;
        edge[0] = refractV * scale;
        edge[1] = rimWidthV * scale;
        edge[2] = shadowV * scale;
        edge[3] = shadowStrength;
    }
}
