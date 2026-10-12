package com.shanhai.common.recipe.editor2;

import java.util.Locale;

/**
 * 山海重构 · <b>「两套界面」开关的纯逻辑核</b> —— <b>零外部依赖</b>（不 import 任何 MC / Forge / 本工程别的类）。
 *
 * <h2>为什么要单独开这一个类</h2>
 * 第 1 步的验收判据是"<b>开关 = ours 时打开的是老那套、= his 时打开的是新那套</b>"。
 * 判据必须能<b>离线跑</b>（不必开游戏、不必进存档），否则"自检"就变成"再进一次游戏看看"。
 * ⇒ 把这条判据真正依赖的三件事收进本类，让它们<b>一个外部符号都不引用</b>：
 * <ol>
 *   <li>{@link Ui}（两套界面的枚举）；</li>
 *   <li>{@link #usesHis(Ui)} —— <b>分派口径本身</b>（谁打开哪一套，只有这一处说了算）；</li>
 *   <li>{@link #parse(String)} —— 命令行那三个字怎么解析。</li>
 * </ol>
 *
 * <h2>🔴 它和 {@link ShanhaiRecipeUiSwitch} 的分工</h2>
 * 本类 = <b>纯函数</b>（可离线断言）；{@link ShanhaiRecipeUiSwitch} = 外面那层壳（读/写 Forge 配置、
 * 打日志、给全息面板出一格文字）。分派那一处<b>只</b>调本类的 {@link #usesHis(Ui)}
 * ⇒ 自检断言的<b>就是</b>生产路径上的那一个函数，不是它的复制品。
 */
public final class ShanhaiRecipeUiRule {

    /** 两套界面。 */
    public enum Ui {
        /** 我们自己那套（默认）。 */
        OURS("老版"),
        /** 上游那套界面的只读浏览页。 */
        HIS("新版");

        /** 面板/聊天栏上显示的两个字。 */
        public final String zh;

        Ui(String zh) {
            this.zh = zh;
        }
    }

    /** 分派口径的字符串形态（自检与日志共用；<b>不要再在别处写第三个版本</b>）。 */
    public static final String SCREEN_OURS = "ours";
    public static final String SCREEN_HIS = "his";

    /** 默认那套（= 加本开关之前的行为）。 */
    public static final Ui DEFAULT = Ui.OURS;

    private ShanhaiRecipeUiRule() {}

    /**
     * <b>这个开关值是不是"打开新版（上游那套）界面"</b> —— 这就是分派口径本身。
     *
     * <p>🔴 生产路径（{@code ShanhaiRecipeEditorFactory.buildUI}）与自检<b>都只调它</b>，
     * 所以"自检绿"与"游戏里真的打开那一套"是同一件事，不存在"两份判断漂移"。
     */
    public static boolean usesHis(Ui ui) {
        return ui == Ui.HIS;
    }

    /** 这个开关值 ⇒ 打开哪一屏（{@link #SCREEN_OURS} / {@link #SCREEN_HIS}）。 */
    public static String screenOf(Ui ui) {
        return usesHis(ui) ? SCREEN_HIS : SCREEN_OURS;
    }

    /**
     * 命令/配置里那三个字 ⇒ 枚举；<b>不认识的一律返回 {@code null}</b>
     * （调用方必须把 null 当"没写成功"报出去，不许当成默认值悄悄吞掉）。
     */
    public static Ui parse(String s) {
        if (s == null) {
            return null;
        }
        final String k = s.trim().toLowerCase(Locale.ROOT);
        if ("ours".equals(k) || "old".equals(k) || "老".equals(k) || "老版".equals(k)) {
            return Ui.OURS;
        }
        if ("his".equals(k) || "new".equals(k) || "上游".equals(k) || "新版".equals(k)) {
            return Ui.HIS;
        }
        return null;
    }
}
