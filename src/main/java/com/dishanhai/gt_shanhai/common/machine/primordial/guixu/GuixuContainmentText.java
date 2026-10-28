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
            case CLOSED -> "关闭";
            case CONTAINED -> "遏制中";
            case UNSTABLE -> "失稳";
            case HYPERSTABLE -> "超稳态";
        };
    }

    /** @param field 生效中的遏制場；{@code null} = 未安裝 */
    public static String hostSummary(GuixuContainmentField field, int lockSeconds) {
        if (lockSeconds > 0) return "遏制场：坍缩锁定｜剩余 " + lockSeconds + " 秒（主机与模组停机）";
        if (field == null) return "遏制场：未安装";
        GuixuContainmentBonus bonus = GuixuContainmentBonus.of(field.status(), field.stability());
        return switch (field.status()) {
            case CLOSED -> "遏制场：关闭（投入黑洞种子开启）";
            case UNSTABLE -> String.format(Locale.ROOT, "遏制场：失稳｜稳定度 %.0f｜产出吞噬机率 %.1f%%",
                    field.stability(), bonus.swallowChance() * 100.0D);
            default -> String.format(Locale.ROOT,
                    "遏制场：%s｜稳定度 %.0f｜并行 ×%d 耗时 ×%s 增产 +%d 耗电 ×%s 数量 ×%d",
                    label(field.status()), field.stability(), bonus.parallelMultiplier(),
                    plain(bonus.durationMultiplier()), bonus.extraOutput(),
                    plain(bonus.energyMultiplier()), bonus.matterCountMultiplier());
        };
    }

    public static List<String> moduleLines(GuixuContainmentField field, int lockSeconds) {
        List<String> lines = new ArrayList<>();
        lines.add("状态：" + label(field.status()));
        lines.add(String.format(Locale.ROOT, "稳定度：%.0f / 100", field.stability()));
        switch (field.status()) {
            case CONTAINED -> {
                lines.add(String.format(Locale.ROOT, "时空流体：每秒 %,d mB（消耗倍率 ×%,d）",
                        field.requiredSpacetime(), field.costModifier()));
                lines.add("催化计数：" + field.catalysisCounter() + " / " + GuixuContainmentField.CATALYSIS_PER_DOUBLING);
            }
            case UNSTABLE -> lines.add(String.format(Locale.ROOT, "产出吞噬机率：%.1f%%｜距坍缩 %.0f 秒",
                    GuixuContainmentBonus.swallowChanceAt(field.stability()) * 100.0D,
                    field.stability() - GuixuContainmentField.COLLAPSE_STABILITY));
            case HYPERSTABLE -> lines.add("超稳态：不衰减、不消耗时空流体");
            case CLOSED -> lines.add("投入「黑洞种子」开启，或「超稳态黑洞种子」进入超稳态");
        }
        if (field.isOpen()) lines.add("投入「黑洞坍缩器」可安全关闭（不锁定）");
        if (lockSeconds > 0) lines.add("归墟坍缩锁定中：剩余 " + lockSeconds + " 秒，暂不接受种子");
        return lines;
    }

    private static String plain(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
