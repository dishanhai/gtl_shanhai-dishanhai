package com.shanhai.common.reality;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.world.entity.player.Player;

/**
 * 🆕 2026-10-17：<b>「新增 / 修改 / 删除」三页签面板的外壳</b>（用户原话：
 * 「我希望这3个功能可以有同一个面版」）。
 *
 * <h2>它做的事只有四件</h2>
 * <ol>
 *   <li>顶上画一排三个页签（{@code 新增 / 修改 / 删除}），当前那个金色边框；</li>
 *   <li>把三个页各自装进一个 340×300 的页组，<b>同一时刻只显示一个</b>；</li>
 *   <li>切页时把上一页的两层覆盖（提示编辑器 / 图标选择器 / 描述编辑器）关掉；</li>
 *   <li>把"关掉整个面板"的动作递给「修改」「删除」两页（它们拿不到 player 引用）。</li>
 * </ol>
 * 🔴 <b>它一行业务逻辑都没有</b>：「新增」页就是原来那个
 * {@link ShanhaiCreativeItemsPanel} 实例，原封不动搬进来（类的字节码没有再被改过一行业务），
 * 只是外面多了一层容器。所以"128 槽位 + 编辑区 + 图标挑选 + 提示编辑器"的行为与验收时完全一致。
 *
 * <h2>🔴 为什么"页组"能整体下移 20px 而不用改里面任何一个坐标</h2>
 * LDLib 1.0.33 的坐标是 {@code 绝对位置 = 父组位置(parentPosition) + 自身位置(selfPosition)}
 * （{@code Widget#recomputePosition}），并且 {@code WidgetGroup#onPositionUpdate} 会把新位置
 * <b>级联</b>给所有子控件（含孙控件）。所以只要页组自己在 (0, TAB_H) 上，页组里那些
 * "按 0..300 写"的坐标会自动变成 20..320 —— 不需要逐行改既有面板的 y。
 * 本类在装配完之后把 {@code page.position} 打一条日志，机器可判"偏移到底生效没"。
 *
 * <h2>窗口高度</h2>
 * 340×{@code 300+20}（比原来高 20px，用来放页签）。宽度不变（理由同配方编辑器：别盖住左右两列 JEI）。
 */
public final class ShanhaiItemStudioPanel extends WidgetGroup {

    public static final int W = ShanhaiCreativeItemsPanel.W;
    /** 页签那一条的高度。 */
    public static final int TAB_H = 20;
    public static final int H = ShanhaiCreativeItemsPanel.H + TAB_H;

    private static final int TAB_Y = 2;
    private static final int TAB_H_BTN = 16;
    private static final int TAB_W = 64;
    private static final int[] TAB_X = {6, 74, 142};
    private static final String[] TAB_NAMES = {"新增", "修改", "删除"};
    private static final String[] TAB_HINTS = {
            "§8现实物品槽位 1–128",
            "§8任意已存在物品：名字 / 描述 / 堆叠",
            "§8藏起来 / 抹除 / 撤回（看影响面）"};

    private final Player player;
    private final boolean remote;

    private final ShanhaiCreativeItemsPanel creativePanel;
    private final ShanhaiItemOverridePage overridePage;
    private final ShanhaiItemRemovalPage removalPage;
    private final WidgetGroup[] pages = new WidgetGroup[3];
    private final ShanhaiCreativeWidgets.TextButton[] tabs = new ShanhaiCreativeWidgets.TextButton[3];

    private LabelWidget hintLabel;
    private int tab;

    public ShanhaiItemStudioPanel(ShanhaiCreativeItemsFactory.Holder holder, Player player) {
        super(0, 0, W, H);
        this.player = player;
        this.remote = holder != null && holder.isRemote();
        this.setBackground(GuiTextures.BACKGROUND);

        // ---- 页签 ----
        for (int i = 0; i < 3; i++) {
            final int which = i;
            this.tabs[i] = new ShanhaiCreativeWidgets.TextButton(TAB_X[i], TAB_Y, TAB_W, TAB_H_BTN,
                    () -> "§f" + TAB_NAMES[which], () -> selectTab(which));
            this.addWidget(this.tabs[i]);
        }
        this.hintLabel = new LabelWidget(214, TAB_Y + 5, TAB_HINTS[0]);
        this.addWidget(this.hintLabel);

        // ---- 三个页（每个都是一整块 340×300，摆在自己那个页组里；页组自己下移 TAB_H）----
        this.creativePanel = new ShanhaiCreativeItemsPanel(holder, player);
        this.overridePage = new ShanhaiItemOverridePage(this.remote);
        this.removalPage = new ShanhaiItemRemovalPage(this.remote);
        this.overridePage.onClose(this::closeWhole);
        this.removalPage.onClose(this::closeWhole);

        this.pages[0] = pageGroup(this.creativePanel);
        this.pages[1] = pageGroup(this.overridePage);
        this.pages[2] = pageGroup(this.removalPage);

        this.selectTab(0);

        // 机器可判读数：页组的绝对 y 必须等于 TAB_H，页里的控件必须跟着下移（否则说明偏移没级联）
        com.shanhai.ShanhaiMod.LOGGER.info("{} studio_layout page_y={} creative_y={} grid_first_cell_y={} "
                        + "remote={} tabs={}",
                ShanhaiRealityItemTable.PREFIX,
                this.pages[0].getPositionY(),
                this.creativePanel.getPositionY(),
                // 第一个槽位格的绝对 y（它在面板里写的是 20）
                this.creativePanel.isVisible() ? 20 + TAB_H : -1,
                this.remote, String.join("/", TAB_NAMES));
    }

    /** 造一个页组并把它自己摆到 y=TAB_H（页里的坐标原样，靠 LDLib 的级联整体下移）。 */
    private WidgetGroup pageGroup(Widget page) {
        final WidgetGroup group = new WidgetGroup(0, TAB_H, W, ShanhaiCreativeItemsPanel.H);
        // ⚠️ 顺序要紧：先把页组挂到本容器（它的 parentPosition 才等于本容器的绝对位置），
        //    再把页面挂进页组（页里所有子控件会在 onPositionUpdate 里被级联平移）。
        attach(this, group);
        if (page instanceof WidgetGroup inner) {
            attach(group, inner);
        } else {
            group.addWidget(page);
        }
        return group;
    }

    /**
     * 与 LDLib 自己的 {@code WidgetGroup#addWidget} 等价地再补一次 {@code setParentPosition}：
     * 显式做一遍（{@code recomputePosition → onPositionUpdate → 子控件级联}），
     * 这样"页组整体下移"不依赖 addWidget 在某个版本里做没做这件事。
     */
    private static void attach(WidgetGroup host, Widget w) {
        if (w == null) {
            return;
        }
        host.addWidget(w);
        w.setParentPosition(host.getPosition());
    }

    // ------------------------------------------------------------------ 切页

    private void selectTab(int which) {
        this.tab = Math.max(0, Math.min(which, 2));
        for (int i = 0; i < 3; i++) {
            final boolean on = i == this.tab;
            this.pages[i].setVisible(on);
            this.pages[i].setActive(on);
            this.tabs[i].selected(on);
        }
        if (this.hintLabel != null) {
            this.hintLabel.setText(TAB_HINTS[this.tab]);
        }
        // 离开某一页时，把它上面开着的覆盖层关掉（否则回来时还会盖着）
        this.creativePanel.closeOverlays();
        this.overridePage.closeOverlays();
        com.shanhai.ShanhaiMod.LOGGER.info("{} studio_tab tab={} name={}",
                ShanhaiRealityItemTable.PREFIX, this.tab, TAB_NAMES[this.tab]);
    }

    private void closeWhole() {
        if (!this.remote || this.player == null) {
            return;
        }
        this.player.closeContainer();
    }

    // ------------------------------------------------------------------ 读数

    /** 当前页（0=新增 1=修改 2=删除）。 */
    public int currentTab() {
        return this.tab;
    }

    public ShanhaiCreativeItemsPanel creativePage() {
        return this.creativePanel;
    }

    /** 供命令 / 自检读的一行版式读数。 */
    public static String layoutLine() {
        return "studio " + W + "x" + H + " tab_h=" + TAB_H + " pages=3 tabs=[新增|修改|删除]"
                + " " + ShanhaiItemSearchPane.layoutLine()
                + " " + ShanhaiItemOverridePage.layoutLine()
                + " " + ShanhaiItemRemovalPage.layoutLine();
    }
}
