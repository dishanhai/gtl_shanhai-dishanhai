package com.shanhai.machine.wildcard;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfigurator;
import com.gregtechceu.gtceu.api.gui.widget.IntInputWidget;
import com.gregtechceu.gtceu.api.gui.widget.ToggleButtonWidget;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * 「神锻样板模式」按钮（需求④）—— 每个仓室右下左侧竖列里的那一个。
 *
 * <h2>一、它是上游哪一个的对应物（逐字对齐，不转述）</h2>
 * 上游 = {@code com.gtladd.gtladditions.api.machine.gui.FOAPatternConfigurator}
 * （源码见 {@code temp\wildcard\decomp6\...\FOAPatternConfigurator.kt}）。
 * 本类与它的<b>控件布局、控件类型、位置尺寸、lang key 全部逐字相同</b>：
 * <pre>
 *   WidgetGroup(0, 0, 118, 56)      ← ⚠️ 2026-10-04 起本工程改成 118×104（下面多了两行，见 §五）
 *   ├─ ToggleButtonWidget(6, 5, 20, 20, GuiTextures.BUTTON_POWER, isEnabled, setEnabled)
 *   │     .setTooltipText("gtladditions.machine.me_super_pattern_buffer.foa_mode")
 *   ├─ LabelWidget(32, 10, "gtladditions.machine.me_super_pattern_buffer.foa_mode")
 *   ├─ LabelWidget(6,  36, "gtladditions.machine.me_super_pattern_buffer.foa_multiplier")
 *   └─ IntInputWidget(58, 31, 54, 20, getMultiplier, setMultiplier).setMin(1).setMax(30)
 *   标题 getTitle()      = "gtladditions.machine.me_super_pattern_buffer.foa_config.title"     → 「神锻样板模式」
 *   提示 getTooltips()   = 标题 + "…foa_config.tooltip.0" + "…foa_config.tooltip.1"
 *                          → 与用户截图那两行**同一批 key**，所以文字逐字一致
 *   ⚠️ 上面这 4 个控件的坐标/尺寸/文案，本轮改动<b>一个都没动</b>；新增的两个开关行
 *      接在它们下面（y=57 起），面板因此从 56 高变成 104 高。
 * </pre>
 *
 * <h2>二、🔴 为什么不能直接 {@code new FOAPatternConfigurator(this)}</h2>
 * 它的构造器签名是 {@code FOAPatternConfigurator(MESuperPatternBufferPartMachine)}
 * —— <b>只收 gtladditions 那一种机器</b>（javap 实测：
 * {@code public com.gtladd.gtladditions.api.machine.gui.FOAPatternConfigurator(com.gtladd.gtladditions.common.machine.multiblock.part.MESuperPatternBufferPartMachine)}）。
 * 本机继承的是 gtlcore 的 {@code MEPatternBufferPartMachineBase}，与它没有继承关系 ⇒ 编译期就传不进去 ✗。
 * <b>而它的实现里除了「读/写那台机器的两个字段 + 画上面那 4 个控件」之外没有任何别的东西</b>
 * （全文 84 行，见上面那个文件）⇒ 照抄 4 个控件 + 把读写接成本机的字段，就是**等价复用**。
 *
 * <h2>三、位置</h2>
 * GTCEu 的 {@code ConfiguratorPanel} 把挂上去的 configurator <b>按挂载顺序自上而下排在界面左侧</b>。
 * 上游在 {@code MESuperPatternBufferPartMachine.attachConfigurators} 里把 FOA
 * <b>放在最后一个</b> {@code attachConfigurators(...)} 调用 ⇒ 它落在整列的最下面 = 截图里的左下角。
 * 本机 {@code attachConfigurators} 同样把它放最后 ⇒ 同一个位置 ✓。
 *
 * <h2>四、功能（不许「点了没反应」）</h2>
 * 开关与乘数都写回机器，并且 {@code SuperWildcardPatternBufferPartMachine} 会<b>立刻重算样板</b>
 * （见该类的 {@code rebuildPatterns()}）：把已展开的通配符样板按神锻规则改写输入输出。
 * 改写本体在 {@link ShanhaiForgePatternMode}（反射复用 gtladditions 的同一份实现）。
 * 万一那份实现取不到，{@link #getTooltips()} 会<b>多出一行显式的「不可用」</b>，并写 ERROR 日志 ——
 * 那种情况下按钮依旧是活的（能切），但玩家能一眼看出它没生效。
 *
 * <h2>五、🔴 2026-10-04 新增：「自动倍率」开关（用户点单 ＋ 同日四条修正）</h2>
 * 用户原话（逐字）：
 * <b>「就是那个倍率不是需要你配置的嘛，我希望给他添加一个开关，打开之后可以自动配置这个倍率
 * （通过读取那台机器上的倍率）」</b>；「读哪台机器」他选的是<b>「读它所在多方块的控制器」</b>。
 * 第二轮又追加四条：默认开 ／ 来源不止伪神之锻炉、还有<b>我们的原始终焉引擎</b> ／
 * 这个数<b>向下取整</b> ／ <b>读不到 ⇒ 默认 1</b>（「因为没有额外产出的机器才读不到啊」）。
 *
 * <p>落点 = {@link #createConfigurator()} 里新增的三件：一个开关 + 两行状态；
 * 「打开时把倍率输入框禁用/置灰」照本工程既有先例
 * （{@code ParallelOverrideConfigurator#applyPowerAutoDisabled}：「电力自动开着 ⇒ 禁用输入框与一键最大」）。
 * 逻辑本体在 {@link SuperWildcardPatternBufferPartMachine#getEffectiveForgePatternMultiplier()}，
 * 两种来源的读取器在 {@link ShanhaiForgeMultiplierReader}，纯判定在 {@link ShanhaiAutoForgeMultiplier}。
 * <p>⚠️ 面板那两行的口径（用户修正后）：
 * <pre>
 *   自动关        ：手动·倍率 15      ／ 关掉开关即可改倍率
 *   自动开+读到   ：自动·读到 23      ／ 来源：伪神之锻炉控制器 或 原始终焉引擎
 *   自动开+读不到 ：自动·读不到 -&gt; ×1 ／ 原因：控制器无额外产出（或未成型 / 数值非法 / 读取异常）
 * </pre>
 */
public class ForgePatternConfigurator implements IFancyConfigurator {

    /** 与上游完全相同的 lang key 前缀（复用它的文案，不另起一套）。 */
    private static final String LANG_TITLE = "gtladditions.machine.me_super_pattern_buffer.foa_config.title";
    private static final String LANG_TOOLTIP_0 = "gtladditions.machine.me_super_pattern_buffer.foa_config.tooltip.0";
    private static final String LANG_TOOLTIP_1 = "gtladditions.machine.me_super_pattern_buffer.foa_config.tooltip.1";

    /**
     * 第①行（神锻模式开关）与第②行（倍率）的文案 key —— <b>与上游逐字相同</b>。
     *
     * <p>⚠️ 2026-10-04 追加 B：面板装配整体搬进 {@link AutoForgeMultiplierPanel}（两台机器共用一份），
     * 这两个 key 跟着搬过去用，所以这里从 {@code private} 放宽成包内可见。
     */
    static final String LANG_MODE = "gtladditions.machine.me_super_pattern_buffer.foa_mode";
    static final String LANG_MULTIPLIER = "gtladditions.machine.me_super_pattern_buffer.foa_multiplier";

    // ══ 🔴 2026-10-04 新增（自动倍率）：本工程自己的 key（上游没有这个开关，借不到文案） ══
    //
    // 只在这里用（本 configurator 的 getTooltips）；面板那一行开关自身的 tooltip 用的是
    // AutoForgeMultiplierPanel.LANG_AUTO（同一个 key 的两个用处）。

    private static final String LANG_AUTO_TIP_0 =
            "shanhai.machine.super_wildcard_pattern_buffer.auto_multiplier.tooltip.0";
    private static final String LANG_AUTO_TIP_1 =
            "shanhai.machine.super_wildcard_pattern_buffer.auto_multiplier.tooltip.1";
    private static final String LANG_AUTO_TIP_2 =
            "shanhai.machine.super_wildcard_pattern_buffer.auto_multiplier.tooltip.2";

    /** 上游图标 = 神锻机器本体（{@code MultiBlockMachine.INSTANCE.getFORGE_OF_THE_ANTICHRIST().asStack()}）的物品 id。 */
    private static final ResourceLocation FOA_ITEM_ID = new ResourceLocation("gtladditions", "forge_of_the_antichrist");

    /** 失败提示（本工程自加，上游没有）—— 只在反射复用失败时出现。 */
    private static final String LANG_UNAVAILABLE = "shanhai.machine.super_wildcard_pattern_buffer.foa_unavailable";

    private final AutoForgeMultiplierHost machine;

    public ForgePatternConfigurator(AutoForgeMultiplierHost machine) {
        this.machine = machine;
    }

    @Override
    public Component getTitle() {
        return Component.translatable(LANG_TITLE);
    }

    @Override
    public IGuiTexture getIcon() {
        final ItemStack icon = foaIconStack();
        // 拿不到就退回通用的电源按钮贴图 —— 图标缺失不该把整个 configurator 弄崩。
        return icon.isEmpty() ? GuiTextures.BUTTON_POWER : new ItemStackTexture(icon);
    }

    private static ItemStack foaIconStack() {
        final var item = ForgeRegistries.ITEMS.getValue(FOA_ITEM_ID);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    @Override
    public List<Component> getTooltips() {
        final List<Component> tooltips = new ArrayList<>();
        tooltips.add(this.getTitle());
        tooltips.add(Component.translatable(LANG_TOOLTIP_0));
        tooltips.add(Component.translatable(LANG_TOOLTIP_1));
        if (!ShanhaiForgePatternMode.isAvailable()) {
            tooltips.add(Component.translatable(LANG_UNAVAILABLE));
        }
        // 🔴 2026-10-04 新增：自动倍率那三行（上游没有这个开关，文案是本工程自己的）。
        tooltips.add(Component.translatable(LANG_AUTO_TIP_0));
        tooltips.add(Component.translatable(LANG_AUTO_TIP_1));
        tooltips.add(Component.translatable(LANG_AUTO_TIP_2));
        return tooltips;
    }

    /**
     * 装配面板 —— <b>转发给唯一的装配实现 {@link AutoForgeMultiplierPanel#buildFull}</b>。
     *
     * <h2>版面（见 {@link AutoForgeMultiplierPanel} 类注释，两边同一份坐标）</h2>
     * <pre>
     *   WidgetGroup(0, 0, 140, 104)
     *   ├─ y= 5..25  ToggleButtonWidget(6,5,20,20)      ← 上游原样（神锻模式开关）
     *   ├─ y=10      LabelWidget(32,10)                 ← 上游原样
     *   ├─ y=36      LabelWidget(6,36)                  ← 上游原样（倍率）
     *   ├─ y=31..51  IntInputWidget(58,31,54,20)        ← 上游原样（自动开着时被禁用 + 压暗）
     *   ├─ y=57..77  ToggleButtonWidget(6,57,20,20)     ← 【新增】自动倍率开关
     *   ├─ y=62      LabelWidget(32,62)                 ← 【新增】「自动倍率」
     *   ├─ y=80      LabelWidget(6,80, Supplier)        ← 【新增】手填 N / 读到 N / 读不到→×1
     *   └─ y=92      LabelWidget(6,92, Supplier)        ← 【新增】来源，或读不到的原因
     * </pre>
     *
     * <h2>🔴 2026-10-04 追加 B：为什么本方法只剩一行</h2>
     * 用户逐字：「要加的东西<b>与我们自己那台完全一致（同一套 reader ＋ 同一套判定/文案 ✓
     * 不许出现第二份实现 ✗）</b>」——上游那台「超级样板总成」也要挂同一套开关与两行状态。
     * ⇒ 版面装配、逐帧禁用态、两行文字的取法<b>整体搬进 {@link AutoForgeMultiplierPanel}</b>，
     * 本方法只负责把「本工程那台」传进去；上游那台走同一个类的
     * {@link AutoForgeMultiplierPanel#appendTo}（往上游自己的面板里追加同样三行）。
     *
     * <p>⚠️ 宽 118 → 140：来源标注最长为「原始终焉引擎模块」（8 个全角字 ≈ 72 px）加前缀
     * 「来源：」共 ≈ 117 px，原内容区只有 112 px ⇒ 放不下。上游那 4 个控件的坐标一个都没动
     * （最右边缘仍是 112 &lt; 140）。
     */
    @Override
    public Widget createConfigurator() {
        // 注意：本方法与 attachConfigurators 一样，在【服务端与客户端各跑一次】
        //（UIFactory.createUITemplate 的两个调用点），⇒ 里只许建"两侧都安全"的件。
        return AutoForgeMultiplierPanel.buildFull(this.machine);
    }
}
