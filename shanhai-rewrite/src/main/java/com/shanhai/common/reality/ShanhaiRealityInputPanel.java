package com.shanhai.common.reality;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.recipe.editor.ShanhaiLdlText;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.function.Supplier;

/**
 * 「把『目标』和『要干什么』登记下来」的那一张面板（65 件共用，靠 Holder 里的 itemId 区分）。
 *
 * <h2>一、版式（W=320 × H=136；坐标是<b>面板内</b>绝对坐标，LDLib 的绝对＝父组＋自身）</h2>
 * <pre>
 *   y=6    §f&lt;这件物品的显示名&gt; §8· §7把「目标」和「要干什么」登记下来
 *   y=22   [§7目标]  [ 文本框 196×16 ]               ← 占位提示在下面那行
 *   y=40            §8自己 / 玩家名 / 实体类型 id
 *   y=56   [§7要干什么]  [ 文本框 196×16 ]
 *   y=74            §8取消 / 记录
 *   y=96   [ 确定 64×18 ]  [ 取消 64×18 ]
 *   y=120  §7状态 §f&lt;状态行&gt;                         ← 保存后显示"已登记"
 * </pre>
 *
 * <h2>二、🔴 这一版最要紧的坑（需求点名）：文本框的输入<b>不能只在按钮回调里读</b></h2>
 * 本工程 2026-10-17 已经为此付过一次账（用户报"点保存根本没用"，日志实测
 * {@code save_sent slot=1 name_len=0}）。机制（LDLib 1.0.33 字节码，取证见
 * {@code temp/reality-items/ldlib-src/.../TextFieldWidget.java:232-264}）：
 * <pre>
 *   客户端：EditBox 文本变了 → onTextChanged(newText)
 *             → setCurrentString(newText)                      ← 客户端那份 currentString 跟着变
 *             → if (isClientSideWidget &amp;&amp; textResponder != null) textResponder.accept(...)
 *                ↑ 本面板<b>没有</b> setClientSideWidget() ⇒ 客户端这份的 responder <b>不被调</b>
 *             → writeClientAction(1, newText)                  ← 但同步照发
 *   服务端：handleClientAction(1) → setCurrentString(newText) → textResponder.accept(...)  ← 服务端这份被调
 * </pre>
 * ⇒ 结论：<b>服务端那份</b>控件才有"最新文字"（由 LDLib 自己的 client→server 同步送达），
 * 客户端那份只能靠 {@link TextFieldWidget#getCurrentString()} <b>现取</b>。
 * 所以：① {@link #updateScreen()} 每 tick 从两个控件现取（客户端那份的 Holder 才不会一直是空的）；
 * ② 点「确定」时服务端从<b>自己那份</b>控件再读一次（不依赖 responder 的时序）。
 *
 * <h2>三、两边各跑一次，谁干什么</h2>
 * <ul>
 *   <li><b>客户端</b>：画界面、读文本框、点「确定」时只把状态行改成"正在登记…"（不写盘、不发包）；</li>
 *   <li><b>服务端</b>：{@code ButtonWidget} 的 client action 回调里才是真写盘（
 *       {@code ClickData#isRemote} 为假那一次），写盘成功后回聊天栏、设状态行、
 *       <b>延到下一个服务端 tick</b>再关界面（不能在控件派发栈里当场拆控件树 —— 同
 *       {@code HostPatternMoverConfirmPanel#closeNextTick} 的注释）。</li>
 * </ul>
 *
 * <h2>四、文字为什么大多在面板里现算，而不是服务端算好塞过来</h2>
 * 服务端没有语言表（{@code Component.translatable(...).getString()} 会返回裸 key 或英文），
 * 所以凡是 lang 里的文案，控件一律 {@code setClientSideWidget()}（服务端既不写初始数据、
 * 也不推 update info，客户端自己每帧现算）。<b>唯一</b>例外是底部状态行：它是<b>服务端权威</b>，
 * 里面放的是<b>字面串</b>（"已登记"这种，两侧渲染结果逐字节相同），走 LDLib 既有的
 * {@code LabelWidget} update-info 机制推过来（照 {@code HostPatternMoverConfirmPanel} 的做法）。
 */
public final class ShanhaiRealityInputPanel extends WidgetGroup {

    /** 面板尺寸（版式读数见 {@link #layoutLine()}）。 */
    public static final int W = 320;
    public static final int H = 136;

    /** 标题行。 */
    private static final int TITLE_X = 6;
    private static final int TITLE_Y = 6;

    /** 左边那一列文字（「目标」/「要干什么」/状态）。 */
    private static final int LABEL_X = 6;

    /** 两个输入框共用同一列（对齐好看，也方便截图替代物写坐标表）。 */
    private static final int FIELD_X = 58;
    private static final int FIELD_W = 196;
    private static final int FIELD_H = 16;

    private static final int TARGET_Y = 22;
    private static final int TARGET_HINT_Y = 40;
    private static final int ACTION_Y = 56;
    private static final int ACTION_HINT_Y = 74;

    /** 两个按钮。 */
    private static final int BTN_Y = 96;
    private static final int BTN_W = 64;
    private static final int BTN_H = 18;
    private static final int OK_X = 58;
    private static final int CANCEL_X = 130;

    /** 状态行。 */
    private static final int STATUS_Y = 120;
    /** 状态值的左边界（前缀「状态」两个字 + 一点空隙）。 */
    private static final int STATUS_VALUE_X = 42;

    private final ShanhaiRealityInputFactory.Holder holder;
    private final transient Player player;

    /** 客户端那份 = true（服务端那份 = false）。由 Holder 现场决定，不查全局 dist。 */
    private final boolean remote;

    private TextFieldWidget targetField;
    private TextFieldWidget actionField;

    /**
     * 标题行的成品字（<b>构造时算一次</b>，之后每帧只返回它）。
     *
     * <p>为什么缓存：标题控件是 {@code setClientSideWidget()} 的，它的 supplier <b>每帧</b>都会被调；
     * 而显示名要走一次注册表 + 建 {@code ItemStack}。面板活着的时候这两件事都不会变 ⇒ 算一次就够。
     * 每一侧各算自己的那份（客户端算出中文/英文，服务端算出 en_us —— 服务端那份不会被画出来）。
     */
    private final String titleText;

    private ShanhaiRealityInputPanel(ShanhaiRealityInputFactory.Holder holder, Player player) {
        super(0, 0, W, H);
        this.holder = holder == null ? ShanhaiRealityInputFactory.Holder.EMPTY : holder;
        this.player = player;
        this.remote = this.holder.isRemote();
        this.setBackground(GuiTextures.BACKGROUND);
        // 标题行（这件物品的显示名 + 一句"在配什么"）——只算一次，见 titleText 字段的注释
        // ⚠️ 名字要按【显示宽度】截断：LDLib 的 LabelWidget 不换行也不裁，超宽会画到面板外面
        //    （65 件里最宽的是 PacketUtils.ensureRunningOnSameThread = 222 px，面板可用约 308 px）
        this.titleText = "§f" + fit(ShanhaiRealityInputStore.displayName(this.holder.itemId()).getString(), 250)
                + " §8· §7" + lang("shanhai.reality_input.title.suffix");

        // ── ① 顶部：这件物品的显示名（让玩家知道自己在配哪件）
        this.addWidget(new LabelWidget(TITLE_X, TITLE_Y, ShanhaiLdlText.sup(this::titleLine))
                .setClientSideWidget());

        // ── ② 目标
        this.addWidget(new LabelWidget(LABEL_X, TARGET_Y + 2, lang("shanhai.reality_input.label.target"))
                .setClientSideWidget());
        this.targetField = new TextFieldWidget(FIELD_X, TARGET_Y, FIELD_W, FIELD_H,
                null, value -> this.holder.setTarget(value));
        this.targetField.setMaxStringLength(ShanhaiRealityInputStore.MAX_TARGET_CHARS);
        // 预填上一次的登记（没登记过 = 空串）。⚠️ setCurrentString 在客户端会顺带把 EditBox 的值设好，
        //    且不会回写 client action（TextFieldWidget#setCurrentString 先改 currentString 再 setValue，
        //    onTextChanged 里 lastText == newText ⇒ 不发送，见类注释 §二）。
        this.targetField.setCurrentString(this.holder.target());
        this.addWidget(this.targetField);
        this.addWidget(new LabelWidget(FIELD_X, TARGET_HINT_Y, lang("shanhai.reality_input.hint.target"))
                .setClientSideWidget());

        // ── ③ 要干什么
        this.addWidget(new LabelWidget(LABEL_X, ACTION_Y + 2, lang("shanhai.reality_input.label.action"))
                .setClientSideWidget());
        this.actionField = new TextFieldWidget(FIELD_X, ACTION_Y, FIELD_W, FIELD_H,
                null, value -> this.holder.setAction(value));
        this.actionField.setMaxStringLength(ShanhaiRealityInputStore.MAX_ACTION_CHARS);
        this.actionField.setCurrentString(this.holder.action());
        this.addWidget(this.actionField);
        this.addWidget(new LabelWidget(FIELD_X, ACTION_HINT_Y, lang("shanhai.reality_input.hint.action"))
                .setClientSideWidget());

        // ── ④ 确定 / 取消
        this.addWidget(button(OK_X, BTN_Y, BTN_W, BTN_H, lang("shanhai.reality_input.button.confirm"),
                this::onConfirm));
        this.addWidget(button(CANCEL_X, BTN_Y, BTN_W, BTN_H, lang("shanhai.reality_input.button.cancel"),
                this::onCancel));

        // ── ⑤ 底部状态行（⚠️ 拆成两条：前缀走 lang 且【客户端独有】；值那一半是服务端权威的
        //    字面串，【不】setClientSideWidget ⇒ 靠 LDLib LabelWidget 的 update-info 推过来）
        this.addWidget(new LabelWidget(LABEL_X, STATUS_Y, lang("shanhai.reality_input.label.status"))
                .setClientSideWidget());
        this.addWidget(new LabelWidget(STATUS_VALUE_X, STATUS_Y, this::statusValueText));
    }

    /** 建面板（两侧各建一份；结构完全一致 ⇒ 控件 id 一致 ⇒ 客户端动作能对上服务端控件）。 */
    public static ShanhaiRealityInputPanel create(ShanhaiRealityInputFactory.Holder holder, Player player) {
        return new ShanhaiRealityInputPanel(holder, player);
    }

    // ================================================================ 按钮

    /**
     * 「确定」。
     *
     * <p>🔴 <b>两侧各被调一次</b>：LDLib 的 {@code ButtonWidget#mouseClicked} 先在客户端
     * {@code onPressCallback.accept(...)} 再 {@code writeClientAction}，服务端在
     * {@code handleClientAction} 里再调一次。⇒ 只有 {@code !this.remote} 那一次才写盘。
     */
    private void onConfirm() {
        if (this.remote) {
            // 客户端那一次什么都不做：写盘只在服务端。状态行也<b>不是</b>客户端自己说的 ——
            // 它是服务端权威的字面串，由 LDLib 的 LabelWidget update-info 推过来
            // （"已登记" / "目标不能为空" / "登记失败（看日志）"）。
            // ⚠️ 别在这里自己改状态行：那个标签不是 clientSideWidget，客户端改 Holder 也不会立刻重画，
            //    写了只会变成一行"看起来有效果、其实没人看"的死代码。
            return;
        }
        if (!(this.player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        // 🔴 服务端从【自己这份】控件现读 —— 文字是 LDLib 的 client→server 同步送来的，
        //    不依赖 responder 的时序（见类注释 §二），也不依赖按钮回调里手动塞过什么。
        final String target = liveText(this.targetField, this.holder.target());
        final String action = liveText(this.actionField, this.holder.action());
        if (target.isEmpty()) {
            this.holder.setStatus(ShanhaiRealityInputFactory.STATUS_EMPTY_TARGET);
            return;
        }
        if (action.isEmpty()) {
            this.holder.setStatus(ShanhaiRealityInputFactory.STATUS_EMPTY_ACTION);
            return;
        }
        final String itemId = this.holder.itemId();
        final boolean ok = ShanhaiRealityInputStore.save(itemId, target, action, serverPlayer.getUUID());
        if (!ok) {
            this.holder.setStatus(ShanhaiRealityInputFactory.STATUS_FAILED);
            serverPlayer.displayClientMessage(
                    Component.translatable("shanhai.reality_input.chat.save_failed"), false);
            return;
        }
        this.holder.setStatus(ShanhaiRealityInputFactory.STATUS_SAVED);
        // ── 聊天栏回显一行：[创始现实修改模块] 已登记：<事件名> → 目标 X ／ 动作 Y
        serverPlayer.displayClientMessage(Component.translatable("shanhai.reality_input.chat.saved",
                ShanhaiRealityInputStore.displayName(itemId), target, action), false);
        // ── 60 件：如实说"还没接线"，不许假装有效果
        if (!ShanhaiRealityInputStore.isWired(itemId)) {
            serverPlayer.displayClientMessage(
                    Component.translatable("shanhai.reality_input.chat.not_wired"), false);
        }
        closeNextTick(serverPlayer);
    }

    /** 「取消」：一个字节都不写，直接关（服务端那次有效）。 */
    private void onCancel() {
        if (this.remote) {
            return;      // 客户端那一次什么都不做（服务端会关）
        }
        if (!(this.player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        ShanhaiMod.LOGGER.info("{} panel_cancelled player={} item={}", ShanhaiRealityInputStore.PREFIX,
                serverPlayer.getName().getString(), this.holder.itemId());
        closeNextTick(serverPlayer);
    }

    /**
     * 排到下一个服务端 tick 再关界面。
     *
     * <p>🔴 为什么不能当场关：这里正处在 LDLib 的控件动作派发栈里，当场 {@code closeContainer}
     * 会把控件树在派发途中拆掉（{@code HostPatternMoverConfirmPanel} 的注释里记着同类面板踩过）。
     */
    private static void closeNextTick(ServerPlayer player) {
        try {
            player.server.execute(player::closeContainer);
        } catch (Throwable t) {
            // 拿不到 server（理论上不会）⇒ 退回当场关：关不掉也不许把已写好的数据回滚
            player.closeContainer();
        }
    }

    // ================================================================ 每 tick 现取（🔴 需求点名的坑）

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!this.remote) {
            return;      // 服务端那份没有 EditBox，也没有"屏幕上的字"可读
        }
        // 🔴 LDLib 的 TextFieldWidget 在【客户端这份】上不会调 textResponder
        //    （只在 isClientSideWidget 时才调，本面板没开那个开关）
        //    ⇒ 客户端这份的 Holder 必须每 tick 从控件里现取，否则永远是空的。
        if (this.targetField != null) {
            final String live = this.targetField.getCurrentString();
            if (live != null && !live.equals(this.holder.target())) {
                this.holder.setTarget(live);
            }
        }
        if (this.actionField != null) {
            final String live = this.actionField.getCurrentString();
            if (live != null && !live.equals(this.holder.action())) {
                this.holder.setAction(live);
            }
        }
    }

    // ================================================================ 文本

    /** 标题行：`§f<显示名> §8· §7把「目标」和「要干什么」登记下来`（构造时已算好，见 {@link #titleText}）。 */
    private String titleLine() {
        return this.titleText;
    }

    /**
     * 状态行<b>值</b>那一半：`§f<状态>`。
     *
     * <p>🔴 只返回<b>服务端权威的字面串</b>（"已登记" / "填好后点确定" …）。这里<b>不许</b>出现
     * {@code Component.translatable(...)}：服务端没有语言表，推过去的会是裸键/英文，把客户端
     * 正确的中文冲掉（见 {@link ShanhaiRealityInputFactory#STATUS_IDLE} 的注释）。
     * 永远不会返回 {@code null}（{@code LabelWidget.detectAndSendChanges} 会对 null 直接 NPE）。
     */
    private String statusValueText() {
        final String s = this.holder.status();
        final String shown = s == null || s.isEmpty() ? ShanhaiRealityInputFactory.STATUS_IDLE : s;
        return ShanhaiLdlText.esc("§f" + shown);
    }

    /** 读一个输入框（取不到就回落到 Holder 里的那一份）。 */
    private static String liveText(TextFieldWidget field, String fallback) {
        final String raw = field == null ? null : field.getCurrentString();
        final String s = raw == null || raw.trim().isEmpty() ? fallback : raw;
        return s == null ? "" : s.trim();
    }

    /**
     * 按<b>显示宽度</b>截断（中文 9 px/字、ASCII 6 px/字），超了补 {@code …}。
     *
     * <p>为什么必须截：{@code LabelWidget} <b>不换行也不裁</b>，超宽会画到面板外面去
     * （65 件里最宽的显示名 222 px；面板可用宽度约 308 px，留给名字的预算 250 px）。
     * 与 {@code HostPatternMoverConfirmPanel#fit} 同一套算法（那边是包内私有，这里抄一份）。
     */
    static String fit(String text, int maxPx) {
        if (text == null) {
            return "";
        }
        int px = 0;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            px += c < 0x2E80 ? 6 : 9;
            if (px > maxPx) {
                return text.substring(0, Math.max(0, i - 1)) + "…";
            }
        }
        return text;
    }

    // ================================================================ 控件工厂

    /** lang 键 → 当前语言下的字（客户端现算；服务端算出来的是英文，只用于"两侧结构一致"）。 */
    private static String lang(String key) {
        try {
            return Component.translatable(key).getString();
        } catch (Throwable t) {
            return key;
        }
    }

    /**
     * 带文字的按钮：{@code GuiTextureGroup(GuiTextures.BUTTON, TextTexture(Supplier))}。
     *
     * <p>为什么用 {@code TextTexture(Supplier<String>)} 而不是 {@code TextTexture(String)}：
     * 与工程既有面板同一条铁律（{@code TextTexture(String)} 那个重载在某些 LDLib 版本里让
     * {@code textSupplier} 留在 null，服务端 tick 时 NPE —— 见 {@code HostPatternMoverConfirmPanel}）。
     */
    private ButtonWidget button(int x, int y, int w, int h, String text, Runnable action) {
        final String fixed = ShanhaiLdlText.esc(text);
        return new ButtonWidget(x, y, w, h,
                new GuiTextureGroup(GuiTextures.BUTTON,
                        new TextTexture((Supplier<String>) () -> fixed)),
                clickData -> {
                    try {
                        action.run();
                    } catch (Throwable t) {
                        ShanhaiMod.LOGGER.warn("{} panel_button_failed item={} err={}",
                                ShanhaiRealityInputStore.PREFIX, this.holder.itemId(), t.toString());
                    }
                });
    }

    // ================================================================ 读数

    /**
     * 版式一行读数（装配时打进日志；也是"画不出截图"时的替代物之一）。
     *
     * <p>形如：{@code panel 320x136 title=(6,6) label_x=6 field=(58,w196,h16) target_y=22 ...}
     */
    public static String layoutLine() {
        return "panel " + W + "x" + H
                + " title=(" + TITLE_X + "," + TITLE_Y + ")"
                + " label_x=" + LABEL_X
                + " field_x=" + FIELD_X + " field_w=" + FIELD_W + " field_h=" + FIELD_H
                + " target_y=" + TARGET_Y + " target_hint_y=" + TARGET_HINT_Y
                + " action_y=" + ACTION_Y + " action_hint_y=" + ACTION_HINT_Y
                + " btn_y=" + BTN_Y + " btn=" + BTN_W + "x" + BTN_H
                + " ok_x=" + OK_X + " cancel_x=" + CANCEL_X
                + " status=(" + LABEL_X + "/" + STATUS_VALUE_X + "," + STATUS_Y + ")"
                + " widgets=11 (1 title, 2 labels, 2 text_fields, 2 hints, 2 buttons, 2 status halves)";
    }
}
