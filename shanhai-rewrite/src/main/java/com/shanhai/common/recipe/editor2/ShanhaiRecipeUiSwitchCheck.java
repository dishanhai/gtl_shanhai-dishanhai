package com.shanhai.common.recipe.editor2;

import com.shanhai.common.recipe.editor2.ShanhaiRecipeUiRule.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 山海重构 · <b>「开关 = ours 打开老那套 / = his 打开新那套」的离线和自检</b>（第 1 步 ③）。
 *
 * <h2>为什么单独一个类、而不是加进 {@code ShanhaiRecipeEditorSelfcheck}</h2>
 * 那个文件这一轮<b>有另一条线正在改</b>（用户任务书明写"不许改"）⇒ 本步的自检落在
 * 自己这一包（{@code com.shanhai.common.recipe.editor2}）里，一行都不碰它。
 *
 * <h2>🔴 它断言的是"生产路径上那一个函数"，不是复制品</h2>
 * 被断言的 {@link ShanhaiRecipeUiRule#usesHis(Ui)} <b>就是</b>
 * {@code ShanhaiRecipeEditorFactory.buildUI} 里用的那一个函数
 * （见 {@link ShanhaiRecipeUiSwitch#usesHis(Ui)} 的转调）⇒ 这里绿 = 游戏里真的按它分派。
 *
 * <h2>🔴 负对照为什么必需</h2>
 * 只有正对照的话，一个"永远返回 false"的假实现也能全绿（那等于新界面永远打不开）。
 * 所以本类<b>同时</b>断言：
 * <ul>
 *   <li><b>N1</b>：一个"写反了的实现"（{@code ui == OURS}）必须在某一个输入上与真实现<b>不一致</b>
 *       —— 证明这一对判据真的能看见"开关反了"这件事；</li>
 *   <li><b>N2</b>：{@link ShanhaiRecipeUiRule#screenOf(Ui)} 对两个取值的返回值<b>必须不同</b>
 *       —— 证明"两套界面真的分得开"（若两边一样，"开关"这个词就是假的）；</li>
 *   <li><b>N3</b>：不认识的字符串必须解析成 {@code null}，<b>不许</b>悄悄回落成某一套。</li>
 * </ul>
 *
 * <h2>怎么跑</h2>
 * <pre>
 *   · 离线：{@code java -cp <classes> com.shanhai.common.recipe.editor2.ShanhaiRecipeUiSwitchCheck}
 *           —— 本类与 {@link ShanhaiRecipeUiRule} 都不引用 MC/Forge/配置，所以离线可跑；
 *   · 游戏内：{@code /shanhai edit ui}（念当前值）＋ 服务端启动时打的那行
 *           {@code ui_switch_selfcheck}（由 {@link ShanhaiRecipeUiSwitch#selfcheckLines()} 产出）。
 * </pre>
 */
public final class ShanhaiRecipeUiSwitchCheck {

    private ShanhaiRecipeUiSwitchCheck() {}

    /** 判据行（每行一句，含正/负对照的读数）。 */
    public static List<String> run() {
        final List<String> out = new ArrayList<>();
        int pass = 0;
        int total = 0;
        final StringBuilder bad = new StringBuilder();

        // ── ① 正对照：分派口径（就是生产路径上那一个函数） ──
        total += 2;
        if (ShanhaiRecipeUiRule.usesHis(Ui.OURS)) {
            bad.append(" P1[OURS] 被当成新版");
        } else {
            pass++;
        }
        if (ShanhaiRecipeUiRule.usesHis(Ui.HIS)) {
            pass++;
        } else {
            bad.append(" P2[HIS] 没被当成新版");
        }
        total += 2;
        if (ShanhaiRecipeUiRule.SCREEN_OURS.equals(ShanhaiRecipeUiRule.screenOf(Ui.OURS))) {
            pass++;
        } else {
            bad.append(" P3[OURS] 的屏名=").append(ShanhaiRecipeUiRule.screenOf(Ui.OURS));
        }
        if (ShanhaiRecipeUiRule.SCREEN_HIS.equals(ShanhaiRecipeUiRule.screenOf(Ui.HIS))) {
            pass++;
        } else {
            bad.append(" P4[HIS] 的屏名=").append(ShanhaiRecipeUiRule.screenOf(Ui.HIS));
        }

        // ── ② N1 负对照：写反了的实现必须与真实现不一致（否则这一对判据看不见"开关反了"） ──
        total++;
        final boolean flippedAgreesEverywhere =
                (Ui.OURS == Ui.OURS) == ShanhaiRecipeUiRule.usesHis(Ui.OURS)
                        && (Ui.HIS == Ui.OURS) == ShanhaiRecipeUiRule.usesHis(Ui.HIS);
        if (flippedAgreesEverywhere) {
            bad.append(" N1[负对照失效] 写反的实现与真实现完全一致 ⇒ 本检查器看不见这个病");
        } else {
            pass++;
        }

        // ── ③ N2 负对照：两套界面必须真的分得开（屏名不同） ──
        total++;
        if (ShanhaiRecipeUiRule.screenOf(Ui.OURS).equals(ShanhaiRecipeUiRule.screenOf(Ui.HIS))) {
            bad.append(" N2[负对照失效] 两个开关值指向同一屏 ⇒ 根本不存在「两套」");
        } else {
            pass++;
        }

        // ── ④ 解析：认识的三种写法 + N3 负对照（不认识的必须 null） ──
        final Object[][] parseCases = {
                {"ours", Ui.OURS}, {"OURS", Ui.OURS}, {"老版", Ui.OURS},
                {"his", Ui.HIS}, {"His", Ui.HIS}, {"新版", Ui.HIS},
                {"neither", null}, {"", null}, {null, null},
        };
        for (Object[] cs : parseCases) {
            total++;
            final Ui got = ShanhaiRecipeUiRule.parse((String) cs[0]);
            if (got == cs[1]) {
                pass++;
            } else {
                bad.append(" P5[").append(cs[0]).append("] -> ").append(got).append(" 期望 ").append(cs[1]);
            }
        }
        total++;
        if (ShanhaiRecipeUiRule.parse("neither") != null) {
            bad.append(" N3[负对照失效] 不认识的字符串被解析成了 ").append(ShanhaiRecipeUiRule.parse("neither"));
        } else {
            pass++;
        }

        // ── ⑤ 默认值：常量口径（离线可断言的那一半；"配置项默认值"那一半见 N4） ──
        total++;
        if (ShanhaiRecipeUiRule.DEFAULT == Ui.OURS) {
            pass++;
        } else {
            bad.append(" P6 默认值不是 OURS（加开关之前的行为就是 OURS）");
        }

        out.add("ui_switch_selfcheck " + pass + "/" + total + " PASS=" + (pass == total)
                + " （判据：①生产路径上那一个分派函数 usesHis/screenOf 对 OURS/HIS 各一条正对照；"
                + "②N1 写反的实现必须与它不一致；③N2 两套屏名必须不同；"
                + "④parse 六种写法 + N3 不认识的必须 null；⑤默认值 = OURS）");
        if (bad.length() > 0) {
            out.add("ui_switch_selfcheck_FAILED items=" + (total - pass) + bad);
        }
        return out;
    }

    /**
     * 离线跑：本类与 {@link ShanhaiRecipeUiRule} <b>都不引用 MC/Forge/配置</b>
     * ⇒ 可以直接 {@code java} 起（这一条是<b>实测过的</b>，见交付报告"自检读数"）。
     * <p>⚠️ "当前真的取值"那一行（要读 Forge 配置）<b>不在本类</b>：
     * 它在 {@code ShanhaiRecipeUiSwitch#selfcheckLines()} 里拼上去 —— 那样本类才能零依赖编译。
     */
    public static void main(String[] args) {
        for (String line : run()) {
            System.out.println(line);
        }
    }
}
