package com.shanhai.machine.stellar;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 「样板 NBT 原文」的<b>纯格式化核</b> —— <b>零 Minecraft / GT / AE 依赖</b>，
 * 可以被 {@code javac} 单独编译、在裸 JVM 上实跑断言（与 {@link ShanhaiRecipeTypeLabels} 同一条纪律）。
 *
 * <h2>一、为什么需要它（本轮的实机现象）</h2>
 * <pre>
 *   17:35:26 [SHANHAI-STELLAR] XRECIPE slot=3 claimOut=shanhai:light_voyagex8
 *            slotType=none execRecipe=null execType=none host=single claimOk=true verdict=NO_BIND
 *   17:35:31 [SHANHAI-WHY] #6 产物=shanhai:light_voyage × 8 声明类型=unknown
 * </pre>
 * 玩家把样板重新编码后，GUI 上的类型胶囊明明写着「原初因果编织」，而读取层拿到的是
 * {@code none / unknown}。此时<b>唯一能定案</b>的事实是「这块样板的 NBT 里到底有哪些键、
 * 每个键的原值是什么」——只打解析后的结果（{@code none}）永远看不出这件事。
 *
 * <h2>二、两条输出（格式稳定，便于 grep 与断言）</h2>
 * <pre>
 *   [SHANHAI-STELLAR] XRECIPE_NBT slot=3 keys=[a, b, c] raw=[gt_shanhai_recipe_type='原初因果编织']
 *     · keys = 【全部】键名（超过 {@value #KEYS_MAX} 个时尾部写 …+N）
 *     · raw  = 最多 {@value #XRECIPE_RAW_MAX} 个键的原始值，单值超过 {@value #VALUE_MAX_CHARS} 字截断
 *
 *   … #6 产物=shanhai:light_voyage × 8 声明类型=unknown raw=[gt_shanhai_recipe_type='原初因果编织']
 *     · /shanhai why ③ 每块样板最多 {@value #WHY_RAW_MAX} 个键
 * </pre>
 *
 * <h2>三、🔴 本类只拼字符串</h2>
 * 它<b>不认识 NBT</b>（{@code CompoundTag} 在 {@link ShanhaiPatternTypeReader#rawPairs} 里读）、
 * <b>不判断类型对不对</b>、<b>不写任何东西</b>。前缀 {@code [SHANHAI-STELLAR]} 由
 * {@link ShanhaiStellarLog} 加（本类只产出 {@code XRECIPE_NBT …} 这一段正文），
 * 这样本类能在没有 MC 的 JVM 里被单独断言。
 */
public final class ShanhaiPatternRawNbt {

    /** {@code XRECIPE_NBT} 那一行里最多列几个键的原始值。 */
    public static final int XRECIPE_RAW_MAX = 3;

    /** {@code /shanhai why} ③ 每块样板最多列几个键的原始值。 */
    public static final int WHY_RAW_MAX = 2;

    /** 单条原始值最多保留多少个字符（超出截断并补 …）。 */
    public static final int VALUE_MAX_CHARS = 120;

    /** {@code keys=[…]} 最多列几个键名（其余折叠成 {@code …+N}）：防一块大 NBT 打出几 KB。 */
    public static final int KEYS_MAX = 24;

    /** {@code keys=[…]} 里「还有 N 个没列」的写法（探针断言用，别改格式）。 */
    public static final String ELIDED_PREFIX = "…+";

    /** 一个「键 → 原始值」对。 */
    public record Pair(@NotNull String key, @NotNull String value) {}

    private ShanhaiPatternRawNbt() {}

    /**
     * 截断到 {@code max} 个字符（{@code max <= 0} ⇒ 空串；不超长 ⇒ 原样）。
     *
     * <p>按 UTF-16 码元截断：中文与常见符号都只占 1 个码元，不会切出半个字符
     * （emoji 之类代理对不在本场景：NBT 里是物品 id / 中文名 / SNBT）。
     */
    public static @NotNull String truncate(@Nullable String raw, int max) {
        if (raw == null) {
            return "";
        }
        if (max <= 0) {
            return "";
        }
        return raw.length() <= max ? raw : raw.substring(0, max) + "…";
    }

    /** 单行化：{@code \r\n\t} 一律折成空格（日志行不许被 NBT 里的换行劈开）。 */
    public static @NotNull String oneLine(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            sb.append(c == '\r' || c == '\n' || c == '\t' ? ' ' : c);
        }
        return sb.toString();
    }

    /**
     * {@code keys=[a, b, c]}；超过 {@link #KEYS_MAX} 个 ⇒ 前 {@code KEYS_MAX} 个 + {@code …+N}。
     *
     * @param keys 键名（可为 null / 空表 ⇒ {@code keys=[]}）
     */
    public static @NotNull String keysText(@Nullable List<String> keys) {
        final StringBuilder sb = new StringBuilder("[");
        if (keys != null) {
            final int shown = Math.min(keys.size(), KEYS_MAX);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(oneLine(keys.get(i)));
            }
            if (keys.size() > shown) {
                sb.append(", ").append(ELIDED_PREFIX).append(keys.size() - shown);
            }
        }
        return sb.append(']').toString();
    }

    /**
     * {@code raw=[k='v', k2='v2']}（最多 {@code maxPairs} 个键；值截断到 {@link #VALUE_MAX_CHARS}）。
     *
     * <p>写法照用户给的样例：键名裸写、值用单引号包住。值里的单引号原样保留
     * （它只给人看，不参与任何解析）。
     */
    public static @NotNull String rawText(@Nullable List<Pair> pairs, int maxPairs) {
        final StringBuilder sb = new StringBuilder("[");
        if (pairs != null && maxPairs > 0) {
            final int shown = Math.min(pairs.size(), maxPairs);
            for (int i = 0; i < shown; i++) {
                final Pair pair = pairs.get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(oneLine(pair == null ? "" : pair.key())).append("='")
                        .append(truncate(oneLine(pair == null ? "" : pair.value()), VALUE_MAX_CHARS))
                        .append('\'');
            }
        }
        return sb.append(']').toString();
    }

    /**
     * {@code XRECIPE_NBT} 那一行的正文（<b>不含</b> {@code [SHANHAI-STELLAR]} 前缀）。
     *
     * @param slot  对外槽号（同 {@code XRECIPE} 行的 slot）
     * @param keys  全部键名
     * @param pairs 键→原始值（调用方已按「先类型相关键、再其余」排好）
     */
    public static @NotNull String body(int slot, @Nullable List<String> keys, @Nullable List<Pair> pairs) {
        return "XRECIPE_NBT slot=" + slot
                + " keys=" + keysText(keys)
                + " raw=" + rawText(pairs, XRECIPE_RAW_MAX);
    }
}
