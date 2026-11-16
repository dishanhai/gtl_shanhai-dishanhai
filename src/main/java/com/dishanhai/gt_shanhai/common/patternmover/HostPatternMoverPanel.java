package com.dishanhai.gt_shanhai.common.patternmover;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.dishanhai.gt_shanhai.common.patternmover.compat.ShanhaiLdlText;
import com.dishanhai.gt_shanhai.common.patternmover.compat.ShanhaiTextParser;
import com.dishanhai.gt_shanhai.common.patternmover.compat.ShanhaiRecipeTypeLabels;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.function.Supplier;

/**
 * 物品面板：<b>按配方类型分组、分页列出</b>已装的样板 ＋ <b>每行一个「取出」</b> ＋ <b>清空物品</b>。
 *
 * <h2>一、这一版的三件事（B / C / F）</h2>
 * <pre>
 *   B 分页    每组一行（类型名 ＋ 块数 ＋ 「取出」按钮），【每页 8 行】，
 *             底部 ▶ 上一页 · n / N · 下一页 ◀。页号是【面板本地】状态：
 *             两侧各改自己那份（翻页只是界面状态，不写任何东西），
 *             组数变少（取出/清空之后）就重算页数并把当前页夹到最后一页。
 *   C 刷新    动作结束后【重新读物品 NBT 并**整份重建分组列表**】：
 *             ① 服务端重读 → 新的 {@link HostPatternMoverPanelState} → writeUpdateInfo 推给客户端；
 *             ② 客户端另有一层镜像：每 5 帧用自己手上那份物品 NBT 重读一次
 *                （第五刀起凭 {@code rev} 严格更大才许覆盖，见类注释 §二）；
 *             ③ 🔴 <b>第四刀 ③（用户实测「点了取出/清空之后分组没消失」）</b>：任何一条通道换了状态，
 *                都调 {@link #rebuildRows()} —— 页号夹紧 → 用 {@link HostPatternMoverPager#view} 重算
 *                「本页几行 / 每行第几组」→ 多出来的行<b>连「取出」按钮一起</b>隐藏 → 0 组时显示空提示。
 *                改前只把标签文字改成空串，按钮还杵在那里 ⇒ 看起来就是"分组没消失"。
 *   F 清空物品 一个按钮，文字里带当前块数（「清空物品内的 N 块样板」），点一次直接执行。
 * </pre>
 * 「取出」为什么是<b>每行一个按钮</b>而不是"选中一组再点一个按钮"：用户不喜欢二次确认，
 * 所以要把误点面收窄 —— 点哪一行就只取那一组，<b>没有</b>"一键全取"这条路
 * （"全取"在物品这一侧对应的是 F 的「清空」—— 注意两者的语义完全不同：取出=交给玩家，清空=销毁数据）。
 *
 * <h2>二、🔴 界面上的数字必须是活的（本工程红线：活的界面上不许放假数据）</h2>
 * 两条路一起上，互为兜底：
 * <ol>
 *   <li>服务端每 tick {@link #detectAndSendChanges()}：<b>现读物品 NBT</b> 造状态，
 *       与"上次真的推出去的那一份"不同才推（比较只决定发不发，绝不拿旧值盖新值）；</li>
 *   <li>客户端 {@link #updateScreen()}：每 5 帧拿自己手上那份物品 NBT 重读一次，
 *       <b>只有 rev 严格更大才许覆盖</b>（{@link HostPatternMoverPanelState#afterMirror}）。
 *       <br>⚠️ 这一条是<b>实测反馈</b>逼出来的：用户「点击取出之后页面没更新」。
 *       离线核对 LDLib 字节码后确认：{@code LabelWidget.updateScreen()} 里那句
 *       {@code textSupplier.get()} 被 {@code isClientSideWidget} 挡着 ⇒ 普通标签的文本
 *       <b>只</b>靠服务端标签级推送刷新；那条路一旦没到，数字就永远停在<b>建面板那一刻</b>。
 *       ⇒ 本面板的标签一律 {@code setClientSideWidget()}（每帧现取），再加镜像兜底。</li>
 * </ol>
 *
 * <h2>二·补、🔴 第五刀：为什么镜像必须被 rev 闸住</h2>
 * 用户实测：「取出后会瞬间刷新一次到正确的位置，但是过零点几秒它又会重新刷新到原本的不正确
 * （和取出之前）的位置」。
 * 根因不是推送有问题，而是<b>镜像的判据错了</b>：它拿"客户端手上那份物品 NBT"算状态，而那份在服务端
 * 写完之后还<b>没同步过来</b> ⇒ 块数确实"和当前状态不一样" ⇒ 旧状态把新状态盖了回去。
 * ⇒ 现在镜像那份只有 {@code rev}（物品 NBT 根上的 {@code shanhai:mover_rev}，每次真写入 ＋1）
 * <b>严格更大</b>才许覆盖；动作回执与推送永远优先。推送丢了、客户端 NBT 反而更新时，
 * 镜像仍能兜住（第三刀那条兜底没被拆掉）。
 * <p>实机判读一行：{@code [SHANHAI-MOVER] panel_state rev=… groups=… bytes=… src=action|open|mirror …}。
 *
 * <h2>三、🔴 LDLib 铁律（本工程踩过崩服：2026-10-31 的翻页崩服）</h2>
 * <ol>
 *   <li>文本控件一律 <b>Supplier 构造器</b>：{@code LabelWidget(int,int,Supplier<String>)}、
 *       {@code TextTexture(Supplier<String>)}；不用 {@code (int,int,Component)} 那个重载
 *       （它只 {@code putfield component}、{@code textSupplier} 留 null ⇒ 服务端 tick NPE）。</li>
 *   <li>每一个字都过 {@link #sanitize}（剥样式码 ＋ 去 {@code &} ＋ 裸 {@code %} ⇒ {@code %%}）。</li>
 *   <li><b>控件数在构造时定死</b>（LDLib 的控件表建好之后不能再改）⇒ 行数 = 每页 8 行写死，
 *       分页靠换行内容，不靠增删控件。
 *       <br>第四刀 ③ 的"重建"因此落在 <b>可见性 + 文字</b>上（{@link #rebuildRows()}）：
 *       不该存在的行连按钮一起 {@code setVisible(false)}、0 组显示空提示 ——
 *       效果与"增删行"一样，但两侧控件 id 始终对得上（运行期单侧 add/remove 会让
 *       客户端点的 id 指向服务端另一个控件）。</li>
 *   <li>按钮<b>不许</b> {@code setClientSideWidget()}：{@code writeClientAction} 对客户端小部件
 *       直接 return（服务端就收不到点击了）。</li>
 * </ol>
 */
public final class HostPatternMoverPanel extends WidgetGroup {

    /** 面板宽（与配方编辑器/物品工作台同量级：不盖住左右两列 JEI）。 */
    public static final int W = 340;

    /** 面板高（8 行组 ＋ 翻页行 ＋ 字节/计数/状态 ＋ 清空按钮）。 */
    public static final int H = 256;

    /** 每页几行（用户口径 8；控件数因此写死 8 行）。 */
    private static final int ROWS = HostPatternMoverPager.ROWS;

    /** 分组行高（每行右边带一个 14 px 的「取出」按钮）。 */
    private static final int GROUP_ROW_H = 16;

    private static final int GROUP_Y = 41;

    /** 分组行右边那个「取出」按钮。 */
    private static final int ROW_BTN_X = 236;
    private static final int ROW_BTN_W = 98;
    private static final int ROW_BTN_H = 14;

    /** 翻页行。 */
    private static final int PAGER_Y = 172;
    private static final int PAGER_BTN_W = 62;
    private static final int PAGER_BTN_H = 16;
    private static final int PAGER_PREV_X = 6;
    private static final int PAGER_NEXT_X = 272;

    /** 0 组时的空提示（lang 键；中英两份都有）。 */
    static final String LANG_EMPTY_LIST = "shanhai.mover.panel.empty";

    private final HostPatternMoverFactory.Holder holder;
    private final transient Player player;

    /** 客户端是否是"远端"（= 这份面板建在客户端）。服务端为 false。 */
    private final boolean remote;

    /**
     * 🔴 第四刀 ③：{@link #ROWS} 个行标签 / 行按钮 / 空提示的<b>引用</b>。
     *
     * <p>为什么要留着引用：「组数变少」不能只把标签文字改成空串就完事 ——
     * 那样<b>「取出」按钮还杵在那一行上</b>，玩家看到的就是「分组没消失」。
     * 有了引用，回执一到就能把"不该存在的行"（连按钮）一起 {@code setVisible(false)}，
     * 并在 0 组时显示空提示行。判据在纯核 {@link HostPatternMoverPager#view}（自检 C26 组）。
     */
    private final LabelWidget[] rowLabels = new LabelWidget[ROWS];
    private final ButtonWidget[] rowButtons = new ButtonWidget[ROWS];
    private LabelWidget emptyHint;

    /** 上次行计划是按哪组（组数, 页号）建的；两者都没变 ⇒ 不再动控件（文字本来就每帧现取）。 */
    private int builtGroups = Integer.MIN_VALUE;
    private int builtPage = Integer.MIN_VALUE;

    /** 行计划一共重建过几次（面板不显示；日志与自检读数用）。 */
    private int rowRebuilds;

    /** 服务端：上次<b>真的推出去</b>的那一份状态（推送去重只比它 —— 比较只决定"发不发"）。 */
    private HostPatternMoverPanelState lastPushedState;

    /** 服务端：上次推出去时附带的状态行（状态行变了也要推，否则玩家看不到"已取出 N 块"）。 */
    private String lastPushedStatus = "";

    /**
     * 服务端：这一拍<b>强制</b>推一份（翻页这种"该刷一次但数据没变"的时机置它）。
     *
     * <p>第五刀 ②：推送改成<b>事件驱动</b> —— 打开面板（开界面那次同步）／每次动作完成／玩家切页
     * 这三种时机才推。数据真变了仍然会推（{@link #lastPushedState} 比不上 ⇒ 推）。
     */
    private boolean forcePush;

    /**
     * 服务端：下一次推送该标成哪个来源（动作之后置 {@code action}，其余默认 {@code mirror} = 现读）。
     *
     * <p>它只影响日志里的 {@code src=}，但正因为如此，实机判读才能一眼看出"这一份是不是动作回执"。
     */
    private String pendingSrc = HostPatternMoverPanelState.SRC_MIRROR;

    /** 客户端：上一次"镜像被拒"记的是哪一组读数（同一组只记一行，绝不许每 5 帧刷屏）。 */
    private long mirrorRejectRev = Long.MIN_VALUE;
    private int mirrorRejectGroups = Integer.MIN_VALUE;

    /**
     * 当前页（0 基）。<b>两侧各一份</b>：翻页按钮在两侧都执行（纯界面状态），
     * 组数变化时两侧用同一条 {@link HostPatternMoverPager#clamp} 夹紧 ⇒ 不会出现"客户端第 2 页、
     * 服务端第 1 页"这种会让「取出」取错组的分叉。
     */
    private int page;

    /** 客户端镜像重读的帧计数器（每 5 帧一次）。 */
    private int frame;

    private HostPatternMoverPanel(HostPatternMoverFactory.Holder holder, Player player) {
        super(0, 0, W, H);
        this.holder = holder;
        this.player = player;
        this.remote = holder != null && holder.isRemote();
        this.setBackground(GuiTextures.BACKGROUND);

        // ── 顶部：标题 ＋ 来源主机 ＋ 提示
        this.addWidget(label(6, 4, () -> "§b§l" + Component.translatable("shanhai.mover.panel.title").getString()
                + " §8· §7取出 / 清空"));
        this.addWidget(label(6, 17, this::hostLine));
        this.addWidget(label(6, 29, () -> "§7按配方类型分组 §8（每页 " + ROWS
                + " 组；右边「取出」只取那一组）"));

        // ── 分组行：**每行一个「取出」按钮**（用户口径：不要"一键取全部"，把误点面收窄）
        //    🔴 第四刀 ③：行控件在这里一次建够 ROWS 个，引用留在数组里；之后每次回执到达都
        //    重算行计划、把"不该存在的行"（**连按钮**）一起隐藏 —— 见 rebuildRows()。
        //    ⚠️ 为什么不在运行期 add/remove 控件：LDLib 的控件 id 是两侧各自建树时按顺序发的
        //    （2026-10-31 那次翻页崩服的根子），只在一侧增删会让"客户端点的 id / 服务端的控件"对不上。
        for (int row = 0; row < ROWS; row++) {
            final int index = row;
            final int y = GROUP_Y + row * GROUP_ROW_H;
            this.rowLabels[index] = label(6, y + 3, () -> groupLine(index));
            this.rowButtons[index] = button(ROW_BTN_X, y, ROW_BTN_W, ROW_BTN_H, () -> "§e取出",
                    () -> this.onTakeOut(index));
            this.addWidget(this.rowLabels[index]);
            this.addWidget(this.rowButtons[index]);
        }
        // 0 组时的空提示（"没有样板"）：只在列表为空时可见（见 rebuildRows()）。
        // 🔴 文本控件仍用 Supplier 构造器 ＋ setClientSideWidget（LDLib 铁律，见类注释 §三）。
        this.emptyHint = label(6, GROUP_Y + 3, () -> "§8" + Component.translatable(LANG_EMPTY_LIST).getString());
        this.addWidget(this.emptyHint);

        // ── 翻页行（两侧都改自己那份页号；见类注释 §一-B）
        this.addWidget(flipButton(PAGER_PREV_X, PAGER_Y, PAGER_BTN_W, PAGER_BTN_H,
                () -> "§e◀ 上一页", () -> this.flip(-1)));
        this.addWidget(label(74, PAGER_Y + 4, this::pageLine));
        this.addWidget(flipButton(PAGER_NEXT_X, PAGER_Y, PAGER_BTN_W, PAGER_BTN_H,
                () -> "§e下一页 ▶", () -> this.flip(1)));

        // ── 读数
        this.addWidget(label(6, 194, this::bytesLine));
        this.addWidget(label(6, 206, this::wildcardLine));
        this.addWidget(label(6, 218, this::statusLine));

        // ── F：清空物品（文字里带当前块数；点一次直接执行）
        this.addWidget(button(6, 230, 214, 18,
                () -> "§c清空物品内的 " + this.state().count() + " 块样板", this::onClearItem));

        // 建完就把行计划落一次（开局那份状态就要是对的：0 组 ⇒ 8 行全隐、空提示可见）。
        this.rebuildRows();
        // 第五刀 ①：开面板时那一份（来自开界面同步包里的 Holder）—— 两侧各记一行，
        // 实机判读时"起点是哪一份、rev 多少"一目了然（这就是事件驱动里的 ① 打开面板）。
        this.state().logState(this.remote ? "client" : "server", HostPatternMoverPanelState.SRC_OPEN, "recompute");
    }

    /** 建面板（两侧各建一份；结构完全一致 ⇒ 控件 id 一致 ⇒ 客户端动作能对上服务端控件）。 */
    public static HostPatternMoverPanel create(HostPatternMoverFactory.Holder holder, Player player) {
        return new HostPatternMoverPanel(holder, player);
    }

    // ================================================================ 状态同步（服务端 → 客户端）

    /**
     * 服务端每 tick：<b>现读物品 NBT</b> 造一份状态，变了才推。
     *
     * <h2>🔴 第五刀 ①：绝不推"字段快照"</h2>
     * 改前这里是「Holder 版本一变就把 {@code holder.state()} 推出去」—— 那是<b>字段</b>里的值。
     * 现在每一拍都从物品 NBT 现读一份（{@link HostPatternMoverPanelState#of}），
     * 比较只用来决定"发不发"（{@code rev} ＋ 全部数据字段 ＋ 状态行都相同才跳过），
     * <b>绝不拿旧值覆盖新值</b> ⇒ 推送内容永远等于"此刻物品上的样子"。
     *
     * <p>第五刀 ②：推的时机是<b>事件驱动</b>的 —— 数据/状态行真的变了才推，外加
     * {@link #forcePush}（翻页时置）强制刷一次。面板打开时那一份由开界面同步包带下去（{@code src=open}）。
     */
    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        if (this.remote || this.holder == null) {
            return;
        }
        // 🔴【现读】物品此刻的 NBT（不读任何缓存 / 字段快照）
        final HostPatternMoverPanelState fresh = this.readItemState();
        if (fresh == null) {
            return; // 物品不在手上 ⇒ 保持现状（绝不清空界面上的数字）
        }
        // 服务端自己那份也换成现读的（面板文本与推送内容永远是同一份；replaceState 不动版本计数）
        final HostPatternMoverPanelState shown = this.holder.state();
        if (!fresh.sameAs(shown) || fresh.rev() != shown.rev()) {
            this.holder.replaceState(fresh);
        }
        // 服务端这一侧也把行计划对齐（动作改完 holder 之后不必各自记得再调一次）。
        this.rebuildRows();

        final String status = this.holder.status();
        final boolean same = !this.forcePush
                && this.lastPushedState != null
                && fresh.sameAs(this.lastPushedState)
                && fresh.rev() == this.lastPushedState.rev()
                && java.util.Objects.equals(status, this.lastPushedStatus);
        if (same) {
            return; // ← 比较只决定"发不发"，一个字节都不推
        }
        this.pushPanelState(fresh, status, this.pendingSrc);
    }

    /**
     * 推一份状态包（失败 ⇒ 下个 tick 再试；绝不因为"刷新失败"把动作本身回滚）。
     *
     * <p>🔴 先判 {@code gui}/{@code uiAccess}：LDLib 的 {@code writeUpdateInfo} 在界面还没挂上时
     * <b>静默什么都不做</b>（字节码：{@code uiAccess == null || gui == null ⇒ return}，<b>不抛</b>）。
     * 不判这一下就会出现"以为推出去了、其实一个字节没发"—— 那正是本刀要消灭的形态（界面停在旧值）。
     *
     * @param src 这份状态是谁造出来的（{@code action} / {@code open} / {@code mirror}）——
     *            随包发给客户端，客户端那行 {@code panel_state … src=…} 就靠它，实机判读不用猜
     */
    private void pushPanelState(HostPatternMoverPanelState state, String status, String src) {
        if (this.gui == null || this.uiAccess == null) {
            this.forcePush = true; // 界面还没挂上 ⇒ 这一份欠着，下个 tick 再来
            return;
        }
        final int code = HostPatternMoverPanelState.srcCode(src);
        try {
            this.writeUpdateInfo(1, buf -> writePanelState(buf, state, status, code));
            this.forcePush = false;
            this.lastPushedState = state;
            this.lastPushedStatus = status == null ? "" : status;
            this.pendingSrc = HostPatternMoverPanelState.SRC_MIRROR;
            state.logState("server", src, "push");
        } catch (Throwable t) {
            // 推不动 ⇒ 下个 tick 再来；lastPushedState 不前进 ⇒ 这一份还欠着
            this.forcePush = true;
        }
    }

    /** 服务端：现读玩家手上那件搬运工具 NBT 造状态；物品不在手上 / 读不出来 ⇒ {@code null}。 */
    private HostPatternMoverPanelState readItemState() {
        try {
            final ItemStack stack = this.holder.tool(this.player);
            if (stack == null || stack.isEmpty()) {
                return null;
            }
            return HostPatternMoverPanelState.of(stack.getTag());
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void readUpdateInfo(int id, FriendlyByteBuf buf) {
        if (id == 1 && this.holder != null) {
            try {
                final int src = readPanelState(this.holder, buf);
                this.page = HostPatternMoverPager.clamp(this.page, this.state().groups());
                // 🔴 第四刀 ③：回执到了就**重建行计划**（不是只换数字）：
                //    多出来的行连按钮一起隐藏、0 组时显示空提示、越界页号夹紧。
                this.rebuildRows();
                // 推送是权威且有序的（同一连接上一路发来）⇒ 这里<b>不</b>比 rev；
                // rev 只是用来挡住"镜像拿旧 NBT 盖新状态"那一条（见 updateScreen）。
                this.state().logState("client", HostPatternMoverPanelState.srcName(src), "push");
            } catch (Throwable t) {
                HostPatternMoverLog.warn("{} 面板刷新读数失败 err={}", HostPatternMoverLog.PREFIX, t.toString());
            }
            return;
        }
        super.readUpdateInfo(id, buf);
    }

    /**
     * 🔴 第四刀 ③：<b>把分组列表整份重算并落到控件上</b>。
     *
     * <pre>
     *   ① 页号先夹紧（组数变少 ⇒ 老页号自动落到最后一页）
     *   ② 用纯核 {@link HostPatternMoverPager#view} 算出「本页几行 / 每行是第几组 / 列表是不是空的」
     *   ③ 本页不存在的行 ⇒ 标签 **和** 按钮一起 {@code setVisible(false)}（清掉旧行）
     *   ④ 0 组 ⇒ 显示「没有样板」空提示行
     * </pre>
     * 组数与页号都没变时直接返回（文字本来就由 supplier 每帧现取，不必动控件）。
     * <p>⚠️ 这个方法在客户端**每帧**都会被调一次（{@code updateScreen}）⇒ 稳态下只做"两个 int 比较"，
     * 不建 {@link HostPatternMoverPager.View} 记录、不碰控件（省掉每帧一次小分配）。
     *
     * <p>⚠️ 这里<b>只</b>改可见性/文字，不增删控件 —— 理由见构造函数里那段（控件 id 两侧必须对齐）。
     * 因此本方法可以同时在两侧跑：服务端在动作之后/每 tick，客户端在回执到达/每帧（`updateScreen`）。
     */
    private void rebuildRows() {
        final int groups = this.state().groups();
        final int clampedPage = HostPatternMoverPager.clamp(this.page, groups);
        this.page = clampedPage;
        if (groups == this.builtGroups && clampedPage == this.builtPage) {
            return; // 行计划没变：连 View 都不建（客户端每帧都走这一句）
        }
        final HostPatternMoverPager.View view = HostPatternMoverPager.view(clampedPage, groups);
        this.builtGroups = groups;
        this.builtPage = view.page();
        this.rowRebuilds++;
        for (int row = 0; row < ROWS; row++) {
            final boolean live = view.absoluteRow(row) >= 0;
            if (this.rowLabels[row] != null) {
                this.rowLabels[row].setVisible(live);
            }
            if (this.rowButtons[row] != null) {
                this.rowButtons[row].setVisible(live);
            }
        }
        if (this.emptyHint != null) {
            this.emptyHint.setVisible(view.empty());
        }
        // 读数：一眼能看出"行计划真的重建过"（每侧一次动作只该多一行）。
        HostPatternMoverLog.info("{} panel_rows side={} rebuilds={} {}",
                HostPatternMoverLog.PREFIX, this.remote ? "client" : "server",
                Integer.valueOf(this.rowRebuilds), view.describe());
    }

    /**
     * C 的<b>第二条路</b>：客户端每 5 帧用自己手上那份物品 NBT 重读一次。
     *
     * <h2>🔴 第五刀 ①：这一条就是用户报的那个"零点几秒后又变回去"</h2>
     * 改前这里的判据是「块数/字节和当前状态不一样 ⇒ 就用镜像那份覆盖」。而动作之后
     * <b>客户端手上那份物品 NBT 还没同步</b> ⇒ 镜像算出的是<b>旧状态</b>，块的数字确实"不一样"
     * ⇒ 于是把服务端刚推来的新状态盖回去了（用户看到的：瞬间刷新对、过零点几秒又变回取出前）。
     *
     * <p>现在门是<b>版本</b>：{@code rev = 物品 NBT 上的 shanhai:mover_rev}（每次真写入 ＋1）。
     * 镜像那份只有 {@code rev} <b>严格更大</b>才许覆盖（{@link HostPatternMoverPanelState#afterMirror}）——
     * 动作回执 / 推送永远优先。而"推送丢了、客户端 NBT 反而更新"那种情形里镜像的 rev 更大，
     * 它仍然能兜住（第三刀那条兜底没被拆掉）。
     *
     * <p>便宜口径仍在：数据与版本都一样就直接返回（1000 块的物品也不会被这个镜像拖成卡顿）。
     */
    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!this.remote || this.holder == null || this.player == null) {
            return;
        }
        // 🔴 第四刀 ③：先按【手上的状态包】把行计划对齐一次（组数/页号没变时它自己短路）。
        //    这样即使推送那条路没到，只要状态包被别的路径换过（readUpdateInfo），这里也会补上。
        this.rebuildRows();
        if ((++this.frame % 5) != 0) {
            return;
        }
        try {
            final ItemStack stack = this.holder.tool(this.player);
            if (stack == null || stack.isEmpty()) {
                return;
            }
            final HostPatternMoverPanelState cur = this.state();
            // 🔴 便宜门：物品上的版本戳与已显示的那份相同 ⇒ 数据不可能变（版本只在唯一写入口 ＋1）
            //    ⇒ 1000 块的物品在稳态下也只读一个 long，不做整表遍历。
            if (HostPatternMoverNbt.readRev(stack.getTag()) == cur.rev()) {
                return;
            }
            // of() 只读 NBT：rev 就在物品 tag 根上（与推来那份同一个数）
            final HostPatternMoverPanelState fresh = HostPatternMoverPanelState.of(stack.getTag());
            if (fresh.sameAs(cur) && fresh.rev() == cur.rev()) {
                return; // 一模一样：连分配都不做
            }
            // ★判据：镜像必须比"当前已显示的那一份"【更新】才许覆盖（唯一判定点在纯核里）
            final HostPatternMoverPanelState next = HostPatternMoverPanelState.afterMirror(cur, fresh);
            if (next == cur) {
                // 被 rev 闸住（= 正是用户报的那个回退形态）⇒ 记一行便于实机判，同一组读数只记一次
                if (fresh.rev() != this.mirrorRejectRev || fresh.groups() != this.mirrorRejectGroups) {
                    this.mirrorRejectRev = fresh.rev();
                    this.mirrorRejectGroups = fresh.groups();
                    HostPatternMoverLog.info("{} panel_state_mirror_reject rev={} curRev={} groups={} "
                                    + "curGroups={} side=client（旧镜像不许覆盖新值）",
                            HostPatternMoverLog.PREFIX, Long.valueOf(fresh.rev()), Long.valueOf(cur.rev()),
                            Integer.valueOf(fresh.groups()), Integer.valueOf(cur.groups()));
                }
                return;
            }
            this.holder.replaceState(next);
            this.page = HostPatternMoverPager.clamp(this.page, next.groups());
            // 镜像刚换了状态 ⇒ 行计划必须跟着重建（否则"分组没消失"照旧）。
            this.rebuildRows();
            next.logState("client", HostPatternMoverPanelState.SRC_MIRROR, "mirror");
        } catch (Throwable t) {
            // 镜像只是兜底：读不出来就保持现状（绝不清空界面上的数字）
        }
    }

    private static void writePanelState(FriendlyByteBuf buf, HostPatternMoverPanelState state,
                                        String status, int srcCode) {
        state.write(buf);
        buf.writeUtf(status == null ? "" : status, 512);
        buf.writeByte(srcCode);
    }

    /** 读回一份状态包；返回它的来源码（{@link HostPatternMoverPanelState#srcName} 翻成日志标签）。 */
    private static int readPanelState(HostPatternMoverFactory.Holder holder, FriendlyByteBuf buf) {
        holder.replaceState(HostPatternMoverPanelState.read(buf));
        holder.replaceStatus(buf.readUtf(512));
        return buf.readByte();
    }

    // ================================================================ 动作

    /**
     * 「取出」：把<b>本页第 {@code index} 行那一组</b>样板<b>作为物品交给玩家</b>（不是销毁）。
     *
     * <p>🔴 用户口径（2026-11-06 改）：先 {@code getInventory().add(...)}，背包满才
     * {@code player.drop(...)} 掉在脚下，两条都不成就<b>留在物品里</b>；措辞里不出现"移除/销毁"。
     */
    private void onTakeOut(int index) {
        if (!(this.player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        final String type = this.groupTypeAt(index);
        if (type == null) {
            return; // 这一行没有组（末页不满 / 空页）⇒ 点了什么也不做
        }
        final ItemStack stack = this.holder.tool(serverPlayer);
        if (stack == null) {
            this.setState("§c搬运工具已更换或移动，请重新打开面板");
            return;
        }
        try {
            final HostPatternMoverTakeout.Result r =
                    HostPatternMoverActions.takeOut(serverPlayer, stack, type);
            if (r == null) {
                this.setState("§c物品里这份数据已经超限，先别动它（一个字节都没改）");
            } else if (r.nothingChanged()) {
                this.setState(r.wanted() == 0 ? "§7这一组已经没有样板了"
                        : "§c一块都没交出去（背包与掉落都失败）⇒ 全部留在物品里");
            } else {
                this.setState("§a已取出 " + r.removed() + " 块（背包 " + r.toInventory()
                        + " 块，掉落 " + r.dropped() + " 块）"
                        + (r.kept() > 0 ? "，另有 " + r.kept() + " 块没交出去、留在物品里" : ""));
            }
        } catch (Throwable t) {
            HostPatternMoverLog.warn("{} 取出失败 err={}", HostPatternMoverLog.PREFIX, t.toString());
            this.setState("§c取出失败（看日志 [SHANHAI-MOVER]）");
        }
        this.rebuildState(stack);
    }

    /** 「清空物品」：把物品里的全部样板清掉（F：点一次直接执行，没有二次确认）。 */
    private void onClearItem() {
        if (!(this.player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        final ItemStack stack = this.holder.tool(serverPlayer);
        if (stack == null) {
            this.setState("§c搬运工具已更换或移动，请重新打开面板");
            return;
        }
        try {
            final int cleared = HostPatternMoverActions.clearItem(serverPlayer, stack);
            this.setState(cleared > 0 ? "§a已清空物品里的 " + cleared + " 块样板"
                    : "§7物品里本来就没有样板");
        } catch (Throwable t) {
            HostPatternMoverLog.warn("{} 清空物品失败 err={}", HostPatternMoverLog.PREFIX, t.toString());
            this.setState("§c清空失败（看日志 [SHANHAI-MOVER]）");
        }
        this.rebuildState(stack);
    }

    /**
     * 动作之后<b>重新读物品 NBT 并重建列表</b>（C 的核心一步；第五刀 ① 的 {@code src=action}）。
     *
     * <p>换的是整份 {@link HostPatternMoverPanelState}（组数、每组的块数、已用字节、<b>修订号</b>
     * 都是重读出来的），然后把页号夹紧 —— 组数变少时当前页越界就落到最后一页。
     *
     * <p>⚠️ 这里<b>不</b>动 {@link #lastPushedState}：动作之后就是要"还欠一次推送"，
     * 下一个 tick 的 {@link #detectAndSendChanges} 现读同一份、发现和上次推的不同 ⇒ 推给客户端
     * （这就是"动作回执"那条路）。
     */
    private void rebuildState(ItemStack stack) {
        final HostPatternMoverPanelState fresh = HostPatternMoverPanelState.of(stack == null ? null : stack.getTag());
        this.page = HostPatternMoverPager.clamp(this.page, fresh.groups());
        if (this.holder == null) {
            return;
        }
        this.holder.setState(fresh, this.currentStatus());
        // 🔴 第四刀 ③：动作之后**当场重建行计划**（不等下一次推送/镜像）——
        //    "点取出/点清空之后行还在"就是这一步缺了。
        this.rebuildRows();
        // 第五刀 ①：动作这一份就是"正确的那一份"；下个 tick 现读同一份推给客户端时，
        // 日志就标成 src=action（实机判读靠它区分"动作回执照上了没有"）
        this.pendingSrc = HostPatternMoverPanelState.SRC_ACTION;
        fresh.logState(this.remote ? "client" : "server", HostPatternMoverPanelState.SRC_ACTION, "recompute");
    }

    /** 翻页（两侧都执行；纯界面状态，不写任何东西）。 */
    private void flip(int delta) {
        final int groups = this.state().groups();
        this.page = delta < 0 ? HostPatternMoverPager.prev(this.page, groups)
                : HostPatternMoverPager.next(this.page, groups);
        // 页号变了 ⇒ 行计划跟着换（否则翻到第 2 页还是第 1 页那几行）。
        this.rebuildRows();
        // 第五刀 ②：③ 玩家切换页码 —— 这是一个"该刷一次"的事件（服务端下个 tick 现读 NBT 推一份）。
        if (!this.remote) {
            this.forcePush = true;
        }
    }

    // ================================================================ 文本

    private HostPatternMoverPanelState state() {
        return this.holder == null ? HostPatternMoverPanelState.EMPTY : this.holder.state();
    }

    private String currentStatus() {
        return this.holder == null ? "" : this.holder.status();
    }

    private void setState(String text) {
        if (this.holder != null) {
            this.holder.setStatus(text);
        }
    }

    private String statusLine() {
        final String status = currentStatus();
        return "§7状态 §f" + sanitize(status == null || status.isEmpty() ? "（还没操作过）" : status);
    }

    private String hostLine() {
        final HostPatternMoverPanelState s = this.state();
        return sanitize("§7来源主机 §f" + hostDisplayName(s.fromHost())
                + "   §7样板 §f" + s.count() + " §7块 · §f" + s.groups() + " §7组");
    }

    private String pageLine() {
        final HostPatternMoverPanelState s = this.state();
        final HostPatternMoverPager.View view = HostPatternMoverPager.view(this.page, s.groups());
        return sanitize("§7第 §f" + (view.page() + 1) + " §7/ §f" + view.pages() + " §7页"
                + " §8（本页 " + view.rows() + " 组 / 共 " + view.groups() + " 组）");
    }

    private String bytesLine() {
        final HostPatternMoverPanelState s = this.state();
        return sanitize("§7已用 §f" + withComma(s.bytes()) + " §7B / 上限 §f" + withComma(s.limit())
                + " §7B（1.5 MiB）· 剩余 §f" + withComma(s.remaining()) + " §7B");
    }

    /** 「多义未写」那一行（A：用户点名面板上要看得见）。 */
    private String wildcardLine() {
        final HostPatternMoverPanelState s = this.state();
        if (!s.hasWildcardReads()) {
            return "§8通配符自动识别：上次读取没有遇到「自身没类型」的通配符样板";
        }
        return sanitize("§7通配符自动识别 §8上次读取： §a写入 §f" + s.readResolved()
                + " §8· §c多义未写 §f" + s.readAmbiguous()
                + " §8· §7识别不到 §f" + s.readUnidentified());
    }

    /** 第 {@code row} 行（本页内 0..7）的文本：{@code 类型名 × 块数 (类型id)}。 */
    private String groupLine(int row) {
        final HostPatternMoverPanelState s = this.state();
        // 🔴 第四刀 ③：文字与可见性用**同一个**行计划口径（HostPatternMoverPager#view），
        //    否则"显示第几组"与"点了取哪一组"会是两套下标。
        final int abs = HostPatternMoverPager.view(this.page, s.groups()).absoluteRow(row);
        if (abs < 0) {
            return "";
        }
        final String typeId = s.typeAt(abs);
        final int count = s.countAt(abs);
        return sanitize("§f" + typeDisplayName(typeId) + " §7× §f" + count
                + " §8(" + (typeId == null || typeId.isEmpty() ? "未标注" : typeId) + ")");
    }

    /** 第 {@code row} 行那一组的 type id（{@code null} = 这一行没有组）。 */
    private String groupTypeAt(int row) {
        final HostPatternMoverPanelState s = this.state();
        final int abs = HostPatternMoverPager.view(this.page, s.groups()).absoluteRow(row);
        return abs < 0 ? null : s.typeAt(abs);
    }

    /** 来源主机 → 显示名（不认识的写法原样显示，宁可丑也不编）。 */
    private static String hostDisplayName(String fromHost) {
        if (fromHost == null || fromHost.isEmpty()) {
            return "未记录";
        }
        if (HostPatternMoverReader.HOST_GUIXU.equals(fromHost)) {
            return "归墟视界枢核";
        }
        if (HostPatternMoverReader.HOST_PRIMORDIAL.equals(fromHost)) {
            return "原始终焉引擎";
        }
        return sanitize(fromHost);
    }

    /** 配方类型 id → lang 显示名（五级回退；查不到就用 path）。 */
    static String typeDisplayName(String typeId) {
        if (typeId == null || typeId.trim().isEmpty()) {
            return sanitize(Component.translatable(HostPatternMoverEntry.LANG_UNKNOWN_TYPE).getString());
        }
        final String id = typeId.trim();
        final String namespace = ShanhaiRecipeTypeLabels.namespaceOf(id);
        final String path = ShanhaiRecipeTypeLabels.pathOf(id);
        final String name = ShanhaiRecipeTypeLabels.displayName(namespace, path,
                key -> Component.translatable(key).getString());
        return sanitize(name);
    }

    // ================================================================ 控件工厂

    /**
     * 一个标签（🔴 {@code setClientSideWidget()}：客户端每帧现取 supplier）。
     *
     * <p>依据（LDLib 1.0.33 字节码）：{@code LabelWidget.updateScreen()} 里那句
     * {@code textSupplier.get()} 被 {@code isClientSideWidget} 挡着 ⇒ 不设成客户端小部件，
     * 标签文本就只靠服务端标签级推送刷新（本工程那类"点了动作数字不更新"的病根）。
     */
    private LabelWidget label(int x, int y, Supplier<String> text) {
        final LabelWidget w = new LabelWidget(x, y, ShanhaiLdlText.sup(() -> sanitize(text.get())));
        w.setClientSideWidget();
        return w;
    }

    /**
     * 带文字的按钮（<b>只在服务端执行</b>；客户端那次回调 {@code isRemote=true} 直接返回）。
     *
     * @see HostPatternMoverConfirmPanel 类注释 §二（为什么用 Supplier 构造器）
     */
    private ButtonWidget button(int x, int y, int w, int h, Supplier<String> text, Runnable action) {
        final Supplier<String> safe = ShanhaiLdlText.sup(() -> sanitize(text.get()));
        return new ButtonWidget(x, y, w, h,
                new GuiTextureGroup(GuiTextures.BUTTON,
                        new TextTexture(safe)),
                clickData -> {
                    if (clickData != null && clickData.isRemote) {
                        return; // 客户端那次不执行（真正的写入只在服务端）
                    }
                    try {
                        action.run();
                    } catch (Throwable t) {
                        HostPatternMoverLog.warn("{} 面板按钮失败 err={}",
                                HostPatternMoverLog.PREFIX, t.toString());
                    }
                });
    }

    /**
     * 翻页按钮（<b>两侧都执行</b>）：页号是纯界面状态，不写任何东西，
     * 所以客户端那一拍也直接把本页翻过去（服务端那一拍翻它自己那份）。
     */
    private ButtonWidget flipButton(int x, int y, int w, int h, Supplier<String> text, Runnable action) {
        final Supplier<String> safe = ShanhaiLdlText.sup(() -> sanitize(text.get()));
        return new ButtonWidget(x, y, w, h,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(safe)),
                clickData -> {
                    try {
                        action.run();
                    } catch (Throwable t) {
                        HostPatternMoverLog.warn("{} 翻页失败 err={}",
                                HostPatternMoverLog.PREFIX, t.toString());
                    }
                });
    }

    /** 送进 LDLib 标签之前的净化（见类注释 §三-2）。 */
    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw;
        try {
            s = ShanhaiTextParser.stripStyleCode(s);
        } catch (Throwable ignored) {
            // 剥码失败不影响显示，继续走"删 &"
        }
        if (s.indexOf('&') >= 0) {
            s = s.replace("&", "");
        }
        return ShanhaiLdlText.esc(s);
    }

    /** 千分位（自己写：{@code String.format} 依赖 locale，本工程界面文本不碰 locale）。 */
    static String withComma(long value) {
        final boolean negative = value < 0;
        final String digits = Long.toString(Math.abs(value));
        final StringBuilder sb = new StringBuilder(digits.length() + digits.length() / 3 + 2);
        final int firstGroup = digits.length() % 3 == 0 ? 3 : digits.length() % 3;
        sb.append(digits, 0, firstGroup);
        for (int i = firstGroup; i < digits.length(); i += 3) {
            sb.append(',').append(digits, i, i + 3);
        }
        return negative ? "-" + sb : sb.toString();
    }
}
