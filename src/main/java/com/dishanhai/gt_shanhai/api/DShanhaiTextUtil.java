package com.dishanhai.gt_shanhai.api;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
public class DShanhaiTextUtil {

    // ========== Wobble（上下波动）渲染上下文 ==========

    /** Wobble 激活标记（非空时 WobbleFontMixin 生效） */
    public static final ThreadLocal<float[]> WOBBLE_OFFSETS = ThreadLocal.withInitial(() -> null);
    /**
     * Wobble 文本过滤器：返回 true 表示该文本应触发 wobble 效果。
     * 设 null 时由 WOBBLE_OFFSETS 单独控制（所有文本都可能 wobble）。
     */
    public static final ThreadLocal<java.util.function.Predicate<String>> WOBBLE_FILTER = ThreadLocal.withInitial(() -> null);
    /** 展开动画样式名（完全展开后 mixin 用此动态循环色板，不依赖 FCS 静态颜色） */
    public static final ThreadLocal<String> WOBBLE_STYLE = ThreadLocal.withInitial(() -> null);
    /** 展开文本逐行动态色板映射：文本 → 样式名。mixin 在 FCS 路径据此分配逐字动态颜色 */
    public static final ThreadLocal<java.util.Map<String, String>> WOBBLE_STYLE_MAP = ThreadLocal.withInitial(java.util.HashMap::new);
    /** 逐字效果映射：文本 → 每字效果标记串（_=无,%=shake,~=floatY,*=floatX,@=circle,!=bounce） */
    public static final ThreadLocal<java.util.Map<String, String>> PER_CHAR_EFFECTS = ThreadLocal.withInitial(java.util.HashMap::new);

    public enum AnimType {
        CYCLE, BREATHE, SCAN, STATIC, TWINKLE, DISTORT, WOBBLE
    }


    // ========== RGB 平滑色板（电光设计：对称 + 高反差 + 各具特色峰色） ==========

    static final int[] RAINBOW_RGB = {
            0xFF3333, 0xFF4F22, 0xFF6C11, 0xFF8800, 0xFF9F00, 0xFFB500, 0xFFCC00,
            0xBBD211, 0x77D722, 0x33DD33, 0x33DD6C, 0x33DDA4, 0x33DDDD,
            0x33B5E8, 0x338EF4, 0x3366FF, 0x6655F4, 0x9944E8, 0xCC33DD
    };

    static final int[] GOLDEN_RGB = {
            0x995500, 0xAA6600, 0xBB7700, 0xCC8800, 0xDD9300, 0xEE9F00, 0xFFAA00,
            0xFFBB17, 0xFFCC2D, 0xFFDD44, 0xFFE85B, 0xFFF471, 0xFFFF88,
            0xFFFF9F, 0xFFFFB5, 0xFFFFCC, 0xFFFFB5, 0xFFFF9F, 0xFFFF88,
            0xFFF471, 0xFFE85B, 0xFFDD44, 0xFFCC2D, 0xFFBB17, 0xFFAA00,
            0xEE9F00, 0xDD9300, 0xCC8800, 0xBB7700, 0xAA6600, 0x995500
    };

    static final int[] FIRE_RGB = {
            0x992200, 0xA42800, 0xB02D00, 0xBB3300, 0xC63900, 0xD23E00, 0xDD4400,
            0xE84F00, 0xF45B00, 0xFF6600, 0xFF7D00, 0xFF9300, 0xFFAA00,
            0xFFC11C, 0xFFD739, 0xFFEE55, 0xFFF48E, 0xFFF9C6, 0xFFFFFF,
            0xFFF9C6, 0xFFF48E, 0xFFEE55, 0xFFD739, 0xFFC11C, 0xFFAA00,
            0xFF9300, 0xFF7D00, 0xFF6600, 0xF45B00, 0xE84F00, 0xDD4400,
            0xD23E00, 0xC63900, 0xBB3300, 0xB02D00, 0xA42800, 0x992200
    };

    static final int[] WATER_RGB = {
            0x004488, 0x004F93, 0x005B9F, 0x0066AA, 0x1177BB, 0x2288CC, 0x3399DD,
            0x44AAE8, 0x55BBF4, 0x66CCFF, 0x7DD7FF, 0x93E3FF, 0xAAEEFF,
            0xC6F4FF, 0xE3F9FF, 0xFFFFFF, 0xE3F9FF, 0xC6F4FF, 0xAAEEFF,
            0x93E3FF, 0x7DD7FF, 0x66CCFF, 0x55BBF4, 0x44AAE8, 0x3399DD,
            0x2288CC, 0x1177BB, 0x0066AA, 0x004F93, 0x004488
    };

    static final int[] MAGIC_RGB = {
            0x550088, 0x600099, 0x6C00AA, 0x7700BB, 0x8217C6, 0x8E2DD2, 0x9944DD,
            0xA44FE3, 0xB05BE8, 0xBB66EE, 0xC671F4, 0xD27DF9, 0xDD88FF,
            0xE888FF, 0xF488FF, 0xFF88FF, 0xF488FF, 0xE888FF, 0xDD88FF,
            0xD27DF9, 0xC671F4, 0xBB66EE, 0xB05BE8, 0xA44FE3, 0x9944DD,
            0x8E2DD2, 0x8217C6, 0x7700BB, 0x6C00AA, 0x600099, 0x550088
    };

    static final int[] NATURE_RGB = {
            0x006633, 0x0B7739, 0x17883E, 0x229944, 0x2DAA44, 0x39BB44, 0x44CC44,
            0x5BD244, 0x71D744, 0x88DD44, 0xA4E84A, 0xC1F44F, 0xDDFF55,
            0xC1F44F, 0xA4E84A, 0x88DD44, 0x71D744, 0x5BD244, 0x44CC44,
            0x39BB44, 0x2DAA44, 0x229944, 0x17883E, 0x0B7739, 0x006633
    };

    static final int[] ELECTRIC_RGB = {
            0xFFDD00, 0xC1DD55, 0x82DDAA, 0x44DDFF, 0x82E8FF, 0xC1F4FF, 0xFFFFFF,
            0xC1F4FF, 0x82E8FF, 0x44DDFF, 0x82DDAA, 0xC1DD55, 0xFFDD00
    };

    static final int[] ICE_RGB = {
            0x003366, 0x0B4488, 0x1755AA, 0x2266CC, 0x2D77D7, 0x3988E3, 0x4499EE,
            0x5BAAF4, 0x71BBF9, 0x88CCFF, 0xB0DDFF, 0xD7EEFF, 0xFFFFFF,
            0xD7EEFF, 0xB0DDFF, 0x88CCFF, 0x71BBF9, 0x5BAAF4, 0x4499EE,
            0x3988E3, 0x2D77D7, 0x2266CC, 0x1755AA, 0x0B4488, 0x003366
    };

    static final int[] LAVA_RGB = {
            0x771100, 0x881700, 0x991C00, 0xAA2200, 0xBB2D00, 0xCC3900, 0xDD4400,
            0xE84F00, 0xF45B00, 0xFF6600, 0xFF7D00, 0xFF9300, 0xFFAA00,
            0xFFBB17, 0xFFCC2D, 0xFFDD44, 0xFFE882, 0xFFF4C1, 0xFFFFFF,
            0xFFEEBB, 0xFFDD77, 0xFFCC33, 0xFFB522, 0xFF9F11, 0xFF8800,
            0xF47100, 0xE85B00, 0xDD4400, 0xC13300, 0xA42200, 0x881100
    };

    static final int[] ULTIMATE_RAINBOW_RGB = {
            0xFF4444, 0xFF5B2D, 0xFF7117, 0xFF8800,
            0xFF9900, 0xFFAA00, 0xFFBB00, 0xFFCC17,
            0xFFDD2D, 0xFFEE44, 0xE3F444, 0xC6F944,
            0xAAFF44, 0x88FF44, 0x66FF44, 0x44FF44,
            0x44FF66, 0x44FF88, 0x44FFAA, 0x44F4C6,
            0x44E8E3, 0x44DDFF, 0x44C1FF, 0x44A4FF,
            0x4488FF, 0x5571FF, 0x665BFF, 0x7744FF,
            0x8E44FF, 0xA444FF, 0xBB44FF, 0xD244FF,
            0xE844FF, 0xFF44FF, 0xFF4FE8, 0xFF5BD2,
            0xFF66BB, 0xFF71AA, 0xFF7D99, 0xFF8888
    };

    // ========== 扩展色板 ==========

    static final int[] SUNSET_RGB = {
            0xFFF8F0, 0xFFF5EE, 0xFFF2EC, 0xFFEFEA, 0xFEECE8, 0xFCE9E6, 0xFAE6E4,
            0xF8E3E2, 0xF6E0E0, 0xF4DDDE, 0xF0DADC, 0xECD7DA, 0xE8D4D8,
            0xE4D1D6, 0xE0CED4, 0xDCCBD2, 0xD8C8D0, 0xD4C5CE, 0xD0C2CC,
            0xCCBFC8, 0xC8BCC4, 0xC4B9C0, 0xC0B6BC, 0xBCB3B8, 0xB8B0B4
    };

    static final int[] AURORA_RGB = {
            0x33FF44, 0x33EE55, 0x33DD66, 0x33CC77, 0x33BB88, 0x33AA99, 0x3399BB, 0x3388CC, 0x4477DD,
            0x5566EE, 0x7755FF, 0x9944FF, 0xBB33FF, 0x9944FF, 0x7755FF, 0x5566EE, 0x4477DD, 0x3388CC,
            0x3399BB, 0x33AA99, 0x33BB88, 0x33CC77, 0x33DD66, 0x33EE55, 0x33FF44
    };

    static final int[] CRIMSON_RGB = {
            0x991111, 0xAA1111, 0xBB1111, 0xCC1111, 0xDD2222, 0xEE3333, 0xFF4444, 0xFF5555, 0xFF6666,
            0xFF5555, 0xFF4444, 0xEE3333, 0xDD2222, 0xCC1111, 0xBB1111, 0xAA1111, 0x991111
    };

    static final int[] NEON_RGB = {
            0xFF33FF, 0xFF55CC, 0xFF7799, 0xFFAA66, 0xFFCC44, 0xAAFF33, 0x77FF44, 0x44FF77, 0x33FFBB,
            0x33FFEE, 0xFFFFFF, 0x33FFEE, 0x33FFBB, 0x44FF77, 0x77FF44, 0xAAFF33, 0xFFCC44, 0xFFAA66,
            0xFF7799, 0xFF55CC, 0xFF33FF
    };

    static final int[] SAKURA_RGB = {
            0xFF99BB, 0xFFA3C4, 0xFFADCC, 0xFFB7D4, 0xFFC1DD, 0xFFCBE5, 0xFFD5ED, 0xFFDFF0, 0xFFEFF8,
            0xFFFFFF, 0xFFEFF8, 0xFFDFF0, 0xFFD5ED, 0xFFCBE5, 0xFFC1DD, 0xFFB7D4, 0xFFADCC, 0xFFA3C4, 0xFF99BB
    };

    static final int[] COSMIC_RGB = {
            0x553388, 0x6644AA, 0x7755CC, 0x6688EE, 0x44AAFF, 0x88CCFF, 0xDDF4FF,
            0xFFFFFF, 0xE8D8FF, 0xCCAAFF, 0xAA77FF, 0x8866DD, 0x6644AA, 0x553388
    };

    static final int[] VOID_RGB = {
            0x3A2255, 0x4A2A66, 0x5A3380, 0x6A4499, 0x7A55AA, 0x9977CC, 0xC8AAEE,
            0xFFFFFF, 0xC8AAEE, 0x9977CC, 0x7A55AA, 0x6A4499, 0x4A2A66, 0x3A2255
    };

    static final int[] JADE_RGB = {
            0x003322, 0x005533, 0x007744, 0x119955, 0x33BB77, 0x66DDAA, 0xCCFFEE,
            0xFFFFFF, 0xCCFFEE, 0x66DDAA, 0x33BB77, 0x119955, 0x005533
    };

    static final int[] PLASMA_RGB = {
            0x220033, 0x550066, 0x990088, 0xDD2277, 0xFF5555, 0xFFAA33, 0xFFFF66,
            0xFFFFFF, 0xFFFF66, 0xFFAA33, 0xFF5555, 0xDD2277, 0x990088, 0x550066
    };

    static final int[] STARLIGHT_RGB = {
            0x7788AA, 0x99AACC, 0xBBC8EE, 0xDDE8FF, 0xFFFFFF, 0xFFF2BB, 0xFFE088,
            0xFFFFFF, 0xDDE8FF, 0xBBC8EE, 0x99AACC, 0x7788AA
    };

    static final int[] ABYSS_RGB = {
            0x224466, 0x2A5577, 0x336688, 0x4477AA, 0x5588CC, 0x66AAEE, 0x99DDFF,
            0xDDFFFF, 0x99DDFF, 0x66AAEE, 0x5588CC, 0x4477AA, 0x224466
    };

    // ========== 正文柔和色板（去饱和，慢速动画，适合大段正文显示） ==========

    static final int[] BODY_GOLDEN_RGB = {
            0x887744, 0x998855, 0xAA9966, 0xBBAA77, 0xCCBB88, 0xDDCC99,
            0xCCBB88, 0xBBAA77, 0xAA9966, 0x998855, 0x887744
    };
    static final int[] BODY_FIRE_RGB = {
            0x885522, 0x996633, 0xAA7744, 0xBB8855, 0xCC9966,
            0xBB8855, 0xAA7744, 0x996633, 0x885522
    };
    static final int[] BODY_WATER_RGB = {
            0x446688, 0x557799, 0x6688AA, 0x7799BB, 0x88AACC,
            0x7799BB, 0x6688AA, 0x557799, 0x446688
    };
    static final int[] BODY_MAGIC_RGB = {
            0x775588, 0x886699, 0x9977AA, 0xAA88BB, 0xBB99CC,
            0xAA88BB, 0x9977AA, 0x886699, 0x775588
    };
    static final int[] BODY_NATURE_RGB = {
            0x557755, 0x668866, 0x779977, 0x88AA88, 0x99BB99,
            0x88AA88, 0x779977, 0x668866, 0x557755
    };
    static final int[] BODY_CRIMSON_RGB = {
            0x883333, 0x994444, 0xAA5555, 0xBB6666, 0xCC7777,
            0xBB6666, 0xAA5555, 0x994444, 0x883333
    };
    static final int[] BODY_SILVER_RGB = {
            0xFFFFFF, 0xF0F0F0, 0xE0E0E0, 0xD0D0D0, 0xC0C0C0, 0xB0B0B0,
            0xA0A0A0, 0x909090, 0x808080, 0x707070, 0x808080, 0x909090,
            0xA0A0A0, 0xB0B0B0, 0xC0C0C0, 0xD0D0D0, 0xE0E0E0, 0xF0F0F0, 0xFFFFFF
    };
    static final int[] BODY_SUNSET_RGB = {
            0xE8E0DA, 0xE4DCD6, 0xE0D8D2, 0xDCD4CE, 0xD8D0CA,
            0xDCD4CE, 0xE0D8D2, 0xE4DCD6, 0xE8E0DA
    };
    static final int[] BODY_AURORA_RGB = {
            0x557766, 0x668877, 0x779988, 0x88AA99, 0x99BBAA,
            0x88AA99, 0x779988, 0x668877, 0x557766
    };
    static final int[] BODY_NEON_RGB = {
            0x885588, 0x996699, 0xAA77AA, 0xBB88BB, 0xCC99CC,
            0xBB88BB, 0xAA77AA, 0x996699, 0x885588
    };
    static final int[] BODY_ELECTRIC_RGB = {
            0x557788, 0x668899, 0x7799AA, 0x88AABB, 0x99BBCC,
            0x88AABB, 0x7799AA, 0x668899, 0x557788
    };
    static final int[] BODY_ICE_RGB = {
            0x7799AA, 0x88AABB, 0x99BBCC, 0xAACCDD, 0xBBDDEE,
            0xAACCDD, 0x99BBCC, 0x88AABB, 0x7799AA
    };
    static final int[] BODY_LAVA_RGB = {
            0x773322, 0x884433, 0x995544, 0xAA6655, 0xBB7766,
            0xAA6655, 0x995544, 0x884433, 0x773322
    };
    static final int[] BODY_CREAM_RGB = {
            0xD4C5A8, 0xD8C9AE, 0xDCCDB4, 0xE0D1BA, 0xE4D5C0,
            0xE0D1BA, 0xDCCDB4, 0xD8C9AE, 0xD4C5A8
    };
    static final int[] BODY_AMBER_RGB = {
            0xB89860, 0xC0A068, 0xC8A870, 0xD0B078, 0xD8B880,
            0xD0B078, 0xC8A870, 0xC0A068, 0xB89860
    };
    static final int[] BODY_SLATE_RGB = {
            0x788898, 0x8090A0, 0x8898A8, 0x90A0B0, 0x98A8B8,
            0x90A0B0, 0x8898A8, 0x8090A0, 0x788898
    };
    static final int[] BODY_ROSE_RGB = {
            0xB88888, 0xC09090, 0xC89898, 0xD0A0A0, 0xD8A8A8,
            0xD0A0A0, 0xC89898, 0xC09090, 0xB88888
    };
    static final int[] BODY_MOSS_RGB = {
            0x809870, 0x88A078, 0x90A880, 0x98B088, 0xA0B890,
            0x98B088, 0x90A880, 0x88A078, 0x809870
    };

    private static long colorInterval = 80;
    private static final long BODY_INTERVAL = 200;
    private static int colorDirection = 1;

    // ========== 多行同步支持 ==========

    private static long sharedTimestamp = -1;

    public static void lockTime() { sharedTimestamp = System.currentTimeMillis(); }
    public static void unlockTime() { sharedTimestamp = -1; }
    private static long getTime() {
        return sharedTimestamp >= 0 ? sharedTimestamp : System.currentTimeMillis();
    }

    public static void setSpeed(long ms) { colorInterval = Math.max(ms, 1); }
    public static void setDirection(int dir) { colorDirection = (dir >= 0) ? 1 : -1; }

    // ========== 彩虹 ==========

    public static Component createRainbowText(String text) {
        return perCharGradientRGB(text, RAINBOW_RGB);
    }

    public static Component wrapRainbow(Component component) {
        return component.copy().withStyle(Style.EMPTY.withColor(
                TextColor.fromRgb(RAINBOW_RGB[cycleIndex(RAINBOW_RGB.length) % RAINBOW_RGB.length])));
    }

    public static Component createObfuscatedRainbow(String text) {
        return Component.literal(text).withStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(RAINBOW_RGB[cycleIndex(RAINBOW_RGB.length)]))
                        .applyFormat(ChatFormatting.OBFUSCATED));
    }

    // ========== 终极彩虹（逐字流动） ==========

    public static Component createUltimateRainbow(String text) {
        return perCharGradientRGB(text, ULTIMATE_RAINBOW_RGB);
    }

    public static Component wrapUltimateRainbow(Component component) {
        return component.copy().withStyle(Style.EMPTY.withColor(
                TextColor.fromRgb(ULTIMATE_RAINBOW_RGB[cycleIndex(ULTIMATE_RAINBOW_RGB.length)])));
    }

    // ========== 金色系 ==========

    public static Component createGoldenText(String text) {
        return perCharGradientRGB(text, GOLDEN_RGB);
    }

    public static Component wrapGolden(Component component) {
        return component.copy().withStyle(Style.EMPTY.withColor(
                TextColor.fromRgb(GOLDEN_RGB[cycleIndex(GOLDEN_RGB.length) % GOLDEN_RGB.length])));
    }

    // ========== 火焰系 ==========

    public static Component createFireText(String text) {
        return perCharGradientRGB(text, FIRE_RGB);
    }

    public static Component wrapFire(Component component) {
        return component.copy().withStyle(Style.EMPTY.withColor(
                TextColor.fromRgb(FIRE_RGB[cycleIndex(FIRE_RGB.length) % FIRE_RGB.length])));
    }

    // ========== 水流系 ==========

    public static Component createWaterText(String text) {
        return perCharGradientRGB(text, WATER_RGB);
    }

    public static Component wrapWater(Component component) {
        return component.copy().withStyle(Style.EMPTY.withColor(
                TextColor.fromRgb(WATER_RGB[cycleIndex(WATER_RGB.length) % WATER_RGB.length])));
    }

    // ========== 魔法系 ==========

    public static Component createMagicText(String text) {
        return perCharGradientRGB(text, MAGIC_RGB);
    }

    public static Component wrapMagic(Component component) {
        return component.copy().withStyle(Style.EMPTY.withColor(
                TextColor.fromRgb(MAGIC_RGB[cycleIndex(MAGIC_RGB.length) % MAGIC_RGB.length])));
    }

    // ========== 自然系 ==========

    public static Component createNatureText(String text) {
        return perCharGradientRGB(text, NATURE_RGB);
    }

    public static Component wrapNature(Component component) {
        return component.copy().withStyle(Style.EMPTY.withColor(
                TextColor.fromRgb(NATURE_RGB[cycleIndex(NATURE_RGB.length) % NATURE_RGB.length])));
    }

    // ========== 电流系 ==========

    public static Component createElectricText(String text) {
        return perCharGradientRGB(text, ELECTRIC_RGB);
    }

    public static Component wrapElectric(Component component) {
        return component.copy().withStyle(Style.EMPTY.withColor(
                TextColor.fromRgb(ELECTRIC_RGB[cycleIndex(ELECTRIC_RGB.length) % ELECTRIC_RGB.length])));
    }

    // ========== 冰霜系 ==========

    public static Component createIceText(String text) {
        return perCharGradientRGB(text, ICE_RGB);
    }

    public static Component wrapIce(Component component) {
        return component.copy().withStyle(Style.EMPTY.withColor(
                TextColor.fromRgb(ICE_RGB[cycleIndex(ICE_RGB.length) % ICE_RGB.length])));
    }

    // ========== 熔岩系 ==========

    public static Component createLavaText(String text) {
        return perCharGradientRGB(text, LAVA_RGB);
    }

    public static Component wrapLava(Component component) {
        return component.copy().withStyle(Style.EMPTY.withColor(
                TextColor.fromRgb(LAVA_RGB[cycleIndex(LAVA_RGB.length) % LAVA_RGB.length])));
    }

    // ========== 日落系 ==========

    public static Component createSunsetText(String text) {
        return perCharGradientRGB(text, SUNSET_RGB);
    }

    public static Component wrapSunset(Component component) {
        return component.copy().withStyle(Style.EMPTY.withColor(
                TextColor.fromRgb(SUNSET_RGB[cycleIndex(SUNSET_RGB.length) % SUNSET_RGB.length])));
    }

    // ========== 极光系 ==========

    public static Component createAuroraText(String text) {
        return perCharGradientRGB(text, AURORA_RGB);
    }

    public static Component wrapAurora(Component component) {
        return component.copy().withStyle(Style.EMPTY.withColor(
                TextColor.fromRgb(AURORA_RGB[cycleIndex(AURORA_RGB.length) % AURORA_RGB.length])));
    }

    // ========== 猩红系 ==========

    public static Component createCrimsonText(String text) {
        return perCharGradientRGB(text, CRIMSON_RGB);
    }

    public static Component wrapCrimson(Component component) {
        return component.copy().withStyle(Style.EMPTY.withColor(
                TextColor.fromRgb(CRIMSON_RGB[cycleIndex(CRIMSON_RGB.length) % CRIMSON_RGB.length])));
    }

    // ========== 霓虹系 ==========

    public static Component createNeonText(String text) {
        return perCharGradientRGB(text, NEON_RGB);
    }

    public static Component wrapNeon(Component component) {
        return component.copy().withStyle(Style.EMPTY.withColor(
                TextColor.fromRgb(NEON_RGB[cycleIndex(NEON_RGB.length) % NEON_RGB.length])));
    }

    // ========== 樱花系 ==========

    public static Component createSakuraText(String text) {
        return perCharGradientRGB(text, SAKURA_RGB);
    }

    public static Component wrapSakura(Component component) {
        return component.copy().withStyle(Style.EMPTY.withColor(
                TextColor.fromRgb(SAKURA_RGB[cycleIndex(SAKURA_RGB.length) % SAKURA_RGB.length])));
    }

    public static Component createCosmicText(String text) { return perCharGradientRGB(text, COSMIC_RGB); }
    public static Component createVoidText(String text) { return perCharGradientRGB(text, VOID_RGB); }
    public static Component createJadeText(String text) { return perCharGradientRGB(text, JADE_RGB); }
    public static Component createPlasmaText(String text) { return perCharGradientRGB(text, PLASMA_RGB); }
    public static Component createStarlightText(String text) { return perCharGradientRGB(text, STARLIGHT_RGB); }
    public static Component createAbyssText(String text) { return perCharGradientRGB(text, ABYSS_RGB); }

    // ========== 正文柔和渐变（慢速 500ms/步，适合大段正文） ==========

    public static Component createBodyGolden(String text) { return perCharGradientRGBBody(text, BODY_GOLDEN_RGB); }
    public static Component createBodyFire(String text) { return perCharGradientRGBBody(text, BODY_FIRE_RGB); }
    public static Component createBodyWater(String text) { return perCharGradientRGBBody(text, BODY_WATER_RGB); }
    public static Component createBodyMagic(String text) { return perCharGradientRGBBody(text, BODY_MAGIC_RGB); }
    public static Component createBodyNature(String text) { return perCharGradientRGBBody(text, BODY_NATURE_RGB); }
    public static Component createBodyCrimson(String text) { return perCharGradientRGBBody(text, BODY_CRIMSON_RGB); }
    public static Component createBodySilver(String text) { return perCharGradientRGBBody(text, BODY_SILVER_RGB); }
    public static Component createBodySunset(String text) { return perCharGradientRGBBody(text, BODY_SUNSET_RGB); }
    public static Component createBodyAurora(String text) { return perCharGradientRGBBody(text, BODY_AURORA_RGB); }
    public static Component createBodyNeon(String text) { return perCharGradientRGBBody(text, BODY_NEON_RGB); }
    public static Component createBodyElectric(String text) { return perCharGradientRGBBody(text, BODY_ELECTRIC_RGB); }
    public static Component createBodyIce(String text) { return perCharGradientRGBBody(text, BODY_ICE_RGB); }
    public static Component createBodyLava(String text) { return perCharGradientRGBBody(text, BODY_LAVA_RGB); }
    public static Component createBodyCream(String text) { return perCharGradientRGBBody(text, BODY_CREAM_RGB); }
    public static Component createBodyAmber(String text) { return perCharGradientRGBBody(text, BODY_AMBER_RGB); }
    public static Component createBodySlate(String text) { return perCharGradientRGBBody(text, BODY_SLATE_RGB); }
    public static Component createBodyRose(String text) { return perCharGradientRGBBody(text, BODY_ROSE_RGB); }
    public static Component createBodyMoss(String text) { return perCharGradientRGBBody(text, BODY_MOSS_RGB); }



    // ========== 自定义逐字渐变（ChatFormatting 版，保留兼容） ==========

    public static Component createCustomGradient(String text, ChatFormatting[] colors, int intervalMs, int mask) {
        int len = colors.length;
        long now = System.currentTimeMillis();
        int offset = (int) ((now & mask) / Math.max(intervalMs, 1)) % len;
        MutableComponent result = Component.literal(String.valueOf(text.charAt(0)))
                .withStyle(colors[offset % len]);
        for (int i = 1; i < text.length(); i++) {
            result.append(Component.literal(String.valueOf(text.charAt(i)))
                    .withStyle(colors[(offset + i) % len]));
        }
        return result;
    }

    public static Component createCustomGradientRGB(String text, int[] rgbColors, int intervalMs, int dir) {
        int len = rgbColors.length;
        long now = System.currentTimeMillis();
        int offset = (int) ((now % (Math.max(intervalMs, 1) * len)) / Math.max(intervalMs, 1)) % len;
        MutableComponent result = Component.literal(String.valueOf(text.charAt(0)))
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgbColors[rgbIndex(offset, 0, len, dir)])));
        for (int i = 1; i < text.length(); i++) {
            result.append(Component.literal(String.valueOf(text.charAt(i)))
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgbColors[rgbIndex(offset, i, len, dir)]))));
        }
        return result;
    }

    // ========== 核心渲染 ==========

    /** 自适应间隔：文本越长流动越快，短文本慢速展示颜色过渡 */
    private static long calcInterval(int textLen) {
        if (colorInterval != 80) return colorInterval;
        if (textLen <= 5) return 80;
        if (textLen <= 8) return 60;
        if (textLen <= 10) return 50;
        return 65;
    }

    /** 获取有效间隔：自定义速度时全统一，否则 body 慢速 / 非 body 自适应 */
    private static long getEffectiveInterval(boolean isBody, int textLen) {
        if (colorInterval != 80) return colorInterval;
        return isBody ? BODY_INTERVAL : calcInterval(Math.max(textLen, 1));
    }

    private static Component perCharGradientRGB(String text, int[] rgbColors) {
        if (text == null || text.isEmpty()) return Component.literal("");
        int len = rgbColors.length;
        long now = getTime();
        long interval = calcInterval(text.length());
        long cycle = interval * rgbColors.length;
        long masked = now % (cycle > 0 ? cycle : 1);
        double rawPhase = (double) masked / interval;
        int intPhase = (int) rawPhase;
        double frac = rawPhase - intPhase;
        MutableComponent result = Component.literal("");
        int dir = colorDirection;
        for (int i = 0; i < text.length(); i++) {
            int idx1 = (intPhase + dir * i) % len;
            if (idx1 < 0) idx1 += len;
            int idx2 = (idx1 + 1) % len;
            int c1 = rgbColors[idx1], c2 = rgbColors[idx2];
            int r = (int) (((c1 >> 16) & 0xFF) * (1 - frac) + ((c2 >> 16) & 0xFF) * frac);
            int g = (int) (((c1 >> 8) & 0xFF) * (1 - frac) + ((c2 >> 8) & 0xFF) * frac);
            int b = (int) ((c1 & 0xFF) * (1 - frac) + (c2 & 0xFF) * frac);
            int interpolated = (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
            result.append(Component.literal(String.valueOf(text.charAt(i)))
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(interpolated))));
        }
        return result;
    }

    // ========== 正文慢速渐变渲染（500ms/步，柔和配色） ==========

    private static Component perCharGradientRGBBody(String text, int[] rgbColors) {
        if (text == null || text.isEmpty()) return Component.literal("");
        int len = rgbColors.length;
        long now = getTime();
        long effInterval = getEffectiveInterval(true, text.length());
        long cycle = effInterval * len;
        long masked = now % (cycle > 0 ? cycle : 1);
        double rawPhase = (double) masked / effInterval;
        int intPhase = (int) rawPhase;
        double frac = rawPhase - intPhase;
        MutableComponent result = Component.literal("");
        int dir = colorDirection;
        for (int i = 0; i < text.length(); i++) {
            int idx1 = (intPhase + dir * i) % len;
            if (idx1 < 0) idx1 += len;
            int idx2 = (idx1 + 1) % len;
            int c1 = rgbColors[idx1], c2 = rgbColors[idx2];
            int r = (int) (((c1 >> 16) & 0xFF) * (1 - frac) + ((c2 >> 16) & 0xFF) * frac);
            int g = (int) (((c1 >> 8) & 0xFF) * (1 - frac) + ((c2 >> 8) & 0xFF) * frac);
            int b = (int) ((c1 & 0xFF) * (1 - frac) + (c2 & 0xFF) * frac);
            int interpolated = (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
            result.append(Component.literal(String.valueOf(text.charAt(i)))
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(interpolated))));
        }
        return result;
    }

    private static int rgbIndex(int offset, int i, int len, int dir) {
        int idx = (offset + dir * i) % len;
        if (idx < 0) idx += len;
        return idx;
    }

    // ========== 多行渐变同步 ==========

    public static Component[] createMultiLineText(String[] lines, int[] rgbColors) {
        int totalLen = 0;
        for (String l : lines) totalLen += l.length();
        long interval = calcInterval(Math.max(totalLen, 1));
        long now = getTime();
        long cycle = interval * rgbColors.length;
        long masked = now % (cycle > 0 ? cycle : 1);
        double rawPhase = (double) masked / interval;
        int intPhase = (int) rawPhase;
        double frac = rawPhase - intPhase;
        Component[] results = new Component[lines.length];
        int pos = 0;
        int dir = colorDirection;
        for (int l = 0; l < lines.length; l++) {
            String text = lines[l].replace("——", "----");
            if (text.isEmpty()) { results[l] = Component.literal(" "); continue; }
            MutableComponent line = Component.literal("");
            for (int i = 0; i < text.length(); i++) {
                int idx1 = (intPhase + dir * (pos + i)) % rgbColors.length;
                if (idx1 < 0) idx1 += rgbColors.length;
                int idx2 = (idx1 + 1) % rgbColors.length;
                int c1 = rgbColors[idx1], c2 = rgbColors[idx2];
                int r = (int) (((c1 >> 16) & 0xFF) * (1 - frac) + ((c2 >> 16) & 0xFF) * frac);
                int g = (int) (((c1 >> 8) & 0xFF) * (1 - frac) + ((c2 >> 8) & 0xFF) * frac);
                int b = (int) ((c1 & 0xFF) * (1 - frac) + (c2 & 0xFF) * frac);
                int interpolated = (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
                line.append(Component.literal(String.valueOf(text.charAt(i)))
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(interpolated))));
            }
            results[l] = line;
            pos += text.length();
        }
        return results;
    }

    /**
     * 获取动态色板颜色（time-based 插值）。
     * 与 perCharGradientRGB 相同的算法，返回单个字符的颜色值。
     *
     * @param palette   颜色数组
     * @param charIndex 当前字符索引
     * @param totalLen  文本总长度（用于自适应间隔）
     * @param isBody    是否为正文慢速样式
     * @return ARGB 颜色值
     */
    public static int getDynamicColor(int[] palette, int charIndex, int totalLen, boolean isBody) {
        long interval = getEffectiveInterval(isBody, totalLen);
        long now = getTime();
        long cycle = interval * palette.length;
        long masked = now % (cycle > 0 ? cycle : 1);
        double rawPhase = (double) masked / interval;
        int intPhase = (int) rawPhase;
        double frac = rawPhase - intPhase;
        int len = palette.length;
        int dir = colorDirection;
        int idx1 = (intPhase + dir * charIndex) % len;
        if (idx1 < 0) idx1 += len;
        int idx2 = (idx1 + 1) % len;
        int c1 = palette[idx1], c2 = palette[idx2];
        int r = (int) (((c1 >> 16) & 0xFF) * (1 - frac) + ((c2 >> 16) & 0xFF) * frac);
        int g = (int) (((c1 >> 8) & 0xFF) * (1 - frac) + ((c2 >> 8) & 0xFF) * frac);
        int b = (int) ((c1 & 0xFF) * (1 - frac) + (c2 & 0xFF) * frac);
        return (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
    }

    public static int getPaletteEndColor(int[] rgbColors, int charCount) {
        long interval = calcInterval(Math.max(charCount, 1));
        int offset = (int) ((getTime() % (interval * rgbColors.length)) / interval) % rgbColors.length;
        return rgbColors[rgbIndex(offset, Math.max(charCount - 1, 0), rgbColors.length, colorDirection)];
    }

    public static int getPaletteStartColor(int[] rgbColors) {
        int offset = (int) ((getTime() % (calcInterval(1) * rgbColors.length)) / calcInterval(1)) % rgbColors.length;
        return rgbColors[rgbIndex(offset, 0, rgbColors.length, colorDirection)];
    }

    public static Component createGradientFiller(String text, int startColor, int endColor) {
        if (text == null || text.isEmpty()) return Component.literal(" ");
        int len = text.length();
        MutableComponent result = null;
        for (int i = 0; i < len; i++) {
            float t = len > 1 ? (float) i / (len - 1) : 0.5f;
            int r = (int) (((startColor >> 16) & 0xFF) * (1 - t) + ((endColor >> 16) & 0xFF) * t);
            int g = (int) (((startColor >> 8) & 0xFF) * (1 - t) + ((endColor >> 8) & 0xFF) * t);
            int b = (int) ((startColor & 0xFF) * (1 - t) + (endColor & 0xFF) * t);
            var part = Component.literal(String.valueOf(text.charAt(i)))
                    .withStyle(Style.EMPTY.withColor(TextColor.fromRgb((r << 16) | (g << 8) | b)));
            if (result == null) result = part;
            else result.append(part);
        }
        return result;
    }

    // ========== 工具 ==========

    private static int cycleIndex(int len) {
        return (int) ((getTime() % (Math.max(colorInterval, 1) * len)) / Math.max(colorInterval, 1)) % len;
    }

    public static Component createStyled(String text, String style) {
        if (style == null || text == null || text.isEmpty()) return Component.literal(text != null ? text : "");

        String s = style.toLowerCase(java.util.Locale.ROOT);
        String raw = style; // 保留原名用于 camelCase → snake_case fallback

        if ("obfuscatedRainbow".equals(s) || "obfuscatedrainbow".equals(s)) return createObfuscatedRainbow(text);

        var def = DShanhaiStyleRegistry.get(s);
        // camelCase → snake_case fallback：bodySilver 查不到时试 body_silver
        if (def == null) {
            String snake = raw.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
            if (!snake.equals(s)) def = DShanhaiStyleRegistry.get(snake);
        }
        if (def != null) {
            return switch (def.anim()) {
                case BREATHE -> perCharGradientRGBBreathe(text, def.rgb(), def.isBody());
                case SCAN -> perCharGradientRGBScan(text, def.rgb(), def.isBody());
                case STATIC -> perCharGradientRGBStatic(text, def.rgb());
                case TWINKLE -> perCharGradientRGBTwinkle(text, def.rgb(), def.isBody());
                case DISTORT -> perCharGradientRGBDistort(text, def.rgb(), def.isBody());
                case WOBBLE -> createWobbleText(text, def.rgb());
                default -> def.isBody() ? perCharGradientRGBBody(text, def.rgb()) : perCharGradientRGB(text, def.rgb());
            };
        }
        return createUltimateRainbow(text);
    }

    // ========== 呼吸动画 ==========

    private static Component perCharGradientRGBBreathe(String text, int[] rgb, boolean isBody) {
        long interval = getEffectiveInterval(isBody, text.length());
        double rawPhase = (double)(getTime() % (interval * rgb.length * 2)) / (interval);
        int phase = (int)rawPhase % rgb.length;
        int colorIdx = Math.min(phase, rgb.length - 1);
        MutableComponent result = Component.literal("");
        for (int i = 0; i < text.length(); i++) {
            int ci = Math.min(colorIdx, rgb.length - 1);
            result.append(Component.literal(String.valueOf(text.charAt(i)))
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb[ci]))));
        }
        return result;
    }

    // ========== 扫光动画 ==========

    private static Component perCharGradientRGBScan(String text, int[] rgb, boolean isBody) {
        long scanPeriod = 1200;
        double rawPhase = (double)(getTime() % scanPeriod) / (scanPeriod / text.length());
        int lightPos = (int)rawPhase % text.length();
        MutableComponent result = Component.literal("");
        for (int i = 0; i < text.length(); i++) {
            int dist = Math.abs(i - lightPos);
            int base = rgb[Math.max(0, rgb.length / 4)];
            int r = (base >> 16) & 0xFF;
            int g = (base >> 8) & 0xFF;
            int b = base & 0xFF;
            if (dist == 0) { r += 100; g += 100; b += 100; }
            else if (dist == 1) { r += 50; g += 50; b += 50; }
            int col = (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
            result.append(Component.literal(String.valueOf(text.charAt(i)))
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(col))));
        }
        return result;
    }

    // ========== 静态 ==========

    private static Component perCharGradientRGBStatic(String text, int[] rgb) {
        int mid = rgb.length / 2;
        MutableComponent result = Component.literal("");
        for (int i = 0; i < text.length(); i++) {
            result.append(Component.literal(String.valueOf(text.charAt(i)))
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb[mid]))));
        }
        return result;
    }

    // ========== 闪烁动画 ==========

    private static Component perCharGradientRGBTwinkle(String text, int[] rgb, boolean isBody) {
        long interval = getEffectiveInterval(isBody, text.length()) * (isBody ? 3 : 4);
        MutableComponent result = Component.literal("");
        long now = getTime();
        for (int i = 0; i < text.length(); i++) {
            double t = (double)((now + i * 3000L) % (interval * 4)) / (interval);
            double wave = Math.abs(Math.sin(t * 0.5));
            int idx = (int)(wave * (rgb.length - 1));
            idx = Math.min(idx, rgb.length - 1);
            result.append(Component.literal(String.valueOf(text.charAt(i)))
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb[idx]))));
        }
        return result;
    }

    // ========== 乱码扭曲动画 ==========

    private static Component perCharGradientRGBDistort(String text, int[] rgb, boolean isBody) {
        int len = rgb.length;
        long now = getTime();
        long fastInterval = 50;
        MutableComponent result = Component.literal("");
        for (int i = 0; i < text.length(); i++) {
            long charPhase = (now + i * 137L) / fastInterval;
            int idx = (int)(charPhase % len);
            idx = Math.abs(idx) % len;
            boolean obfuscated = ((now + i * 131L) % 400L) < 120L;
            var style = Style.EMPTY.withColor(TextColor.fromRgb(rgb[idx]));
            if (obfuscated) style = style.withObfuscated(true);
            result.append(Component.literal(String.valueOf(text.charAt(i))).withStyle(style));
        }
        return result;
    }

    // ========== 居中逐字揭示 ==========

    // ========== 渲染级居中支持 ==========

    /** 需要居中的目标文本集合（用于 mixin 匹配行身份） */
    public static final ThreadLocal<java.util.Set<String>> CENTER_TARGETS = ThreadLocal.withInitial(java.util.HashSet::new);
    /** tooltip 最大行宽（像素），由 handler 在构建 tooltip 时计算 */
    public static final ThreadLocal<Integer> CENTER_CONTAINER_WIDTH = ThreadLocal.withInitial(() -> 0);

    /**
     * 创建逐字揭示文本（左对齐，mixin 在渲染时自动居中）。
     * 未揭示位置显示为全角空格。
     * @param text 完整文本
     * @param revealed 已揭示字符数
     * @param rgb 色板
     * @param obfuscatedTail 末尾混淆字符数
     */
    public static Component createRevealText(String text, int revealed, int[] rgb, int obfuscatedTail) {
        if (text == null || text.isEmpty()) return Component.literal("");
        int len = rgb.length;
        int mid = len / 2;
        int total = text.length();
        long now = getTime();
        // 使用 calcInterval 实现颜色流动（比 BODY_INTERVAL 更快更顺滑）
        long phaseInterval = calcInterval(Math.max(total, 1));
        int phase = (int)((now % (phaseInterval * len)) / phaseInterval);
        MutableComponent result = Component.literal("");
        for (int pos = 0; pos < total; pos++) {
            if (pos < revealed) {
                int colorIdx = (phase + pos) % len;
                if (colorIdx < 0) colorIdx += len;
                var style = Style.EMPTY.withColor(TextColor.fromRgb(rgb[colorIdx]));
                if (obfuscatedTail > 0 && pos >= revealed - obfuscatedTail) {
                    long decodeFlip = (long)(System.currentTimeMillis() * 0.005);
                    if (((pos + decodeFlip) % 3) <= 0) style = style.withObfuscated(true);
                }
                result.append(Component.literal(String.valueOf(text.charAt(pos))).withStyle(style));
            } else {
                result.append(Component.literal("　"));
            }
        }
        return result;
    }

    public static Component createRevealText(String text, int revealed, String style, int obfuscatedTail) {
        int[] rgb = DShanhaiStyleRegistry.getRGB(style);
        return createRevealText(text, revealed, rgb, obfuscatedTail);
    }

    // ========== 上下波动文本（参考 Goety Revelation MinecraftFont.renderEden()） ==========

    /**
     * 创建逐字符 Y 轴偏移的 wobble 文本。
     * 激活 WobbleFontMixin，仅该文本会触发逐字符波动效果。
     * @param text 文本
     * @param rgb  色板
     */
    public static Component createWobbleText(String text, int[] rgb) {
        Component c = perCharGradientRGB(text, rgb);
        WOBBLE_OFFSETS.set(new float[1]);
        String t = text;
        WOBBLE_FILTER.set(s -> s.equals(t));
        return c;
    }

    /**
     * 创建 wobble 文本（通过样式名查色板）。
     */
    public static Component createWobbleText(String text, String style) {
        int[] rgb = DShanhaiStyleRegistry.getRGB(style);
        return createWobbleText(text, rgb);
    }

    // ========== 工具提示辅助 ==========

    public static Component fromLegacy(String legacy) {
        if (legacy == null || legacy.isEmpty()) return Component.literal("");
        MutableComponent result = null;
        StringBuilder buf = new StringBuilder();
        ChatFormatting color = null;
        for (int i = 0; i < legacy.length(); i++) {
            char c = legacy.charAt(i);
            if (c == '§' && i + 1 < legacy.length()) {
                if (buf.length() > 0) {
                    var part = Component.literal(buf.toString());
                    if (color != null) part = part.withStyle(color);
                    if (result == null) result = part; else result.append(part);
                    buf = new StringBuilder();
                }
                ChatFormatting cf = ChatFormatting.getByCode(legacy.charAt(i + 1));
                if (cf != null && cf.isColor()) color = cf;
                else if (cf == ChatFormatting.RESET) color = null;
                i++;
            } else {
                buf.append(c);
            }
        }
        if (buf.length() > 0) {
            var part = Component.literal(buf.toString());
            if (color != null) part = part.withStyle(color);
            if (result == null) result = part; else result.append(part);
        }
        return result != null ? result : Component.literal("");
    }
}
