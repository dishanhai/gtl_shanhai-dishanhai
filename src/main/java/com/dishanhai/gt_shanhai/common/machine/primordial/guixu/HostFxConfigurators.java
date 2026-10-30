package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

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
 * ⇒ 一个 tab「渲染参数」容納原有控制、AR 遮罩及外層星辰／星雲獨立開關。
 *
 * <h2>🔴 字段与语义一个字都没改（只是换了 UI 摆在哪）</h2>
 * <pre>
 *   hostRingDegPerSec / hostShipUpDegPerSec / hostShipDownDegPerSec   (°/s，整数，setter 里钳位)
 *   hostBlackHoleRadiusTenths                                          (×0.1 格，10..120)
 *   hostLensEnabled / hostFxEnabled / hostArMaskEnabled                (三个开关)
 *   hostOuterStarsEnabled / hostNebulaEnabled                         (外層獨立開關)
 * </pre>
 * 全部仍是 {@code @Persisted @DescSynced} 机器字段，读写仍是原来那对 getter/setter。
 *
 * <h2>面板尺寸（算出来的，不是拍的）</h2>
 * {@code ConfiguratorPanel$Tab} 的字节码原文：
 * <pre>
 *   view.setSize(new Size(widget.getWidth() + border * 2,                     // 宽 = 面板宽 + 8
 *                         widget.getHeight() + button.getHeight() + border)); // 高 = 面板高 + 24 + 4
 * </pre>
 * ⇒ 本面板 {@code 150 × 166}，新增外層開關列並保留底部 5 px。
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
 *   y=145  外層星辰／外層星雲兩個獨立按鈕，底 161
 *   → 161 + 5 = 166（{@link #PANEL_H}）
 * </pre>
 * 输入框内部布局由 {@code NumberInputWidget.buildUI} 算：{@code buttonWidth = clamp(w/5,15,40)}
 * ⇒ 82 宽 ⇒ 两个 ± 各 16、文本框 <b>50 px</b>（三位数「120」= 18 px，够）。
 *
 * <h2>🔴 三个开关为什么是「铺满按钮 + 上面盖一行字」</h2>
 * LDLib 的 {@code ButtonWidget} <b>没有 setText</b>（javap 全量方法表里没有）
 * ⇒ 沿用本 GUI 已有的写法（同列「并行数」面板的「电力自动」开关、上一轮的黑洞面板）。
 * ⚠️ 盖上去的 {@code LabelWidget} 必须用 <b>Supplier 构造器</b>，否则开关状态在界面上不会变
 * （活的界面放死数据是本工程红线）。
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
     * 面板高 = <b>166</b>，外層開關列底邊 161，保留 5 px。
     *
     * <b>再加一行就需要同步加高</b>，否则那一行会被静默裁掉。
     */
    private static final int PANEL_H = 166;

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
                    Component.literal("§7三个开关：引力透镜 / AR 遮罩 / 特效渲染")
                            .withStyle(ChatFormatting.GRAY),
                    Component.literal("§7外层开关：外层星辰 / 外层星云")
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

            // 山海：外層效果可完全停繪，與原本只降級動畫的特效按鈕分開。
            group.addWidget(new ButtonWidget(4, 145, 68, 16, GuiTextures.BUTTON,
                    clickData -> host.setHostOuterStarsEnabled(!host.isHostOuterStarsEnabled()))
                    .setHoverTooltips(
                            Component.literal("§b外层星辰"),
                            Component.literal("§7控制背景星空、环绕星尘与流星；关后完全停止绘制"),
                            Component.literal("§8不影响黑洞内侧的吸入星辰；随主机存档并同步给所有玩家")));
            group.addWidget(new LabelWidget(8, 149, this::outerStarsLabelText));
            group.addWidget(new ButtonWidget(74, 145, 72, 16, GuiTextures.BUTTON,
                    clickData -> host.setHostNebulaEnabled(!host.isHostNebulaEnabled()))
                    .setHoverTooltips(
                            Component.literal("§b外层星云"),
                            Component.literal("§7控制结构周围的紫蓝云雾；关后完全停止绘制"),
                            Component.literal("§8与外层星辰独立；随主机存档并同步给所有玩家")));
            group.addWidget(new LabelWidget(78, 149, this::nebulaLabelText));

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

        private String outerStarsLabelText() {
            return host.isHostOuterStarsEnabled() ? "§a外层星辰：开" : "§8外层星辰：关";
        }

        private String nebulaLabelText() {
            return host.isHostNebulaEnabled() ? "§a外层星云：开" : "§8外层星云：关";
        }
    }

    /** 供外部報告／自檢讀取實際尺寸。 */
    public static List<String> layoutTable() {
        final List<String> rows = new ArrayList<>(9);
        rows.add("面板 " + PANEL_W + "×" + PANEL_H + "（弹出面板 " + (PANEL_W + 8) + "×" + (PANEL_H + 24) + "）");
        rows.add("标题 Label(4,3)");
        rows.add("内环 Label(4,20) + IntInput(" + INPUT_X + ",17," + INPUT_W + "," + INPUT_H + ")");
        rows.add("上舰 Label(4,42) + IntInput(" + INPUT_X + ",39," + INPUT_W + "," + INPUT_H + ")");
        rows.add("下舰 Label(4,64) + IntInput(" + INPUT_X + ",61," + INPUT_W + "," + INPUT_H + ")");
        rows.add("洞径 Label(4,86) + IntInput(" + INPUT_X + ",83," + INPUT_W + "," + INPUT_H + ")");
        rows.add("引力透镜 Button(4,105,72,16)+Label(10,109) ／ AR遮罩 Button(74,105,72,16)+Label(80,109)");
        rows.add("特效渲染 Button(4,125,72,16)+Label(10,129)");
        rows.add("外层星辰 Button(4,145,68,16)+Label(8,149) ／ 外层星云 Button(74,145,72,16)+Label(78,149)");
        return rows;
    }
}
