package com.dishanhai.gt_shanhai.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static com.dishanhai.gt_shanhai.api.DShanhaiTextUtil.AnimType.*;

/**
 * 山海动态文本样式注册表 — 集中定义所有样式。
 *
 * 所有颜色样式在此统一注册，Java 和 KubeJS 侧共享此数据。
 * 新增样式可在此静态块中添加，也可在运行时通过 register() 动态注册。
 */
public final class DShanhaiStyleRegistry {

    private DShanhaiStyleRegistry() {}

    /** 一条样式定义 */
    public record StyleDef(
            String name,
            int[] rgb,
            boolean isBody,
            DShanhaiTextUtil.AnimType anim,
            String modSource,
            String description
    ) {
        /** 简写构造（modSource 默认为 "gt_shanhai"，description 默认为空） */
        public StyleDef(String name, int[] rgb, boolean isBody, DShanhaiTextUtil.AnimType anim) {
            this(name, rgb, isBody, anim, "gt_shanhai", "");
        }
    }

    private static final Map<String, StyleDef> STYLES = new LinkedHashMap<>();

    static {
        // ===== 标题样式（快速 自适应间隔） =====
        reg("ultimate",      DShanhaiTextUtil.ULTIMATE_RAINBOW_RGB, false, CYCLE, "全色域终极彩虹");
        reg("rainbow",       DShanhaiTextUtil.RAINBOW_RGB, false, CYCLE, "7色彩虹");
        reg("obfuscatedrainbow", DShanhaiTextUtil.RAINBOW_RGB, false, CYCLE, "混淆彩虹 FCS 别名");
        reg("obfuscatedRainbow", DShanhaiTextUtil.RAINBOW_RGB, false, CYCLE, "混淆彩虹 FCS 别名");
        reg("golden",        DShanhaiTextUtil.GOLDEN_RGB, false, CYCLE, "金色系");
        reg("fire",          DShanhaiTextUtil.FIRE_RGB, false, CYCLE, "火焰系");
        reg("water",         DShanhaiTextUtil.WATER_RGB, false, CYCLE, "水流系");
        reg("magic",         DShanhaiTextUtil.MAGIC_RGB, false, CYCLE, "魔法系");
        reg("nature",        DShanhaiTextUtil.NATURE_RGB, false, CYCLE, "自然系");
        reg("electric",      DShanhaiTextUtil.ELECTRIC_RGB, false, CYCLE, "电流系");
        reg("ice",           DShanhaiTextUtil.ICE_RGB, false, CYCLE, "冰霜系");
        reg("lava",          DShanhaiTextUtil.LAVA_RGB, false, CYCLE, "熔岩系");
        reg("sunset",        DShanhaiTextUtil.SUNSET_RGB, false, CYCLE, "日落系（鱼肚白）");
        reg("aurora",        DShanhaiTextUtil.AURORA_RGB, false, CYCLE, "极光系");
        reg("crimson",       DShanhaiTextUtil.CRIMSON_RGB, false, CYCLE, "猩红系");
        reg("neon",          DShanhaiTextUtil.NEON_RGB, false, CYCLE, "霓虹系");
        reg("sakura",        DShanhaiTextUtil.SAKURA_RGB, false, CYCLE, "樱花系");
        reg("cosmic",        DShanhaiTextUtil.COSMIC_RGB, false, CYCLE, "宇宙星云");
        reg("void",          DShanhaiTextUtil.VOID_RGB, false, CYCLE, "虚空紫黑");
        reg("jade",          DShanhaiTextUtil.JADE_RGB, false, CYCLE, "青玉翠光");
        reg("plasma",        DShanhaiTextUtil.PLASMA_RGB, false, CYCLE, "等离子辉光");
        reg("starlight",     DShanhaiTextUtil.STARLIGHT_RGB, false, CYCLE, "星辉白金");
        reg("abyss",         DShanhaiTextUtil.ABYSS_RGB, false, CYCLE, "深渊蓝光");

        // ===== 正文柔和样式（慢速 200ms） =====
        reg("body_golden",   DShanhaiTextUtil.BODY_GOLDEN_RGB, true, CYCLE, "正文·金色");
        reg("body_fire",     DShanhaiTextUtil.BODY_FIRE_RGB, true, CYCLE, "正文·火焰");
        reg("body_water",    DShanhaiTextUtil.BODY_WATER_RGB, true, CYCLE, "正文·水流");
        reg("body_magic",    DShanhaiTextUtil.BODY_MAGIC_RGB, true, CYCLE, "正文·魔法");
        reg("body_nature",   DShanhaiTextUtil.BODY_NATURE_RGB, true, CYCLE, "正文·自然");
        reg("body_crimson",  DShanhaiTextUtil.BODY_CRIMSON_RGB, true, CYCLE, "正文·猩红");
        reg("body_silver",   DShanhaiTextUtil.BODY_SILVER_RGB, true, CYCLE, "正文·银色");
        reg("body_sunset",   DShanhaiTextUtil.BODY_SUNSET_RGB, true, CYCLE, "正文·日落");
        reg("body_aurora",   DShanhaiTextUtil.BODY_AURORA_RGB, true, CYCLE, "正文·极光");
        reg("body_neon",     DShanhaiTextUtil.BODY_NEON_RGB, true, CYCLE, "正文·霓虹");
        reg("body_electric", DShanhaiTextUtil.BODY_ELECTRIC_RGB, true, CYCLE, "正文·电流");
        reg("body_ice",      DShanhaiTextUtil.BODY_ICE_RGB, true, CYCLE, "正文·寒冰");
        reg("body_lava",     DShanhaiTextUtil.BODY_LAVA_RGB, true, CYCLE, "正文·熔岩");
        reg("body_cream",    DShanhaiTextUtil.BODY_CREAM_RGB, true, CYCLE, "正文·奶油");
        reg("body_amber",    DShanhaiTextUtil.BODY_AMBER_RGB, true, CYCLE, "正文·琥珀");
        reg("body_slate",    DShanhaiTextUtil.BODY_SLATE_RGB, true, CYCLE, "正文·灰板岩");
        reg("body_rose",     DShanhaiTextUtil.BODY_ROSE_RGB, true, CYCLE, "正文·玫瑰");
        reg("body_moss",     DShanhaiTextUtil.BODY_MOSS_RGB, true, CYCLE, "正文·苔藓");

        // ===== 动画效果变体 =====
        regVariant("body_cream", DShanhaiTextUtil.BODY_CREAM_RGB, true, BREATHE);
        regVariant("body_cream", DShanhaiTextUtil.BODY_CREAM_RGB, true, SCAN);
        regVariant("body_cream", DShanhaiTextUtil.BODY_CREAM_RGB, true, STATIC);
        regVariant("body_cream", DShanhaiTextUtil.BODY_CREAM_RGB, true, TWINKLE);
        regVariant("golden",     DShanhaiTextUtil.GOLDEN_RGB, false, BREATHE);
        regVariant("golden",     DShanhaiTextUtil.GOLDEN_RGB, false, STATIC);

        // ===== 扭曲变形变体 =====
        regVariant("ultimateRainbow", DShanhaiTextUtil.ULTIMATE_RAINBOW_RGB, false, DISTORT);
        regVariant("body_cream",      DShanhaiTextUtil.BODY_CREAM_RGB, true, DISTORT);
        regVariant("body_silver",     DShanhaiTextUtil.BODY_SILVER_RGB, true, DISTORT);

        // ===== 上下波动变体 =====
        regVariant("ultimateRainbow", DShanhaiTextUtil.ULTIMATE_RAINBOW_RGB, false, WOBBLE);
        regVariant("body_cream",      DShanhaiTextUtil.BODY_CREAM_RGB, true, WOBBLE);
        regVariant("body_silver",     DShanhaiTextUtil.BODY_SILVER_RGB, true, WOBBLE);
        regVariant("golden",          DShanhaiTextUtil.GOLDEN_RGB, false, WOBBLE);
        regVariant("fire",            DShanhaiTextUtil.FIRE_RGB, false, WOBBLE);
    }

    // ========== 注册（static 块内用） ==========

    /** 核心注册（static block 专用，不暴露） */
    private static void reg(String name, int[] rgb, boolean isBody, DShanhaiTextUtil.AnimType anim) {
        STYLES.put(name, new StyleDef(name, rgb, isBody, anim));
        if (isBody) {
            STYLES.put(name.replace("body_", "body"), new StyleDef(name, rgb, true, anim));
        }
    }

    /** 带描述的注册（static block 专用） */
    private static void reg(String name, int[] rgb, boolean isBody, DShanhaiTextUtil.AnimType anim, String desc) {
        STYLES.put(name, new StyleDef(name, rgb, isBody, anim, "gt_shanhai", desc));
        if (isBody) {
            STYLES.put(name.replace("body_", "body"), new StyleDef(name, rgb, true, anim, "gt_shanhai", desc));
        }
    }

    /** 变体注册：baseName_animName（static block 专用） */
    private static void regVariant(String baseName, int[] rgb, boolean isBody, DShanhaiTextUtil.AnimType anim) {
        String fullName = (baseName + "_" + anim.name().toLowerCase(Locale.ROOT)).toLowerCase(Locale.ROOT);
        STYLES.put(fullName, new StyleDef(fullName, rgb, isBody, anim));
    }

    // ========== 公共运行时注册 API ==========

    /**
     * 运行时注册自定义样式（可从 KubeJS 或任意 Java 类调用）。
     * 同名已存在时不会覆盖，返回已有定义。
     *
     * @param name   样式名（不区分大小写，自动转小写）
     * @param rgb    RGB 色板数组
     * @param isBody 是否为正文样式（慢速）
     * @param anim   动画类型
     * @param modSource 来源模组 ID
     * @param description 用途描述
     * @return 注册成功返回新定义，已存在返回已有定义
     */
    public static StyleDef register(String name, int[] rgb, boolean isBody,
                                     DShanhaiTextUtil.AnimType anim,
                                     String modSource, String description) {
        String key = name.toLowerCase(Locale.ROOT);
        if (STYLES.containsKey(key)) {
            return STYLES.get(key);
        }
        var def = new StyleDef(key, rgb, isBody, anim, modSource, description);
        STYLES.put(key, def);
        if (isBody) {
            STYLES.put(key.replace("body_", "body"), def);
        }
        return def;
    }

    /** 运行时注册（无 modSource/description，自动填充） */
    public static StyleDef register(String name, int[] rgb, boolean isBody, DShanhaiTextUtil.AnimType anim) {
        return register(name, rgb, isBody, anim, "runtime", "");
    }

    /**
     * 批量注册动画变体：为指定色板自动生成全部动画变体。
     * 变体名格式：{baseName}_{breathe|scan|static|twinkle|distort}
     *
     * @param baseName 基础样式名（如 "body_cream"、"golden"）
     * @param rgb      RGB 色板
     * @param isBody   是否为正文
     * @param anims    要生成的动画类型（不传则生成全部 5 种）
     * @return 实际注册数（不含已存在的）
     */
    public static int registerVariants(String baseName, int[] rgb, boolean isBody,
                                        DShanhaiTextUtil.AnimType... anims) {
        DShanhaiTextUtil.AnimType[] types;
        if (anims == null || anims.length == 0) {
            types = new DShanhaiTextUtil.AnimType[]{BREATHE, SCAN, STATIC, TWINKLE, DISTORT};
        } else {
            types = anims;
        }
        int count = 0;
        for (var anim : types) {
            String fullName = baseName + "_" + anim.name().toLowerCase(Locale.ROOT);
            if (!STYLES.containsKey(fullName)) {
                STYLES.put(fullName, new StyleDef(fullName, rgb, isBody, anim));
                count++;
            }
        }
        return count;
    }

    // ========== 查询 API ==========

    /** 获取样式定义 */
    public static StyleDef get(String name) {
        if (name == null) return null;
        return STYLES.get(name.toLowerCase(Locale.ROOT));
    }

    /** 获取色板 RGB 数组 */
    public static int[] getRGB(String name) {
        StyleDef def = get(name);
        return def != null ? def.rgb() : DShanhaiTextUtil.ULTIMATE_RAINBOW_RGB;
    }

    /** 判断是否为正文样式 */
    public static boolean isBody(String name) {
        StyleDef def = get(name);
        return def != null && def.isBody();
    }

    /** 判断样式是否自带混淆语义。 */
    public static boolean isObfuscatedStyle(String name) {
        if (name == null) return false;
        String key = name.toLowerCase(Locale.ROOT);
        return "obfuscatedrainbow".equals(key);
    }

    /** 获取所有注册的样式名（用于 KubeJS 枚举） */
    public static String[] getAllNames() {
        List<String> names = new ArrayList<>();
        for (StyleDef def : STYLES.values()) {
            if (!def.name().startsWith("body") || def.name().startsWith("body_")) {
                if (!names.contains(def.name())) names.add(def.name());
            }
        }
        return names.toArray(new String[0]);
    }

    /** 获取所有标题样式名 */
    public static String[] getHeadlineNames() {
        List<String> names = new ArrayList<>();
        for (StyleDef def : STYLES.values()) {
            if (!def.isBody() && !names.contains(def.name())) names.add(def.name());
        }
        return names.toArray(new String[0]);
    }

    /** 获取所有正文样式名 */
    public static String[] getBodyNames() {
        List<String> names = new ArrayList<>();
        for (StyleDef def : STYLES.values()) {
            if (def.isBody() && def.name().startsWith("body_") && !names.contains(def.name())) names.add(def.name());
        }
        return names.toArray(new String[0]);
    }

    /** 获取全部 StyleDef（用于遍历/筛选） */
    public static Collection<StyleDef> getAll() {
        return STYLES.values();
    }

    /** 按动画类型筛选样式 */
    public static List<StyleDef> getByAnim(DShanhaiTextUtil.AnimType anim) {
        List<StyleDef> result = new ArrayList<>();
        for (StyleDef def : STYLES.values()) {
            if (def.anim() == anim && !result.contains(def)) {
                result.add(def);
            }
        }
        return result;
    }

    /** 按色板长度范围筛选样式 */
    public static List<StyleDef> getByPaletteSize(int min, int max) {
        List<StyleDef> result = new ArrayList<>();
        for (StyleDef def : STYLES.values()) {
            int len = def.rgb().length;
            if (len >= min && len <= max && !result.contains(def)) {
                result.add(def);
            }
        }
        return result;
    }

    /** 是否有指定样式 */
    public static boolean has(String name) {
        return name != null && STYLES.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /** 获取注册总数 */
    public static int count() {
        return STYLES.size();
    }

    // ========== 移除 ==========

    /** 移除指定样式（运行时动态注册的样式可移除） */
    public static boolean remove(String name) {
        if (name == null) return false;
        StyleDef removed = STYLES.remove(name.toLowerCase(Locale.ROOT));
        if (removed != null && removed.isBody()) {
            STYLES.remove(name.replace("body_", "body"));
        }
        return removed != null;
    }

    // ========== KubeJS 绑定 ==========

    /**
     * KubeJS（Rhino）友好入口：逐个传参，自动类型转换。
     *
     * KubeJS 调用示例：
     * <pre>
     * var DShanhaiStyleRegistry = Java.loadClass('com.dishanhai.gt_shanhai.api.DShanhaiStyleRegistry');
     * var AnimType = Java.loadClass('com.dishanhai.gt_shanhai.api.DShanhaiTextUtil$AnimType');
     * // 注册
     * DShanhaiStyleRegistry.kjsRegister('my_style', [0xFF0000, 0x00FF00], false, AnimType.CYCLE);
     * // 查询
     * var names = DShanhaiStyleRegistry.kjsGetAllNames();
     * </pre>
     */
    public static StyleDef kjsRegister(String name, int[] rgb, boolean isBody, DShanhaiTextUtil.AnimType anim) {
        return register(name, rgb, isBody, anim, "kubejs", "KubeJS dynamic");
    }

    public static String[] kjsGetAllNames() { return getAllNames(); }
    public static String[] kjsGetHeadlineNames() { return getHeadlineNames(); }
    public static String[] kjsGetBodyNames() { return getBodyNames(); }
    public static int kjsCount() { return count(); }
}
