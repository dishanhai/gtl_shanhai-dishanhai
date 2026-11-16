package com.dishanhai.gt_shanhai.common.patternmover;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>「改配方类型」与「放入样板」这两个功能被删干净了吗</b> —— 一条可机器判的检查（纯核）。
 *
 * <h2>一、为什么需要它</h2>
 * 用户（2026-11-08）：「我又想了一下，修改类型和放入样板的功能就不需要了」。
 * 「删掉」这种事最容易演变成<b>删了一半</b>：按钮没了、动作还在；动作没了、lang 键还在；
 * 或者反过来 —— 面板调一个已经不存在的方法（编译能过是因为它在别处还留着）。
 * ⇒ 把「不许再出现的词」做成常量表，源码与两份 lang 都过一遍扫描，**0 命中**才算删干净。
 *
 * <h2>二、判据（自检第 20 组）</h2>
 * <pre>
 *   正向：包内 .java ＋ zh_cn.json ＋ en_us.json 扫描 ⇒ 命中数必须为 0
 *   负对照：在语料里<b>故意留一条</b>（{@code "shanhai.mover.put_no_source": "..."}）⇒ 扫描必须报出来
 * </pre>
 * ⚠️ 扫描时<b>必须排除本类自己的源文件</b>（常量表本身就含这些词）—— 这一条写在报告里，
 * 探针 {@code temp/pattern-mover/probe/ThirdCutProbe.java} 就是这么做的。
 */
public final class HostPatternMoverRemovedFeatures {

    /** 本类的源文件名（扫描语料时要排除它，理由见类注释 §二）。 */
    public static final String SELF_FILE = "HostPatternMoverRemovedFeatures.java";

    /**
     * 已被删除、<b>不许再出现</b>的词。
     *
     * <p>① {@code setType} = 「设置配方类型」的服务端动作；② {@code putIn} = 「放入样板」的服务端动作；
     * ③④ 是那两件事在 {@code zh_cn.json} / {@code en_us.json} 里的语言键前缀。
     *
     * <p>🔴 前两个是<b>整词</b>匹配（{@code \bsetType\b} / {@code \bputIn\b}）：
     * NBT 的 `putInt`、`offsetType` 这类词里含有同样的字母序列，
     * 用 {@code contains} 会报<b>假命中</b>（第一版就撞了：27 份里报了 3 份，全是 putInt）。
     */
    public static final List<String> FORBIDDEN = List.of(
            "setType",
            "putIn",
            "shanhai.mover.settype_",
            "shanhai.mover.put_");

    /** 整词匹配（见 {@link #FORBIDDEN} 的说明）。 */
    private static final java.util.regex.Pattern WORD_SET_TYPE =
            java.util.regex.Pattern.compile("\\bsetType\\b");

    /** 整词匹配。 */
    private static final java.util.regex.Pattern WORD_PUT_IN =
            java.util.regex.Pattern.compile("\\bputIn\\b");

    private HostPatternMoverRemovedFeatures() {}

    /**
     * 扫一段文本。
     *
     * @return 命中的词（每个词只报一次；没命中 ⇒ 空表）
     */
    public static List<String> scan(String text) {
        final List<String> hits = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return hits;
        }
        if (WORD_SET_TYPE.matcher(text).find()) {
            hits.add(FORBIDDEN.get(0));
        }
        if (WORD_PUT_IN.matcher(text).find()) {
            hits.add(FORBIDDEN.get(1));
        }
        for (int i = 2; i < FORBIDDEN.size(); i++) {
            if (text.contains(FORBIDDEN.get(i))) {
                hits.add(FORBIDDEN.get(i));
            }
        }
        return hits;
    }

    /**
     * 扫一批「文件名 → 文本」。
     *
     * @return {@code 文件: 命中词} 的清单（空表 = 删干净了）
     */
    public static List<String> scanAll(Map<String, String> files) {
        final List<String> out = new ArrayList<>();
        if (files == null) {
            return out;
        }
        for (Map.Entry<String, String> e : files.entrySet()) {
            final String name = e.getKey() == null ? "?" : e.getKey();
            if (SELF_FILE.equals(name)) {
                continue; // 常量表自己含这些词
            }
            for (String hit : scan(e.getValue())) {
                out.add(name + ": " + hit);
            }
        }
        return out;
    }

    /** 便利重载（文本按顺序装进 {@code file#0}、{@code file#1}…）。 */
    public static List<String> scanAll(Collection<String> texts) {
        final Map<String, String> map = new LinkedHashMap<>();
        int i = 0;
        if (texts != null) {
            for (String t : texts) {
                map.put("text#" + (i++), t);
            }
        }
        return scanAll(map);
    }

    /** 一行式读数（日志用）。 */
    public static String describe(Collection<String> hits) {
        if (hits == null || hits.isEmpty()) {
            return "removed_features hits=0 (clean)";
        }
        return "removed_features hits=" + hits.size() + " " + hits;
    }
}
