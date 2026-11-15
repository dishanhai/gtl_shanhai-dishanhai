package com.shanhai.common.saveedit;

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
                "§c读不到 level.dat：" + error, "", ""));
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
        lines.add("§7世界名 §f" + pick(c, "Data.LevelName", "-")
                + " §8· §7难度 §f" + difficultyName(pick(c, "Data.Difficulty", "1"))
                + " §8· §7锁 §f" + pick(c, "Data.DifficultyLocked", "false")
                + " §8· §7版本 §f" + pick(c, "Data.version", "?"));
        lines.add("§7已过刻 §f" + pick(c, "Data.Time", "-")
                + " §8· §7时刻 §f" + pick(c, "Data.DayTime", "-")
                + " §8· §7出生 §f(" + pick(c, "Data.SpawnX", "-") + "," + pick(c, "Data.SpawnY", "-")
                + "," + pick(c, "Data.SpawnZ", "-") + ") §8· §7朝向 §f" + pick(c, "Data.SpawnAngle", "-"));
        lines.add("§7天气 §f" + weather(pick(c, "Data.raining", "false"), pick(c, "Data.thundering", "false"))
                + " §8· §7晴天计时 §f" + pick(c, "Data.clearWeatherTime", "-")
                + " §8· §7不掉落 §f" + pick(c, "Data.GameRules.keepInventory", "-")
                + " §8· §7随机刻 §f" + pick(c, "Data.GameRules.randomTickSpeed", "-"));
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
            case "0": return "和平(0)";
            case "1": return "简单(1)";
            case "2": return "普通(2)";
            case "3": return "困难(3)";
            default: return "未知(" + id + ")";
        }
    }

    private static String weather(String raining, String thundering) {
        final boolean r = "true".equals(raining);
        final boolean t = "true".equals(thundering);
        if (t) {
            return "雷暴";
        }
        return r ? "下雨" : "晴";
    }
}
