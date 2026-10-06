package com.shanhai.machine.wildcard;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.widget.IntInputWidget;
import com.gregtechceu.gtceu.api.gui.widget.ToggleButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <b>「自动倍率」面板的唯一装配实现</b> —— 本工程那台仓室与上游「超级样板总成」共用它。
 *
 * <h2>🔴 两条入口（用户 2026-10-04 追加需求 B 的落点）</h2>
 * <pre>
 *   ① {@link #buildFull}  —— 本工程那台：把上游那 4 个控件 + 我们的 3 行一起建出来
 *   ② {@link #appendTo}   —— 上游那台：往【它自己那个】面板 group 里追加我们的 3 行
 *      （上游那 4 个控件的坐标/尺寸/文案一个字都不动；只把 group 从 118×56 撑到 118×104，
 *        并把【它那个】IntInputWidget 接管过来做禁用态）
 * </pre>
 *
 * <h2>版面（两台的控件坐标完全一致）</h2>
 * <pre>
 *   WidgetGroup(0, 0, 140, 104)
 *   ├─ y= 5..25  ToggleButtonWidget(6,5,20,20)      ← 上游原样（神锻模式开关）
 *   ├─ y=10      LabelWidget(32,10)                 ← 上游原样
 *   ├─ y=36      LabelWidget(6,36)                  ← 上游原样（倍率）
 *   ├─ y=31..51  IntInputWidget(58,31,54,20)        ← 上游原样（自动开着时被禁用 + 压暗）
 *   ├─ y=57..77  ToggleButtonWidget(6,57,20,20)     ← 【新增】自动倍率开关
 *   ├─ y=62      LabelWidget(32,62)                 ← 【新增】「自动倍率」
 *   ├─ y=80      LabelWidget(6,80, Supplier)        ← 【新增】手填 N / 读到 N / 读不到 -&gt; ×1
 *   └─ y=92      LabelWidget(6,92, Supplier)        ← 【新增】来源（或读不到的原因）
 * </pre>
 * ⚠️ 宽度 118 → <b>140</b>：来源标注最长为「原始终焉引擎模块」（8 个全角字 ≈ 72 px）加前缀
 * 「来源：」共 ≈ 117 px，118 宽放不下（内容区 112 px）⇒ 加宽到 140（内容区 134 px）。
 * 上游那 4 个控件的坐标一个都没动（最右边缘仍是 112 &lt; 140）。
 * ⚠️ 上游那台的面板 group <b>是上游自己的对象</b>，我们不改它的类、也不重建它 ——
 * 只 {@code setSize} 撑高 + {@code addWidget} 追加三行（见 {@link #appendTo}）。
 *
 * <h2>🔴 「自动开着 ⇒ 输入框禁用」怎么做的（照本工程既有先例）</h2>
 * 先例 = {@code ParallelOverrideConfigurator#applyPowerAutoDisabled}。两半，缺一半就是<b>假禁用</b>：
 * <ol>
 *   <li><b>点不动</b>：{@code input.setActive(false)}。依据（{@code javap -c} 复核过）：
 *       {@code WidgetGroup.mouseClicked} 遍历 children 时先判 {@code isVisible()} 再判
 *       {@code isActive()}，任一为 false <b>就不把事件派给那个子控件</b>。</li>
 *   <li><b>看得出来</b>：把输入框<b>内部那个</b> {@code TextFieldWidget} 的文字压暗。
 *       取法 = {@code WidgetGroup#getWidgetsByType(TextFieldWidget.class)} ——
 *       {@code NumberInputWidget.buildUI()} 在<b>构造器末尾</b>就 {@code addWidget(textField)} 了
 *       （{@code javap -c} 实证）⇒ 控件刚建好就能取到。
 *       ⚠️ <b>没有</b>去 {@code GuiTextures.BUTTON.setColor(...)}：那是改<b>共享静态贴图</b>，
 *       会把全游戏所有按钮一起压暗。</li>
 * </ol>
 * <p>⚠️ <b>为什么不能只在建控件时置一次</b>：LDLib 的灰态是 build 期快照，而玩家完全可能
 * 「先开着面板、再点开关」⇒ 只置一次的话灰态永远不刷新 = 假禁用。
 * ⇒ 挂在一个<b>自定义 LabelWidget 子类</b>的 {@code updateScreen()} 里逐帧对齐
 * （依据 {@code javap -c}：{@code WidgetGroup.updateScreen()} 会遍历 children 逐个调它们的
 * {@code updateScreen()}，且只对 {@code isActive()} 的孩子调 —— 这个钩子永不置 false）。
 *
 * <p>⚠️ <b>诚实边界</b>：{@code setActive} 只挡鼠标；LDLib 不保证挡键盘（Tab 聚焦）。
 * 真正的边界在服务端：自动开着时 {@code effective(...)} 压根不读手填值。
 */
public final class AutoForgeMultiplierPanel {

    /** 面板宽（118 = 上游原宽 → 140：见类注释里那句「放不下」）。 */
    public static final int PANEL_W = 140;

    /** 面板高（上游 56 → 104：多出自动倍率开关行 + 两行状态）。 */
    public static final int PANEL_H = 104;

    /** 「自动倍率」开关那一行（照第①行同款：20×20 的开关 + 右侧文字）。 */
    private static final int AUTO_TOGGLE_Y = 57;
    private static final int AUTO_LABEL_Y = 62;

    /** 面板第三行：<b>当前倍率是手填的还是读来的、读到的值是多少</b>。 */
    private static final int STATUS_Y = 80;

    /** 面板第四行：来源 / 读不到的原因。 */
    private static final int REASON_Y = 92;

    /** 可用态 / 禁用态的文字色（{@code 0xFFFFFFFF} = 原色；灰 = 本工程既有的「压暗」写法）。 */
    private static final int TEXT_ENABLED = 0xFFFFFFFF;
    private static final int TEXT_DISABLED = 0xFF7F7F7F;

    /** 「自动倍率」那一行开关的文字（本工程自己的 lang key，上游没有这个开关）。 */
    static final String LANG_AUTO = "shanhai.machine.super_wildcard_pattern_buffer.auto_multiplier";

    private AutoForgeMultiplierPanel() {}

    /**
     * <b>标记接口：谁被「给上游那台加三行」的 mixin 加装过。</b>
     *
     * <p>由 {@code ShanhaiFoaPanelAutoMultiplierMixin} 实现（mixin 给上游的
     * {@code FOAPatternConfigurator} 加接口）。
     *
     * <p>🔴 它的用途是<b>可机器验证</b>：mixin 生效与否在运行时是个「看不见」的事实，
     * 而 {@code UpstreamPanelExtender.class.isAssignableFrom(FOAPatternConfigurator.class)}
     * 是一个一行的判据 —— 冒烟测试据此给出「mixin 到底进没进去」的原始证据，
     * 而不是靠「日志里没报错」猜。
     */
    public interface UpstreamPanelExtender {}

    // ⚠️ 2026-10-04 第二次返工：原来这里有个 `findHost(Object)`（反射扫字段找机器）——
    //    已整体搬进 {@code UpstreamAutoForgeMultiplierHost.ofHolder(Object)}。
    //    那个类才是「碰目标机器」的唯一入口（反射读 mixin 字段 / 反射调上游方法 /
    //    直接转型调 gtceu 继承来的方法）；这里不留第二份，也不再有「把机器当宿主传出去」的路径。

    // ══════════════════════════ ① 本工程那台：全建 ══════════════════════════

    /**
     * 把整个面板建出来（上游那 4 个控件 + 我们的 3 行）。
     *
     * <p>⚠️ 上游那 4 个控件的坐标、尺寸、lang key <b>逐字不变</b>（与上游
     * {@code FOAPatternConfigurator} 相同）。
     */
    public static WidgetGroup buildFull(AutoForgeMultiplierHost host) {
        final WidgetGroup group = new WidgetGroup(0, 0, PANEL_W, PANEL_H);

        // ── 上游那 4 个控件：坐标、尺寸、lang key 逐字不变 ──
        group.addWidget(new ToggleButtonWidget(
                6, 5, 20, 20,
                GuiTextures.BUTTON_POWER,
                host::isForgePatternModeEnabled,
                host::setForgePatternModeEnabled)
                .setTooltipText(ForgePatternConfigurator.LANG_MODE));
        group.addWidget(new LabelWidget(32, 10, ForgePatternConfigurator.LANG_MODE));
        group.addWidget(new LabelWidget(6, 36, ForgePatternConfigurator.LANG_MULTIPLIER));

        final IntInputWidget multiplier = new IntInputWidget(
                58, 31, 54, 20,
                host::getForgePatternMultiplier,
                host::setForgePatternMultiplier);
        multiplier.setMin(ShanhaiForgePatternMode.MIN_MULTIPLIER);
        multiplier.setMax(ShanhaiForgePatternMode.MAX_MULTIPLIER);
        multiplier.setHoverTooltips(
                Component.literal("§7神锻模式的输出倍率（上游范围 " + ShanhaiForgePatternMode.MIN_MULTIPLIER
                        + "–" + ShanhaiForgePatternMode.MAX_MULTIPLIER + "）"),
                Component.literal("§8这是【手填值】，不是生效值"),
                Component.literal("§8🔴「自动倍率」开着时本框被禁用（变灰、点不动），且框里的值不参与运算"),
                Component.literal("§8🔴 关掉「自动倍率」⇒ 手填值立刻生效"));
        group.addWidget(multiplier);

        appendTo(group, host);
        return group;
    }

    // ══════════════════════════ ② 上游那台：追加 3 行 ══════════════════════════

    /**
     * 往<b>已经建好的</b>面板 group 里追加「自动倍率」开关与两行状态，并接管它那个输入框。
     *
     * <p>上游那台走这条：上游自己的 {@code FOAPatternConfigurator.createConfigurator()} 已经把
     * 4 个控件摆在 {@code WidgetGroup(0,0,118,56)} 里了 —— 我们只
     * ① 把 group 撑到 {@value #PANEL_W}×{@value #PANEL_H}（{@code Widget.setSize(int,int)} 是 public final），
     * ② 追加 3 行，③ 把<b>它那个</b> {@code IntInputWidget} 拿来做禁用态。
     * <b>上游的控件对象、坐标、内容一个都没动。</b>
     */
    public static void appendTo(WidgetGroup group, AutoForgeMultiplierHost host) {
        group.setSize(PANEL_W, PANEL_H);

        // 上游那台的输入框是它自己建的；本工程那台是 buildFull 刚建好放进去的。两种都在这儿取到。
        final IntInputWidget input = firstInput(group);

        // ── 新增①：「自动倍率」开关（照第①行同款） ──
        group.addWidget(new ToggleButtonWidget(
                6, AUTO_TOGGLE_Y, 20, 20,
                GuiTextures.BUTTON_POWER,
                host::isAutoForgeMultiplierEnabled,
                host::setAutoForgeMultiplierEnabled)
                .setTooltipText(LANG_AUTO));
        group.addWidget(new LabelWidget(32, AUTO_LABEL_Y, LANG_AUTO));

        // ── 新增②③：两行状态。⚠️ 必须用 Supplier（每帧现读），否则「开关一拨而读数不变」
        //     = 活的界面上放死数据。第二行那个 Label 同时兼作「逐帧对齐禁用态」的钩子。 ──
        group.addWidget(new AutoStatusLabel(6, STATUS_Y, host, input));
        group.addWidget(new LabelWidget(6, REASON_Y, host::shanhaiAutoForgeMultiplierReasonText));

        applyAutoDisabled(host.isAutoForgeMultiplierEnabled(), input);
    }

    // ══════════════════════════ 工具 ══════════════════════════

    /** 取面板里那个倍率输入框（上游那台拿的是上游自己建的那个）。 */
    private static IntInputWidget firstInput(WidgetGroup group) {
        final List<IntInputWidget> found = group.getWidgetsByType(IntInputWidget.class);
        return found.isEmpty() ? null : found.get(0);
    }

    /** 取 {@code IntInputWidget} 内部那个 {@code TextFieldWidget}（取不到就 null）。 */
    private static TextFieldWidget innerTextField(IntInputWidget widget) {
        if (widget == null) {
            return null;
        }
        final List<TextFieldWidget> found = widget.getWidgetsByType(TextFieldWidget.class);
        return found.isEmpty() ? null : found.get(0);
    }

    /** 把「自动开着 ⇒ 倍率输入框禁用」施加到控件上（点不动 + 看得出来，两半缺一不可）。 */
    private static void applyAutoDisabled(boolean autoOn, IntInputWidget input) {
        if (input == null) {
            return;     // 上游面板里没有输入框（理论上不会）⇒ 不做假动作
        }
        final boolean enabled = !autoOn;
        input.setActive(enabled);
        final TextFieldWidget inner = innerTextField(input);
        if (inner != null) {
            inner.setTextColor(enabled ? TEXT_ENABLED : TEXT_DISABLED);
        }
    }

    /**
     * 面板第一行那个 Label —— <b>兼作逐帧钩子</b>。
     *
     * <p>为什么用 LabelWidget 子类而不是外层 WidgetGroup 子类：上游那台的面板 group
     * <b>是上游自己的对象</b>，我们不能改它的类；而往里面 addWidget 一个我们自己的子类控件是允许的。
     * 依据（{@code javap -c}）：{@code WidgetGroup.updateScreen()} 遍历 children 逐个调
     * {@code updateScreen()}（只对 {@code isActive()} 的孩子调）⇒ 这个 Label 每帧都会被调到。
     */
    static final class AutoStatusLabel extends LabelWidget {

        private final AutoForgeMultiplierHost host;
        private final IntInputWidget input;
        private boolean lastAutoOn;

        AutoStatusLabel(int x, int y, AutoForgeMultiplierHost host, IntInputWidget input) {
            super(x, y, host::shanhaiAutoForgeMultiplierText);
            this.host = host;
            this.input = input;
            this.lastAutoOn = host.isAutoForgeMultiplierEnabled();
        }

        @Override
        public void updateScreen() {
            super.updateScreen();
            final boolean on = this.host.isAutoForgeMultiplierEnabled();
            if (on == this.lastAutoOn) {
                return;     // 状态没变 ⇒ 一个字节都不动（不做无谓的写）
            }
            this.lastAutoOn = on;
            applyAutoDisabled(on, this.input);
        }
    }
}
