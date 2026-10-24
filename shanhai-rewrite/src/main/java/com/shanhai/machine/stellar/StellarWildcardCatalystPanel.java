package com.shanhai.machine.stellar;

import com.gregtechceu.gtceu.api.machine.fancyconfigurator.CircuitFancyConfigurator;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.lowdragmc.lowdraglib.gui.util.ClickData;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.shanhai.machine.wildcard.ShanhaiCircuitGrid;
import com.shanhai.machine.wildcard.ShanhaiSlotCircuit;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import org.gtlcore.gtlcore.api.gui.MEPatternCatalystUIManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * <b>通配符样板格的中键面板</b>（C 刀 · A 段）——骨架<b>原样复用</b>
 * {@link MEPatternCatalystUIManager}（与「普通样板格」那一页用的是同一个类），
 * 只在它下面再接一段 GTCEu 自带的电路设置。
 *
 * <h2>一、为什么是「照现有普通样板格的做法」，而不是自画一套</h2>
 * 普通样板页那一块是这么造的（本类逐字照抄这段用法，只换数据源）：
 * <pre>
 *   new MEPatternCatalystUIManager(PAGE_W + 4, catalystItems, catalystFluids, cacheRecipeCount, slot -&gt; ...)
 *   page.waitToAdded(panel);
 *   slotWidget.setOnMiddleClick(panel::toggleFor);
 * </pre>
 * 那个类自带三段（{@code javap -p} 实证：{@code toggleFor(int)} / 私有的
 * {@code show(int,IItemTransfer,FluidTransferList)} / {@code createCacheCountInputWidget} /
 * {@code createInventoryContainer} / {@code createFluidContainer}）：
 * <ol>
 *   <li>「实际配方数量」——{@code -1} / 数字输入框 / {@code +1}，写的就是传进去的 {@code byte[]}；</li>
 *   <li>「物品催化剂槽」3×3（{@code IItemTransfer[]} 里那一项，通常 9 格）；</li>
 *   <li>「流体催化剂槽」3×3（{@code FluidTransferList[]} 里那一项，通常 9 罐）。</li>
 * </ol>
 * ⇒ 我们<b>不重画</b>这三段，只把「第 N 个通配符母槽」的三份存储交给它。
 *
 * <h2>二、第四段：33 个编程电路按钮</h2>
 * 用户口径：「33 个编程电路按钮」。那一段就是 GTCEu 的
 * {@link CircuitFancyConfigurator#createConfigurator()} —— 同一份被本工程
 * {@code machine/wildcard/ShanhaiCatalystPanel} 用了两轮的组件（取证见那个类的类注释：
 * {@code createConfigurator()} = {@code WidgetGroup(0,0,174,132)} ＋ 33 个按钮 + 1 个槽）。
 * 本轮<b>再次 javap 复核</b>：{@code public Widget createConfigurator()} ⇒ 它天然可嵌入任意
 * {@code WidgetGroup}（不是只能挂 FancyConfigurator 侧栏）。返回的若是 {@code WidgetGroup}
 * （实测是），就能按坐标把 33 个按钮认出来并接管点击。
 *
 * <h2>三、🔴 点击为什么必须由本类先接一手（照 ShanhaiCatalystPanel 的既有结论）</h2>
 * 父类 {@code MEPatternCatalystUIManager} 覆写了 {@code mouseClicked}：点落在面板矩形内、
 * 且<b>不在任何孙级 WidgetGroup 里</b>时，它把这次点击当「拖面板的手柄」直接吞掉
 * （{@code isDragging = true; return true}），<b>一次都不调 super</b>。
 * 电路段的 33 个按钮就是段的<b>直接</b>子节点（扁平一层）⇒ 不先接一手，按钮永远收不到点击
 * ⇒ 用户看到的是「一张不能交互的贴图」。
 * <p>本轮做法与那台机器完全一致：{@link #mouseClicked} 里<b>先问电路段</b>，
 * 段不要（返回 false）才走 {@code super}（父类那套拖拽语义一个字没改）。
 *
 * <h2>四、🔴 点击怎么到服务端（本类自己发，走最短那条链）</h2>
 * 33 个按钮（＋ ghostCircuit 的「清除」键）的回调被 {@link ButtonWidget#setOnPressCallback}
 * 重绑（该 setter 由 {@code javap -p ButtonWidget} 核实存在）：
 * <ul>
 *   <li><b>客户端</b>：{@link #writeClientAction}（动作号 {@value #ACTION_SET_WILDCARD_CIRCUIT}）——
 *       路由链只有「根组 → 页 → 本面板」三级，且这三级的下标<b>不随面板内容变化</b>；
 *       ⚠️ 不走按钮自带的 {@code id=1} 那条（它要把「根→页→面板→电路段→按钮」逐级现算下标，
 *       而电路段是本类换格时<b>现场挂上/摘下</b>的 ⇒ 下标会漂）。</li>
 *   <li><b>服务端</b>：本类覆写的 {@link #handleClientAction} 收到后落库
 *       （{@code machine.shanhaiApplyWildcardCircuit(母槽, 电路)}）。</li>
 * </ul>
 * ⚠️ {@code handleClientAction} 对不认识的 id <b>必须 {@code super}</b>：
 * {@code WidgetGroup.handleClientAction} 里 {@code id == 1} 那一支是「按子控件下标分发」，
 * 不转发会把面板下面三段催化剂控件的动作全掐掉。
 */
public class StellarWildcardCatalystPanel extends MEPatternCatalystUIManager {

    /**
     * 「点通配符格的电路按钮」的客户端动作号。
     *
     * <p>取 {@code 21330}：与既有的 {@code 21320}/{@code 21321}/{@code 21322}
     * （{@code ShanhaiCircuitSlotWidget} / {@code ShanhaiCatalystPanel}）相邻但不相同。
     * ⚠️ <b>绝不能取 1</b> —— {@code WidgetGroup.handleClientAction} 把 {@code id == 1}
     * 当成「子控件下标分发」，取 1 会把包体当成（下标, 子 id）去解析。
     */
    public static final int ACTION_SET_WILDCARD_CIRCUIT = 21330;

    /** 电路段（GTCEu 那张面板）的原始尺寸，{@code CircuitFancyConfigurator} javap 原文。 */
    private static final int CIRCUIT_SECTION_WIDTH = 174;
    private static final int CIRCUIT_SECTION_HEIGHT = 132;

    /** 电路段与上面催化剂段之间的间隔。 */
    private static final int SECTION_GAP = 4;

    private final StellarSampleAssemblerPartMachine machine;

    /** 代理容器：读/写都落到「当前那一格」的电路上（不落任何影子状态）。 */
    private final ItemStackTransfer circuitSlot = new SlotCircuitTransfer();

    /** 当前被中键选中的通配符母槽（{@code -1} = 还没选过）。 */
    private int currentSlot = -1;

    /** 当前挂在下面的电路段（换格时整个换掉，避免残留旧回调）。 */
    private @Nullable Widget circuitSection;

    /** 「还没有电路段」时面板的基准尺寸（父类 {@code show} 里 {@code setSize} 算出来的那个）。 */
    private int baseWidth = -1;
    private int baseHeight = -1;

    public StellarWildcardCatalystPanel(int x, StellarSampleAssemblerPartMachine machine) {
        super(x,
                machine.shanhaiWildcardCatalystItemTransfers(),
                machine.shanhaiWildcardCatalystFluidTransfers(),
                machine.shanhaiWildcardCacheCountArray(),
                machine::shanhaiOnWildcardCacheCountChanged);
        this.machine = machine;
    }

    /** 当前被选中的母槽号（{@code -1} = 没选过）。 */
    public int shanhaiCurrentSlot() {
        return this.currentSlot;
    }

    /**
     * 中键的落点。照上游语义：同一格再按一次 = 收起；换一格 = 重开并换成那一格的内容。
     *
     * <p>⚠️ 必须在 {@code super.toggleFor} <b>之后</b>再决定要不要补电路段：
     * 父类 {@code show(...)} 第一句是 {@code clearAllWidgets()}，会把上一轮挂的电路段一并清掉。
     * <p>⚠️ 本方法在客户端与服务端<b>各跑一次</b>（中键走 {@code handleClientAction} ＋ 本地回调），
     * 两侧各持一份自己的界面状态 ⇒ 两侧都要挂出来。
     */
    @Override
    public void toggleFor(int index) {
        final boolean switchedSlot = (index != this.currentSlot);
        this.currentSlot = index;
        super.toggleFor(index);
        if (!this.isVisible()) {
            return;
        }
        if (switchedSlot || this.baseHeight < 0) {
            // super 刚跑完 show() ⇒ 此刻的尺寸就是「还没有电路段」的基准尺寸
            this.baseWidth = this.getSizeWidth();
            this.baseHeight = this.getSizeHeight();
        }
        this.shanhaiAttachCircuitSection();
    }

    /**
     * 把点击<b>先</b>交给电路段（理由见类注释 §三）。
     *
     * <p>段没接这次点击（返回 false）⇒ 原样走 {@code super}，父类「点到空白处 = 拖面板」的语义不变。
     * ⚠️ 有意的差别：点在电路段上的那一下<b>不再</b>把父类的 {@code isDragging} 置真
     * ⇒ 按住电路按钮挪鼠标不会再拖着整块面板跑。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        final Widget section = this.circuitSection;
        if (section != null && section.isVisible() && section.isActive()
                && section.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 服务端收到「客户端点了第 N 个通配符母槽的第 M 号电路」。
     *
     * <p>⚠️ 对不认识的 id <b>必须 {@code super.handleClientAction}</b>（见类注释 §四末段）。
     */
    @Override
    public void handleClientAction(int id, FriendlyByteBuf buffer) {
        if (id == ACTION_SET_WILDCARD_CIRCUIT) {
            final int mother = buffer.readInt();
            final int circuit = buffer.readInt();
            this.machine.shanhaiApplyWildcardCircuit(mother, circuit);
            return;
        }
        super.handleClientAction(id, buffer);
    }

    /** 客户端点某个电路按钮。 */
    private void shanhaiOnCircuitButton(int circuit) {
        final int mother = this.currentSlot;
        if (mother < 0) {
            return;
        }
        if (this.machine.isRemote()) {
            this.writeClientAction(ACTION_SET_WILDCARD_CIRCUIT, buf -> {
                buf.writeInt(mother);
                buf.writeInt(circuit);
            });
        } else {
            this.machine.shanhaiApplyWildcardCircuit(mother, circuit);
        }
    }

    // ------------------------------------------------------------ 电路段挂载

    /**
     * 把 GTCEu 的「电路设置」整段接到当前内容的最下面，并把面板撑高。
     *
     * <p>⚠️ 父类 {@code show(...)} 是 private，钩不到它的尺寸计算 ⇒ 只能在它算完之后追加一段并
     * {@code setSize}。每次先撤掉上一轮那段再按基准尺寸重挂：否则「同一格收起后再展开」这条路上
     * （父类 {@code toggleFor} 格号相同就只翻 visible/active，不重跑 {@code show}）会挂出<b>第二份</b>
     * 33 个按钮，面板高度每次涨一截。
     */
    private void shanhaiAttachCircuitSection() {
        this.shanhaiDetachCircuitSection();
        final Widget created = new CircuitFancyConfigurator(this.circuitSlot).createConfigurator();
        if (created instanceof WidgetGroup section) {
            this.shanhaiRebindCircuitButtons(section);
        }
        this.circuitSection = created;
        if (created != null) {
            created.setSelfPosition(0, this.baseHeight + SECTION_GAP);
            this.addWidget(created);
        }
        this.setSize(Math.max(this.baseWidth, CIRCUIT_SECTION_WIDTH),
                this.baseHeight + SECTION_GAP + CIRCUIT_SECTION_HEIGHT);
    }

    /** 撤掉当前那段电路设置（子节点 ＋ 尺寸都回到基底）。 */
    private void shanhaiDetachCircuitSection() {
        if (this.circuitSection != null) {
            this.removeWidget(this.circuitSection);
            this.circuitSection = null;
        }
    }

    /**
     * 把 33 个按钮（＋ ghostCircuit 的「清除」键）的点击回调重绑到本面板的通道上。
     *
     * <p>🔴 <b>不是重画</b>：布局、贴图、标签、编程电路槽全是 {@code createConfigurator()} 原样造的，
     * 我们只用公开 setter {@code setOnPressCallback} 换掉回调。
     * <p>识别按键靠<b>坐标</b>不靠顺序（顺序会被 ghostCircuit 那个条件性的「清除」键推歪）；
     * 坐标表与算术都在纯逻辑核 {@code ShanhaiCircuitGrid} 里（javap 偏移算出来的死数）。
     */
    private void shanhaiRebindCircuitButtons(WidgetGroup section) {
        final List<ButtonWidget> buttons = section.getWidgetsByType(ButtonWidget.class);
        final List<ButtonWidget> bound = new ArrayList<>(buttons.size());
        for (final ButtonWidget button : buttons) {
            final int circuit = ShanhaiCircuitGrid.circuitAt(button.getSelfPositionX(), button.getSelfPositionY());
            if (circuit == ShanhaiCircuitGrid.UNKNOWN) {
                continue;
            }
            final int value = circuit;
            final Consumer<ClickData> callback = clickData -> this.shanhaiOnCircuitButton(value);
            button.setOnPressCallback(callback);
            // 上游自带的悬停提示（1px 白边）：这一批按钮走的是不带 initTemplate() 的构造器，
            // 不补的话它们【没有任何悬停高亮】，看上去像一张死贴图。
            button.initTemplate();
            bound.add(button);
        }
        // 正常应是 33（0..32 那 33 个）；ghostCircuit 开启时上游会【再】加 1 个「清除」键 ⇒ 34。
        // 少于 33 才说明上游改了版面（认出来的按钮数会立刻暴露它）。
        if (bound.size() < ShanhaiCircuitGrid.CIRCUIT_BUTTON_COUNT) {
            ShanhaiStellarLog.warn("通配符电路段接线数偏少：认出 {} 个按钮（应为 {}，ghostCircuit 开启时再多 1 个清除键）",
                    bound.size(), ShanhaiCircuitGrid.CIRCUIT_BUTTON_COUNT);
        }
    }

    /**
     * 1 格代理容器 —— 让 GTCEu 那张电路网格直接读写「当前那一格」的电路。
     *
     * <p>⚠️ 不落任何影子状态：读完即走机器的 {@code shanhaiWildcardCircuit}（{@code @DescSynced @Persisted} 权威字段）。
     */
    private final class SlotCircuitTransfer extends ItemStackTransfer {

        SlotCircuitTransfer() {
            super(1);
        }

        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            final int circuit = StellarWildcardCatalystPanel.this.machine
                    .shanhaiWildcardCircuit(StellarWildcardCatalystPanel.this.currentSlot);
            return circuit == ShanhaiSlotCircuit.NONE ? ItemStack.EMPTY : IntCircuitBehaviour.stack(circuit);
        }

        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
            final int circuit;
            if (stack.isEmpty() || !IntCircuitBehaviour.isIntegratedCircuit(stack)) {
                circuit = ShanhaiSlotCircuit.NONE;
            } else {
                circuit = IntCircuitBehaviour.getCircuitConfiguration(stack);
            }
            StellarWildcardCatalystPanel.this.machine
                    .shanhaiApplyWildcardCircuit(StellarWildcardCatalystPanel.this.currentSlot, circuit);
            this.onContentsChanged(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return IntCircuitBehaviour.isIntegratedCircuit(stack);
        }
    }
}
