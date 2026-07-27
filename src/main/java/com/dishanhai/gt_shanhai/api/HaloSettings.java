package com.dishanhai.gt_shanhai.api;

/**
 * 光环渲染参数（Avaritia 风格物品光环）。
 * 不可变数据对象，客户端渲染与 KubeJS 注册共用。
 * <p>
 * 颜色为 ARGB；style 为位掩码，可用 "halo+rays" 这类字符串组合解析。
 * hueCore/hueRim 按通道独立控制彩虹循环：为 true 的通道忽略固定色相
 * 按时间循环彩虹色（core 与 rim 相位错开），另一通道保持显式颜色。
 */
public final class HaloSettings {

    /** 径向渐变底暈 */
    public static final int STYLE_HALO = 1;
    /** 细亮环（日食冕环） */
    public static final int STYLE_RING = 2;
    /** 旋转星芒 */
    public static final int STYLE_RAYS = 4;

    /** 不抖动 */
    public static final int SHAKE_NONE = 0;
    /** 平滑高频颤抖（双正弦叠频，无跳变） */
    public static final int SHAKE_QUIVER = 1;
    /** 故障式跳位：位移离散保持后瞬跳，带随机失稳尖峰（位移放大+急促滚转+光环胀大） */
    public static final int SHAKE_GLITCH = 2;

    public final int style;
    /** 底暈颜色 ARGB */
    public final int coreColor;
    /** 环/星芒颜色 ARGB */
    public final int rimColor;
    /** 光环直径相对物品尺寸的倍数（1.0 = 16px） */
    public final float scale;
    /** 呼吸脉冲（缩放+透明度） */
    public final boolean pulse;
    /** 脉冲周期毫秒 */
    public final int pulsePeriodMs;
    /** 星芒转速：转/秒，负数逆时针 */
    public final float rotateSpeed;
    /** 底暈彩虹色相循环（仅 core 通道） */
    public final boolean hueCore;
    /** 环/星芒彩虹色相循环（仅 rim 通道） */
    public final boolean hueRim;
    /** 抖动模式：{@link #SHAKE_NONE}/{@link #SHAKE_QUIVER}/{@link #SHAKE_GLITCH} */
    public final int shakeMode;
    /** 抖动幅度，相对物品尺寸（0.03 ≈ GUI 半像素）；0 = 关 */
    public final float shakeAmp;
    /** quiver=振动周期毫秒；glitch=跳位保持间隔毫秒 */
    public final int shakePeriodMs;
    /** RGB 色差残影错位幅度，相对物品尺寸；0 = 关 */
    public final float chromaAmp;

    public HaloSettings(int style, int coreColor, int rimColor, float scale,
                        boolean pulse, int pulsePeriodMs, float rotateSpeed,
                        boolean hueCore, boolean hueRim) {
        this(style == 0 ? STYLE_HALO : style, coreColor, rimColor, scale,
                pulse, pulsePeriodMs, rotateSpeed, hueCore, hueRim,
                SHAKE_NONE, 0.0F, 90, 0.0F);
    }

    /** 完整构造：style 允许为 0（纯抖动、无光环面） */
    private HaloSettings(int style, int coreColor, int rimColor, float scale,
                         boolean pulse, int pulsePeriodMs, float rotateSpeed,
                         boolean hueCore, boolean hueRim,
                         int shakeMode, float shakeAmp, int shakePeriodMs, float chromaAmp) {
        this.style = style;
        this.coreColor = coreColor;
        this.rimColor = rimColor;
        this.scale = scale <= 0 ? 1.55F : scale;
        this.pulse = pulse;
        this.pulsePeriodMs = pulsePeriodMs <= 0 ? 1400 : pulsePeriodMs;
        this.rotateSpeed = rotateSpeed;
        this.hueCore = hueCore;
        this.hueRim = hueRim;
        this.shakeMode = shakeMode;
        this.shakeAmp = Math.max(0.0F, shakeAmp);
        this.shakePeriodMs = shakePeriodMs <= 0 ? 90 : shakePeriodMs;
        this.chromaAmp = Math.max(0.0F, chromaAmp);
    }

    /** 纯抖动设置（无光环面），供 makeShake 使用；可与光环注册合并 */
    public static HaloSettings shakeOnly(int shakeMode, float shakeAmp, int shakePeriodMs, float chromaAmp) {
        return new HaloSettings(0, 0, 0, 1.55F, false, 1400, 0.0F, false, false,
                shakeMode, shakeAmp, shakePeriodMs, chromaAmp);
    }

    /**
     * 合并同一物品的两次注册：新注册中显式给出的通道覆盖旧值，未给出的保留。
     * 光环通道以 style != 0 为显式标志，抖动通道以 shakeMode != NONE 或 chromaAmp > 0 为显式标志。
     * 使脚本可对同一物品先 makeHalo 再 makeShake（顺序无关）。
     */
    public static HaloSettings merge(HaloSettings oldS, HaloSettings newS) {
        if (oldS == null) return newS;
        if (newS == null) return oldS;
        HaloSettings h = newS.style != 0 ? newS : oldS;
        HaloSettings s = (newS.shakeMode != SHAKE_NONE || newS.chromaAmp > 0) ? newS : oldS;
        return new HaloSettings(h.style, h.coreColor, h.rimColor, h.scale,
                h.pulse, h.pulsePeriodMs, h.rotateSpeed, h.hueCore, h.hueRim,
                s.shakeMode, s.shakeAmp, s.shakePeriodMs, s.chromaAmp);
    }

    /**
     * 解析样式串："halo"、"eclipse"（=halo+ring）、"ring"、"rays"，可用 + 组合，如 "halo+rays"。
     * 无法识别的片段忽略；全部无效时退回 halo。
     */
    public static int parseStyle(String spec) {
        if (spec == null || spec.isEmpty()) return STYLE_HALO;
        int style = 0;
        for (String part : spec.toLowerCase().split("\\+")) {
            switch (part.trim()) {
                case "halo" -> style |= STYLE_HALO;
                case "ring" -> style |= STYLE_RING;
                case "eclipse" -> style |= STYLE_HALO | STYLE_RING;
                case "rays" -> style |= STYLE_RAYS;
                default -> { }
            }
        }
        return style == 0 ? STYLE_HALO : style;
    }

    /**
     * 解析颜色："#RRGGBB" / "#AARRGGBB"（也接受无 # 前缀）；"rainbow" 返回白色（配合 hue 循环使用）。
     * 6 位默认 alpha=0xFF。解析失败返回不透明白。
     * 逐字符校验十六进制，拒绝 Long.parseLong 会接受的 '+'/'-' 前缀。
     */
    public static int parseColor(String hex) {
        if (hex == null || hex.isEmpty()) return 0xFFFFFFFF;
        String s = hex.trim();
        if (s.equalsIgnoreCase("rainbow") || s.equalsIgnoreCase("hue")) return 0xFFFFFFFF;
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 6 && isHex(s)) return 0xFF000000 | (int) Long.parseLong(s, 16);
        if (s.length() == 8 && isHex(s)) return (int) Long.parseLong(s, 16);
        return 0xFFFFFFFF;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    /** 解析抖动模式："glitch"/"故障" → 跳位；"none"/"off" → 关；其余（含 "quiver"/"颤抖"）→ 平滑颤抖 */
    public static int parseShakeMode(String spec) {
        return switch (spec == null ? "" : spec.trim().toLowerCase()) {
            case "glitch", "故障" -> SHAKE_GLITCH;
            case "none", "off" -> SHAKE_NONE;
            default -> SHAKE_QUIVER;
        };
    }

    /** 颜色串是否表示彩虹模式 */
    public static boolean isRainbow(String hex) {
        return hex != null && (hex.trim().equalsIgnoreCase("rainbow") || hex.trim().equalsIgnoreCase("hue"));
    }
}
