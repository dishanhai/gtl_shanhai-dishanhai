package com.shanhai.machine.stellar;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「显示名 → 配方类型 id」的<b>反查核</b> —— <b>零 Minecraft / GT 依赖</b>
 * （只 {@code java.*}），可以被 {@code javac} 单独编译、在裸 JVM 上实跑断言。
 *
 * <h2>一、它解决什么（本轮实机现象）</h2>
 * 样板里存的可能<b>不是</b> {@code ns:path} 英文 id，而是<b>显示名</b>——
 * 例如玩家看到的那五个字：{@code 原初因果编织}。
 * {@link ShanhaiRecipeTypeIds#canonical(String)} 的老路径只认 id 形态
 * （补 {@code gtceu:} → {@code ResourceLocation.tryParse}），中文名<b>必然解析失败</b>
 * ⇒ 读出来就是 {@code none / unknown}（实机就是这个读数）。
 *
 * <h2>二、表从哪来（🔴 不许硬编码中文）</h2>
 * 本类<b>只有查表逻辑</b>，表由调用方 {@link ShanhaiRecipeTypeIds} 现建：
 * <pre>
 *   for (id : GTRegistries.RECIPE_TYPES.entries())        // 宿主真实存在的类型，一个不多一个不少
 *       name = ShanhaiRecipeTypeLabels.displayName(ns, path, key -> Component.translatable(key).getString())
 *       if (name 有译文 &amp;&amp; name ≠ path)  entries.add(new Entry(id.toString(), name))
 * </pre>
 * 也就是<b>复用本工程既有的 5 级 lang 回退链</b>（{@link ShanhaiRecipeTypeLabels}，上游
 * {@code wildcardRecipeTypeDisplayName} 逐字照抄），<b>没有一个字是手写的中文</b> ⇒
 * 上游改名/加类型/换语言时这张表自动跟着变。
 *
 * <h2>三、🔴 三条「不猜」的硬规则（缺一条就会造出假类型）</h2>
 * <ol>
 *   <li><b>只做整串相等</b>：不做前缀/包含/模糊匹配 —— 「原初因果编织矩阵」不等于
 *       「原初因果编织」，差一个字就是<b>没查到</b>；</li>
 *   <li><b>重名 ⇒ 放弃</b>：两个不同的 id 撞同一个显示名时，这个名字<b>整体作废</b>
 *       （查它返回空）。挑一个「最像的」正是本工程反复禁止的「猜」；</li>
 *   <li><b>查不到 ⇒ 空串</b>（由 {@link ShanhaiRecipeTypeIds#canonical} 继续走老路径 /
 *       最终返回 {@code ""} = NONE），<b>绝不回落到"表里第一个类型"</b>。</li>
 * </ol>
 *
 * <h2>四、名字的归一化（{@link #nameKey}）</h2>
 * 只做「同一个名字的不同写法」这一层，不做语义近似：
 * <ul>
 *   <li>{@link Normalizer.Form#NFKC}：全角字母/数字/空格折成半角（{@code ＡＢＣ１２３} ⇒ {@code ABC123}）；</li>
 *   <li>去掉成对的双引号（JSON 串整段写进 NBT 时会带上）、去掉首尾空白；</li>
 *   <li>去掉 Minecraft 的 {@code §} 格式码（{@code §k混沌合成} 这种情况，lang 里真有）；</li>
 *   <li>去掉<b>所有</b>空白（含 {@code \u00A0} / {@code \u3000}），{@code small chemical reactor}
 *       与 {@code small_chemical_reactor} 的<b>空格变体</b>因此能对上；</li>
 *   <li>{@link Locale#ROOT} 小写（英文显示名因此大小写不敏感；中文不受影响）。</li>
 * </ul>
 * ⚠️ 归一化<b>不</b>把 {@code _} 折成空格或反之：id 与显示名是两张表，
 * 只有 {@link ShanhaiRecipeTypeIds} 负责「先按 id 认、认不出再按名字认」的顺序。
 */
public final class ShanhaiRecipeTypeNameIndex {

    /** 一条候选：真实 id + 它的显示名（中文/英文都算，取决于当前语言表）。 */
    public record Entry(@NotNull String id, @NotNull String displayName) {}

    private ShanhaiRecipeTypeNameIndex() {}

    /**
     * 名字的归一化键（见类注释 §四）。{@code null}/空 ⇒ {@code ""}。
     *
     * @param raw 任意字符串（玩家输入、NBT 里的值、lang 值都行）
     */
    public static @NotNull String nameKey(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw;
        try {
            s = Normalizer.normalize(s, Normalizer.Form.NFKC);
        } catch (Throwable ignored) {
            // NFKC 在某些畸形输入上抛 ⇒ 用原串继续（归一化失败不该让读取层失效）
        }
        s = s.trim();
        // 成对双引号（JSON 串整段写进 NBT 时会带）
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        final StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '\u00A7') {
                // § + 一个格式化字符：整对丢掉
                i++;
                continue;
            }
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u00A0' || c == '\u3000') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * 把候选对建成「归一化名字 → id」表。
     *
     * <p>🔴 同名撞多个 id ⇒ 该名字<b>整体丢弃</b>（记进 {@code ambiguous}，见 {@link #build} 的重载），
     * 于是查它只会得到空串 —— 宁可查不到，也不给一个「可能是对的」的类型。
     *
     * @param entries 候选（{@code null} 项跳过；空 id / 空名字跳过）
     */
    public static @NotNull Map<String, String> build(@Nullable List<Entry> entries) {
        final Map<String, String> map = new HashMap<>();
        final Map<String, Boolean> ambiguous = new HashMap<>();
        if (entries != null) {
            for (Entry entry : entries) {
                if (entry == null || entry.id() == null || entry.id().isEmpty()) {
                    continue;
                }
                final String key = nameKey(entry.displayName());
                if (key.isEmpty()) {
                    continue;
                }
                if (Boolean.TRUE.equals(ambiguous.get(key))) {
                    continue;
                }
                final String previous = map.get(key);
                if (previous == null) {
                    map.put(key, entry.id());
                } else if (!previous.equals(entry.id())) {
                    // 撞名：这个名字不再可信 ⇒ 撤掉，并且以后也不许再放回去
                    map.remove(key);
                    ambiguous.put(key, Boolean.TRUE);
                }
            }
        }
        return map;
    }

    /**
     * 诊断版：把「因重名而作废」的名字交出来（只用于日志/报告，不参与查表）。
     *
     * @return 名字键 → 撞在它上面的 id 列表（≥2 个才在返回表里）
     */
    public static @NotNull Map<String, List<String>> ambiguousNames(@Nullable List<Entry> entries) {
        final Map<String, List<String>> seen = new LinkedHashMap<>();
        if (entries != null) {
            for (Entry entry : entries) {
                if (entry == null || entry.id() == null || entry.id().isEmpty()) {
                    continue;
                }
                final String key = nameKey(entry.displayName());
                if (key.isEmpty()) {
                    continue;
                }
                final List<String> ids = seen.computeIfAbsent(key, ignored -> new ArrayList<>(2));
                if (!ids.contains(entry.id())) {
                    ids.add(entry.id());
                }
            }
        }
        final Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : seen.entrySet()) {
            if (e.getValue().size() > 1) {
                out.put(e.getKey(), List.copyOf(e.getValue()));
            }
        }
        return out;
    }

    /**
     * 查表：整串相等，命中返回 id，否则 {@code ""}（<b>不猜</b>）。
     *
     * @param table 「归一化名字 → id」（{@link #build} 的产物）
     * @param raw   任意写法（会先过 {@link #nameKey}）
     */
    public static @NotNull String lookup(@Nullable Map<String, String> table, @Nullable String raw) {
        if (table == null || table.isEmpty()) {
            return "";
        }
        final String key = nameKey(raw);
        if (key.isEmpty()) {
            return "";
        }
        final String id = table.get(key);
        return id == null ? "" : id;
    }

    /** 表里有多少条（日志/探针读数用）。 */
    public static int size(@Nullable Map<String, String> table) {
        return table == null ? 0 : table.size();
    }
}
