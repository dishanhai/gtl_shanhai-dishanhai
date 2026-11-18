package com.dishanhai.gt_shanhai.common.saveedit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code level.dat} 的<b>只读摘要</b>（纯 JDK：一个记录 + 字符串）。
 *
 * <p>面板第二页每一行的"当前值"都从 {@link #current} 里取；三行 {@link #lines()} 是给人看的
 * 总览（世界名/难度/时间/天气/规则）。它存在的理由与一期 {@link ShanhaiPlayerDataSnapshot}
 * 完全一样：<b>服务端算好字面串，客户端只负责显示</b>（服务端没有语言表，见
 * {@code ShanhaiSaveEditFactory} 类注释 §三）。
 *
 * <p>🔴 {@link #present} 为 {@code false} 时，{@link #error} 说明为什么——绝不留一个空白摘要
 * 让人以为"值就是空"。
 */
public record ShanhaiLevelDataSnapshot(String name, boolean present, long bytes, String error,
                                       Map<String, String> current, List<String> lines) {

    /** {@code level.dat} 不存在 / 读不出来时的那一条。 */
    public static ShanhaiLevelDataSnapshot missing(String name, String error) {
        return new ShanhaiLevelDataSnapshot(name, false, 0L, error, Map.of(), List.of(
                ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.4a8ffec652f5cbbc") + error, "", ""));
    }

    /** 某个字段的当前值（取不到 = 空串；面板会显示成「-」）。 */
    public String value(String id) {
        final String v = this.current == null ? null : this.current.get(id);
        return v == null ? "" : v;
    }

    /** 三行摘要里的第 {@code index} 行（永不 {@code null}：LDLib 的 LabelWidget 会对 null NPE）。 */
    public String line(int index) {
        final List<String> l = this.lines == null ? List.of() : this.lines;
        if (index < 0 || index >= l.size()) {
            return "";
        }
        final String s = l.get(index);
        return s == null ? "" : s;
    }

    /** 一行读数（日志用）。 */
    public String joined() {
        return "present=" + this.present + " bytes=" + this.bytes
                + (this.error == null || this.error.isEmpty() ? "" : " error=" + this.error)
                + " values=" + (this.current == null ? 0 : this.current.size());
    }

    /** 用解析好的字段值造一份摘要（三行字在这里拼）。 */
    public static ShanhaiLevelDataSnapshot of(String name, long bytes, Map<String, String> current) {
        final Map<String, String> c = current == null ? Map.of() : new LinkedHashMap<>(current);
        final List<String> lines = new ArrayList<>(3);
        lines.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.326d84f9bb070d45") + pick(c, "Data.LevelName", "-")
                + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.5505a167569b2eb0") + difficultyName(pick(c, "Data.Difficulty", "1"))
                + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.45651e61da63e698") + pick(c, "Data.DifficultyLocked", "false")
                + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.2a71241b21ace363") + pick(c, "Data.version", "?"));
        lines.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.fa7d9fabd1a4d225") + pick(c, "Data.Time", "-")
                + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.3dfdcbf23a7e9c1f") + pick(c, "Data.DayTime", "-")
                + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.d3922c4c5f14ec55") + pick(c, "Data.SpawnX", "-") + "," + pick(c, "Data.SpawnY", "-")
                + "," + pick(c, "Data.SpawnZ", "-") + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.ba7afbe6a146bede") + pick(c, "Data.SpawnAngle", "-"));
        lines.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.63d0327ff8c945f2") + weather(pick(c, "Data.raining", "false"), pick(c, "Data.thundering", "false"))
                + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.0d2d1d8aa4a1f097") + pick(c, "Data.clearWeatherTime", "-")
                + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.b53401b316760adc") + pick(c, "Data.GameRules.keepInventory", "-")
                + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.c72c1ee8528a6568") + pick(c, "Data.GameRules.randomTickSpeed", "-"));
        return new ShanhaiLevelDataSnapshot(name, true, bytes, "", c, lines);
    }

    private static String pick(Map<String, String> m, String key, String dflt) {
        final String v = m.get(key);
        return v == null || v.isEmpty() ? dflt : v;
    }

    /** 难度 0–3 的中文名（与 {@code Difficulty.getId()} 同口径）。 */
    public static String difficultyName(String id) {
        if (id == null) {
            return "?";
        }
        switch (id.trim()) {
            case "0": return com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.7147a33f05d9c391");
            case "1": return com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.9faa64756a547f45");
            case "2": return com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.c0c1f1c278327f13");
            case "3": return com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.a38f774c47cd6924");
            default: return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.8bdd3514ed1f20db") + id + ")";
        }
    }

    private static String weather(String raining, String thundering) {
        final boolean r = "true".equals(raining);
        final boolean t = "true".equals(thundering);
        if (t) {
            return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.23d19c85af00d38c");
        }
        return r ? ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.024b2708ed8e3ffc") : ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.6379ede5c239d1fa");
    }
}
