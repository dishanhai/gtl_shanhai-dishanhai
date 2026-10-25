package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 山海：遏制場的顯示文字（純字串，主機抬頭與模組抬頭共用）。 */
public final class GuixuContainmentText {

    private GuixuContainmentText() {}

    public static String label(GuixuContainmentField.Status status) {
        return switch (status) {
            case CLOSED -> "關閉";
            case CONTAINED -> "遏制中";
            case UNSTABLE -> "失穩";
            case HYPERSTABLE -> "超穩態";
        };
    }

    /** @param field 生效中的遏制場；{@code null} = 未安裝 */
    public static String hostSummary(GuixuContainmentField field, int lockSeconds) {
        if (lockSeconds > 0) return "遏制場：坍縮鎖定｜剩餘 " + lockSeconds + " 秒（主機與模組停機）";
        if (field == null) return "遏制場：未安裝";
        GuixuContainmentBonus bonus = GuixuContainmentBonus.of(field.status(), field.stability());
        return switch (field.status()) {
            case CLOSED -> "遏制場：關閉（投入黑洞種子開啟）";
            case UNSTABLE -> String.format(Locale.ROOT, "遏制場：失穩｜穩定度 %.0f｜產出吞噬機率 %.1f%%",
                    field.stability(), bonus.swallowChance() * 100.0D);
            default -> String.format(Locale.ROOT,
                    "遏制場：%s｜穩定度 %.0f｜並行 ×%d 耗時 ×%s 增產 +%d 耗電 ×%s 數量 ×%d",
                    label(field.status()), field.stability(), bonus.parallelMultiplier(),
                    plain(bonus.durationMultiplier()), bonus.extraOutput(),
                    plain(bonus.energyMultiplier()), bonus.matterCountMultiplier());
        };
    }

    public static List<String> moduleLines(GuixuContainmentField field, int lockSeconds) {
        List<String> lines = new ArrayList<>();
        lines.add("狀態：" + label(field.status()));
        lines.add(String.format(Locale.ROOT, "穩定度：%.0f / 100", field.stability()));
        switch (field.status()) {
            case CONTAINED -> {
                lines.add(String.format(Locale.ROOT, "時空流體：每秒 %,d mB（消耗倍率 ×%,d）",
                        field.requiredSpacetime(), field.costModifier()));
                lines.add("催化計數：" + field.catalysisCounter() + " / " + GuixuContainmentField.CATALYSIS_PER_DOUBLING);
            }
            case UNSTABLE -> lines.add(String.format(Locale.ROOT, "產出吞噬機率：%.1f%%｜距坍縮 %.0f 秒",
                    GuixuContainmentBonus.swallowChanceAt(field.stability()) * 100.0D,
                    field.stability() - GuixuContainmentField.COLLAPSE_STABILITY));
            case HYPERSTABLE -> lines.add("超穩態：不衰減、不消耗時空流體");
            case CLOSED -> lines.add("投入「黑洞種子」開啟，或「超穩態黑洞種子」進入超穩態");
        }
        if (field.isOpen()) lines.add("投入「黑洞坍縮器」可安全關閉（不鎖定）");
        if (lockSeconds > 0) lines.add("歸墟坍縮鎖定中：剩餘 " + lockSeconds + " 秒，暫不接受種子");
        return lines;
    }

    private static String plain(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
