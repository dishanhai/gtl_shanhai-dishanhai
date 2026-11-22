package com.shanhai.machine.host;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfigurator;
import com.gregtechceu.gtceu.api.gui.widget.IntInputWidget;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 归墟视界枢核 · 侧栏里那<b>一个</b>「渲染参数」面板（2026-10-25 第二十一改：三个按钮合并成一个）。
 *
 * <h2>用户原话（逐字）</h2>
 * <pre>
 *   「把主机那 3 个调整渲染的按钮<b>合并成一个</b>吧，然后再<b>新增一个功能开关</b>
 *    （是否在 AR 屏幕背后添加<b>灰色半透明遮罩</b>（这样字可以看得更清楚））」
 * </pre>
 *
 * <h2>合并前是哪三个 tab（都在基类 {@code PrimordialOmegaEngineMachine#attachConfigurators} 里挂）</h2>
 * <pre>
 *   ③「黑洞」（上一轮由「中心球体渲染风格」改来）        ⇒ 黑洞半径 IntInputWidget + 引力透镜开关
 *   ⑤「角速度」（上一轮由「中子星渲染/大小与颜色」改来）  ⇒ 内环 / 上舰 / 下舰 三个 IntInputWidget
 *   ⑥「特效渲染」（上一轮由「始终渲染为工作状态」改来）    ⇒ 一个 IFancyConfiguratorButton.Toggle（无面板）
 * </pre>
 * ⇒ 现在只有<b>一个</b> tab「渲染参数」，点开一次就同时给出这 6 项 + 新增的第 7 项（AR 遮罩）。
 *
 * <h2>🔴 字段与语义一个字都没改（只是换了 UI 摆在哪）</h2>
 * <pre>
 *   hostRingDegPerSec / hostShipUpDegPerSec / hostShipDownDegPerSec   (°/s，整数，setter 里钳位)
 *   hostBlackHoleRadiusTenths                                          (×0.1 格，10..120)
 *   hostLensEnabled / hostFxEnabled / hostArMaskEnabled                (三个开关)
 * </pre>
 * 全部仍是 {@code @Persisted @DescSynced} 机器字段，读写仍是原来那对 getter/setter。
 *
 * <h2>面板尺寸（算出来的，不是拍的）</h2>
 * {@code ConfiguratorPanel$Tab} 的字节码原文：
 * <pre>
 *   view.setSize(new Size(widget.getWidth() + border * 2,                     // 宽 = 面板宽 + 8
 *                         widget.getHeight() + button.getHeight() + border)); // 高 = 面板高 + 24 + 4
 * </pre>
 * ⇒ 本面板 {@code 150 × 168} ⇒ 弹出面板 <b>158 × 192</b>（比它替换掉的「角速度」面板 158×144 高 48px）。
 * 每一行都是逐像素排的（控件越过 {@code PANEL_H} 时 LDLib <b>不报错、只被父容器裁掉</b> ⇒ 静默消失）：
 * <pre>
 *   y=3    标题「渲染参数（°/s · 洞径 ×0.1 格）」               底 11
 *   y=17   内环   Label(4,20) + IntInput(64,17,82,20)           底 37
 *   y=39   上舰   Label(4,42) + IntInput(64,39,82,20)           底 59
 *   y=61   下舰   Label(4,64) + IntInput(64,61,82,20)           底 81
 *   y=83   洞径   Label(4,86) + IntInput(64,83,82,20)           底 103
 *   y=105  引力透镜 Button(4,105,72,16)+Label(10,109)           底 121
 *          AR遮罩   Button(74,105,72,16)+Label(80,109)
 *   y=125  特效渲染 Button(4,125,72,16)+Label(10,129)           底 141
 *          环显示   Button(74,125,72,16)+Label(80,129)           ← 2026-11-21 新增，只占右半格那个空位
 *   y=147  星辰场   Button(4,147,72,16)+Label(10,151)            底 163  ← 2026-11-22 新增
 *          结构闪电 Button(74,147,72,16)+Label(80,151)
 *   → 163 + 5 = 168（{@link #PANEL_H}）
 * </pre>
 * 🔴 2026-11-21 新增「环显示」三态时<b>面板尺寸一个字都没改</b>：它落在 {@code y=125} 那一行
 * <b>原本空着的右半格</b>（{@code x=74..146}）。
 * <p>🔴 2026-11-22 新增「星辰场 / 结构闪电」时<b>只把 {@code PANEL_H} 从 146 抬到 168</b>
 * （= 行高 16 + 行距 6 = 22px）：{@code PANEL_W=150} 与既有 6 行的坐标<b>一个都没动</b>，
 * 新行落在 {@code y=147}（按钮底 163，留 5px）。
 *
 * <p>输入框内部布局由 {@code NumberInputWidget.buildUI} 算：{@code buttonWidth = clamp(w/5,15,40)}
 * ⇒ 82 宽 ⇒ 两个 ± 各 16、文本框 <b>50 px</b>（三位数「120」= 18 px，够）。
 *
 * <h2>🔴 六个控件为什么都是「铺满按钮 + 上面盖一行字」</h2>
 * LDLib 的 {@code ButtonWidget} <b>没有 setText</b>（javap 全量方法表里没有）
 * ⇒ 沿用本 GUI 已有的写法（同列「并行数」面板的「电力自动」开关、上一轮的黑洞面板）。
 * ⚠️ 盖上去的 {@code LabelWidget} 必须用 <b>Supplier 构造器</b>，否则开关状态在界面上不会变
 * （活的界面放死数据是本工程红线）。
 * <p>第四件「环显示」是<b>三态</b>（不是开关）：同一套写法 + 点击循环 0→1→2→0，
 * 标签文字直接读机器字段 {@code hostRingVisualMode}（同样是 Supplier，不是 build 期快照）。
 * <p>第五、第六件（星辰场 / 结构闪电）是普通开关，🔴 但它们的<b>客户端配置</b>
 * （{@code [hostfx] starField / structureLightning}）<b>仍然是总闸</b>：配置关 ⇒ 强制不画，
 * 面板关 ⇒ 这一台机器不画；两者是「与」关系（口径在 {@code HostFxSwitch} 一处）。
 *
 * <h2>为什么这里可以在 common 包里 new {@code GuiTextures}</h2>
 * 本类的方法只会在<b>客户端 GUI 打开时</b>被调用（{@code attachConfigurators} 的那一支），
 * 与基类里原来那三处写法完全同源 —— 所以贴图对象必须<b>在方法内现建</b>，不能放进 static 字段
 * （{@code GuiTextures} 是客户端类，专用服务端加载本类时不许碰它）。
 */
public final class HostFxConfigurators {

    /** 面板宽（弹出面板 = 本宽 + 8）。 */
    private static final int PANEL_W = 150;

    /**
     * 面板高 = <b>168</b>（弹出面板 158×192）。
     *
     * <p>逐行算的，见类注释那张表：最后一个控件（结构闪电按钮）底边 {@code y=163}，留 5 px。
     * <b>再加一行就需要同步加高</b>，否则那一行会被静默裁掉。
     * <p>2026-11-21 之前是 146（最后一行 141+5）；2026-11-22 加「星辰场 / 结构闪电」那一行时
     * 一次性 +22（= 行高 16 + 行距 6），既有 6 行坐标一个都没动。
     */
    private static final int PANEL_H = 168;

    /** 输入框的 x/宽（与标签列 4..60 不重叠）。 */
    private static final int INPUT_X = 64;
    private static final int INPUT_W = 82;
    private static final int INPUT_H = 20;

    private HostFxConfigurators() {
    }

    /**
     * 唯一的入口：造那<b>一个</b>「渲染参数」面板。
     *
     * @param host 归墟视界枢核（只在客户端 GUI 打开时调用；{@code null} 会 NPE —— 调用方必须先判 {@code instanceof}）
     */
    public static IFancyConfigurator renderPanel(GuixuVisionCoreMachine host) {
        // ⚠️ 图标现建：GuiTextures 是客户端侧常量类，不能进 static 字段（见类注释末节）。
        //    图标沿用原来「球体风格/黑洞」那枚（BUTTON_SWITCH_VIEW 上半帧）⇒ 玩家在原位置仍认得它。
        return new RenderPanelConfigurator(host,
                GuiTextures.BUTTON_SWITCH_VIEW.getSubTexture(0.0D, 0.0D, 1.0D, 0.5D));
    }

    /** 「渲染参数」面板本体（包内私有嵌套类：只被 {@link #renderPanel} 造一次）。 */
    private static final class RenderPanelConfigurator implements IFancyConfigurator {

        private final GuixuVisionCoreMachine host;
        private final IGuiTexture icon;

        RenderPanelConfigurator(GuixuVisionCoreMachine host, IGuiTexture icon) {
            this.host = host;
            this.icon = icon;
        }

        @Override
        public Component getTitle() {
            // 字面量而不是 translatable：与本工程既有约定一致（缺 lang 条目时 translatable 会把裸 key 画在 GUI 上）。
            return Component.literal("渲染参数");
        }

        @Override
        public IGuiTexture getIcon() {
            // 每帧都会被 drawInBackground 调一次 ⇒ 建好一次、之后只返引用（不要每帧 new 纹理）。
            return icon;
        }

        @Override
        public List<Component> getTooltips() {
            return List.of(
                    Component.literal("渲染参数：角速度 / 黑洞 / 遮罩 / 特效").withStyle(ChatFormatting.AQUA),
                    Component.literal("§7内环 §f" + GuixuVisionCoreMachine.HOST_RING_DEG_MIN + "–"
                                    + GuixuVisionCoreMachine.HOST_RING_DEG_MAX + "§7°/s · 上舰/下舰 §f"
                                    + GuixuVisionCoreMachine.HOST_SHIP_DEG_MIN + "–"
                                    + GuixuVisionCoreMachine.HOST_SHIP_DEG_MAX + "§7°/s")
                            .withStyle(ChatFormatting.GRAY),
                    Component.literal("§7洞径 §f" + GuixuVisionCoreMachine.HOST_HOLE_RADIUS_TENTHS_MIN + "–"
                                    + GuixuVisionCoreMachine.HOST_HOLE_RADIUS_TENTHS_MAX
                                    + "§7（×0.1 格，§f52§7 = §f5.2§7 格）")
                            .withStyle(ChatFormatting.GRAY),
                    Component.literal("§7六个开关：引力透镜 / AR 遮罩 / 特效渲染 / 环显示（三态） / 星辰场 / 结构闪电")
                            .withStyle(ChatFormatting.GRAY),
                    Component.literal("§8星辰场 / 结构闪电：客户端的 [hostfx] 配置仍是总闸，"
                                    + "配置关 ⇒ 强制不画，面板关 ⇒ 本机不画")
                            .withStyle(ChatFormatting.GRAY),
                    Component.literal("改的是机器状态，").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal("所有人都会看到").withStyle(ChatFormatting.GOLD))
                            .append(Component.literal("（会随存档保存）").withStyle(ChatFormatting.GRAY)));
        }

        @Override
        public Widget createConfigurator() {
            final WidgetGroup group = new WidgetGroup(0, 0, PANEL_W, PANEL_H);
            group.setBackground(GuiTextures.BACKGROUND_INVERSE);

            group.addWidget(new LabelWidget(4, 3, "§b§l渲染参数 §8（°/s · 洞径 ×0.1 格）"));

            // ── 第 1 行：内环角速度 ──────────────────────────────────────────────
            group.addWidget(new LabelWidget(4, 20, "§7内环§8°/s"));
            group.addWidget(new IntInputWidget(INPUT_X, 17, INPUT_W, INPUT_H,
                    host::getHostRingDegPerSec, host::setHostRingDegPerSec)
                    .setMin(GuixuVisionCoreMachine.HOST_RING_DEG_MIN)
                    .setMax(GuixuVisionCoreMachine.HOST_RING_DEG_MAX)
                    .setHoverTooltips(
                            Component.literal("§b内环带自转速度"),
                            Component.literal("§7单位 §f°/s§7（每秒转多少度）"),
                            Component.literal("§7范围 §f" + GuixuVisionCoreMachine.HOST_RING_DEG_MIN + "–"
                                    + GuixuVisionCoreMachine.HOST_RING_DEG_MAX + "§7，默认 §f"
                                    + GuixuVisionCoreMachine.HOST_RING_DEG_DEFAULT),
                            Component.literal("§8点击 − / + 微调，或直接在框里输数字"),
                            Component.literal("§8· 光柱环（内外环之间那圈粗光柱）跟着内环一起转")));

            // ── 第 2 行：上 4 舰角速度 ───────────────────────────────────────────
            group.addWidget(new LabelWidget(4, 42, "§7上舰§8°/s"));
            group.addWidget(new IntInputWidget(INPUT_X, 39, INPUT_W, INPUT_H,
                    host::getHostShipUpDegPerSec, host::setHostShipUpDegPerSec)
                    .setMin(GuixuVisionCoreMachine.HOST_SHIP_DEG_MIN)
                    .setMax(GuixuVisionCoreMachine.HOST_SHIP_DEG_MAX)
                    .setHoverTooltips(
                            Component.literal("§b上组 4 艘飞船的绕行速度"),
                            Component.literal("§7单位 §f°/s§7，范围 §f"
                                    + GuixuVisionCoreMachine.HOST_SHIP_DEG_MIN + "–"
                                    + GuixuVisionCoreMachine.HOST_SHIP_DEG_MAX
                                    + "§7，默认 §f" + GuixuVisionCoreMachine.HOST_SHIP_UP_DEG_DEFAULT),
                            Component.literal("§8方向与内环相同（顺行）"),
                            Component.literal("§8· 填 §f0 §8= 这一组不转")));

            // ── 第 3 行：下 4 舰角速度 ───────────────────────────────────────────
            group.addWidget(new LabelWidget(4, 64, "§7下舰§8°/s"));
            group.addWidget(new IntInputWidget(INPUT_X, 61, INPUT_W, INPUT_H,
                    host::getHostShipDownDegPerSec, host::setHostShipDownDegPerSec)
                    .setMin(GuixuVisionCoreMachine.HOST_SHIP_DEG_MIN)
                    .setMax(GuixuVisionCoreMachine.HOST_SHIP_DEG_MAX)
                    .setHoverTooltips(
                            Component.literal("§b下组 4 艘飞船的绕行速度"),
                            Component.literal("§7单位 §f°/s§7，范围 §f"
                                    + GuixuVisionCoreMachine.HOST_SHIP_DEG_MIN + "–"
                                    + GuixuVisionCoreMachine.HOST_SHIP_DEG_MAX
                                    + "§7，默认 §f" + GuixuVisionCoreMachine.HOST_SHIP_DOWN_DEG_DEFAULT),
                            Component.literal("§8🔴 填正数即可：方向本来就是逆向（渲染侧取负号）"),
                            Component.literal("§8· 填 §f0 §8= 这一组不转")));

            // ── 第 4 行：黑洞视界半径（标签带实时读数，省一行） ─────────────────
            group.addWidget(new LabelWidget(4, 86, this::holeRadiusLabel));
            group.addWidget(new IntInputWidget(INPUT_X, 83, INPUT_W, INPUT_H,
                    host::getHostBlackHoleRadiusTenths, host::setHostBlackHoleRadiusTenths)
                    .setMin(GuixuVisionCoreMachine.HOST_HOLE_RADIUS_TENTHS_MIN)
                    .setMax(GuixuVisionCoreMachine.HOST_HOLE_RADIUS_TENTHS_MAX)
                    .setHoverTooltips(
                            Component.literal("§b黑洞视界半径"),
                            Component.literal("§7单位 §f×0.1 格§7：填 §f52§7 就是 §f5.2§7 格"),
                            Component.literal("§7范围 §f"
                                    + GuixuVisionCoreMachine.HOST_HOLE_RADIUS_TENTHS_MIN + "–"
                                    + GuixuVisionCoreMachine.HOST_HOLE_RADIUS_TENTHS_MAX
                                    + "§7，默认 §f" + GuixuVisionCoreMachine.HOST_HOLE_RADIUS_TENTHS_DEFAULT
                                    + "§8（= 5.2 格，改动前的实际半径）"),
                            Component.literal("§8点击 − / + 微调，或直接在框里输数字"),
                            Component.literal("§8· 6 根主光柱的端点跟着它走（洞变小 ⇒ 光柱变长）")));

            // ── 第 5 行：引力透镜 + AR 遮罩（两开关并排） ───────────────────────
            group.addWidget(new ButtonWidget(4, 105, 72, 16, GuiTextures.BUTTON,
                    clickData -> host.setHostLensEnabled(!host.isHostLensEnabled()))
                    .setHoverTooltips(
                            Component.literal("§b引力透镜"),
                            Component.literal("§7开：背景与星空被黑洞的引力弯折（真透镜）"),
                            Component.literal("§7关：只画黑盘 + 光子环 + 吸积盘，不做背景弯折"),
                            Component.literal("§8默认 §f开§8（= 改动前的画面）")));
            group.addWidget(new LabelWidget(10, 109, this::lensLabelText));

            group.addWidget(new ButtonWidget(74, 105, 72, 16, GuiTextures.BUTTON,
                    clickData -> host.setHostArMaskEnabled(!host.isHostArMaskEnabled()))
                    .setHoverTooltips(
                            Component.literal("§b玻璃 AR 屏背后的灰色半透明遮罩"),
                            Component.literal("§7开：AR 区域先铺一层中性灰半透明底，字看得更清楚"),
                            Component.literal("§7关：遮罩整块不画，退回改动前的观感（直接看穿玻璃）"),
                            Component.literal("§8默认 §f开§8；改的是机器状态，所有人都会看到")));
            group.addWidget(new LabelWidget(80, 109, this::arMaskLabelText));

            // ── 第 6 行：特效渲染总开关 ─────────────────────────────────────────
            group.addWidget(new ButtonWidget(4, 125, 72, 16, GuiTextures.BUTTON,
                    clickData -> host.setHostFxEnabled(!host.isHostFxEnabled()))
                    .setHoverTooltips(
                            Component.literal("§b特效渲染总开关"),
                            Component.literal("§7开：内环 / 上下 8 舰按角速度转，光柱与光柱环全部照画"),
                            Component.literal("§7关：环与舰停在当前姿态、中央两束变红、"
                                    + "解压机主光柱消失（12 条细紫小束与黑洞照画）"),
                            Component.literal("§8只管渲染特效：机壳/贴图的工作态外观不受它影响")));
            group.addWidget(new LabelWidget(10, 129, this::fxLabelText));

            // ── 第 6 行右半格：「环显示」三态（外缘吸积流 ⇄ 粗光柱环）──────────────
            // 🔴 2026-11-21：这一格原本空着（第 6 行只有左边那个「特效渲染」）
            //    ⇒ 面板尺寸（PANEL_W/PANEL_H）与所有既有控件坐标一个字节都没改。
            group.addWidget(new ButtonWidget(74, 125, 72, 16, GuiTextures.BUTTON,
                    clickData -> host.setHostRingVisualMode(nextRingVisualMode()))
                    .setHoverTooltips(
                            Component.literal("§b环显示：两条 / 只吸积流 / 只光柱环"),
                            Component.literal("§7外缘吸积流：§f51..57 格§7那圈等离子流（外缘、薄盘）"),
                            Component.literal("§7粗光柱环：§fr=54、管径 6§7 那圈很粗的光柱环"),
                            Component.literal("§8两条不是「换掉一条」：默认两条都画，这里只选显示哪一条"),
                            Component.literal("§8· 这是玩家自己的选择，会随存档保存；所有人都会看到"),
                            Component.literal("§8默认 §f两条")));
            group.addWidget(new LabelWidget(80, 129, this::ringVisualLabelText));

            // ── 第 7 行（y=147）：星辰场 + 结构闪电（2026-11-22 新增）──────────────────
            // 🔴 用户原话：「这里我让你加的开关你怎么没加，就是结构闪电还有这个围绕黑洞的粒子的开关」
            //    ⇒ 这两个以前只能在客户端配置 [hostfx] 里关，现在面板上也能点。
            //    ⚠️ 配置**仍然是总闸**（配置关 ⇒ 强制不画），面板关 ⇒ 本机不画；两者是「与」关系。
            //    面板尺寸：只把 PANEL_H 从 146 抬到 168（+22），既有 6 行坐标一个都没动。
            group.addWidget(new ButtonWidget(4, 147, 72, 16, GuiTextures.BUTTON,
                    clickData -> host.setHostStarFieldEnabled(!host.isHostStarFieldEnabled()))
                    .setHoverTooltips(
                            Component.literal("§b星辰场（围绕黑洞的那些粒子）"),
                            Component.literal("§7开：围绕黑洞的星尘/星空粒子照画（≤1256 quads）"),
                            Component.literal("§7关：这一层整段不提交 —— 黑洞、环、光柱都不受影响"),
                            Component.literal("§8· 客户端配置 [hostfx] starField 仍是总闸："
                                    + "配置关 ⇒ 强制不画（面板打开也没用）"),
                            Component.literal("§8· 改的是机器状态，会随存档保存；所有人都会看到"),
                            Component.literal("§8默认 §f开")));
            group.addWidget(new LabelWidget(10, 151, this::starFieldLabelText));

            group.addWidget(new ButtonWidget(74, 147, 72, 16, GuiTextures.BUTTON,
                    clickData -> host.setHostStructureLightningEnabled(!host.isHostStructureLightningEnabled()))
                    .setHoverTooltips(
                            Component.literal("§b结构闪电（机器表面跳来跳去的电弧）"),
                            Component.literal("§7开：结构表面逐格核对后画 ≤12 条电弧（第二次核对 250 ms 一次）"),
                            Component.literal("§7关：整件不提交 —— 不再做世界核对，也不建结构模型"),
                            Component.literal("§8· 客户端配置 [hostfx] structureLightning 仍是总闸："
                                    + "配置关 ⇒ 强制不画（面板打开也没用）"),
                            Component.literal("§8· 改的是机器状态，会随存档保存；所有人都会看到"),
                            Component.literal("§8默认 §f开")));
            group.addWidget(new LabelWidget(80, 151, this::lightningLabelText));

            return group;
        }

        /** 第 4 行标签：`洞径 5.2格`（Supplier ⇒ 每帧现读，不是 build 期快照）。 */
        private String holeRadiusLabel() {
            return "§7洞径 §f" + String.format(Locale.ROOT, "%.1f", host.getHostBlackHoleRadiusBlocks()) + "§7格";
        }

        private String lensLabelText() {
            return host.isHostLensEnabled() ? "§a引力透镜：开" : "§8引力透镜：关";
        }

        private String arMaskLabelText() {
            return host.isHostArMaskEnabled() ? "§aAR遮罩：开" : "§8AR遮罩：关";
        }

        private String fxLabelText() {
            return host.isHostFxEnabled() ? "§a特效渲染：开" : "§8特效渲染：关";
        }

        /**
         * 第 6 行右侧标签：<b>环显示三态</b>（与机器字段 {@code hostRingVisualMode} 一一对应）。
         *
         * <p>三行字互为一一映射，面板上看到什么就是渲染侧 {@code HostFxSwitch} 会读到什么
         * （Supplier ⇒ 每帧现读；点击后同一帧就变）。
         */
        private String ringVisualLabelText() {
            switch (host.getHostRingVisualMode()) {
                case GuixuVisionCoreMachine.HOST_RING_VISUAL_ACCRETION_ONLY:
                    return "§a环：吸积流";
                case GuixuVisionCoreMachine.HOST_RING_VISUAL_BEAM_RING_ONLY:
                    return "§a环：光柱环";
                default:
                    return "§a环：两条";
            }
        }

        /** 点击一次走一格：0 → 1 → 2 → 0（越界由机器侧 setter 再夹一次）。 */
        private int nextRingVisualMode() {
            return (host.getHostRingVisualMode() + 1) % 3;
        }

        /**
         * 第 7 行左侧标签：<b>星辰场</b>（与机器字段 {@code hostStarFieldEnabled} 一一对应）。
         *
         * <p>⚠️ 它只显示<b>面板值</b>；客户端配置总闸（{@code [hostfx] starField}）关掉时画面照样不画，
         * 但标签仍显示面板选的是什么 —— 否则玩家会以为"点了没生效"（配置在客户端，机器侧不可见）。
         * tooltip 与类注释已写明这条「与」关系。
         */
        private String starFieldLabelText() {
            return host.isHostStarFieldEnabled() ? "§a星辰场：开" : "§8星辰场：关";
        }

        /** 第 7 行右侧标签：<b>结构闪电</b>（与机器字段 {@code hostStructureLightningEnabled} 一一对应）。 */
        private String lightningLabelText() {
            return host.isHostStructureLightningEnabled() ? "§a结构闪电：开" : "§8结构闪电：关";
        }
    }

    /** 供外部（报告/自检）读的尺寸，避免"文档里写 150×168、代码里改了常量却没人知道"。 */
    public static List<String> layoutTable() {
        final List<String> rows = new ArrayList<>(8);
        rows.add("面板 " + PANEL_W + "×" + PANEL_H + "（弹出面板 " + (PANEL_W + 8) + "×" + (PANEL_H + 24) + "）");
        rows.add("标题 Label(4,3)");
        rows.add("内环 Label(4,20) + IntInput(" + INPUT_X + ",17," + INPUT_W + "," + INPUT_H + ")");
        rows.add("上舰 Label(4,42) + IntInput(" + INPUT_X + ",39," + INPUT_W + "," + INPUT_H + ")");
        rows.add("下舰 Label(4,64) + IntInput(" + INPUT_X + ",61," + INPUT_W + "," + INPUT_H + ")");
        rows.add("洞径 Label(4,86) + IntInput(" + INPUT_X + ",83," + INPUT_W + "," + INPUT_H + ")");
        rows.add("引力透镜 Button(4,105,72,16)+Label(10,109) ／ AR遮罩 Button(74,105,72,16)+Label(80,109)");
        rows.add("特效渲染 Button(4,125,72,16)+Label(10,129)");
        rows.add("环显示 Button(74,125,72,16)+Label(80,129)（三态：两条 / 吸积流 / 光柱环）");
        rows.add("星辰场 Button(4,147,72,16)+Label(10,151) ／ 结构闪电 Button(74,147,72,16)+Label(80,151)");
        return rows;
    }
}
