package com.shanhai.common.recipe.editor2;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.recipe.editor2.ShanhaiRecipeUiRule.Ui;
import com.shanhai.config.ShanhaiConfig;

import java.util.List;
import java.util.Locale;

/**
 * 山海重构 · <b>配方编辑器「两套界面共存」的开关（外面的壳）</b>（第 1 步的骨架件）。
 *
 * <h2>1. 两套界面各是什么</h2>
 * <ul>
 *   <li>{@link Ui#OURS} = <b>我们自己那套</b>（{@code ShanhaiRecipeEditorPanel}，三段式，可编辑）
 *       —— <b>默认值</b>，与加本开关之前<b>逐字节同行为</b>；</li>
 *   <li>{@link Ui#HIS} = <b>上游那套界面里的【只读浏览页】</b>（类型列表 / 配方卡片 / 翻页），
 *       见 {@code ShanhaiRecipeBrowsePanel}。本步<b>只读</b>：点编辑动作不响应。</li>
 * </ul>
 *
 * <h2>2. 分派口径住在哪（这是本步最要紧的一条纪律）</h2>
 * 真正的判断只有一处：{@link ShanhaiRecipeUiRule#usesHis(Ui)}（纯函数，零依赖）。
 * <b>生产路径</b>（{@code ShanhaiRecipeEditorFactory.buildUI}）与<b>自检</b>
 * （{@link ShanhaiRecipeUiSwitchCheck}）读的都是它 ⇒ "自检绿"与"游戏里真打开那一套"是同一件事，
 * 不存在两份判断漂移。
 *
 * <h2>3. 开关存哪（为什么是它）</h2>
 * 存进<b>已有的</b>配置文件 {@code config/shanhai-common.toml}
 * （{@link ShanhaiConfig#COMMON_SPEC} 的 {@code recipe_editor.ui} 一项），<b>不新造任何文件</b>。
 * <p>为什么是 COMMON 而不是 CLIENT：界面的 ModularUI 是
 * {@code UIFactory.createUITemplate} <b>两侧各建一次</b>的（见
 * {@code ShanhaiRecipeEditorFactory} 类注释），两侧必须选出<b>同一套</b>界面，
 * 否则控件表对不上。Forge 的 COMMON 配置两端各读本地同名 toml —— 单机（本工程唯一的验证环境）
 * 两侧读的是同一个文件，天然一致。
 *
 * <h2>4. 读不动时一律回 {@link Ui#OURS}</h2>
 * {@code ConfigValue.get()} 在 spec 未加载时会抛 {@code IllegalStateException}
 * ⇒ {@link #current()} 整段 try/catch，任何异常都回默认那套（"宁可老的那套，不可开不了面板"）。
 */
public final class ShanhaiRecipeUiSwitch {

    public static final String PREFIX = "[SHANHAI-EDIT] editor2";

    private ShanhaiRecipeUiSwitch() {}

    // ------------------------------------------------------------ 纯逻辑：全部转调规则类（一处口径）

    /** 见 {@link ShanhaiRecipeUiRule#usesHis(Ui)} —— <b>分派口径本身</b>。 */
    public static boolean usesHis(Ui ui) {
        return ShanhaiRecipeUiRule.usesHis(ui);
    }

    /** 现在生效的是哪一套（读不到配置 ⇒ {@link ShanhaiRecipeUiRule#DEFAULT} = OURS）。 */
    public static Ui current() {
        try {
            if (!ShanhaiConfig.isLoaded()) {
                return ShanhaiRecipeUiRule.DEFAULT;
            }
            final ShanhaiConfig.ConfigValues.Ui v = ShanhaiConfig.COMMON.editorUi.get();
            if (v == null) {
                return ShanhaiRecipeUiRule.DEFAULT;
            }
            return v == ShanhaiConfig.ConfigValues.Ui.HIS ? Ui.HIS : Ui.OURS;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} ui_switch_read_failed err={} -> OURS", PREFIX, t.toString());
            return ShanhaiRecipeUiRule.DEFAULT;
        }
    }

    /** 现在这一拍该不该打开新版界面（生产路径就调它）。 */
    public static boolean usesHisNow() {
        return usesHis(current());
    }

    /** {@code "ours"} / {@code "his"}（大小写不敏感）⇒ 枚举；不认识返回 {@code null}。 */
    public static Ui parse(String s) {
        return ShanhaiRecipeUiRule.parse(s);
    }

    // ------------------------------------------------------------ 写：进配置文件

    /**
     * 写进配置文件并落盘。
     *
     * @return true = 写成功且已 {@code save()}；false = 配置没加载 / 写不动（调用方必须如实报给用户）
     */
    public static boolean set(Ui ui) {
        if (ui == null) {
            return false;
        }
        try {
            if (!ShanhaiConfig.isLoaded()) {
                ShanhaiMod.LOGGER.warn("{} ui_switch_write_skipped reason=config_not_loaded want={}", PREFIX, ui);
                return false;
            }
            ShanhaiConfig.COMMON.editorUi.set(ui == Ui.HIS
                    ? ShanhaiConfig.ConfigValues.Ui.HIS : ShanhaiConfig.ConfigValues.Ui.OURS);
            ShanhaiConfig.COMMON_SPEC.save();
            ShanhaiMod.LOGGER.info("{} ui_switch_set ui={} now={} (落盘到 config/shanhai-common.toml)",
                    PREFIX, ui, current());
            return true;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} ui_switch_write_failed want={} err={}", PREFIX, ui, t.toString(), t);
            return false;
        }
    }

    /** 便利：从字符串切（{@code null} = 不是合法取值）。 */
    public static boolean setFrom(String s) {
        final Ui ui = parse(s);
        return ui != null && set(ui);
    }

    /** 反过来切（全息设置面板那一格用它）。 */
    public static Ui flip() {
        final Ui next = usesHisNow() ? Ui.OURS : Ui.HIS;
        set(next);
        return next;
    }

    // ------------------------------------------------------------ 给人看的文字

    /** 全息设置面板那一格上的字。 */
    public static String cellText() {
        return "界面：" + current().zh;
    }

    /** 一行机器可读读数（命令与日志共用同一个口径）。 */
    public static String statsLine() {
        return "editor_ui=" + current().name().toLowerCase(Locale.ROOT)
                + " uses_his=" + usesHisNow()
                + " loaded=" + ShanhaiConfig.isLoaded()
                + " default=" + ShanhaiRecipeUiRule.DEFAULT.name().toLowerCase(Locale.ROOT);
    }

    /** 自检那几行：纯逻辑那部分（离线可跑）＋ 一行"当前真的取值"（要读配置，只有游戏里跑得动）。 */
    public static List<String> selfcheckLines() {
        final List<String> out = new java.util.ArrayList<>(ShanhaiRecipeUiSwitchCheck.run());
        out.add("ui_switch_selfcheck_runtime " + statsLine());
        return out;
    }
}
