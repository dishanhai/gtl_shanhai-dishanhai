package com.shanhai.common.machine;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfigurator;
import com.gregtechceu.gtceu.api.gui.widget.IntInputWidget;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture;
import com.lowdragmc.lowdraglib.gui.util.ClickData;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import com.shanhai.common.recipe.PrimordialRecipeEffects;
import com.shanhai.machine.host.GuixuVisionCoreMachine;

import java.util.List;

/**
 * 主机（原始终焉引擎）侧栏的「中子星渲染」面板。
 *
 * <h2>它长在哪里、为什么长在那里</h2>
 * {@code PrimordialOmegaEngineMachine#attachConfigurators} 把它挂在<b>最后一个</b>，
 * 于是它落在主机 GUI 左侧那条按钮条（GTCEu {@code ConfiguratorPanel}）的<b>最下面</b> ——
 * 依据是 {@code ConfiguratorPanel$Tab} 的 y = {@code index * (tabSize + 2)}，而整条面板底对齐。
 *
 * <h2>🔴 为什么必须实现 {@link IFancyConfigurator}（而不是 {@code IFancyConfiguratorButton}）</h2>
 * 两者在 {@code ConfiguratorPanel$Tab} 的构造器里走<b>两条不同的路</b>（逐指令核对过）：
 * <pre>
 *   IFancyConfiguratorButton   ⇒ 只挂一个 ButtonWidget，点了就回调，没有面板
 *   其它 IFancyConfigurator    ⇒ 调 createConfigurator() 造一个 WidgetGroup，
 *                                包进可拖动的浮动面板（Tab.expand()），点了才弹出来
 * </pre>
 * 我们需要"弹出一组控件"，所以必须走第二条（与侧栏那第 5 个「配方最短耗时」同构 ——
 * 玩家已经会这个交互）。
 *
 * <h2>面板尺寸怎么算出来的（这不是猜的）</h2>
 * {@code Tab} 构造器的字节码原文是：
 * <pre>
 *   widget.setSelfPosition(new Position(border, tabSize));                 // 我们的面板落在 (4, 24)
 *   view.setSize(new Size(widget.getSize().width  + border * 2,            // 宽 = 面板宽 + 8
 *                         widget.getSize().height + button.getSize().height + border));  // 高 = 面板高 + 24 + 4
 *   view.addWidget(new ImageWidget(border + 5, border,                     // 标题条 (9, 4)
 *                                  widget.getSize().width - tabSize - 5,  // 宽 = 面板宽 - 29
 *                                  tabSize - border,                      // 高 = 20
 *                                  new TextTexture(configurator.getTitle().getString())));
 * </pre>
 * ⇒ <b>标题条占据面板上方 20px，我们的控件从面板自身的 y=0 开始即可</b>；
 * 本类取 150×116（2026-09-23 由 96 加高，见 {@link #PANEL_H}）⇒ 弹出面板实际为 158×144，
 * 标题条 (9,4,121,20) 与控件<b>不重叠</b>。
 *
 * <h2>🔴 2026-10-25 第十九改：这个 tab 在「归墟视界枢核」上已经<b>换用途</b>了（中子星外观 → 角速度）</h2>
 * 用户原话（逐字）：「这个角速度干脆让玩家可以在主机控制器里面调得了，<b>可以修改那个没用的调整中子星的
 * 大小与颜色的按钮</b>」。
 *
 * <p>⇒ 本类现在是<b>双形态</b>，挂载点（{@code attachConfigurators} 里的位置 ⑤）与类名都没动：
 * <pre>
 *   宿主是 {@link GuixuVisionCoreMachine}（归墟视界枢核） ⇒ 标题「角速度」，面板 = 3 个 IntInputWidget
 *   其它（原始终焉引擎 = 基类本体）                   ⇒ 标题「中子星渲染」，面板 = 原来那 7 个控件（一字未改）
 * </pre>
 * 🔴 <b>为什么要分形态</b>：这个 tab 挂在<b>基类</b> {@code PrimordialOmegaEngineMachine} 上，而那台
 * 老主机（{@code shanhai:primordial_omega_engine}）<b>仍然注册着、仍然能摆</b>
 * （{@code ShanhaiMachines:182}）。新的 6 个字段只在 {@link GuixuVisionCoreMachine} 里
 * ⇒ 若把面板无条件换成角速度，老主机上就会出现<b>三个点了没反应的控件</b>（本工程明令禁止的"假开关"），
 * 同时它的中子星外观面板（唯一入口）会消失。分形态 = 老主机逐位不变、新主机换用途。
 *
 * <h2>角速度面板的口径（与机器字段同源，见 {@code GuixuVisionCoreMachine} 的同名常量）</h2>
 * <pre>
 *   内环  1..60 °/s（默认 12）     上舰  0..60 °/s（默认  9）     下舰  0..60 °/s（默认  9，玩家填正数）
 * </pre>
 * 输入框用的是 GTCEu 的 {@code IntInputWidget}（文本框 + ± 按钮，默认 100×20）—— 与侧栏
 * 「配方最短耗时」（gtladditions 的 {@code LimitedDurationConfigurator}）<b>同一款</b>，
 * 玩家已经会这个交互。钳位不在这里做第二道（与半径按钮同一条纪律：真正要防的是伪造的
 * {@code writeClientAction} 包，那只能服务端拦）⇒ 钳位在机器侧 setter。
 *
 * <h2>面板尺寸</h2>
 * 角速度面板与外观面板<b>共用</b> {@link #PANEL_W} / {@link #PANEL_H} = 150×116
 * ⇒ 弹出面板仍是 <b>158×144</b>，与改造前<b>逐像素相同</b>（用户已验收过这个占位，不动它）。
 *
 * <h2>🔴 为什么控件用 ButtonWidget + LabelWidget，而不用 IntInputWidget（<b>2026-10-18 的旧结论，已部分作废</b>）</h2>
 * <b>本工程编译期吃的是 {@code libs/ldlib-forge-1.20.1-1.0.33.b.jar}，它里面【没有】
 * {@code com.lowdragmc.lowdraglib.gui.widget.IntInputWidget}</b>
 * （逐条列举过该 jar 的 {@code gui/widget/} 与 {@code gui/texture/} 目录；
 * {@code IntInputWidget} / {@code ColorTexture} 都不在）。
 * 侧栏第 5 个控件能用它、是因为 gtladditions 自己那套 LDLib 里有 —— 我们引用的是<b>它的类</b>
 * （{@code LimitedDurationConfigurator}），不是自己 new。
 * ⇒ 外观面板只用<b>已核实存在</b>的件：
 * {@code ButtonWidget(…, IGuiTexture, Consumer<ClickData>)}、
 * {@code LabelWidget(int,int,Supplier&lt;String&gt;)}、
 * {@code ImageWidget(int,int,int,int,Supplier&lt;IGuiTexture&gt;)}、
 * {@code ColorRectTexture(int)}、{@code GuiTextures.BUTTON}。
 *
 * <p>✅ <b>2026-10-25 订正</b>：上面这段只对 <b>LDLib 那个包名</b>成立。角速度面板用的是
 * <b>GTCEu 的</b> {@code com.gregtechceu.gtceu.api.gui.widget.IntInputWidget}
 * （{@code gtceu-1.20.1-1.4.4.jar} 里有、public、四个构造器全可用 —— {@code javap} 实证见交付报告），
 * 而且<b>同一台机器</b>的侧栏第 ④ 个 tab「配方最短耗时」就是它
 * （{@code LimitedDurationConfigurator.createConfigurator()} 的字节码原文：
 * {@code new IntInputWidget(getter,setter).setMin(10).setMax(200)} 塞进 {@code WidgetGroup(0,0,100,20)}）
 * ⇒ 这个类在本机的 UI 上早就在跑了，不是新依赖。
 *
 * <h2>点击怎么到达服务端（没有新网络代码）</h2>
 * {@code ButtonWidget.mouseClicked} 的实现是「{@code writeClientAction(1, …)} + 本地也调一次回调」
 * （逐指令核对），`writeClientAction` → `WidgetUIAccess` → C2S {@code CPacketUIClientAction}
 * → 服务端同 id widget 的 {@code handleClientAction} 再触发一次回调。
 * ⇒ <b>同一个 lambda 会跑两遍</b>（客户端本地一次给即时反馈、服务端一次是权威写入），
 * 而服务端那次会经 {@code notifyBlockUpdate()} 把 {@code @DescSynced} 值广播给所有观看者。
 * <p>⛔ <b>【已订正 · 2026-09-23】下面这三行原文是【错的】，保留原文仅为留档：</b>
 * <pre>
 * ⚠️ 由此推出一条纪律：回调里不许依赖 ClickData.isShiftClick ——
 * 服务端侧的 ClickData 是 new ClickData()(默认值)，修饰键信息不会过网，
 * 用了就会"客户端按 shift 改了 5、服务端只改了 1"。
 * </pre>
 * <p>🔴 <b>错在哪（反编译 + {@code javap} 逐指令实证，证据在
 * {@code temp/evidence\panel-rainbow\}）</b>：
 * <ol>
 *   <li>{@code ClickData} <b>有</b> {@code public final boolean isShiftClick} 与 {@code isCtrlClick}
 *       两个字段（不是"默认值"）。</li>
 *   <li><b>无参构造器 {@code ClickData()} 整个标了 {@code @OnlyIn(CLIENT)}</b> ——
 *       <b>服务端根本调不到它</b>，所以"服务端侧是 {@code new ClickData()}"这一句从根上就不成立。</li>
 *   <li>服务端用的是 {@code ClickData.readFromBuf(buf)}；而 {@code writeToBuf} 里
 *       {@code buf.writeBoolean(this.isShiftClick)} / {@code writeBoolean(this.isCtrlClick)}
 *       <b>把两个布尔写进了网络包</b> ⇒ <b>修饰键确实过网</b>。</li>
 *   <li>整条链路内建于 {@code ButtonWidget.mouseClicked}：
 *       {@code new ClickData()} → {@code writeClientAction(1, clickData::writeToBuf)}
 *       → 服务端 {@code handleClientAction(1, buf)} → {@code ClickData.readFromBuf(buf)}。</li>
 * </ol>
 * <p>✅ <b>正确做法（本类现在的写法）</b>：<b>回调里可以直接用
 * {@code clickData.isShiftClick} / {@code clickData.isCtrlClick}</b>
 * —— LDLib 的 {@code ButtonWidget} 已随 client action 把它们发过来；
 * <b>服务端那次 {@code isRemote=false}、客户端那次 {@code isRemote=true}，同一个 lambda 跑两遍、
 * 修饰键完全相同</b>（因为过了网）⇒ 两边算出同一个值，<b>不会</b>出现"客户端按 shift 改了 5、
 * 服务端只改了 1"。当前的使用点有两个：色相按钮与半径按钮，共用 {@link #acceleratedStep(ClickData, int)}。
 * <p>⚠️ <b>与本项目既有约定的兼容性</b>：这条修正与"回调两边都算、服务端覆盖"的写法并不冲突 ——
 * 我们<b>不</b>照抄 GTCEu 的 {@code if (!cd.isRemote) return;}，因为那会让客户端本地失去即时反馈
 * （球要等服务端回包才变色）。
 *
 * <h2>条件不满足时怎么变灰</h2>
 * {@code IGuiTexture.setColor(int)} 是接口默认方法（javap 核实），
 * {@code ResourceTexture.setColor(int)} 覆写为返回自身类型 ⇒
 * {@code getIcon()} 在条件不满足时返回<b>同一个贴图的暗色副本</b>。
 * 这只是<b>提示</b>：真正的边界是 {@code PrimordialOmegaEngineMachine} 的 setter 里那道
 * {@code canControlStarRender()} 闸门（防伪造包）。
 *
 * <h2>🔴 2026-10-18：上面这一节已停用（原文保留）</h2>
 * 用户要求去掉外观面板的模块等级门槛 ⇒ {@code getIcon()} 恒返回 {@link #ICON_ACTIVE}、
 * 7 个控件恒可用；{@link #ICON_DISABLED} 不再被引用。
 * 等级仍决定「跟随等级」档的自动大小/颜色（机器侧 {@code moduleSlotBonus()} 未动），
 * 只是不再是操作前提。
 */
public final class StarRenderConfigurator implements IFancyConfigurator {

    /** 我们的图标：自带资源（不引用 gtmthings 的覆盖板贴图）。 */
    private static final ResourceLocation ICON =
            new ResourceLocation("shanhai", "textures/gui/neutron_render_button.png");

    /** 正常态图标（懒建一次即可；{@code Tab.drawInBackground} 每帧都会调 {@code getIcon()}）。 */
    private static final IGuiTexture ICON_ACTIVE = new ResourceTexture(ICON);

    /**
     * 🔴 <b>已停用（2026-10-18）</b>：外观面板不再受模块等级限制 ⇒ 图标恒亮，
     * 本暗色副本不再被引用；保留仅为留档（⛔ 别再拿它当门）。
     *
     * <p>条件不足时的暗色副本。
     *
     * <p>{@code 0xFF3F3F3F} 是"与白相乘后的灰度系数"—— LDLib 的 {@code setColor} 语义就是色乘子
     * （同类用法见 GTCEu 自己：{@code FancyMachineUIWidget} 里
     * {@code GuiTextures.BACKGROUND.copy().setColor(...)}）。
     */
    private static final IGuiTexture ICON_DISABLED = new ResourceTexture(ICON).setColor(0xFF3F3F3F);

    /** 面板自身的尺寸（弹出面板 = 本尺寸 + (8, 28)，见类注释）。 */
    private static final int PANEL_W = 150;

    /**
     * 面板高度 <b>116</b>（<b>2026-09-23 由 96 加高</b>；最后一行落在 y=97..113，底部留 3px）。
     *
     * <p>⚠️ 这是<b>算出来的</b>，不是拍的：控件若越过 {@code PANEL_H}，LDLib 不会报错，
     * 只会被父容器裁掉看不见（静默消失），所以每加一行都要回算一次。
     *
     * <h2>🔴 为什么要加高（第 7 个按钮放不下）</h2>
     * 加高之前是 150×96，逐像素实测的空隙只有
     * {@code y=91..96}（150×5px，比最小控件 14px 还矮）、
     * {@code x=22..44,y=41..55}（22×14 太窄）、
     * {@code x=118..146,y=41..55}（28×14，而且跟随等级档时会被 {@code (62,43)} 那个
     * 宽约 81px 的读数标签压住）——<b>一个"带文字的按钮"确实放不下</b>。
     * ⇒ 用户拍板「<b>A · 面板加高 96 → 116px</b>」，腾出 {@code y=97..113} 给第 6 行。
     *
     * <h2>尺寸连带变化（{@code ConfiguratorPanel$Tab} 的字节码算法，不是猜的）</h2>
     * <pre>
     *   弹出面板 = (面板宽 + 8, 面板高 + 24 + 4)
     *   加高前   : 150×96  ⇒ 158×124
     *   加高后   : 150×116 ⇒ 158×144   （宽不变，高 +20px）
     * </pre>
     *
     * <h2>🔴🔴 已知风险：弹出面板可能溢出游戏界面（<b>未实测，需用户进游戏看</b>）</h2>
     * {@code ConfiguratorPanel$Tab.expand()} / {@code onChildSizeUpdate()} 的<b>字节码里没有任何
     * 把弹出面板钳到屏幕/视口内的逻辑</b>（只有 {@code new Position(dragOffsetX - view.width + …,
     * dragOffsetY)} 与 {@code Animation.position/size}，<b>没有</b> {@code Math.min} 与视口比较）
     * ⇒ {@code [推断]} 面板变高 20px 后<b>可能超出屏幕/窗口外框</b>。
     *
     * <h3>如果真的溢出 ⇒ 备用方案 B（回退用，<b>不要顺手实现</b>）</h3>
     * <pre>
     *   保持 PANEL_H = 96（本常量改回 96，并删掉第 6 行那两个控件）：
     *   把第 5 行的色块 ImageWidget(126,77,14,14) 换成 14×14 的周期循环按钮，
     *   周期读数并进第 5 行的色相读数标签（"§f240° · §7周期 §f30s"）。
     *   代价：① 失去实时色块（它本来就只是"近似提示"，见 previewRgb() 的 javadoc）；
     *         ② 14×14 放不下文字，只能画图标/短码；
     *         ③ 14×14 的按钮踩不准（比现在最小的 14×14 色相按钮还挤）。
     *   备选 C：第 5 行按档位"变形"（手动档显示色相、跟随+彩虹档显示周期）
     *           —— 同一位置控件语义随档位变，玩家容易看错。
     *   备选 D：把周期并进「配色」按钮的 Shift+点击
     *           —— 用户明确要"一个可以调节彩虹变化周期的按钮"，这会变成看不出来的隐藏功能。
     * </pre>
     * <p>⚠️ <b>本轮【没有】做出这个判断</b>：红线禁止启动客户端，所以"到底溢不溢出"是
     * <b>没做到</b>的验证项，只能由用户进游戏看（已写进交付报告）。
     */
    private static final int PANEL_H = 116;

    /** 色相按钮的步长（度）。 */
    private static final int HUE_STEP = 15;

    /**
     * 半径按钮的<b>基准</b>步长（格）。
     *
     * <p>🔴 2026 本轮新增 Shift/Ctrl 加速（用户原话：「我希望它上面那个大小调整也能快速调整」）。
     * 四档 = {@link #acceleratedStep} 的 ×1/×3/×6/×12：
     * <pre>
     *   不按修饰键 ⇒ +1 ／ Shift ⇒ +3 ／ Ctrl ⇒ +6 ／ Shift+Ctrl ⇒ +12
     * </pre>
     * <b>刻意不用色相那套 15/45/90/180</b>：色相是 mod 360 的环（大步长只是"跳得更远"），
     * 而半径是 <b>13–43 的有限区间</b>（共 31 格）—— 步长给到 12 时，从底到头最多 3 下，
     * 再大就会"一下跳过头"，玩家反而要多点几次找回来。
     *
     * <p>🔴 <b>加速后仍被钳在 13–43</b>：钳位不在面板里，而在
     * {@code PrimordialOmegaEngineMachine#stepStarRadius(int)} → {@code clampStarRadius(int)}
     * （{@code Math.max(STAR_RADIUS_MIN, Math.min(radius, STAR_RADIUS_MAX))}）。
     * 面板这里<b>不做第二道钳位</b>是有意的：真正要防的是<b>伪造的 {@code writeClientAction} 包</b>，
     * 那只能服务端拦；面板自己再钳一次只会让"UI 看着没越界、包里越界"这种不一致更难发现。
     */
    private static final int RADIUS_STEP = 1;

    private final PrimordialOmegaEngineMachine machine;

    /**
     * 宿主如果是「归墟视界枢核」则非 {@code null} ⇒ 本 tab 显示<b>角速度</b>面板；
     * {@code null}（= 原始终焉引擎本体）⇒ 显示原来的<b>中子星外观</b>面板。见类注释"双形态"一节。
     */
    private final GuixuVisionCoreMachine host;

    /**
     * @param machine 挂载它的那台机器（基类）。内部按 {@code instanceof} 分形态 ——
     *                {@code attachConfigurators} 那边<b>不需要</b>分支，一个构造器通吃。
     */
    public StarRenderConfigurator(PrimordialOmegaEngineMachine machine) {
        this.machine = machine;
        this.host = machine instanceof GuixuVisionCoreMachine guixu ? guixu : null;
    }

    @Override
    public Component getTitle() {
        // 字面量而不是 translatable：与本工程既有约定一致（lang 归 java-assets 管，
        // 缺条目时 translatable 会把裸 key 直接画在 GUI 上）。
        return Component.literal(host != null ? "角速度" : "中子星渲染");
    }

    @Override
    public IGuiTexture getIcon() {
        // 2026-10-18：外观面板不再受模块等级限制 ⇒ 图标恒亮（ICON_DISABLED 已停用，保留）。
        // 2026-10-25：图标<b>两个形态共用</b>这一枚（同一张贴图、同一个位置）
        //   ⇒ 玩家看到的是"原来那个按钮还在原地，只是点开的东西换了"，不会找不到。
        return ICON_ACTIVE;
    }

    /**
     * tab 的悬停说明。
     *
     * <p>⚠️ 这里刻意<b>不</b>说「只影响你自己」：本面板写的是机器状态、经 {@code @DescSynced} 广播，
     * <b>所有人都会看到</b>（旧那句 tooltip 的不一致已在本轮一并订正，见
     * {@code PrimordialOmegaEngineMachine#sphereStyleTooltips}）。
     */
    @Override
    public List<Component> getTooltips() {
        if (host != null) {
            return List.of(
                    Component.literal("三组角速度（°/s，整数）").withStyle(ChatFormatting.AQUA),
                    Component.literal("§7内环 §f" + GuixuVisionCoreMachine.HOST_RING_DEG_MIN + "–"
                                    + GuixuVisionCoreMachine.HOST_RING_DEG_MAX + "§7 · 上舰 §f"
                                    + GuixuVisionCoreMachine.HOST_SHIP_DEG_MIN + "–"
                                    + GuixuVisionCoreMachine.HOST_SHIP_DEG_MAX + "§7 · 下舰 §f"
                                    + GuixuVisionCoreMachine.HOST_SHIP_DEG_MIN + "–"
                                    + GuixuVisionCoreMachine.HOST_SHIP_DEG_MAX)
                            .withStyle(ChatFormatting.GRAY),
                    Component.literal("改的是机器状态，").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal("所有人都会看到").withStyle(ChatFormatting.GOLD))
                            .append(Component.literal("（会随存档保存）").withStyle(ChatFormatting.GRAY)),
                    Component.literal("§8下舰填正数即可：方向本来就是逆向（渲染侧取负号）")
                            .withStyle(ChatFormatting.DARK_GRAY));
        }
        return List.of(
                Component.literal("中子星的大小与颜色").withStyle(ChatFormatting.AQUA),
                Component.literal("改的是机器状态，").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal("所有人都会看到").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal("（会随存档保存）").withStyle(ChatFormatting.GRAY)),
                Component.literal("⚠ 宇宙模式下本面板不生效（当前渲染风格见下）").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public Widget createConfigurator() {
        // 双形态：归墟视界枢核 ⇒ 角速度（用户 2026-10-25 的"改这个没用的按钮"）；
        //         原始终焉引擎本体 ⇒ 原来的外观面板（一字未改，老存档不受影响）。
        return host != null ? createAngularSpeedPanel() : createAppearancePanel();
    }

    /**
     * 「角速度」面板（<b>归墟视界枢核</b>）：标题行 + 三个 {@code IntInputWidget}（内环 / 上舰 / 下舰）。
     *
     * <h2>几何（150 × 116，与外观面板同一个 {@link #PANEL_W}/{@link #PANEL_H}）</h2>
     * <pre>
     *   y=3   "§7角速度（单位 §f°/s§7，整数）"                 ← 说明行
     *   y=17  "内环°/s"  + IntInputWidget(48, 17, 98, 20)     ← 输入框真实高度恒 20（± 按钮 20 高）
     *   y=45  "上舰°/s"  + IntInputWidget(48, 45, 98, 20)
     *   y=73  "下舰°/s"  + IntInputWidget(48, 73, 98, 20)     ← 底 93
     *   y=99  "§8下舰为逆向，填正数即可"                        ← 底 107 < 116
     * </pre>
     * 输入框内部布局是 {@code NumberInputWidget.buildUI} 算的（{@code javap -c} 实证）：
     * {@code buttonWidth = clamp(w/5, 15, 40)}、文本框宽 = {@code w − 2·buttonWidth − 4}
     * ⇒ 本面板 98 宽 ⇒ 两个 ± 各 19、文本框 56 宽（够 2 位数，上限 60）。
     *
     * <h2>为什么不在这里钳位</h2>
     * 与半径按钮同一条纪律：真正要防的是<b>伪造的 {@code writeClientAction} 包</b>，
     * 那只能服务端拦（{@code GuixuVisionCoreMachine#setHostRingDegPerSec} 等已经钳过）。
     * 面板再钳一次只会让"UI 看着没越界、包里越界"更难发现。
     */
    private WidgetGroup createAngularSpeedPanel() {
        WidgetGroup group = new WidgetGroup(0, 0, PANEL_W, PANEL_H);
        group.setBackground(GuiTextures.BACKGROUND_INVERSE);

        group.addWidget(new LabelWidget(4, 3, "§7角速度（单位 §f°/s§7，整数）"));

        group.addWidget(new LabelWidget(4, 21, "§7内环§8°/s"));
        group.addWidget(new IntInputWidget(48, 17, 98, 20,
                host::getHostRingDegPerSec,
                host::setHostRingDegPerSec)
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

        group.addWidget(new LabelWidget(4, 49, "§7上舰§8°/s"));
        group.addWidget(new IntInputWidget(48, 45, 98, 20,
                host::getHostShipUpDegPerSec,
                host::setHostShipUpDegPerSec)
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

        group.addWidget(new LabelWidget(4, 77, "§7下舰§8°/s"));
        group.addWidget(new IntInputWidget(48, 73, 98, 20,
                host::getHostShipDownDegPerSec,
                host::setHostShipDownDegPerSec)
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

        group.addWidget(new LabelWidget(4, 99, "§8下舰为逆向，填正数即可"));
        return group;
    }

    /**
     * 原来的「中子星渲染」面板（<b>原始终焉引擎</b>本体用；2026-10-25 之后内容一字未改）。
     *
     * <p>⚠️ 不要把它删掉：这是老主机<b>唯一</b>的中子星大小/颜色入口（见类注释"双形态"一节）。
     */
    private WidgetGroup createAppearancePanel() {
        // 2026-10-18：外观面板不再受模块等级限制 ⇒ 灰态判断恒为可用
        //（等级只决定「跟随等级」档的自动大小/颜色，不是操作前提）。
        final boolean enabled = true;

        WidgetGroup group = new WidgetGroup(0, 0, PANEL_W, PANEL_H);
        group.setBackground(GuiTextures.BACKGROUND_INVERSE);

        // ── 第 0 行：外观状态（中性说明；等级是「跟随等级」档的自动值，不再是门槛） ──
        group.addWidget(new LabelWidget(4, 3, () -> "§7外观可调 · 默认随等级 Lv."
                + machine.moduleSlotBonus()));

        // ── 第 1 行：既有读数（用户要求"面板顺带显示读数"；全部取现成方法，不新增计算） ──
        group.addWidget(new LabelWidget(4, 15, this::readoutText));

        // ── 第 2 行：宇宙模式提示（结构上无效，只是说清楚，见类注释） ──
        group.addWidget(new LabelWidget(4, 27, () -> machine.getSphereStyle()
                == PrimordialSphereStyle.UNIVERSE
                ? "§e当前为宇宙模式，本面板不生效"
                : ""));

        // ── 第 3 行：大小（−1 / +1，可 Shift/Ctrl 加速） ────────────────────
        // 🔴 2026 本轮：与色相那对按钮同款，按修饰键加速（用户明确要求「大小调整也能快速调整」）。
        //    四档 1/3/6/12；钳位在机器侧（stepStarRadius → clampStarRadius），见 RADIUS_STEP 的注释。
        group.addWidget(new LabelWidget(4, 43, "§7大小"));
        ButtonWidget radiusMinus = new ButtonWidget(44, 41, 14, 14, GuiTextures.BUTTON,
                clickData -> machine.stepStarRadius(-acceleratedStep(clickData, RADIUS_STEP)));
        radiusMinus.setHoverTooltips(
                Component.literal("半径 −" + RADIUS_STEP + "（按修饰键加速）").withStyle(ChatFormatting.GRAY),
                Component.literal(RADIUS_ACCEL_TOOLTIP).withStyle(ChatFormatting.DARK_GRAY));
        group.addWidget(radiusMinus);
        group.addWidget(new LabelWidget(62, 43, () -> "§f" + currentRadiusText()));
        ButtonWidget radiusPlus = new ButtonWidget(104, 41, 14, 14, GuiTextures.BUTTON,
                clickData -> machine.stepStarRadius(acceleratedStep(clickData, RADIUS_STEP)));
        radiusPlus.setHoverTooltips(
                Component.literal("半径 +" + RADIUS_STEP + "（按修饰键加速）").withStyle(ChatFormatting.GRAY),
                Component.literal(RADIUS_ACCEL_TOOLTIP).withStyle(ChatFormatting.DARK_GRAY));
        group.addWidget(radiusPlus);

        // ── 第 4 行：档位开关（跟随等级 ⇄ 手动） + 自动配色开关（原光谱 ⇄ 彩虹） ──
        // 🔴 两个开关并排放在同一行，是为了【不动面板高度】（加行会让弹出面板变高，而弹出面板
        //    尺寸 = 本组尺寸 + (8,28)，改高就要连带复核屏幕占位）。
        ButtonWidget modeButton = new ButtonWidget(4, 60, 72, 16, GuiTextures.BUTTON,
                clickData -> machine.toggleStarRenderMode());
        modeButton.setHoverTooltips(
                Component.literal("点击切换：跟随等级 ⇄ 手动（自己定大小/颜色）")
                        .withStyle(ChatFormatting.GRAY),
                Component.literal("跟随等级 = 尺寸与颜色都由专属槽的模块等级决定").withStyle(ChatFormatting.DARK_GRAY));
        group.addWidget(modeButton);
        group.addWidget(new LabelWidget(10, 64, () -> machine.isStarRenderManual()
                ? "§d手动" : "§b跟随等级"));

        // 自动配色开关：默认「原光谱」（用户裁决：彩虹只当可选，不抢默认位）。
        // ⚠️ 手动档里它点了不会变（机器侧 toggleStarPalette 直接 return）——所以按钮标签会
        //    明写「手动档不用」，不让它变成一个"按了没反应又不说为什么"的假开关。
        ButtonWidget paletteButton = new ButtonWidget(80, 60, 66, 16, GuiTextures.BUTTON,
                clickData -> machine.toggleStarPalette());
        paletteButton.setHoverTooltips(
                Component.literal("自动配色（只作用于「跟随等级」档）").withStyle(ChatFormatting.GRAY),
                Component.literal("原光谱：Lv.1 橙红 → 中段近白 → Lv.17 冷蓝（默认）")
                        .withStyle(ChatFormatting.GRAY),
                Component.literal("彩虹：红 → 黄绿 → 青 → 蓝 → 紫 → 品红").withStyle(ChatFormatting.GRAY),
                Component.literal("手动档的颜色由下面的色相环决定，与本开关无关")
                        .withStyle(ChatFormatting.DARK_GRAY));
        group.addWidget(paletteButton);
        group.addWidget(new LabelWidget(86, 64, this::paletteLabelText));

        // ── 第 5 行：色相（−15° / +15°）+ 实时色块 ──────────────────────────
        // 🔴 2026-09-23：色相步长按修饰键加速（用户说的"调颜色那里"）。四档 15/45/90/180 全部整除 360
        //    ⇒ 色相永远落在干净网格上；【刻意不用】GTCEu 的 1/8/64/512 —— 色相是 mod 360 的环，
        //    ×512 会让 15×512 mod 360 = 120°，看起来像随机跳，玩家无法预期。
        //    ⚠️ 修饰键【确实过网】（见类注释"点击怎么到达服务端"那节的订正）⇒ 客户端与服务端
        //    算出的 step 相同，不会出现两边不一致。
        group.addWidget(new LabelWidget(4, 79, "§7色相"));
        ButtonWidget hueMinus = new ButtonWidget(44, 77, 14, 14, GuiTextures.BUTTON,
                clickData -> machine.stepStarHue(-acceleratedStep(clickData, HUE_STEP)));
        hueMinus.setHoverTooltips(
                Component.literal("色相 −" + HUE_STEP + "°（按修饰键加速）").withStyle(ChatFormatting.GRAY),
                Component.literal(ACCEL_TOOLTIP).withStyle(ChatFormatting.DARK_GRAY));
        group.addWidget(hueMinus);
        group.addWidget(new LabelWidget(62, 79, () -> "§f" + currentHueText()));
        ButtonWidget huePlus = new ButtonWidget(104, 77, 14, 14, GuiTextures.BUTTON,
                clickData -> machine.stepStarHue(acceleratedStep(clickData, HUE_STEP)));
        huePlus.setHoverTooltips(
                Component.literal("色相 +" + HUE_STEP + "°（按修饰键加速）").withStyle(ChatFormatting.GRAY),
                Component.literal(ACCEL_TOOLTIP).withStyle(ChatFormatting.DARK_GRAY));
        group.addWidget(huePlus);
        // 色块：用 ImageWidget 的 Supplier 构造器（每帧取一次 texture）⇒ 取值随档位/色相/配色档实时变，
        // 不需要额外的同步机制（两侧读的都是同一份 @DescSynced 字段）。
        group.addWidget(new ImageWidget(126, 77, 14, 14,
                        () -> new ColorRectTexture(0xFF000000 | previewRgb()))
                .setBorder(1, 0xFF000000)
                .setHoverTooltips(
                        Component.literal("当前配色的提示色").withStyle(ChatFormatting.GRAY),
                        Component.literal("手动档 = 精确；跟随等级档 = 近似（真值走 7 档 + sRGB↔线性插值）")
                                .withStyle(ChatFormatting.DARK_GRAY),
                        Component.literal("彩虹档开了周期后，色块只示「等级对应的静态色」")
                                .withStyle(ChatFormatting.DARK_GRAY)));

        // ── 第 6 行：彩虹变化周期（★ 2026-09-23 新增，第 7 个按钮） ──────────────
        // 照第 4 行的"整宽按钮 + 覆盖标签"写法（14px 高的小按钮放不下中文标签）。
        // 🔴 它【不加】Shift/Ctrl 加速：循环按钮每点一下就是"下一格"，加速会变成"跳过档位"。
        ButtonWidget periodButton = new ButtonWidget(4, 97, 142, 16, GuiTextures.BUTTON,
                clickData -> machine.cycleRainbowPeriod());
        periodButton.setHoverTooltips(
                Component.literal("彩虹变化周期（只作用于彩虹档）").withStyle(ChatFormatting.GRAY),
                Component.literal("0 = 不变化（静态）／20 tick = 1 秒 走完一个往返").withStyle(ChatFormatting.GRAY),
                Component.literal("一个往返 = 红 → 品红 → 红").withStyle(ChatFormatting.DARK_GRAY),
                Component.literal("默认「不变」，所以不动它画面与以前完全一样").withStyle(ChatFormatting.DARK_GRAY));
        group.addWidget(periodButton);
        group.addWidget(new LabelWidget(10, 101, this::rainbowPeriodLabelText));

        // ── 档位不适用时把 7 个旋钮置为"不可用" ─────────────────────────────
        // 2026-10-18：模块等级门控已停用 ⇒ 7 个控件不再因等级变灰（enabled 恒 true），
        //    这里只剩下面那条"周期按钮按档位置灰"的真实理由。
        // ⚠️ 刻意【不】再往面板上叠一行文字：第 0 行已说明"等级只决定默认值"，
        //    再叠一层只会压在第 4 行按钮上（控件重叠是 LDLib 里最常见的"看不清"来源）。
        for (Widget w : List.of(radiusMinus, radiusPlus, modeButton, paletteButton, hueMinus, huePlus,
                periodButton)) {
            w.setActive(enabled);
        }
        // 🔴 周期按钮比上面更严：它在【手动档】与【光谱档】下都无效 ⇒ 必须置灰，
        //    否则就是一个"按了没反应又不说为什么"的假开关（本项目明规，见 paletteButton 的注释）。
        // ⚠️ 诚实标注一个【既有】限制：setActive 是 build 期快照（enabled 在 createConfigurator()
        //    里只取一次），所以打开 GUI 后再切档，灰态不会实时刷新
        //    —— 既有 6 个按钮也是这样，不是本次引入的退化；要实时刷新得在 WidgetGroup.updateScreen
        //    里逐帧 setActive，本轮不做。
        periodButton.setActive(enabled
                && !machine.isStarRenderManual()
                && machine.getStarPalette() == PrimordialOmegaEngineMachine.STAR_PALETTE_RAINBOW);
        return group;
    }

    /**
     * <b>修饰键加速的步长</b>（本方法的两个调用方：色相、半径）。
     *
     * <pre>
     *   无修饰键        ⇒ base × 1
     *   Shift           ⇒ base × 3
     *   Ctrl            ⇒ base × 6
     *   Shift + Ctrl    ⇒ base × 12
     * </pre>
     * 色相（{@code base = 15°}）⇒ 15 / 45 / 90 / 180，<b>四档全部整除 360</b>
     * ⇒ 色相永远落在干净的网格上（15 的整数倍）。
     * <p>⚠️ <b>刻意不用 GTCEu 的 1/8/64/512</b>（{@code IntInputWidget.getChangeValues}）：
     * 那是给"数量"用的（数量可以很大），而色相是 <b>mod 360 的环</b> ——
     * {@code 15 × 512 = 7680°，mod 360 = 120°}，看起来像随机跳，玩家无法预期。
     * <p>半径（{@code base = 1}）⇒ 1 / 3 / 6 / 12，区间只有 31 格，再大就是"跳过头"。
     *
     * <p>🔴 <b>修饰键确实过网</b>（{@code ClickData} 的两个布尔随 client action 一起发过来，
     * 见类注释那节订正）⇒ 同一个 lambda 在客户端（{@code isRemote=true}）与服务端
     * （{@code isRemote=false}）各跑一次，<b>两边的标志完全相同</b> ⇒ 算出同一个 step、同一个新值。
     * <p>⚠️ <b>不能照抄 GTCEu 的 {@code if (!cd.isRemote) return;}</b>：那会让客户端本地不再即时反馈
     * （球要等服务端回包才变色）。我们这套"两边都算、服务端覆盖"的写法本身就自洽，<b>保持现状</b>。
     *
     * @param clickData LDLib 随按钮点击传进来的点击数据（含修饰键；永不为 {@code null}）
     * @param base      基准步长（色相 = {@link #HUE_STEP} 15；半径 = {@link #RADIUS_STEP} 1）
     */
    private static int acceleratedStep(ClickData clickData, int base) {
        if (clickData.isCtrlClick) {
            return clickData.isShiftClick ? base * 12 : base * 6;   // ×12 / ×6
        }
        if (clickData.isShiftClick) {
            return base * 3;                                         // ×3
        }
        return base;                                                 // ×1
    }

    /** 色相按钮 hover 里那行加速说明（两个按钮共用一句，避免抄两遍还不一致）。 */
    private static final String ACCEL_TOOLTIP =
            "加速：无 = 15° ／ Shift = 45° ／ Ctrl = 90° ／ Shift+Ctrl = 180°";

    /**
     * 半径按钮 hover 里那行加速说明。
     *
     * <p>末句「范围 13–43」是刻意写的：加速到 +12 时，从 39 再点一下会停在 43 而不是 51 ——
     * <b>说明白"会被钳住"，比让玩家自己发现"点了没反应"要好</b>。
     */
    private static final String RADIUS_ACCEL_TOOLTIP =
            "加速：无 = 1 ／ Shift = 3 ／ Ctrl = 6 ／ Shift+Ctrl = 12（范围 13–43，到边界就停）";

    /**
     * 第一行读数：等级 / 产出倍率 / 耗电减免。
     *
     * <p>三项都直接调<b>既有</b>方法（{@code moduleSlotBonus} / {@code PrimordialRecipeEffects}），
     * 面板不重新实现任何公式 —— 这行字与主机自身 {@code addDisplayText} 里那些行同源，
     * 所以"面板显示的数"和"侧栏显示的数"不可能对不上。
     */
    private String readoutText() {
        final int bonus = machine.moduleSlotBonus();
        if (bonus <= 0) {
            return "§8产出 ×1 · 耗电 ×1（未生效）";
        }
        // 🔴 2026-10-25 修正：倍率必须问【主机自己】，本面板一个字都不许重算。
        //    这里以前写的是 PrimordialRecipeEffects.outputMultiplier(bonus)（= 1+等级，**不含**宿主的额外量），
        //    于是同一台归墟主机上：它自己 GUI 印「材料增产：x23」、这个侧栏印「产出 ×18」—— 两行打架，
        //    而且 18 不是任何一台机器真实生效的数（真实是 23）。
        //    现在改用「宿主的钩子 + 全工程唯一算式」这一个出口：
        //      · 归墟：getHostProductionBonusExtra() 门控生效时给 5 ⇒ 与
        //        GuixuVisionCoreMachine#productionMultiplier() **逐位相同**（那边的方法体就是
        //        productionMultiplierOf(moduleSlotBonus(), getHostProductionBonusExtra())，同一个算式、同一个钩子）；
        //      · 老主机（原始终焉引擎）：getHostProductionBonusExtra() 恒 0 ⇒ base + 0 == base
        //        ⇒ 与改前的 outputMultiplier(bonus) **逐字不变**（×2…×18 一个都不动）；
        //      · 空槽 / 未满 64 格：上面 bonus <= 0 已经提前返回，额外量不加在"未生效"之上。
        //    ⚠️ 不许把 base + extra 抄到这里 —— 抄一份就多一个会漂的数（这次的事故形态就是"两处各算一份"）。
        final int multiplier = PrimordialRecipeEffects.productionMultiplierOf(
                bonus, machine.getHostProductionBonusExtra());
        final double energyFactor = PrimordialRecipeEffects.reductionFactor(bonus);
        return "§7产出 §f×" + multiplier + "§7 · 耗电 §f×"
                + String.format(java.util.Locale.ROOT, "%.4f", energyFactor);
    }

    /** 当前生效半径的文字（手动档显示覆盖值，跟随档显示"等级值"）。 */
    private String currentRadiusText() {
        if (machine.isStarRenderManual() && machine.getStarRadiusOverride() > 0) {
            return machine.getStarRadiusOverride() + "（手动）";
        }
        return "13–35.1（随等级）";
    }

    /** 当前色相文字。 */
    private String currentHueText() {
        if (machine.isStarRenderManual() && machine.getStarHueOverride() >= 0) {
            return machine.getStarHueOverride() + "°";
        }
        return "随等级";
    }

    /**
     * 自动配色按钮上的文字 —— <b>要求「一眼看出当前是哪个」</b>，所以三种状态三种样子：
     * <pre>
     *   跟随等级 + 原光谱（默认） ⇒ §b光谱
     *   跟随等级 + 彩虹           ⇒ §d彩虹
     *   手动档                    ⇒ §8手动档不用
     * </pre>
     * 第三行不是敷衍：<b>手动档里这个按钮点了真的不会变</b>
     * （机器侧 {@code toggleStarPalette()} 直接返回）——不说清楚它就是个"按了没反应"的假开关，
     * 那正是本项目 {@code sphereStyleTooltips} 的 javadoc 里明令避免的形态。
     */
    private String paletteLabelText() {
        if (machine.isStarRenderManual()) {
            return "§8手动档不用";
        }
        return machine.getStarPalette() == PrimordialOmegaEngineMachine.STAR_PALETTE_RAINBOW
                ? "§d彩虹" : "§b光谱";
    }

    /**
     * 彩虹周期按钮上的文字 —— 照 {@link #paletteLabelText()} 的先例，<b>要求"一眼看出当前是哪个"</b>：
     * <pre>
     *   手动档        ⇒ §8彩虹周期：手动档不用
     *   光谱档        ⇒ §8彩虹周期：需先切到彩虹档
     *   彩虹 + 周期 0 ⇒ §7彩虹周期：§f不变（静态）      （默认）
     *   彩虹 + 有周期 ⇒ §7彩虹周期：§f30s / 5min / 10min
     * </pre>
     * 前两行不是敷衍：那两种情况下按钮<b>真的被置灰且点了没反应</b>，
     * 不说清楚它就是个"按了没反应"的假开关（本项目明令避免的形态）。
     * <p>⚠️ 文案里的「§8」是深灰：不可用时<b>连文字也压暗</b>，与既有「§8手动档不用」同款。
     */
    private String rainbowPeriodLabelText() {
        if (machine.isStarRenderManual()) {
            return "§8彩虹周期：手动档不用";
        }
        if (machine.getStarPalette() != PrimordialOmegaEngineMachine.STAR_PALETTE_RAINBOW) {
            return "§8彩虹周期：需先切到彩虹档";
        }
        final int period = machine.getStarRainbowPeriodTicks();
        if (period <= 0) {
            return "§7彩虹周期：§f不变（静态）";
        }
        return "§7彩虹周期：§f" + formatTicks(period);
    }

    /**
     * tick → 人话（{@code 20 tick = 1 秒}）。
     *
     * <pre>
     *   &lt; 1 秒   ⇒ "N tick"
     *   &lt; 1 分钟 ⇒ "Ns"
     *   整分钟    ⇒ "Nmin"
     *   非整分钟  ⇒ "Nm Ns"
     * </pre>
     * ⚠️ <b>档位表里的值全部落在前两档格式上</b>（{@code 20/60/200/600/1800/6000/12000} ⇒ 1s/3s/10s/30s/90s/5min/10min）——
     * 后两档格式是给"存档里存在非档位值"这条理论路径留的（{@code indexOfPeriod} 已考虑同一件事），
     * 不会因为除不尽就显示成 {@code 1.5min} 那种不好读的形态。
     *
     * @param ticks 周期（tick）；调用方已保证 {@code > 0}
     */
    private static String formatTicks(int ticks) {
        if (ticks < 20) {
            return ticks + " tick";
        }
        final int totalSeconds = ticks / 20;
        if (totalSeconds < 60) {
            return totalSeconds + "s";
        }
        final int minutes = totalSeconds / 60;
        final int seconds = totalSeconds % 60;
        return seconds == 0 ? minutes + "min" : minutes + "m " + seconds + "s";
    }

    /**
     * 色块用的 RGB（{@code 0xRRGGBB}）。
     *
     * <p>主色在<b>客户端渲染类</b>里（{@code PrimordialStarGradient} 是包私有、且只喂渲染），
     * 所以这里<b>不</b>去调它 —— 否则 common 侧就要依赖 client 类（而那个类静态初始化会碰
     * gtladditions 的 @OnlyIn(CLIENT) 类，专用服务端加载它是要出事的）。
     *
     * <h2>🔴 各档的取色口径（与渲染同源就能同色，不同源的部分我标出来）</h2>
     * <pre>
     *   手动档        ⇒ {@link #hsvToRgb}(hue, 0.85, 1.0) —— 与渲染侧 {@code rgbFloatsFromHue}
     *                    【同常数同算法】⇒ 色块与画面【精确一致】
     *   彩虹档        ⇒ hue 0→300° 线性、S=0.85/V=1.0 —— 与 {@code RAINBOW_COLORS} 的生成方式
     *                    相同 ⇒【近似一致】（真值走 7 档 + sRGB↔线性插值）
     *   原光谱档(默认)⇒ 三个锚点 #F0673D(0) → #FFFFFF(0.5) → #85AAFF(1) 的<b>普通 RGB 线性</b>取样
     *                    ⇒【近似一致】（真值走 7 档 + sRGB↔线性插值，中段更亮一点点）
     * </pre>
     * 🔴 <b>为什么允许这点近似</b>：色块只有 14×14 像素、用途是"提示当前偏哪一端"，不是对色卡。
     * 要让它 100% 一致，就得在 common 侧把 {@code STOPS/COLORS/lerpSRGB} 再抄一份
     * ——那是本项目最忌讳的"两套颜色规则"（真出分歧时没人知道该信哪份）。这里只抄
     * <b>3 个色值字面量</b>（都是 {@code PrimordialStarGradient} javadoc 里已公开的端点），
     * <b>没有抄算法</b>。绝对色以实机画面为准，已写进色块的 hover 说明。
     */
    private int previewRgb() {
        if (machine.isStarRenderManual() && machine.getStarHueOverride() >= 0) {
            return hsvToRgb(machine.getStarHueOverride(), 0.85D, 1.0D);
        }
        final double ratio = Math.max(0.0D, Math.min(machine.moduleSlotBonus(), 17)) / 17.0D;
        if (machine.getStarPalette() == PrimordialOmegaEngineMachine.STAR_PALETTE_RAINBOW) {
            return hsvToRgb((int) Math.round(300.0D * ratio), 0.85D, 1.0D);
        }
        // 原光谱（默认档）：橙红 → 近白 → 冷蓝
        if (ratio <= 0.5D) {
            return lerpRgb(SPECTRAL_START, SPECTRAL_MID, ratio / 0.5D);
        }
        return lerpRgb(SPECTRAL_MID, SPECTRAL_END, (ratio - 0.5D) / 0.5D);
    }

    /** 原光谱档的三个锚点（字面量取自 {@code PrimordialStarGradient} 的公开色值表：0xF0673D/0xFFFFFF/0x85AAFF）。 */
    private static final int SPECTRAL_START = 0xF0673D;
    private static final int SPECTRAL_MID = 0xFFFFFF;
    private static final int SPECTRAL_END = 0x85AAFF;

    /** 两个 {@code 0xRRGGBB} 之间按分量线性插值（<b>普通 RGB</b>，刻意不做 sRGB↔线性——见 {@link #previewRgb()}）。 */
    private static int lerpRgb(int from, int to, double t) {
        final double k = t < 0.0D ? 0.0D : (t > 1.0D ? 1.0D : t);
        final int r = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * k);
        final int g = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * k);
        final int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * k);
        return (r << 16) | (g << 8) | b;
    }

    /** 与渲染侧 {@code PrimordialStarGradient#hsvToRgb} 同一套纯算术（本类只用来给色块上色）。 */
    private static int hsvToRgb(int hue, double saturation, double value) {
        final double h = (((hue % 360) + 360) % 360) / 60.0D;
        final int sector = (int) Math.floor(h);
        final double f = h - sector;
        final double p = value * (1.0D - saturation);
        final double q = value * (1.0D - saturation * f);
        final double t = value * (1.0D - saturation * (1.0D - f));
        final double[] rgb = switch (sector % 6) {
            case 0 -> new double[] {value, t, p};
            case 1 -> new double[] {q, value, p};
            case 2 -> new double[] {p, value, t};
            case 3 -> new double[] {p, q, value};
            case 4 -> new double[] {t, p, value};
            default -> new double[] {value, p, q};
        };
        return (channel(rgb[0]) << 16) | (channel(rgb[1]) << 8) | channel(rgb[2]);
    }

    private static int channel(double v) {
        final double c = v < 0.0D ? 0.0D : (v > 1.0D ? 1.0D : v);
        return Math.round((float) (c * 255.0D));
    }
}
