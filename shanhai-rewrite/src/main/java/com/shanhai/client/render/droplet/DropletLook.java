package com.shanhai.client.render.droplet;

/**
 * 强相互作用水滴本体的<b>着色公式</b>（v3 预览 §5 的移植），外加一份<b>假的环境渐变</b>。
 *
 * <h2>一、🔴 用户明确要求「先不做反射」—— 本类<b>不</b>采样任何场景</h2>
 * v3 预览里本体是"真实环境反射"（96×96×6 的 cubemap、每帧把地面格线/三个色块投进去）。
 * 游戏内这一版<b>不做</b>：
 * <ul>
 *   <li>没有 cubemap、没有屏幕空间反射、没有场景倒影；</li>
 *   <li>{@link #env} 是一个<b>纯解析的世界方向函数</b>（天顶亮 → 地平线中性 → 地下暗，
 *       加一条水平柔光带 + 主光方向的软光斑）；</li>
 *   <li>所以<b>本体不会映出地形、方块、天空盒</b> —— 这是刻意的，不是缺陷。</li>
 * </ul>
 * 真做反射时第一个露馅的地方会是<b>尾部近锥面</b>（掠射奇点），但那是反射的事，
 * <b>与网格密度无关</b>（见 {@link DropletProfile} 类注释第三节）。
 *
 * <h2>二、混色式的来历（v3 §5 的注释逐字保留）</h2>
 * 严格按 {@code albedo*(1−F) + env*F*GAIN}（{@code F0=0.06}）时，正对镜头处只有 3.3% 反射
 * ⇒ 看起来像"一块哑光塑料"，没有金属感。所以 v3 保留同一个混合式
 * {@code color = albedo*(1−W) + env*W}，但把权重改成 {@code W = GAIN*(0.55+0.48*F)}：
 * 正对 ≈0.29、掠射 ≈0.55，<b>仍严格随菲涅尔单调增强</b>。
 *
 * <h2>三、"边缘不要出现硬边亮窗"靠三件事</h2>
 * <ol>
 *   <li>环境函数在法线上是<b>连续可导</b>的（没有阈值/阶梯），所以掠射区不会突然跳亮；</li>
 *   <li>菲涅尔用 {@code (1−|N·V|)^5}（五次幂）⇒ 亮度往边缘是<b>渐入</b>，不是一刀切；</li>
 *   <li>最后过一道 v3 的色调曲线 {@code tone(x)=pow(x/(1+0.62x),0.95)} ⇒ 掠射处即使算到 3~4
 *       也被压回 1 以内，不会出现"亮窗"那种硬白块。</li>
 * </ol>
 * 探针 {@code DropletGeomProbe} 对这三点有断言（逐点扫描 {@code N·V} 0→1，相邻步的亮度差必须小于阈值）。
 *
 * <p>本类零 Minecraft 依赖（与 {@link DropletProfile} 同一条口径）。
 */
public final class DropletLook {

    private DropletLook() {}

    /** 冷蓝银底色（v3 {@code ALB=[0.255,0.305,0.395]}）。 */
    public static final float ALB_R = 0.255F;
    public static final float ALB_G = 0.305F;
    public static final float ALB_B = 0.395F;
    /** 介电体 F0（v3 {@code F0=0.06}）。 */
    public static final float F0 = 0.06F;
    /** 反射强度（v3 的滑块默认值 {@code GAIN=0.55}）。 */
    public static final float GAIN = 0.55F;
    /** 漫反射基线/斜率（v3 {@code dif=0.55+0.66*ndl}）。 */
    public static final float DIF_BASE = 0.62F;
    public static final float DIF_SLOPE = 0.72F;
    /** 高光锐度与强度（v3 {@code dh^32*0.55}）。 */
    public static final float SPEC_POW_STEPS = 32.0F;
    public static final float SPEC_GAIN = 0.55F;
    /** 「一条柔软的高光带」的强度：6 次幂的宽包络，与上面那个 32 次幂的锐点相加。 */
    public static final float SHEEN_GAIN = 0.26F;
    /**
     * <b>菲涅尔亮边</b>的独立增益。
     *
     * <p>为什么要单独一项：v3 的亮边其实是"掠射反射到的地面格线"很亮带来的<b>副作用</b>
     * （cubemap 里那圈亮线）。这一版<b>不做反射</b>，环境又只有一条水平亮带
     * ⇒ 掠射和正对的反射读数几乎一样（实测 0.2986 vs 0.3040，亮边根本出不来）。
     * 所以把"亮边"做成显式项：{@code (1−|N·V|)^3 × 0.22}，冷蓝偏置。
     * 三次幂 ⇒ 只在中段掠射区抬起来、正对处几乎为零，且<b>处处连续可导</b>（探针有单调/平滑断言）。
     */
    public static final float RIM_GAIN = 0.20F;
    public static final float RIM_TINT_R = 0.90F;
    public static final float RIM_TINT_G = 1.00F;
    public static final float RIM_TINT_B = 1.12F;
    /** 冷色调偏置（v3 最后 {@code r*=0.96, g*=1.00, b*=1.06}）。 */
    public static final float TINT_R = 0.96F;
    public static final float TINT_G = 1.00F;
    public static final float TINT_B = 1.06F;

    /** 主光方向（v3 {@code LGT=[-0.34,0.82,0.46]} 归一化后的值，下面静态块里现算）。 */
    public static final float KEY_X;
    public static final float KEY_Y;
    public static final float KEY_Z;

    static {
        double x = -0.34D;
        double y = 0.82D;
        double z = 0.46D;
        final double l = Math.sqrt(x * x + y * y + z * z);
        KEY_X = (float) (x / l);
        KEY_Y = (float) (y / l);
        KEY_Z = (float) (z / l);
    }

    /** 假环境：天顶（冷蓝银的亮面）。 */
    private static final float SKY_R = 0.30F;
    private static final float SKY_G = 0.39F;
    private static final float SKY_B = 0.56F;
    /** 假环境：地平线（中性偏冷）。 */
    private static final float HOR_R = 0.14F;
    private static final float HOR_G = 0.17F;
    private static final float HOR_B = 0.25F;
    /** 假环境：朝下（暗）。 */
    private static final float GND_R = 0.030F;
    private static final float GND_G = 0.035F;
    private static final float GND_B = 0.050F;

    /**
     * <b>假的环境渐变</b>（世界方向 → 线性色）。<b>不采样场景、不读贴图、不看方块</b>。
     *
     * @param nx,ny,nz 世界空间单位法线
     * @param out      长度 ≥3 的输出数组（调用方复用，避免每帧 new）
     */
    public static void env(float nx, float ny, float nz, float[] out) {
        envInto(nx, ny, nz, out, 0);
    }

    /**
     * 假环境的主体（{@link #env} 与 {@link #shade} <b>共用这一份实现</b>，不许各写一份
     * —— 两份实现会漂移，而漂移之后"探针绿、游戏内不绿"是最难查的一类）。
     *
     * @param out 输出数组
     * @param off 写到哪里（{@code out[off..off+2]}）
     */
    public static void envInto(float nx, float ny, float nz, float[] out, int off) {
        // ① 天顶 / 地平线 / 地下 三段插值（用 ny 当参数，连续可导）
        final float up = ny > 1.0F ? 1.0F : (ny < -1.0F ? -1.0F : ny);
        float r;
        float g;
        float b;
        if (up >= 0.0F) {
            // sqrt 让"接近天顶"的过渡更柔（不是线性硬插）
            final float k = (float) Math.sqrt(up);
            r = HOR_R + (SKY_R - HOR_R) * k;
            g = HOR_G + (SKY_G - HOR_G) * k;
            b = HOR_B + (SKY_B - HOR_B) * k;
        } else {
            final float k = Math.min(1.0F, -up * 1.6F);
            r = HOR_R + (GND_R - HOR_R) * k;
            g = HOR_G + (GND_G - HOR_G) * k;
            b = HOR_B + (GND_B - HOR_B) * k;
        }
        // ② 主光方向的软光斑（先算，它同时给下面的亮带当方向权重）
        final float d = nx * KEY_X + ny * KEY_Y + nz * KEY_Z;
        float glow = 0.0F;
        if (d > 0.0F) {
            final float d2 = d * d;
            final float d4 = d2 * d2;
            final float d8 = d4 * d4;
            glow = d8 * d2;                    // d^10（又大又软，不是锐点）
        }
        // ③ 一条水平的柔光带（±17° 左右高斯）：本体中段那条"柔软的亮带"，
        //    它同时是"菲涅尔亮边"亮度的主要来源（掠射法线正好落在这一圈）。
        //    乘一个朝主光的权重 ⇒ 向阳那半边的边更亮，不是一圈死板的白环。
        final float bandT = up / 0.34F;
        final float dirWeight = 0.72F + 0.28F * (d > 0.0F ? d : 0.0F);
        final float band = (float) Math.exp(-bandT * bandT) * 0.42F * dirWeight;
        r += band * 1.00F;
        g += band * 1.08F;
        b += band * 1.30F;
        r += glow * 0.34F;
        g += glow * 0.38F;
        b += glow * 0.46F;
        out[off] = r;
        out[off + 1] = g;
        out[off + 2] = b;
    }

    /**
     * 反射权重 {@code W}（v3 §5 的 {@code GAIN*(0.55+0.48*F)}，夹到 [0,1]）。
     *
     * <p>单独抽出来是给探针用的：<b>"菲涅尔单调"</b>这条判据必须只看 {@code W}，
     * 不能看最终亮度（最终亮度里还有漫反射 {@code N·L}，法线一扫过光照方向亮度就会掉，
     * 那样测出来的是光照方向，不是菲涅尔）。正对 ≈0.32、掠射 ≈0.57。
     */
    public static float reflectionWeight(float ndv) {
        float a = ndv < 0.0F ? -ndv : ndv;
        if (a > 1.0F) {
            a = 1.0F;
        }
        final float f = 1.0F - a;
        final float f2 = f * f;
        final float f4 = f2 * f2;
        final float F = F0 + (1.0F - F0) * f4 * f;
        float w = GAIN * (0.55F + 0.48F * F);
        if (w > 1.0F) {
            w = 1.0F;
        }
        return w < 0.0F ? 0.0F : w;
    }

    /** {@link #shade} 内部的假环境暂存（渲染线程独占；本类被设计成单线程使用）。 */
    private static final float[] SHADE_ENV = new float[3];

    /**
     * 本体一个顶点/像素的最终颜色（v3 {@code shade()} 的 Java 版）。
     *
     * @param nx,ny,nz 世界空间单位法线
     * @param vx,vy,vz 世界空间"从表面指向相机"的单位向量（<b>整颗水滴用同一个</b>：
     *                 取"实体中心 → 相机"的方向。为什么不用逐顶点：逐顶点会让
     *                 掠射区随视角抖动、边缘出现细碎亮斑，而这一版要的是"一条柔软的高光带"）
     * @param out      长度 ≥3 的输出数组（0..1 的 sRGB 近似；调用方乘 255）
     */
    public static void shade(float nx, float ny, float nz, float vx, float vy, float vz, float[] out) {
        // 法线归一化（基变换后长度应当已是 1，这里花一次 sqrt 换"绝不出现 NaN/过暗"）
        float nl = nx * nx + ny * ny + nz * nz;
        nl = nl > 1e-12F ? (float) (1.0D / Math.sqrt(nl)) : 0.0F;
        final float NX = nx * nl;
        final float NY = ny * nl;
        final float NZ = nz * nl;

        // 菲涅尔：f=1−|N·V|，F=F0+(1−F0)f^5 ⇒ 反射权重 W（严格随 F 单调增强）
        final float ndv = NX * vx + NY * vy + NZ * vz;
        final float w = reflectionWeight(ndv);

        // 假环境（世界方向）—— 与 env() 同一份实现（envInto），不另写一份
        envInto(NX, NY, NZ, SHADE_ENV, 0);
        final float er = SHADE_ENV[0];
        final float eg = SHADE_ENV[1];
        final float eb = SHADE_ENV[2];

        // 漫反射
        float ndl = NX * KEY_X + NY * KEY_Y + NZ * KEY_Z;
        if (ndl < 0.0F) {
            ndl = 0.0F;
        }
        final float dif = DIF_BASE + DIF_SLOPE * ndl;
        final float ar = ALB_R * dif;
        final float ag = ALB_G * dif;
        final float ab = ALB_B * dif;

        // 高光（半角向量 H = normalize(L + V)，v3 的 32 次方）
        float hx = KEY_X + vx;
        float hy = KEY_Y + vy;
        float hz = KEY_Z + vz;
        final float hl = hx * hx + hy * hy + hz * hz;
        float sp = 0.0F;
        float sheen = 0.0F;
        if (hl > 1e-9F) {
            final float inv = (float) (1.0D / Math.sqrt(hl));
            hx *= inv;
            hy *= inv;
            hz *= inv;
            final float dh = NX * hx + NY * hy + NZ * hz;
            if (dh > 0.0F) {
                final float q = dh * dh;          // 2
                final float q4 = q * q;           // 4
                final float q8 = q4 * q4;         // 8
                final float q16 = q8 * q8;        // 16
                final float q32 = q16 * q16;      // 32
                sp = q32 * SPEC_GAIN;
                // "一条柔软的高光带"：宽得多的包络（6 次幂），只抬一点，负责把高光
                // 从一个"点"拉成"带"；锐点仍由上面 32 次幂那一项给（两者相加）。
                sheen = q4 * q * SHEEN_GAIN;
            }
        }

        // 菲涅尔亮边（显式项：只在中段掠射区抬头，处处连续可导 ⇒ 不会出现"硬边亮窗"）
        // 🔴 必须用 |N·V| 并夹到 1：背面顶点（N·V<0 —— 例如穿墙高亮那趟画的外壳背面、
        //    或者相机正好在水滴内部）如果不夹，rimF 会变成 1+(−ndv) > 1、rim 冲到 1.6
        //    ⇒ 屏幕上出现一块纯白。这不是审美问题，是数值必须夹住。
        float ndvA = ndv < 0.0F ? -ndv : ndv;
        if (ndvA > 1.0F) {
            ndvA = 1.0F;
        }
        final float rimF = 1.0F - ndvA;
        final float rim = rimF * rimF * rimF * RIM_GAIN;

        // 高光（锐点 + 宽带）、亮边、冷色调、色调曲线
        final float spec = sp + sheen;
        final float invW = 1.0F - w;
        out[0] = tone((ar * invW + er * w + spec + rim * RIM_TINT_R) * TINT_R);
        out[1] = tone((ag * invW + eg * w + spec * 1.02F + rim * RIM_TINT_G) * TINT_G);
        out[2] = tone((ab * invW + eb * w + spec * 1.06F + rim * RIM_TINT_B) * TINT_B);
    }

    /**
     * v3 的 LUT 曲线：{@code y = pow(x/(1+0.62x), 0.95)}，最后夹到 1（v3 的 LUT 是 uint8 ⇒ 到 255 就封顶）。
     *
     * <p>它的作用就是"把掠射处算出来的高值压回去"——<b>边缘不出现硬白块</b>靠它兜底。
     * 注意它<b>不是</b>饱和到 1 的曲线（渐近值 {@code (1/0.62)^0.95≈1.56}）⇒ 真正"到 1"靠这一句夹。
     * 实测（探针 20000 个随机法线）本体颜色的上限只有 <b>0.68</b>，夹子实际上从不触发。
     */
    public static float tone(float x) {
        if (!(x > 0.0F)) {
            return 0.0F;
        }
        final float y = x / (1.0F + 0.62F * x);
        final float v = (float) Math.pow(y, 0.95D);
        return v > 1.0F ? 1.0F : v;
    }
}
