package com.shanhai.common.machine;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfigurator;

import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import com.shanhai.item.ShanhaiItems;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 🔴 <b>「并行数」侧栏面板（2026-09-27 新增，用户实机提出）。</b>
 *
 * <h2>用户原话（逐字）</h2>
 * <blockquote>「在模块和主机的左下角再新增一个全新的按钮，他可以调节主机或者模块的并行数，
 * 作为一个输入框，可以让玩家输入数字，并且右边有一个一键调至最大的按钮」</blockquote>
 *
 * <h2>「左下角」是哪儿（不是猜的）</h2>
 * GTCEu 的机器 GUI 左侧那条竖排按钮条就是 {@code ConfiguratorPanel}：它的
 * {@code Tab} 的 y = {@code index * (tabSize + 2)}，而整条面板由
 * {@code FancyMachineUIWidget.setupFancyUI} 底对齐（{@code setSelfPosition(new Position(-26,
 * gui.getHeight() - panel.getSize().height - 4))}，字节码）
 * ⇒ <b>最后 attach 的那一个落在整条的最下面 = 左下角</b>。
 * 本工程的既有先例就是这么写的（{@code PrimordialOmegaEngineMachine#attachConfigurators} 里
 * 「始终渲染为工作状态」那一段的注释原文：「最后挂的这一个落在整条【最下面】＝用户指定的「左下角」」）。
 * ⇒ 本面板用 {@code panel.attachConfigurators(new ParallelOverrideConfigurator(this))} 挂在两侧列表的<b>末尾</b>。
 *
 * <h2>面板里有什么（照用户描述：左边输入框 + 右边「一键最大」＋ 下面一个「电力自动」开关）</h2>
 * <pre>
 *   y=3   标题
 *   y=16  实时读数：当前生效 / 自动值（电力自动打开时多一段"电上限"）
 *   y=34  [ TextFieldWidget 86×16 ][ ButtonWidget 52×16 ] ← 右边这个就是「一键最大」
 *   y=54  [ ButtonWidget 全宽 142×16 ]                   ← 🔴 2026-10-02 新增的「电力自动」开关
 *   y=74  🔴 2026-10-02 第二轮：「电上限为什么是这个数」（逐档文案见 EnergyCapState）
 *   y=90  🔴 2026-10-02 第八轮：「每并行耗电 k」———— 用户裁决 ② 点名要的那一行
 *   y=106 脚注：输入 0 = 恢复自动 · 超过上限会被钳到上限
 * </pre>
 * <p>🔴 <b>2026-10-02 第二轮（用户实机报「装了 64 个物质模块后显示尚未算出」）加了两件事</b>：
 * <ol>
 *   <li><b>y=74 那一行</b>：上一轮只有「§8（尚未算出）」五个字，而它同时代表五件不同的事
 *       （开关关着 / 还没跑过配方 / 本轮没有可跑配方 / 没有能源仓 / 抛异常）。
 *       ⇒ 现在由 {@link ParallelOverrideMachine#EnergyCapState} 逐档说人话。</li>
 *   <li><b>电力自动开着 ⇒ 上面的输入框与「一键最大」自动禁用</b>（用户逐字要求），
 *       并且<b>逐帧刷新灰态</b>（不是 build 期快照 —— 见下面「已知边界」的订正）。</li>
 * </ol>
 * <p>🔴 <b>2026-10-02 新增开关（用户原话：「在「一键最大」下面加一个开关」）</b>：
 * 用户定方案是「并行数 = floor(能源仓总功率 ÷ 每并行耗电)」，且点名
 * <b>不能改"输入 0 = 自动"那个旧语义</b>（否则老存档静默变行为）⇒ 做成<b>正交的第三态</b>：
 * 开关只管"在既有口径之上再压一道电力上限"，关着时逐值等于改动前。
 * <p>⚠️ 因为多了一行，{@code PANEL_H} 由 78 改成 94、脚注由 y=58 挪到 y=74
 * （面板加高必须同步改这两个常量，否则最后一行会被裁掉 —— 见下"弹出面板尺寸"）。
 * 实际弹出面板 = 面板尺寸 + (8, 28)（{@code ConfiguratorPanel$Tab} 构造器字节码：
 * {@code view.setSize(widget.width + border*2, widget.height + button.height + border)}，
 * border=4 / tabSize=24），本面板 150×94 ⇒ 弹出 158×122
 * （对照：{@code StarRenderConfigurator} 是 150×116 ⇒ 158×144，且它自己的注释记着"可能溢出屏幕"的已知风险
 * —— 本面板仍比它矮 22px，溢出风险只会更小）。
 * <p>🔴 <b>2026-10-02 第二轮</b>：再加了一行 ⇒ 本面板现为 {@code 150×106} ⇒ 弹出 {@code 158×134}
 * —— <b>仍比 {@code StarRenderConfigurator}（158×144）矮 10px</b>。溢出风险同上一轮结论：只会更小。
 *
 * <h2>🔴 为什么这里敢用文本输入，而 {@code StarRenderConfigurator} 当初不敢</h2>
 * 那份注释写的是「本工程编译期吃的 {@code libs/ldlib-forge-1.20.1-1.0.33.b.jar} 里<b>没有</b>
 * {@code IntInputWidget}」—— <b>前半句为真、结论已过时</b>（2026-09-27 逐条核实）：
 * <pre>
 *   libs/ldlib-…1.0.33.b.jar 的 gui/widget/ 里确实没有 IntInputWidget，
 *   但那不是唯一的来源 —— {@code com.gregtechceu.gtceu.api.gui.widget.IntInputWidget}
 *   就在 libs/gtceu-1.20.1-1.4.4.jar 里（public，可用），而且本工程侧栏第 5 个控件
 *   {@code gtladditions LimitedDurationConfigurator} 用的就是它。
 *   另外 LDLib 自己有 {@code com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget}（本类用的就是这个）。
 * </pre>
 * <b>本类选 {@code TextFieldWidget} 而不是 GTCEu 的 {@code IntInputWidget}</b>，理由是<b>值域</b>：
 * {@code IntInputWidget} 是 {@code Supplier&lt;Integer&gt;}，装不下本工程的 long 并行
 * （主机 {@code MAX_PARALLEL = Long.MAX_VALUE}；模块并行表最高档也是 {@code Long.MAX_VALUE}），
 * 而 {@code TextFieldWidget} 直接提供 {@code setNumbersOnly(long, long)}。
 *
 * <h2>🔴 输入的值怎么一路安全到达服务端（逐条字节码实证，不是推断）</h2>
 * <pre>
 *   ① 客户端打字 → {@code TextFieldWidget.onTextChanged(text)}
 *        → {@code textValidator.apply(text)}（= setNumbersOnly 那一份，见下）
 *        → 变了就 {@code writeClientAction(1, buf -> buf.writeUtf(v))}（C2S 包）；
 *   ② 服务端同 id widget 的 {@code handleClientAction(1, buf)}
 *        → {@code v = textValidator.apply(buf.readUtf())}   ← ★ 服务端【重跑一遍】校验器
 *        → {@code v = v.substring(0, min(len, maxStringLength))}
 *        → 变了才 {@code setCurrentString(v)} + {@code textResponder.accept(v)}   ← 我们写机器的入口
 *   ③ {@code setParallelOverride(...)} 里再钳一次（{@link ParallelOverrideMachine#clampOverride}）并
 *        {@code notifyBlockUpdate()} ⇒ {@code @DescSynced} 字段广播给所有观看者；
 *   ④ 回灌：{@code detectAndSendChanges()} 发现 {@code textSupplier.get() != currentString} 就
 *        {@code writeUpdateInfo(1, …)} 把服务端权威值推回客户端输入框。
 *      ⇒ <b>服务端钳过的值会自己显示到框里</b>，不会出现"框里是 999999、机器里是别的数"。
 * </pre>
 * {@code setNumbersOnly(min, max)} 的校验器语义（{@code lambda$setNumbersOnly$5} 字节码）：
 * <pre>
 *   null / 空串          → String.valueOf(min)         （⇒ 空框 = 自动，合法）
 *   解析失败（非数字）    → 返回 currentString          （⇒ 退回旧值，<b>绝不抛异常</b>）
 *   在 [min,max] 内       → 原样返回
 *   小于 min             → String.valueOf(min)
 *   大于 max             → String.valueOf(max)
 * </pre>
 * ⇒ 用户要求的「输入非法值（0 / 负数 / 超大 / 非数字）要有兜底」由<b>三层</b>共同保证：
 * 控件的校验器（客户端 + 服务端各一次）、{@link ParallelOverrideMachine#clampOverride}（写入口）、
 * 以及 {@link #parseOrDefault(String, long)} 的 {@code try/catch}（非数字永不上抛）。
 *
 * <h2>⚠️ 诚实边界</h2>
 * <ul>
 *   <li>本类<b>不保证</b>"填多少就跑多少"：两台机器的实际并行还要过输入量与输出空间两道闸
 *       （见 {@link ParallelOverrideMachine} 的 javadoc）。工具提示里写的就是"上限"，不写成"精确值"。</li>
 *   <li>⛔ <b>【2026-10-02 第二轮订正，旧句照留】</b>旧句原文：「{@code setActive} 之类的灰态是 build 期快照
 *       （LDLib 既有行为，见 {@code StarRenderConfigurator} 的同款注释）；本面板只有一个按钮、
 *       没有需要变灰的状态，不受影响。」
 *       <b>前半句为真，后半句已作废</b> —— 用户现在要求「电力自动开着 ⇒ 禁用输入框与一键最大」，
 *       于是本面板<b>有了</b>需要变灰的状态。处置办法见 {@link #applyPowerAutoDisabled}
 *       （不在建控件时只置一次，而是挂 {@code WidgetGroup#updateScreen} 逐帧对齐）。</li>
 * </ul>
 */
public final class ParallelOverrideConfigurator implements IFancyConfigurator {

    /** 面板自身尺寸（弹出面板 = 本尺寸 + (8, 28)，见类注释）。 */
    private static final int PANEL_W = 150;
    /**
     * ⛔ 2026-10-02 第二轮：{@code 78 → 94 → }<b>{@code 106}</b> —— 又加了一行
     * 「<b>电上限为什么是这个数</b>」（见类注释的布局表）。
     * <p>🔴 <b>2026-10-02 第八轮：{@code 106 → 122}</b> —— 再加一行「<b>每并行耗电</b>」
     * （用户裁决 ②）。弹出面板随之从 {@code 158×134} 变成 {@code 158×150}；
     * ⚠️ <b>这是本工程目前最高的一个侧栏面板</b>（{@code StarRenderConfigurator} 是 158×144，
     * 它自己的注释里记着"可能溢出屏幕"的已知风险）⇒ <b>如实记录，不粉饰</b>：
     * 溢出风险比以前略大，但那是用户点名要的这行字的代价（"以后一眼能看出对不对"）。
     * <p>⚠️ 面板加高必须同步改 {@link #REASON_Y} / {@link #PER_PARALLEL_Y} / {@link #FOOTNOTE_Y}，
     * 否则最后一行会被裁掉。
     */
    private static final int PANEL_H = 122;

    /** 输入框与按钮的几何（同一行：左边输入框、右边「一键最大」）。 */
    private static final int ROW_Y = 34;
    private static final int FIELD_X = 4;
    private static final int FIELD_W = 86;
    private static final int ROW_H = 16;
    private static final int BUTTON_X = FIELD_X + FIELD_W + 4;   // = 94
    private static final int BUTTON_W = 52;                      // ⇒ 右边缘 146 < PANEL_W - 4

    /** 🔴 2026-10-02 新增「电力自动」开关那一行（在「一键最大」下面）。 */
    private static final int SWITCH_Y = 54;
    private static final int SWITCH_H = 16;
    /**
     * 🔴 2026-10-02 第二轮新增：「<b>电上限为什么是这个数</b>」那一行。
     *
     * <p>用户实机报的那句话就是这一行要回答的：装了 64 个物质模块后面板写着「尚未算出」，
     * 但<b>从那一句话里读不出任何信息</b>（开关关着 / 没跑过配方 / 没有可跑配方 / 没能源仓 / 抛异常，
     * 五件事在面板上长得一模一样）。逐档文案 = {@link ParallelOverrideMachine#energyCapReasonText}
     * （纯函数 ⇒ 离线判据断言的就是界面本身）。
     */
    private static final int REASON_Y = 74;
    /**
     * 🔴🔴 <b>2026-10-02 第八轮新增：「每并行耗电 k」那一行 —— 用户裁决 ② 点名要的。</b>
     *
     * <p>理由（用户原话逐字）：<b>「用户能看到 {@code 688.13K}，却看不到 {@code 2.1} ⇒
     * 所以这次只能靠反推」</b>。上一轮"k 被取整成 2"这件事在界面上<b>完全不可见</b>，
     * 只能拿 {@code 688128 ÷ 327680} 反推出来。这一行把它摆到明面上。
     *
     * <p>⚠️ <b>它必须与「电上限」那一行同一种读法 —— 逐帧现读（Supplier），不是 build 期快照</b>：
     * {@code this::perParallelText} 每帧调 {@code machine.getPerParallelMilliCost()}。
     * 理由与 {@link #applyPowerAutoDisabled} 那段是同一条：LDLib 的控件内容若只在建控件时取一次，
     * 玩家点一下开关/换一块配方之后，面板显示的就是<b>上一轮的数</b>（假的）。
     *
     * <p>文案 = 纯函数 {@link ParallelOverrideMachine#perParallelCostText}（离线判据断言的就是界面本身）。
     */
    private static final int PER_PARALLEL_Y = 90;
    /** 脚注：{@code 58 → 74 → 90 → }<b>{@code 106}</b>（每加一行就往下挪，见类注释）。 */
    private static final int FOOTNOTE_Y = 106;

    /** 可用态 / 禁用态的文字色（{@code 0xFFFFFFFF} = 原色；灰色 = 本工程既有的「压暗」写法）。 */
    private static final int TEXT_ENABLED = 0xFFFFFFFF;
    private static final int TEXT_DISABLED = 0xFF7F7F7F;

    /**
     * 标签图标：本工程自己的「寰宇并行核心」物品。
     *
     * <p>为什么不新做贴图：{@code IFancyUIProvider.getTabIcon()} / {@code IFancyConfigurator.getIcon()}
     * 只要求一个 {@code IGuiTexture}，而 {@code com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture}
     * 是本工程已在用的件（{@code PrimordialModuleMachine.ExtraMountPageProvider#getTabIcon} 就返回
     * {@code new ItemStackTexture(Items.HOPPER)}）⇒ 用物品图标既省一份资源、又与「并行」语义对得上。
     * <p>懒建一次并缓存：{@code ConfiguratorPanel$Tab.drawInBackground} <b>每帧</b>都会调 {@code getIcon()}
     * （字节码：{@code drawInBackground} 里 invokeinterface getIcon），每帧 new 一个纹理会白白造垃圾
     * —— 与 {@code StarRenderConfigurator} 里 {@code ICON_ACTIVE} 的处置同理。
     * <p>⚠️ {@code ItemStackTexture(Item...)} 的构造器<b>只把物品存进数组</b>（字节码核实：
     * 构造器里没有任何贴图/图集解析），真正的贴图在绘制时（客户端）才解析 ⇒ 即便本方法在某些路径上
     * 被服务端调到，也只是多分配一个 ItemStack，不会去碰客户端图集。
     */
    private static ItemStackTexture ICON;

    private final ParallelOverrideMachine machine;

    public ParallelOverrideConfigurator(ParallelOverrideMachine machine) {
        this.machine = machine;
    }

    @Override
    public Component getTitle() {
        return Component.literal("并行数");
    }

    @Override
    public IGuiTexture getIcon() {
        if (ICON == null) {
            // RegistryEntry#get()（ItemEntry<Item> 的超类）—— 只在 GUI 构建期取一次物品，
            // 那时注册早已完成。⚠️ 实测：ItemEntry 上【没有】asItem()（编译报"找不到符号"），
            // 所以这里必须走 get()，不能图省事用 TextFieldWidget 那套写法的同类 API。
            ICON = new ItemStackTexture(ShanhaiItems.UNIVERSAL_PARALLEL_CORE.get());
        }
        return ICON;
    }

    @Override
    public List<Component> getTooltips() {
        return List.of(
                Component.literal("§b§l并行数"),
                Component.literal("§7面板里是一个输入框：填数字 = 该机器的并行上限"),
                Component.literal("§7右边「一键最大」= 填入这台机器§f此刻能达到§7的并行数"),
                Component.literal("§7填 §f0 §7= 恢复自动（跟随物质模块 / 机器自身）"),
                Component.literal("§8填得比能达到的数更大 ⇒ 自动钳到那个数"),
                Component.literal("§d「电力自动」开关§7 = 并行上限改成"
                        + "§fmin(原本的上限, 能源仓总功率 ÷ 每并行耗电)"),
                Component.literal("§8· 关着时与没有这个开关时逐值相同（老存档不受影响）"),
                Component.literal("§8· 开了以后，输入框填的值仍作为额外的一道上限"),
                Component.literal("§8· 🔴 开着时，上面的输入框与「一键最大」会被禁用（变灰、点不动）"),
                Component.literal("§8· 电上限没算出来时，面板那一行会写明是哪一种「没算出来」"),
                Component.literal("§8· 🔴 下面两行是这一次的算式读数：§f电上限§8 ／ §f每并行耗电 k§8（开了减免时 k 是小数）"),
                Component.literal("§8实际跑多少仍由可用输入量与输出空间决定"));
    }

    @Override
    public Widget createConfigurator() {
        // 注意：本方法与 attachConfigurators 一样，在【服务端与客户端各跑一次】
        //（UIFactory.createUITemplate 的两个调用点：服务端直接建、客户端 readHolderFromSyncData 之后建）
        // ⇒ 这里只许建"两侧都安全"的件，不许碰 @OnlyIn(CLIENT) 的东西。
        // 🔴 2026-10-02 第二轮：控件【先建好】，group 后建 —— 因为"逐帧对齐禁用态"的那个
        //    匿名子类要捕获下面的 field / maxButton / maxLabel（Java 的匿名类只能捕获
        //    effectively-final 的局部变量，所以顺序不能反）。
        // ── 输入框：值来自机器的覆盖字段；写入经 C2S 包到服务端后由 textResponder 落库 ──
        TextFieldWidget field = new TextFieldWidget(FIELD_X, ROW_Y, FIELD_W, ROW_H,
                () -> String.valueOf(machine.getParallelOverride()),
                text -> machine.setParallelOverride(
                        parseOrDefault(text, machine.getParallelOverride())));
        // 0 也允许：0 就是「自动」，所以下界是 0 而不是 1。
        field.setNumbersOnly(ParallelOverrideMachine.PARALLEL_AUTO, ParallelOverrideMachine.PARALLEL_MAX);
        // 19 位 = Long.MAX_VALUE 的十进制长度；再长也没有意义（服务端还会按 maxStringLength 截断）。
        field.setMaxStringLength(19);
        field.setBordered(true);
        // 🔴 2026-09-27：上面的 setNumbersOnly 只做【数值类型层】的边界（≥0 / ≤long 上限）。
        //    真正"不许超过这台机器能达到的并行数"那一条由服务端 setParallelOverride →
        //    clampOverrideToCeiling 完成 —— 它必须动态读天花板（模块的自动值每 3 tick 变），
        //    而 setNumbersOnly 的 (min,max) 是【建控件那一刻】的快照，装不下动态语义。
        //    钳过的权威值会经 @DescSynced + writeUpdateInfo(1, …) 回灌到框里（见类注释 ④）。
        field.setHoverTooltips(
                Component.literal("§7填入并行数上限（十进制整数）"),
                Component.literal("§7填 §f0 §7= 恢复自动（跟随物质模块 / 机器自身）"),
                Component.literal("§7超过这台机器能达到的数 ⇒ 自动钳到那个数"),
                Component.literal("§8🔴「电力自动」开着时本框被禁用（变灰、点是点不动的）"),
                Component.literal("§8🔴 而且开着时【框里的值完全不参与运算】（填什么都不算数）"),
                Component.literal("§8非数字 / 空框会被退回上一个合法值，不会崩"));

        // ── 「一键最大」：填这台机器【此刻能达到】的并行数（= 设 0 时机器能达到的那个数） ──
        ButtonWidget maxButton = new ButtonWidget(BUTTON_X, ROW_Y, BUTTON_W, ROW_H, GuiTextures.BUTTON,
                clickData -> machine.setParallelOverride(machine.getParallelOverrideCeiling()));
        maxButton.setHoverTooltips(
                Component.literal("§a一键最大"),
                Component.literal("§7把并行数填成这台机器此刻能达到的值：§f" + machine.getParallelOverrideCeiling()),
                Component.literal("§8= 填 0 时机器自己算出来的并行数"),
                Component.literal("§8🔴「电力自动」开着时本按钮被禁用（变灰、点是点不动的）"),
                Component.literal("§8实际仍受输入量与输出空间限制"));
        // 按钮上的文字：LDLib 的 ButtonWidget 没有 setText（javap 全量方法表里没有），
        // 本工程既有写法是"铺满按钮 + 上面盖一个 LabelWidget"（见 StarRenderConfigurator 第 4/6 行）。
        // 🔴 2026-10-02 第二轮：文字改成 Supplier（禁用时压暗成 §8）—— 既有先例：
        //    StarRenderConfigurator「不可用时连文字也压暗，与既有「§8手动档不用」同款」。
        LabelWidget maxLabel = new LabelWidget(BUTTON_X + 5, ROW_Y + 4, this::maxButtonText);

        // ══ 🔴 2026-10-02 新增：「电力自动」开关（在「一键最大」下面的那一行） ══
        //   用户原话：「在「一键最大」下面加一个开关」。
        //   形态照抄上面那个按钮：ButtonWidget 铺满 + 上面盖一个 LabelWidget（LDLib 的 ButtonWidget
        //   没有 setText，本工程既有写法就是这一套，见 StarRenderConfigurator 第 4/6 行）。
        //   ⚠️ 盖在上面的那个 Label 必须用【Supplier 构造器】（与第 2 行读数同一个写法），
        //      否则开关状态在界面上不会变 —— 那是"活的界面上放死数据"，本工程红线。
        ButtonWidget powerSwitch = new ButtonWidget(FIELD_X, SWITCH_Y, PANEL_W - 2 * FIELD_X, SWITCH_H,
                GuiTextures.BUTTON, clickData -> machine.setPowerAutoParallel(!machine.isPowerAutoParallel()));
        powerSwitch.setHoverTooltips(
                Component.literal("§b§l电力自动并行"),
                Component.literal("§7开：并行上限 = §fmin(这台机器原本的上限, 能源仓总功率 ÷ 每并行耗电)"),
                Component.literal("§7关：完全按上面的输入框（= 本功能出现之前的行为）"),
                Component.literal("§8· 🔴 开着时，上面的输入框与「一键最大」会被自动禁用（变灰、点不动）"),
                Component.literal("§8· 🔴 开着时【框里的值完全不参与运算】—— 填什么都不算数（关掉即恢复生效）"),
                Component.literal("§8· 能源仓可以放 2 个，也可以是两种不同的仓；逐个取稳态功率再相加"),
                Component.literal("§8· 无线电网输入终端 / 创造模式能源仓 ⇒ 不算，直接把并行拉到原本上限"),
                Component.literal("§8· 只在配方开始时算一次，不会打断正在跑的那一轮"),
                Component.literal("§8· 关掉时行为与改动前逐值相同（老存档不会被静默改行为）"));
        LabelWidget powerSwitchLabel = new LabelWidget(FIELD_X + 6, SWITCH_Y + 4, this::powerSwitchText);

        LabelWidget readout = new LabelWidget(4, 16, this::readoutText);
        // 🔴 2026-10-02 第二轮新增的那一行：「电上限为什么是这个数」（黄字那一行下面是灰字）。
        LabelWidget reason = new LabelWidget(4, REASON_Y, this::energyReasonText);
        // 🔴🔴 2026-10-02 第八轮新增的那一行：「每并行耗电 k」（用户裁决 ②）。
        //   ⚠️ 用 Supplier 构造器 ⇒ **每帧现读**，与「电上限」那一行同一种读法（不是 build 期快照）。
        LabelWidget perParallel = new LabelWidget(4, PER_PARALLEL_Y, this::perParallelText);

        // ══ 🔴 2026-10-02 第二轮：电力自动开着 ⇒ 禁用输入框与「一键最大」 ══
        //  用户原话（逐字）：「并且开启这个电力自动并行之后<b>自动禁用上面的输入框和一键最大按钮</b>」。
        //  ⚠️ 为什么不能只在建控件时 setActive 一次：LDLib 的灰态是【build 期快照】
        //     （既有 StarRenderConfigurator 已把这个坑写进注释），而玩家完全可能"先开着面板、
        //     再点开关" ⇒ 那一刻控件已经建好了，只 setActive 一次的话灰态永远不刷新，
        //     玩家看到的就是"开关显示开了、输入框却还能打字" —— 那是【假禁用】（比不禁用更糟）。
        //     ⇒ 挂在本 group 的 updateScreen 里逐帧对齐。依据（javap）：WidgetGroup.updateScreen()
        //       会遍历 children 逐个调用它们的 updateScreen()，而父 group 每帧都会递归到本 group。
        final boolean[] lastPowerAutoOn = { machine.isPowerAutoParallel() };
        applyPowerAutoDisabled(lastPowerAutoOn[0], field, maxButton, maxLabel);
        WidgetGroup group = new WidgetGroup(0, 0, PANEL_W, PANEL_H) {
            @Override
            public void updateScreen() {
                super.updateScreen();
                final boolean on = machine.isPowerAutoParallel();
                if (on == lastPowerAutoOn[0]) {
                    return;     // 状态没变 ⇒ 一个字节都不动（不做无谓的写）
                }
                lastPowerAutoOn[0] = on;
                applyPowerAutoDisabled(on, field, maxButton, maxLabel);
            }
        };
        group.setBackground(GuiTextures.BACKGROUND_INVERSE);

        group.addWidget(new LabelWidget(4, 3, "§b§l并行数上限（玩家可调）"));
        group.addWidget(readout);
        group.addWidget(field);
        group.addWidget(maxButton);
        group.addWidget(maxLabel);
        group.addWidget(powerSwitch);
        group.addWidget(powerSwitchLabel);
        group.addWidget(reason);
        group.addWidget(perParallel);

        // ⚠️ 这一行的渲染宽度要压在面板内（PANEL_W = 150，约 30 个全角/60 个半角字符上限）：
        //    「自动值」已经在上面那行实时读数里了，这里只说"超过上限会被钳到上限"，不重复数字。
        group.addWidget(new LabelWidget(4, FOOTNOTE_Y, "§7输入 §f0 §7= 自动 · 超过上限会被钳到上限"));

        return group;
    }

    /**
     * 🔴 <b>把「电力自动开着 ⇒ 上面两个控件禁用」这件事施加到控件上</b>
     * （用户 2026-10-02 第二轮的逐字要求）。
     *
     * <h2>「禁用」由两件事共同表达，缺一件就是假禁用</h2>
     * <ol>
     *   <li><b>点不动</b>：{@code setActive(false)}。依据（{@code javap -c} 核实，不是我猜的）：
     *       {@code WidgetGroup.mouseClicked} 在把事件派给子控件之前会
     *       {@code invokevirtual Widget.isActive()} 判一次 ⇒ {@code false} 时子控件<b>根本收不到点击</b>。
     *       （LDLib 的 {@code Widget.mouseClicked} 自身不看 active，所以这一道必须靠 group 那一层 ——
     *       而本面板的控件全都是我们那个 group 的孩子，成立。）</li>
     *   <li><b>看得出来</b>：输入框文字压暗（{@code setTextColor(0xFF7F7F7F)}）、
     *       「一键最大」的盖字压暗成 {@code §8}（{@link #maxButtonText}）。
     *       既有先例：{@code StarRenderConfigurator}「不可用时<b>连文字也压暗</b>」；
     *       它的图标用 {@code IGuiTexture.setColor(0xFF3F3F3F)}，本处只改了文字色 ——
     *       ⚠️ <b>没有</b>去 {@code GuiTextures.BUTTON.setColor(...)}：那是对<b>共享静态贴图</b>下手，
     *       会把全游戏所有按钮一起压暗（{@code ResourceTexture.setColor} 是就地改自身）。
     *       「宁可少改，也不要动共享静态资源」——本工程红线。</li>
     * </ol>
     *
     * <p>⚠️ <b>诚实边界</b>：{@code setActive} 只挡鼠标；LDLib 不保证挡键盘（Tab 聚焦）——
     * 真正的边界仍在服务端：{@code setParallelOverride} 的钳位逻辑一个字都没动。
     * <p>⛔ <b>【2026-10-02 第二轮订正，旧句照留】</b>旧句原文：
     * 「而且「电力自动开着」这件事<b>不改变</b>输入框里那个值参与运<u>算</u>的方式（tooltip 已写明）」
     * —— <b>该句已作废</b>。用户第二轮裁决是 <b>A. 不参与</b>：
     * 电力自动开着时那个值<b>完全不参与运算</b>（落点
     * {@link ParallelOverrideMachine#getEffectiveOverride()}，唯一一处）。
     * <b>但"禁用控件"这道守卫仍然必须留着</b>：界面与语义要一致——
     * 玩家的心理模型是"灰了 = 不算数"，若控件可用而值不算数，那是更糟的假交互。
     */
    private static void applyPowerAutoDisabled(boolean powerAutoOn, TextFieldWidget field,
                                               ButtonWidget maxButton, LabelWidget maxLabel) {
        final boolean enabled = !powerAutoOn;
        field.setActive(enabled);
        field.setTextColor(enabled ? TEXT_ENABLED : TEXT_DISABLED);
        maxButton.setActive(enabled);
        maxLabel.setActive(enabled);
    }

    /** 「一键最大」按钮上那行字：电力自动开着 ⇒ 压暗（{@code §8}），与既有"不可用就压暗"同款。 */
    private String maxButtonText() {
        return machine.isPowerAutoParallel() ? "§8一键最大" : "§f一键最大";
    }

    /**
     * 🔴 <b>「电上限为什么是这个数」那一行（2026-10-02 第二轮新增）。</b>
     *
     * <p>逐档文案由 {@link ParallelOverrideMachine#energyCapReasonText} 这个<b>纯函数</b>给出
     * —— 离线判据断言的就是它，所以"面板不会再说尚未算出"这件事是<b>离线可证的</b>。
     */
    private String energyReasonText() {
        if (!machine.isPowerAutoParallel()) {
            return "§8电力自动关着 ⇒ 不按电力算（上面的输入框可用）";
        }
        return ParallelOverrideMachine.energyCapReasonText(
                machine.getEnergyCapState(), machine.getEnergyParallel());
    }

    /**
     * 🔴🔴 <b>「每并行耗电 k」那一行（2026-10-02 第八轮新增 · 用户裁决 ②）。</b>
     *
     * <p>用户原话（逐字）：<b>「用户能看到 {@code 688.13K}，却看不到 {@code 2.1} ⇒ 所以这次只能靠反推」</b>、
     * <b>「加上它，以后一眼能看出对不对」</b>。
     *
     * <p>⚠️ 与 {@link #energyReasonText} <b>同一种读法</b>：本方法每帧被调（Supplier），
     * 每次都<b>现读</b>机器字段 ⇒ 玩家开/关开关、机器换配方之后，这一行立刻跟着变，
     * 不会显示上一轮的数（LDLib 的 build 期快照陷阱见 {@link #applyPowerAutoDisabled}）。
     *
     * <p>逐档文案 = 纯函数 {@link ParallelOverrideMachine#perParallelCostText}
     * （离线判据断言的就是它 ⇒ "面板印出来了"这件事是<b>离线可证</b>的，不靠眼睛看）。
     */
    private String perParallelText() {
        if (!machine.isPowerAutoParallel()) {
            return "§8每并行耗电 —（电力自动关着 ⇒ 不按电力算）";
        }
        return ParallelOverrideMachine.perParallelCostText(
                machine.getEnergyCapState(), machine.getPerParallelMilliCost());
    }

    /**
     * 🔴 <b>开关按钮上那行字（Supplier 形态 ⇒ 每帧现读，不会显示过期状态）。</b>
     *
     * <p>「电力上限」没算出来时<b>不再</b>印 {@code §8（尚未算出）}（那句话同时代表五件不同的事），
     * 而是由 {@link ParallelOverrideMachine#energyCapShortText} 印一个<b>短值</b>
     * （{@code 无 / 待算 / 异常 / ∞ / 数字}），<b>具体原因</b>由下面那一行
     * （{@link #energyReasonText}）完整说出来。
     */
    private String powerSwitchText() {
        if (!machine.isPowerAutoParallel()) {
            return "§7电力自动 §c✘ 关 §8· 点此开启";
        }
        final String shortText = ParallelOverrideMachine.energyCapShortText(
                machine.getEnergyCapState(), machine.getEnergyParallel());
        return "§7电力自动 §a✔ 开 §8· 电上限 " + shortText;
    }

    /**
     * 第 2 行的实时读数：覆盖值 / 生效值并排，玩家一眼能看出"我填的数到底生效没"。
     *
     * <p>🔴 2026-09-27：括号里那个"上限"显示的是 <b>{@code min(存下来的覆盖值, 此刻的天花板)}</b>，
     * 不是存下来的原值 —— 因为覆盖值是持久化的数、而天花板会变（模块的自动值每 3 tick 跟物质模块走）。
     * 若照抄原值，玩家拆掉物质模块后会看到「上限 4096」而机器只能跑 64，那是本工程红线点名的假数据。
     *
     * <p>🔴 <b>2026-10-02 第二轮订正（旧句照留）</b>：旧句原文「没算出来时显示「尚未算出」而【不是】
     * 某个数字」—— <b>方向对、做法错</b>：它把"没有上限"的<b>五种原因</b>焊成了同一句话。
     * 现在这一行只印短值（{@link ParallelOverrideMachine#energyCapShortText}），
     * 原因交给 {@link #energyReasonText}（{@code y=74} 那一行）。
     */
    private String readoutText() {
        final long override = machine.getParallelOverride();
        final long effective = machine.getEffectiveParallel();
        // 🔴 2026-10-02：电力自动打开时，这一行改成讲"电力把并行压到了多少"。
        if (machine.isPowerAutoParallel()) {
            final String capText = ParallelOverrideMachine.energyCapShortText(
                    machine.getEnergyCapState(), machine.getEnergyParallel());
            // 🔴 2026-10-11 口径修正：两行读数【本来就是两个口径】，字面上说清楚，免得被混着读：
            //    生效 = getEffectiveParallel() = 引擎 getMaxParallel() 的同一个源 ⇒【每线程】
            //    （引擎父类再 × T 才是总预算）；电上限 = floor(P÷k) ⇒【总并行】。
            return "§7模式 §d电力自动§7 · 生效 §6" + formatParallel(effective)
                    + " §8（每线程）· 电上限 " + capText + " §8（总并行）";
        }
        if (override <= ParallelOverrideMachine.PARALLEL_AUTO) {
            return "§7模式 §f自动§7 · 生效 §6" + formatParallel(effective);
        }
        final long shownCeiling = Math.min(override, machine.getParallelOverrideCeiling());
        return "§7模式 §e手动§7 · 生效 §6" + formatParallel(effective) + " §8（上限 " + shownCeiling + "）";
    }

    /**
     * 文本 → 覆盖值。<b>永不抛异常</b>。
     *
     * @param text     输入框送来的原文（可能是 null / 空串 / 非数字 —— 客户端与服务端那两道
     *                 {@code setNumbersOnly} 校验器理论上已经拦过，这里仍然兜底）
     * @param fallback 解析不出来时保留的原值（<b>不是</b> 0：报错的输入不该顺手把玩家的设置清掉）
     */
    static long parseOrDefault(String text, long fallback) {
        if (text == null) {
            return fallback;
        }
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            // 空框 = 自动（与 setNumbersOnly 的 "空串 → String.valueOf(min)" 同口径，min = 0）。
            return ParallelOverrideMachine.PARALLEL_AUTO;
        }
        try {
            return ParallelOverrideMachine.clampOverride(Long.parseLong(trimmed));
        } catch (NumberFormatException e) {
            // 超长 / 带符号怪串 / 非数字 ⇒ 退回原值，绝不上抛（上抛会打断 GUI 的 client action 处理）。
            return fallback;
        }
    }

    /** 数值显示：自动档就是"自动"，其余按十进制原样（不做 K/M 缩写 —— 那是"看不清真值"的来源）。 */
    private static String formatParallel(long value) {
        if (value <= ParallelOverrideMachine.PARALLEL_AUTO) {
            return "自动";
        }
        return String.valueOf(value);
    }
}
