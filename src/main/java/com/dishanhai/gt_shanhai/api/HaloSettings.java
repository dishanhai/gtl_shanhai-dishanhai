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

    public HaloSettings(int style, int coreColor, int rimColor, float scale,
                        boolean pulse, int pulsePeriodMs, float rotateSpeed,
                        boolean hueCore, boolean hueRim) {
        this.style = style == 0 ? STYLE_HALO : style;
        this.coreColor = coreColor;
        this.rimColor = rimColor;
        this.scale = scale <= 0 ? 1.55F : scale;
        this.pulse = pulse;
        this.pulsePeriodMs = pulsePeriodMs <= 0 ? 1400 : pulsePeriodMs;
        this.rotateSpeed = rotateSpeed;
        this.hueCore = hueCore;
        this.hueRim = hueRim;
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

    /** 颜色串是否表示彩虹模式 */
    public static boolean isRainbow(String hex) {
        return hex != null && (hex.trim().equalsIgnoreCase("rainbow") || hex.trim().equalsIgnoreCase("hue"));
    }
}
