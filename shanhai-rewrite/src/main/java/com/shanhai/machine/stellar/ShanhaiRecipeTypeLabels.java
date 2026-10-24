package com.shanhai.machine.stellar;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 「配方种类」的<b>显示名</b>与<b>id 列表序列化</b>的纯逻辑核（C 刀 · B 段）。
 *
 * <h2>为什么单独一个类（与 {@code ShanhaiCircuitGrid} 同一条纪律）</h2>
 * 本类<b>不 import 任何 Minecraft / GTCEu / LDLib 类</b> ⇒ 可以被 {@code javac} 单独编译、
 * 在裸 JVM 上实跑断言（含负对照）。本轮的离线自证就是打在这里：
 * 5 级 lang 回退的<b>顺序</b>与<b>「全失败显示 path」</b>，以及 id 列表的 join/split 往返。
 *
 * <h2>5 级回退从哪来（上游原文，逐字照抄）</h2>
 * {@code upstream-RecipeTypePatternBufferPartMachine.java:2262 wildcardRecipeTypeDisplayName}：
 * <pre>
 *   String[] keys = {
 *       type.registryName.toLanguageKey(),                                  // ① = namespace + "." + path
 *       "gtceu." + path,                                                    // ②
 *       "gtceu.recipe_type." + path,                                        // ③
 *       "recipe_type." + path,                                              // ④
 *       "gtceu.recipe_type." + namespace + "." + path                       // ⑤
 *   };
 *   for (String key : keys) {
 *       String translated = Component.translatable(key).getString();
 *       if (!translated.equals(key)) return translated;                     // 第一个「翻译出来了」的就用
 *   }
 *   return path;                                                            // 全失败 ⇒ path
 * </pre>
 * ①的 {@code toLanguageKey()} 语义由 {@code javap -c ResourceLocation.toLanguageKey()} 实证：
 * 方法体只有 `namespace`、`path` 两个字段 + 一个 {@code makeConcatWithConstants}（{@code "\u0001.\u0002"}）
 * ⇒ 就是 {@code namespace + "." + path}，所以本类不需要 MC 类型也能把 ① 拼对。
 *
 * <p>⚠️ 「翻译出来了」的判据是<b>翻译结果 ≠ 键</b>：MC 对缺失的键原样返回键名，
 * 所以这条判据正好区分「有译文」与「没有译文」。<b>不能</b>用 {@code I18n.exists(...)}：
 * 那样在服务端（无语言文件）会让界面名字变来变去。
 */
public final class ShanhaiRecipeTypeLabels {

    /** 回退级数（①..⑤）。探针断言 = 5。 */
    public static final int FALLBACK_LEVELS = 5;

    /** 兜底文本（上游 {@code wildcardRecipeTypeDisplayName} 在 type/registryName 为 null 时返回它）。 */
    public static final String UNKNOWN = "unknown";

    /** id 列表的分隔符（同步字段用它拼成一行字符串）。 */
    public static final String ID_SEPARATOR = ",";

    /** 标签页下半组最多画几个命中物品（用户口径：前 16 个）。 */
    public static final int ICON_LIMIT = 16;

    private ShanhaiRecipeTypeLabels() {}

    /**
     * 5 个候选 lang 键，<b>顺序即优先级</b>。
     *
     * @param namespace 命名空间（{@code gtceu} 等）
     * @param path      路径（{@code assembler} 等）
     */
    public static String @NotNull [] languageKeys(@Nullable String namespace, @Nullable String path) {
        final String ns = namespace == null ? "" : namespace;
        final String p = path == null ? "" : path;
        return new String[] {
                // ① ResourceLocation.toLanguageKey()（javap 实证 = namespace + "." + path）
                ns + "." + p,
                // ② 上游的第二候选
                "gtceu." + p,
                // ③
                "gtceu.recipe_type." + p,
                // ④
                "recipe_type." + p,
                // ⑤
                "gtceu.recipe_type." + ns + "." + p
        };
    }

    /**
     * 5 级回退：第一个「翻译结果 ≠ 键」的译文；全失败 ⇒ {@code path}；path 也空 ⇒ {@link #UNKNOWN}。
     *
     * @param translator 键 → 译文（生产代码里是 {@code key -> Component.translatable(key).getString()}；
     *                   离线探针里是一张假表。传 {@code null} ⇒ 视为「一个都翻译不出来」，直接走兜底）
     */
    public static @NotNull String displayName(@Nullable String namespace,
                                              @Nullable String path,
                                              @Nullable Function<String, String> translator) {
        final String p = path == null ? "" : path;
        if (translator != null) {
            for (String key : languageKeys(namespace, p)) {
                final String translated = translator.apply(key);
                if (translated != null && !translated.equals(key)) {
                    return translated;
                }
            }
        }
        return p.isEmpty() ? UNKNOWN : p;
    }

    /**
     * 逗号分隔的 id 列表 → {@link List}（去掉空白与空项）。
     *
     * <p>用于把服务端算好的「宿主支持的配方类型」同步成一个 {@code String}，
     * 客户端再拆开画按钮（{@code @DescSynced} 只收简单类型，见机器侧字段注释）。
     */
    public static @NotNull List<String> splitIds(@Nullable String joined) {
        if (joined == null || joined.isEmpty()) {
            return List.of();
        }
        final List<String> out = new ArrayList<>();
        for (String part : joined.split(ID_SEPARATOR)) {
            final String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** {@link #splitIds(String)} 的反向：id 列表 → 逗号分隔（空列表 ⇒ {@code ""}）。 */
    public static @NotNull String joinIds(@Nullable List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(ID_SEPARATOR);
            }
            sb.append(id);
        }
        return sb.toString();
    }

    /** 从完整 id（{@code gtceu:assembler}）里取命名空间；没有冒号 ⇒ {@code gtceu}（与别名层同款默认值）。 */
    public static @NotNull String namespaceOf(@Nullable String id) {
        if (id == null) {
            return ShanhaiRecipeTypeIds.DEFAULT_NAMESPACE;
        }
        final int colon = id.indexOf(':');
        return colon <= 0 ? ShanhaiRecipeTypeIds.DEFAULT_NAMESPACE : id.substring(0, colon);
    }

    /** 从完整 id 里取路径部分；没有冒号 ⇒ 整串。 */
    public static @NotNull String pathOf(@Nullable String id) {
        if (id == null) {
            return "";
        }
        final int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }
}
