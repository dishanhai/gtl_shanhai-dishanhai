package com.shanhai.machine.creation;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

import java.util.function.Supplier;

/**
 * 「神锻」子页签 —— 原初造物编译矩阵的<b>纯读数</b>页（<b>P10 起一个控件都没有</b>）。
 *
 * <h2>🔴 为什么这个类是<b>我们自己画的</b></h2>
 * 上游（gtladditions / 星律那台）有现成的神锻面板，但本工程硬约束是<b>不许 import
 * {@code org.gtlcore} / {@code com.gtladd} 的任何东西</b>，那个面板的实现挂在它们的 host/reader 上。
 * ⇒ 这里只照它的<b>形态</b>自己摆，不引用任何上游面板类。
 *
 * <h2>P10 用户拍板（原话逐字）</h2>
 * <blockquote>「<b>干脆去掉开关，永远吃主机增产</b>」</blockquote>
 * ⇒ 这一页<b>不再有开关按钮、也没有倍率输入框</b>：倍率恒等于主机增产，玩家没有任何可拨的东西。
 * 页面只剩<b>读数</b>（用户点名的三行 + 保留的并行上限那一行）：
 * <pre>
 *   ① 本机倍率 = 主机增产 ×N（现读）                    ← 就是报给 AE、也是真出货的那个数
 *   ② 报给 AE 的产物数量与实际出货用同一个倍率            ← 正确性的核心（两处同一个函数）
 *   ③ 主机增产 = 1 + 物质模块等级（归墟门控生效再 +5）    ← 那个 ×N 是怎么算出来的
 *   ④ 并行上限：8^Lv.N = …（无限）                        ← 用户点名要保留，不动
 * </pre>
 *
 * <h2>语义（用户原话，逐字）</h2>
 * <blockquote>「只吃主机增产，那个倍率是你下单是为了方便 ae 计算用的啊，不然它产出 17 个 ae 下单计算时
 * 还以为产出了 1 个，这样就多合成了很多份，这个超级样板总成里面的神锻倍率就是这个意思啊」</blockquote>
 * ⇒ <b>倍率不是手填值，它就是主机增产倍率</b>；它的唯一用途是<b>告诉 AE「一块样板实际出几个」</b>。
 * <pre>
 *   报给 AE 的产物数量 = 样板原产物数量 × 倍率
 *   实际产出的数量     = 样板原产物数量 × 倍率      ← 与上一行必须严格相等
 *   倍率 = 主机增产（现读主机；主机给 0 ⇒ ×1，与改动前逐位相同）
 * </pre>
 *
 * <h2>两侧都会建（同 {@code ParallelOverrideConfigurator} 的纪律）</h2>
 * GUI 的 {@code createMainPage} 在客户端与服务端各跑一次 ⇒ 这里只许建"两侧都安全"的件：
 * 值一律走机器方法（读 {@code @DescSynced} 镜像，不碰 {@code @OnlyIn(CLIENT)} 的东西）。
 * <p>🔴 倍率那一行<b>不许</b>在这里现算：客户端 {@code getHost()} 恒 {@code null} ⇒ 现算恒 ×1（假数据）。
 * 机器侧的 {@code aeMultiplierForDisplay()} 已经按侧分好了（服务端现算 / 客户端读镜像）。
 */
public final class CreationForgePatternPanel implements IFancyUIProvider {

    private static final int WIDTH = 190;
    private static final int HEIGHT = 125;

    private final PrimordialCreationCompilationMatrix machine;

    public CreationForgePatternPanel(PrimordialCreationCompilationMatrix machine) {
        this.machine = machine;
    }

    @Override
    public Component getTitle() {
        return Component.literal("§6神锻");
    }

    @Override
    public IGuiTexture getTabIcon() {
        return new ItemStackTexture(Items.BLAZE_POWDER);
    }

    @Override
    public Widget createMainPage(FancyMachineUIWidget widget) {
        final WidgetGroup group = new WidgetGroup(0, 0, WIDTH, HEIGHT);
        group.setBackground(GuiTextures.BACKGROUND_INVERSE);
        group.addWidget(new LabelWidget(6, 2, () -> "§6神锻§r（纯读数：告诉 AE 一块样板实际出几个）"));

        // ── 三行纯读数 ──
        // ① 本机倍率（= 主机增产；服务端现算、客户端读服务端推下来的镜像）
        group.addWidget(new LabelWidget(6, 18, machine::forgePatternMultiplierLine));
        // ② 报数与出货同源（这一行是这台机器的正确性核心，不是装饰）
        group.addWidget(new LabelWidget(6, 34, machine::forgePatternSameSourceLine));
        // ③ 那个 ×N 怎么来的（解释行：1 + 物质模块等级，归墟门控生效再 +5）
        group.addWidget(new LabelWidget(6, 50, machine::forgePatternHostBonusLine));

        // ── 底部两行 ──
        addBottomLabel(group, "神锻页 y=68", 6, 68,
                () -> "§8倍率变了（换模块 / 门控 / 换主机）⇒ 本机（AE 节点就在控制器上）会让 AE 重算，不会多合份数");
        // 并行上限一行（P4：用户原话「然后并行数的显示你没有改啊」）——
        //   🔴 必须 Supplier（每 tick 现读）：插拔物质模块 / 主机复写等级变化 / 换主机 都要立刻反映。
        //   y=104：本页最后一行在 y=68（高 8px ⇒ 占到 76），104 是唯一整行空位（面板高 125）。
        addBottomLabel(group, "神锻页 y=104（并行上限）", 6, 104, machine::parallelDisplayLine);

        // 🔴 P5 诊断开关（默认关闭）—— 只在 SHANHAI_WIDGET_DUMP=1 时把整棵界面树的
        //    类名 + 文本逐行打出来（用来一次性指认"哪个控件印出那个标记"）。
        CreationWidgetDump.dumpTree("forge-page", widget);
        return group;
    }

    /**
     * 加一行"本页自己画的底部 LabelWidget"——<b>诊断开关打开时不添加，只记一行日志</b>。
     *
     * <p>这是 P5 要求的<b>减法实验</b>：如果 {@code SHANHAI_WIDGET_DUMP=1} 那次实机里那个标记消失了
     * ⇒ 它就是这几行之一（我们画的）；如果仍在 ⇒ 不是我们画的，再用同一次日志里的
     * {@code class=…} 行去抓真正的控件。
     * <p>⚠️ 诊断开关没打开时走的是<b>原路径</b>（照旧 addWidget）⇒ 默认外观一个像素都不变。
     */
    private void addBottomLabel(WidgetGroup group, String where, int x, int y, Supplier<String> text) {
        if (CreationWidgetDump.subtractMode()) {
            String shown;
            try {
                shown = text == null ? "" : text.get();
            } catch (Throwable t) {
                shown = "<supplier 抛异常: " + t + ">";
            }
            CreationWidgetDump.logSubtracted(where, shown);
            return;
        }
        group.addWidget(new LabelWidget(x, y, text));
    }
}
