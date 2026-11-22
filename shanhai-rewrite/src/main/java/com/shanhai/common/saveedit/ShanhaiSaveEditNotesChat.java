package com.shanhai.common.saveedit;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「列不出实体」的<b>原因</b>（{@link ShanhaiEntityEdit.DimensionListing#notes()}）→ 聊天栏里最多 2 行。
 *
 * <p>用户原话：「可以写，<b>打在聊天栏里面</b>，但是要写的<b>简要一点，不要刷屏</b>」。
 * 面板实体页过去只在日志（{@code panel_entity_list … notes=[…]}）和详情覆盖层里说原因，
 * 玩家点「列出」看到 0 行，不知道为啥 —— 这一段就是把那些原因顺手打到聊天栏。
 *
 * <h2>一、这个类为什么是纯 JDK（只依赖 java.util）</h2>
 * 面板类继承 LDLib 的 {@code WidgetGroup}，离线<b>加载不了</b>（静态初始化要 SRG 方法）。
 * 所以"notes → 聊天行"这一层单独抽出来：{@code main} 直接调它就能判，
 * 见 {@code temp/notes-chat/}。面板里发送那一步只做
 * {@code if (!lines.isEmpty()) 发送}。
 *
 * <h2>二、三条硬口径</h2>
 * <ul>
 *   <li><b>简要</b>：一轮最多 {@value #MAX_LINES} 行，每行 ≤{@value #MAX_LINE_CHARS} 字
 *       （<b>含</b>前缀；超出用 {@code …} 截断）；</li>
 *   <li><b>不刷屏</b>：同一段文本 {@value #SAME_TEXT_MS} ms 内不重复发；
 *       任意两轮之间 ≥ {@value #ANY_MS} ms（见 {@link Throttle}）；</li>
 *   <li><b>没原因就什么都不发</b>：{@code notes} 空 / 全是空白 ⇒ 空表
 *       （绝不发"0 条"这种废话）。</li>
 * </ul>
 *
 * <p>🔴 这一层<b>不动</b>任何既有行为：notes 的日志行、详情覆盖层的显示、
 * 列表 / 翻页 / 搜索逻辑都不在这里，也不受影响。
 *
 * <h2>三、怎么分到那 2 行</h2>
 * 第 1 行给<b>第一条</b>原因（通常会是最要紧的那条：目录不存在 / 文件读不了），
 * 最后一行把<b>剩下的全部</b>用 {@value #JOIN} 并起来（太宽就截）。
 * 5 条原因 ⇒ 2 行；1 条原因 ⇒ 1 行；0 条 ⇒ 0 行。
 */
public final class ShanhaiSaveEditNotesChat {

    /** 统一前缀（<b>两行都带</b>：第 2 行也认得出这句是谁说的）。 */
    public static final String PREFIX = "§7[存档直改] §f";
    /** 一轮最多几行（"不要刷屏"）。 */
    public static final int MAX_LINES = 2;
    /** 一行最多几个字符（<b>含前缀</b>）。 */
    public static final int MAX_LINE_CHARS = 60;
    /** 正文预算 = 每行上限 − 前缀长度（§ 颜色码也数进去，宁可短一点）。 */
    public static final int MAX_BODY_CHARS = MAX_LINE_CHARS - PREFIX.length();
    /** 合并多条原因时的分隔符。 */
    public static final String JOIN = "；";
    /** 截断符。 */
    public static final String ELLIPSIS = "…";
    /** 同一段文本的最短重发间隔（毫秒）。 */
    public static final long SAME_TEXT_MS = 30_000L;
    /** 任意两轮之间的最短间隔（毫秒）。 */
    public static final long ANY_MS = 2_000L;
    /** 节流表最多记多少条文本；超了就把已经过期的清掉。 */
    private static final int THROTTLE_KEEP = 16;

    private ShanhaiSaveEditNotesChat() {
    }

    /**
     * 把数据层的 {@code notes} 摊成聊天行（每条都已带 {@link #PREFIX}）。
     *
     * <p>空 / {@code null} / 全是空白 ⇒ <b>空表</b>（调用方据此"什么都不发"）；
     * 内容完全相同的两条只留一条；每行 ≤{@link #MAX_LINE_CHARS} 字符；行数 ≤{@link #MAX_LINES}。
     *
     * @param notes 数据层原因（{@code DimensionListing#notes()}，允许 {@code null}）
     * @return 0 ~ {@link #MAX_LINES} 行，不可变表
     */
    public static List<String> linesOf(List<String> notes) {
        final List<String> clean = normalize(notes);
        if (clean.isEmpty()) {
            return List.of();
        }
        final int lines = Math.min(MAX_LINES, clean.size());
        final List<String> out = new ArrayList<>(lines);
        // 前 lines-1 行：一条一行
        for (int i = 0; i + 1 < lines; i++) {
            out.add(line(clean.get(i)));
        }
        // 最后一行：把剩下的（含"只有 1 条"那种情形）全并进来
        final StringBuilder rest = new StringBuilder();
        for (int i = lines - 1; i < clean.size(); i++) {
            if (rest.length() > 0) {
                rest.append(JOIN);
            }
            rest.append(clean.get(i));
        }
        out.add(line(rest.toString()));
        return List.copyOf(out);
    }

    /** 前缀 + 正文（正文超出预算就截）。 */
    private static String line(String body) {
        return PREFIX + clip(body);
    }

    /** 正文截到 {@link #MAX_BODY_CHARS} 个字符，超出部分换成 {@link #ELLIPSIS}。 */
    private static String clip(String body) {
        if (body.length() <= MAX_BODY_CHARS) {
            return body;
        }
        int cut = MAX_BODY_CHARS - ELLIPSIS.length();
        if (cut < 0) {
            cut = 0;
        }
        // 🔴 别把代理对（🔴 这类 4 字节 emoji 是两个 char）切成两半：截完会变成乱码方框
        if (cut > 0 && Character.isHighSurrogate(body.charAt(cut - 1))) {
            cut--;
        }
        return body.substring(0, cut) + ELLIPSIS;
    }

    /** 去空、把换行/连续空白压成一个空格、去掉内容重复的那几条（保序）。 */
    private static List<String> normalize(List<String> notes) {
        if (notes == null || notes.isEmpty()) {
            return List.of();
        }
        final List<String> out = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        for (String note : notes) {
            final String key = collapse(note);
            if (key.isEmpty()) {
                continue;
            }
            if (!seen.add(key)) {
                // 内容一样的只留第一条（去重）
                continue;
            }
            out.add(key);
        }
        return List.copyOf(out);
    }

    /** 一条原因压成单行（聊天栏里一条原因必须只占一行）。 */
    private static String collapse(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\s\\u00A0\\u3000]+", " ").trim();
    }

    /**
     * 不刷屏的闸门（纯 JDK、时钟从外面传进来 ⇒ 离线可判）。
     *
     * <p>两条同时满足才放行：① 同一段文本距上次发送 ≥ {@link #SAME_TEXT_MS} ms；
     * ② 距<b>任意</b>上一次发送 ≥ {@link #ANY_MS} ms。被挡下的<b>不</b>记账
     * （否则一直挡着的行为会让"30 秒静默"永远不到期）。
     */
    public static final class Throttle {

        /** 文本 → 上次发出去的时刻（毫秒）。 */
        private final Map<String, Long> lastSameText = new LinkedHashMap<>();
        /** 上一次放行的时刻（毫秒）。 */
        private long lastAnyAt;
        /** 是否放行过（第一轮不查"两轮间隔"）。 */
        private boolean sentAny;

        /**
         * 这一轮该不该发。
         *
         * @param lines      {@link #linesOf(List)} 的结果（空 ⇒ 一律不放行）
         * @param nowMillis  当前时刻（{@code System.currentTimeMillis()}）
         * @return true = 可以发；false = 安静（既不发、也不记账）
         */
        public boolean allow(List<String> lines, long nowMillis) {
            if (lines == null || lines.isEmpty()) {
                return false;
            }
            final String key = String.join("\n", lines);
            final Long same = this.lastSameText.get(key);
            if (same != null && nowMillis - same < SAME_TEXT_MS) {
                return false;
            }
            if (this.sentAny && nowMillis - this.lastAnyAt < ANY_MS) {
                return false;
            }
            this.record(key, nowMillis);
            return true;
        }

        private void record(String key, long nowMillis) {
            this.lastSameText.put(key, nowMillis);
            this.lastAnyAt = nowMillis;
            this.sentAny = true;
            if (this.lastSameText.size() > THROTTLE_KEEP) {
                final Iterator<Map.Entry<String, Long>> it = this.lastSameText.entrySet().iterator();
                while (it.hasNext()) {
                    if (nowMillis - it.next().getValue() >= SAME_TEXT_MS) {
                        it.remove();
                    }
                }
            }
        }
    }
}
