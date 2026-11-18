package com.dishanhai.gt_shanhai.common.saveedit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「维度 id → 磁盘根目录」的<b>唯一映射处</b>（纯 JDK：{@code java.nio.file.Path}）。
 *
 * <h2>一、为什么必须只有一处</h2>
 * 第四期起，一个存档里被改的东西分布在<b>四个按维度分开的目录</b>里（{@code region} /
 * {@code entities} / {@code poi} / {@code data}）。如果每类各自拼一次路径，迟早会出现
 * "方块改到了下界、实体却列的是主世界"这种半对不半对的错 —— 那类错在游戏里看起来只是
 * "没生效"，极难定位。所以本类把映射<b>写死一次</b>，别处一律调它。
 *
 * <h2>二、映射表（1.20.1 原版布局，逐条写死在这里）</h2>
 * <pre>
 *   minecraft:overworld    →  &lt;存档&gt;/                     （主世界没有自己的子目录！）
 *   minecraft:the_nether   →  &lt;存档&gt;/DIM-1/
 *   minecraft:the_end      →  &lt;存档&gt;/DIM1/
 *   &lt;ns&gt;:&lt;path&gt;            →  &lt;存档&gt;/dimensions/&lt;ns&gt;/&lt;path&gt;/
 *   ⇒ region   = 上面那个根 + /region
 *   ⇒ entities = 上面那个根 + /entities
 * </pre>
 * 🔴 主世界是<b>特例</b>：它的根就是存档根，而 {@code DIM-1}/{@code DIM1} 是原版硬编码的
 * 目录名（不是 {@code dimensions/minecraft/the_nether}）。判据见探针 c9 的 10 行映射表。
 *
 * <h2>三、别名</h2>
 * 面板上人会写 {@code overworld} / {@code nether} / {@code end}（不带命名空间），
 * 一律归一成 {@code minecraft:*}；空串也当主世界（= 面板默认）。其余写法必须是
 * {@code namespace:path} 形状，否则 {@link #normalize(String)} 返回 {@code null}（= 拒绝）。
 *
 * <h2>四、不在这里的东西</h2>
 * {@code advancements/} 与 {@code stats/} 是<b>按玩家</b>而不是按维度分的（在存档根下），
 * 见 {@link ShanhaiAdvancementEdit#fileOf(Path, String)} 与 {@link ShanhaiStatsEdit#fileOf(Path, String)}。
 * {@code poi/}、{@code data/}、{@code ftb*} 一律不在本功能范围内。
 */
public final class ShanhaiSaveEditDimension {

    /** 主世界。 */
    public static final String OVERWORLD = "minecraft:overworld";
    /** 下界。 */
    public static final String NETHER = "minecraft:the_nether";
    /** 末地。 */
    public static final String END = "minecraft:the_end";

    /** 下界目录名（原版硬编码，注意是 {@code DIM-1} 而不是 {@code DIM-1/region}）。 */
    public static final String NETHER_DIR = "DIM-1";
    /** 末地目录名（原版硬编码）。 */
    public static final String END_DIR = "DIM1";
    /** 模组维度的父目录。 */
    public static final String DIMENSIONS_DIR = "dimensions";

    /** 方块/区块目录名。 */
    public static final String REGION_DIR = "region";
    /** 实体目录名。 */
    public static final String ENTITIES_DIR = "entities";

    /**
     * 判据 c9 用的 10 条输入（**探针与文档共用这一份**，避免两处各写一套例子）。
     *
     * <p>前 9 条必须解析成功，第 10 条必须被拒绝（{@code null}）。
     */
    public static final List<String> MAPPING_SAMPLES = List.of(
            "minecraft:overworld",
            "overworld",
            "minecraft:the_nether",
            "the_nether",
            "minecraft:the_end",
            "the_end",
            "ad_astra:moon",
            "kubejs:void",
            "",
            "Not A Dim");

    private ShanhaiSaveEditDimension() {}

    // ================================================================== 归一化

    /**
     * 维度写法归一化。
     *
     * @return 三个内置维度之一、或合法的 {@code ns:path}；<b>不认识 ⇒ {@code null}</b>（调用方必须拒绝）
     */
    public static String normalize(String raw) {
        final String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return OVERWORLD;
        }
        switch (s.toLowerCase(Locale.ROOT)) {
            case "overworld":
            case "minecraft:overworld":
                return OVERWORLD;
            case "nether":
            case "the_nether":
            case "minecraft:the_nether":
            case "dim-1":                 // 磁盘上的目录名（人从存档目录里抄出来的那种写法）
                return NETHER;
            case "end":
            case "the_end":
            case "minecraft:the_end":
            case "dim1":                  // 同上
                return END;
            default:
                return ShanhaiPlayerDataEdit.isResourceLocation(s) ? s : null;
        }
    }

    /** 是不是三个内置维度之一（内置的走硬编码目录，其余走 {@code dimensions/}）。 */
    public static boolean isBuiltin(String dimension) {
        final String dim = normalize(dimension);
        return OVERWORLD.equals(dim) || NETHER.equals(dim) || END.equals(dim);
    }

    /** 三个内置维度 + 面板默认顺序。 */
    public static List<String> builtinIds() {
        return List.of(OVERWORLD, NETHER, END);
    }

    // ================================================================== 映射

    /**
     * 维度根目录（方块/实体的公共父目录）—— 🔴 <b>整个功能里唯一拼这一段的地方</b>。
     *
     * @return 维度写法不认识 / {@code worldDir} 为空 ⇒ {@code null}
     */
    public static Path root(Path worldDir, String dimension) {
        if (worldDir == null) {
            return null;
        }
        final String dim = normalize(dimension);
        if (dim == null) {
            return null;
        }
        if (OVERWORLD.equals(dim)) {
            return worldDir;
        }
        if (NETHER.equals(dim)) {
            return worldDir.resolve(NETHER_DIR);
        }
        if (END.equals(dim)) {
            return worldDir.resolve(END_DIR);
        }
        final int colon = dim.indexOf(':');
        if (colon <= 0 || colon == dim.length() - 1) {
            return null;
        }
        return worldDir.resolve(DIMENSIONS_DIR).resolve(dim.substring(0, colon)).resolve(dim.substring(colon + 1));
    }

    /** 维度下的 {@code region} 目录（{@code r.x.z.mca} 方块/区块）。 */
    public static Path regionDir(Path worldDir, String dimension) {
        final Path root = root(worldDir, dimension);
        return root == null ? null : root.resolve(REGION_DIR);
    }

    /** 维度下的 {@code entities} 目录（与 region 同布局，只是内容换成实体列表）。 */
    public static Path entitiesDir(Path worldDir, String dimension) {
        final Path root = root(worldDir, dimension);
        return root == null ? null : root.resolve(ENTITIES_DIR);
    }

    /** 某个区（region 坐标）在某个维度下的 {@code region} 文件（不保证存在）。 */
    public static Path regionFile(Path worldDir, String dimension, int x, int z) {
        final Path dir = regionDir(worldDir, dimension);
        return dir == null ? null : dir.resolve(fileNameOf(x, z));
    }

    /** 某个区（region 坐标）在某个维度下的 {@code entities} 文件（不保证存在）。 */
    public static Path entitiesFile(Path worldDir, String dimension, int regionX, int regionZ) {
        final Path dir = entitiesDir(worldDir, dimension);
        return dir == null ? null : dir.resolve(fileNameOf(regionX, regionZ));
    }

    /** {@code r.<x>.<z>.mca}（region 与 entities 用<b>同名</b>规则）。 */
    public static String fileNameOf(int regionX, int regionZ) {
        return "r." + regionX + "." + regionZ + ".mca";
    }

    /** 维度根目录相对存档根的写法（日志/回显用；主世界是 {@code .}）。 */
    public static String relativeRoot(String dimension) {
        final String dim = normalize(dimension);
        if (dim == null) {
            return com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.5eaf971f185bf2de");
        }
        if (OVERWORLD.equals(dim)) {
            return ".";
        }
        if (NETHER.equals(dim)) {
            return NETHER_DIR;
        }
        if (END.equals(dim)) {
            return END_DIR;
        }
        final int colon = dim.indexOf(':');
        return DIMENSIONS_DIR + "/" + dim.substring(0, colon) + "/" + dim.substring(colon + 1);
    }

    // ================================================================== 读数

    /**
     * 一行映射读数：{@code input → <相对存档根的目录>}，认不出来是 {@code REJECT}。
     *
     * <p>判据 c9 逐条打印它，人一眼能对账。
     */
    public static String mappingRow(String input) {
        final String dim = normalize(input);
        if (dim == null) {
            return "\"" + (input == null ? "" : input) + "\" → REJECT（unknown dimension）";
        }
        return "\"" + (input == null ? "" : input) + "\" → " + dim + " ⇒ " + relativeRoot(dim);
    }

    /** 全部 10 条样例的读数（装配时进日志）。 */
    public static List<String> mappingRows() {
        final List<String> out = new ArrayList<>();
        for (String s : MAPPING_SAMPLES) {
            out.add(mappingRow(s));
        }
        return out;
    }

    @Override
    public String toString() {
        return "ShanhaiSaveEditDimension[" + MAPPING_SAMPLES.size() + " samples]";
    }
}
