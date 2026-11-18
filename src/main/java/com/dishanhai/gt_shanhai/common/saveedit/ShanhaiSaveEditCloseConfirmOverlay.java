package com.dishanhai.gt_shanhai.common.saveedit;

import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 「有暂存时要关面板」的<b>二次确认层</b>（用户点名的<b>唯一</b>一类确认弹窗）。
 *
 * <h2>一、用户原话（口径，不许自行发挥）</h2>
 * <blockquote>
 *   「我要暂存一直存到玩家关闭这个存档修改面板，不然就一直存在，并且当玩家以<b>任意形式</b>尝试关闭
 *    有暂存的面板时，弹出窗口进行二次确认，并且这个窗口<b>只能用鼠标点击确认，不能用 esc/enter 什么的确认</b>」
 * </blockquote>
 * ⇒ 三条硬约束：
 * <ol>
 *   <li><b>任意形式</b>：ESC 也算"尝试关闭"（原版 ESC 会直接 {@code onClose()}），所以 ESC 必须被这里拦住；</li>
 *   <li><b>只认鼠标</b>：确认层在场时 {@code ESC(256)} / {@code Enter(257)} / {@code 小键盘 Enter(335)}
 *       一律无效；两个按钮都是 {@code ButtonWidget}（字节码实测它<b>没有</b> {@code keyPressed}
 *       ⇒ 本来就只吃鼠标）；</li>
 *   <li><b>这条确认是用户点名要的</b> —— 其它任何地方都不许再加确认（用户：「除非我指定需要二次确认，否则都不加」）。</li>
 * </ol>
 *
 * <h2>二、🔴 为什么"覆写 {@code keyPressed}"就够，不需要 mixin（javap 证据）</h2>
 * <pre>
 *   ModularUIGuiContainer#keyPressed(int,int,int)        （SRG = m_7933_，LDLib 1.0.33.b 字节码）
 *     :0-2    focused = false
 *     :5-15   modularUI.mainGroup.keyPressed(key, scan, mods)      ← 🔴 第一站就是根 WidgetGroup
 *     :18     ifeq 23                                             ← 它返回 false 才继续
 *     :21-22  iconst_0 / ireturn                                  ← 返回 true ⇒ 本方法直接 return false
 *     :27     invokespecial AbstractContainerScreen.m_7933_        ← 只有走到这里才会进 Screen#keyPressed
 *
 *   WidgetGroup#keyPressed(int,int,int)                  （同 jar 字节码）
 *     :0-70   倒序遍历 widgets；isVisible() 且 isActive() 的子控件调 Widget#keyPressed；
 *             任一返回 true ⇒ WidgetGroup 返回 true（:61-62）
 *
 *   Screen#keyPressed(int,int,int)                       （forge 47.4.16 parchment jar 字节码）
 *     :0-19   if (key == 256 && shouldCloseOnEsc()) { onClose(); return true; }   ← 🔴 ESC 关面板的唯一出口
 * </pre>
 * ⇒ 结论：<b>根 WidgetGroup 就是 ESC 的必经点</b>；只要本控件在树里、可见且 active，返回 {@code true}
 * 就让 {@code ModularUIGuiContainer} 早退，{@code Screen#keyPressed} 一次都不会被调到 ⇒ ESC 关不掉面板。
 * 所以这里用覆写，<b>不挂 mixin</b>（少一个失败面，也不用改 {@code shanhai.mixin.json}）。
 *
 * <h2>三、这一层为什么<b>常驻</b>（不是"弹出来才存在"）</h2>
 * ESC 要在"还没弹确认层"的那一刻就被拦住，所以本控件常态 {@code visible=true / active=true}，
 * 只是：
 * <ul>
 *   <li>{@code asking == false} 时：三个字/两个按钮全部 {@code setVisible(false)}、
 *       {@code drawInBackground} 只画自己（什么都不画）、{@code mouseClicked} <b>直接 return false</b>
 *       （否则整块面板点不动 —— 不能依赖 {@code WidgetGroup} 基类在"没有任何子控件吃点击"时的返回值）；</li>
 *   <li>{@code asking == true} 时：压暗整层 + 居中方框 + 三个字 + 两个按钮，整层吃点击。</li>
 * </ul>
 *
 * <h2>四、"有没有暂存"这个数从哪来</h2>
 * 暂存全在<b>服务端那份</b> Holder 上（客户端那份是远程镜像，它自己没有暂存），所以：
 * <pre>
 *   服务端 detectAndSendChanges()（每 tick，LDLib 的常规通道）
 *     → 数变了才 writeUpdateInfo(UPDATE_STAGED_COUNT, n)
 *   客户端 readUpdateInfo(…) → stagedCount = n → 标题那句"已暂存 N 处"与 ESC 判据都用它
 * </pre>
 * ⇒ 与 {@code ShanhaiSaveEditFactory.stagedCount(holder)} <b>同一个数</b>（不另算一份口径）。
 *
 * <h2>五、"真关"只有一条路</h2>
 * {@link #reallyClose()}。为什么是 {@code player.closeContainer()} 而不是裸 {@code setScreen(null)}：
 * <pre>
 *   LocalPlayer#closeContainer()             （parchment jar 字节码）
 *     :0-18   connection.send(new ServerboundContainerClosePacket(containerMenu.containerId))
 *     :21     clientSideCloseContainer() → AbstractClientPlayer.closeContainer()
 *                                       → Minecraft.setScreen(null)          ← 任务书要的那一句
 *   ⇒ 只调 setScreen(null) 会漏掉"告诉服务端关容器"这一步（服务端那个 ModularUIContainer 还开着）
 *      而 closeContainer() 自己就把 setScreen(null) 带上了。
 * </pre>
 * 服务端那边容器被移除 ⇒ {@code ModularUIContainer#removed} → {@code ModularUI.triggerCloseListeners}
 * ⇒ {@code ShanhaiSaveEditFactory.forget(holder)}（暂存清空、复用表摘掉）。
 *
 * <h2>六、两侧各一份、只有客户端会画</h2>
 * 与第 ④⑤⑥ 页三条覆盖层同一条约定：{@code remote} = 本侧是客户端；
 * {@code drawInBackground} / {@link Minecraft} 只会在客户端被调到（JVM 惰性解析，专用服务端不会出事）。
 */
public final class ShanhaiSaveEditCloseConfirmOverlay extends WidgetGroup {

    /** 日志前缀（与整个存档直改同一个串）。 */
    static final String PREFIX = ShanhaiSaveEditFlow.PREFIX;

    /** 服务端 → 客户端：暂存了几处。 */
    static final int UPDATE_STAGED_COUNT = 93;

    /** 弹窗方框（居中；与 LDLib 控件坐标同一套：左上角原点，单位 px）。 */
    static final int BOX_X = 50;
    static final int BOX_Y = 68;
    static final int BOX_W = 300;
    static final int BOX_H = 96;
    /** 按钮几何（两个并排）。 */
    static final int BTN_W = 130;
    static final int BTN_H = 20;
    static final int BTN_Y = BOX_Y + 56;
    static final int BTN_CONFIRM_X = BOX_X + 10;
    static final int BTN_BACK_X = BOX_X + BOX_W - 10 - BTN_W;
    /** 三行字的宽度上限（方框去掉左右各 10 px）。 */
    static final int TEXT_W = BOX_W - 20;

    /** 压暗整层用的黑（alpha 0x66 ≈ 40%）。 */
    private static final int SCRIM = 0x66000000;
    /** 方框边框 / 底色。 */
    private static final int BOX_FRAME = 0xFF000000;
    private static final int BOX_BG = 0xFFC6C6C6;

    /** 键码（GLFW）。 */
    static final int KEY_ESC = 256;
    static final int KEY_ENTER = 257;
    static final int KEY_KP_ENTER = 335;

    /**
     * {@link #cancelAsk(String)} 之后要"吞掉点击"多久（毫秒）。
     *
     * <p>一次点击的按下/松开在客户端是同一帧到下一帧之间处理完的，250 ms 足够覆盖，
     * 又短到不会让玩家觉得"点不动"。
     */
    static final long IGNORE_CLICK_MS = 250L;

    /** 本侧是不是客户端那份（与三条覆盖层同一条约定）。 */
    private final boolean remote;
    /** 面板持有者（<b>服务端那份</b>才装有暂存；客户端那份只用来取日志里的玩家名）。 */
    private final ShanhaiSaveEditFactory.Holder holder;

    /** 现在是不是"正在问"。 */
    private boolean asking;
    /**
     * 忽略点击到这个时刻（{@link System#currentTimeMillis()}）。
     *
     * <p>为 {@link #cancelAsk(String)} 而设：取消"正在问"的那一下，用户手里那一次点击可能还在同
     * 一帧/同一 tick 的事件队列里，<b>不许</b>它落到底下刚露出来的按钮上（"自己打断自己"）。
     */
    private long ignoreClickBeforeMs;
    /** 「这一层到底画在哪」的读数只写一次（见 {@link #drawInBackground}）。 */
    private boolean absLogged;
    /** 服务端：上一次推过的数（-1 = 还没推过 ⇒ 第一 tick 必推一次）。 */
    private int pushedCount = -1;
    /** 客户端：从服务端推来的暂存条数（"已暂存 N 处"与 ESC 判据共用）。 */
    private int stagedCount = -1;

    private final List<Widget> dialogWidgets = new ArrayList<>();
    private LabelWidget titleLabel;
    private LabelWidget noteLabel;
    private LabelWidget countLabel;

    public ShanhaiSaveEditCloseConfirmOverlay(ShanhaiSaveEditPanel panel,
                                             ShanhaiSaveEditFactory.Holder holder,
                                             boolean remote) {
        super(0, 0, ShanhaiSaveEditLayout.W, ShanhaiSaveEditLayout.H);
        this.remote = remote;
        this.holder = holder;

        // 三行字（🔴 全是 setClientSideWidget：字由客户端按本侧 stagedCount 现拼，
        // 不许走 LDLib 的服务端同步 —— 服务端没有语言表，那一路推过来的一定不是中文）
        this.titleLabel = new LabelWidget(BOX_X + 10, BOX_Y + 8,
                () -> ShanhaiSaveEditPanel.fit(this.titleText(), TEXT_W));
        this.titleLabel.setClientSideWidget();
        this.noteLabel = new LabelWidget(BOX_X + 10, BOX_Y + 24,
                () -> ShanhaiSaveEditPanel.fit(this.noteText(), TEXT_W));
        this.noteLabel.setClientSideWidget();
        this.countLabel = new LabelWidget(BOX_X + 10, BOX_Y + 38,
                () -> ShanhaiSaveEditPanel.fit(this.countText(), TEXT_W));
        this.countLabel.setClientSideWidget();
        this.addWidget(this.titleLabel);
        this.addWidget(this.noteLabel);
        this.addWidget(this.countLabel);
        this.dialogWidgets.add(this.titleLabel);
        this.dialogWidgets.add(this.noteLabel);
        this.dialogWidgets.add(this.countLabel);

        // 两个按钮（ButtonWidget 没有 keyPressed ⇒ 天生只吃鼠标）
        final ButtonWidget confirm = ShanhaiSaveEditPanel.mkButton(BTN_CONFIRM_X, BTN_Y, BTN_W, BTN_H,
                () -> com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.5309f5a593a24186"), this::onConfirm);
        final ButtonWidget back = ShanhaiSaveEditPanel.mkButton(BTN_BACK_X, BTN_Y, BTN_W, BTN_H,
                () -> com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.038e2b49bc42cce5"), this::dismiss);
        this.addWidget(confirm);
        this.addWidget(back);
        this.dialogWidgets.add(confirm);
        this.dialogWidgets.add(back);

        // 常驻但"没在问"：所有子控件藏起来，自己也不吃点击
        this.setVisible(true);
        this.setActive(true);
        this.syncChildren();
    }

    // ================================================================== 状态

    /** 现在是不是"正在问"。 */
    public boolean asking() {
        return this.asking;
    }

    /** 客户端那份从服务端推来的暂存条数（{@code -1} = 还没收到）。 */
    public int stagedCount() {
        return this.stagedCount;
    }

    /** 弹二次确认（两侧都能调；服务端那份只为推计数）。 */
    public void ask() {
        this.asking = true;
        this.syncChildren();
        if (!this.remote) {
            pushCount();
            GTDishanhaiMod.LOGGER.info("{} close_confirm_open player={} staged={} {}",
                    PREFIX, playerName(), ShanhaiSaveEditFactory.stagedCount(this.holder),
                    ShanhaiSaveEditFactory.stagedBreakdown(this.holder));
        }
    }

    /** 收起来（「返回」= 什么都不丢弃）。 */
    public void dismiss() {
        this.asking = false;
        this.syncChildren();
        if (!this.remote) {
            GTDishanhaiMod.LOGGER.info("{} close_confirm_dismissed player={} staged={}",
                    PREFIX, playerName(), ShanhaiSaveEditFactory.stagedCount(this.holder));
        }
    }

    /**
     * 🔴 由外力把"正在问"关掉，并<b>吞掉当前这一下点击</b>（{@link ShanhaiSaveEditPanel#onConfirm()}
     * 一开始就调它）。
     *
     * <p>为什么要"吞掉"：确认流程一旦启动，面板下一 tick 就会被加载屏换掉；此刻若还有一个
     * {@code asking} 的弹层，它<b>整层吃点击</b>（{@link #mouseClicked}），而它那两个按钮此刻可能
     * 正好在玩家要点的位置上 ⇒ 玩家那一下会被弹层抢走，变成"自己打断自己"。
     * 所以：① 立刻把 {@code asking} 关掉；② 接下来 {@link #IGNORE_CLICK_MS} 内的点击只吞不落按钮。
     */
    public void cancelAsk(String why) {
        final boolean wasAsking = this.asking;
        this.asking = false;
        this.syncChildren();
        this.ignoreClickBeforeMs = System.currentTimeMillis() + IGNORE_CLICK_MS;
        if (wasAsking && !this.remote) {
            GTDishanhaiMod.LOGGER.info("{} close_confirm_cancelled reason={} player={} staged={}",
                    PREFIX, why, playerName(), ShanhaiSaveEditFactory.stagedCount(this.holder));
        }
    }

    /** 按 {@code asking} 调一次子控件显隐（两侧各调）。 */
    private void syncChildren() {
        for (Widget w : this.dialogWidgets) {
            w.setVisible(this.asking);
        }
    }

    // ================================================================== 键 / 鼠标（🔴 这一层的核心）

    /**
     * 🔴 <b>只认鼠标</b>。
     *
     * <ul>
     *   <li>{@code asking == true}：<b>所有键</b>都吃掉（返回 true）——
     *       ESC / Enter / 小键盘 Enter 明确无效，其余键也进不到后面的输入框
     *       （确认层在场时界面就该是"停住的"，只有两个按钮能动）；</li>
     *   <li>{@code asking == false}：只处理 ESC。有暂存 ⇒ 弹确认层并<b>吃掉 ESC</b>
     *       （原版那条"ESC = 直接关面板"被拦在 {@code Screen#keyPressed} 之前）；
     *       没暂存 ⇒ 返回 false，<b>放行</b>给原版（没暂存时关面板不该多一步）。</li>
     * </ul>
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.asking) {
            return true;                 // 只认鼠标：任何键都无效
        }
        // 🔴 "任意形式的关闭"有两条原版通路，都得拦：
        //    ① ESC → Screen#keyPressed（:0-19 的 if (key == 256 && shouldCloseOnEsc()) onClose()）
        //    ② 背包键 → AbstractContainerScreen#keyPressed 里 keyInventory.matches(...) ⇒ onClose()
        //       （字节码 :19-42，在 Screen#keyPressed 返回 false 之后）
        if (keyCode == KEY_ESC || isInventoryKey(keyCode, scanCode)) {
            if (this.hasStaged()) {
                this.ask();
                return true;             // 吃掉 ⇒ ModularUIGuiContainer 早退 ⇒ 两条 onClose 都走不到
            }
            return false;                // 没暂存 ⇒ 交给原版去关（不加多余的一步）
        }
        return false;                    // 其它键一律不干预（输入框还要用）
    }

    /**
     * 这个键是不是原版「打开/关闭背包」那个键（客户端的 {@code Options#keyInventory}）。
     *
     * <p>只会在客户端被调到（{@code keyPressed} 只在客户端界面里跑），所以这里出现
     * {@link Minecraft} 与 {@code keyPressed} 里出现 {@code GuiGraphics} 是同一条惰性解析理由。
     */
    private static boolean isInventoryKey(int keyCode, int scanCode) {
        try {
            final Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.options != null && mc.options.keyInventory != null
                    && mc.options.keyInventory.matches(keyCode, scanCode);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 有暂存吗（客户端看推来的数；服务端直接数容器）。 */
    public boolean hasStaged() {
        return this.remote
                ? this.stagedCount > 0
                : ShanhaiSaveEditFactory.hasAnyStaged(this.holder);
    }

    /**
     * 🔴 点击：没在问的时候<b>一次都不吃</b>（否则整块面板点不动）；
     * 在问的时候整层吃掉（落在覆盖层身上、但没打中按钮的那些点击，绝不能穿到下面的页签上）。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (System.currentTimeMillis() < this.ignoreClickBeforeMs) {
            // cancelAsk() 之后的那一下：只吞，不落任何按钮（连"在问"也不重开）
            return isMouseOverElement(mouseX, mouseY);
        }
        if (!this.asking) {
            return false;
        }
        super.mouseClicked(mouseX, mouseY, button);
        return isMouseOverElement(mouseX, mouseY);
    }

    // ================================================================== 画

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        if (this.asking) {
            // 🔴 这一段手写的 fill 必须自己套上<b>本层的绝对位置</b>（= 面板左上角在屏幕上的坐标）。
            //    字节码事实（LDLib 1.0.33.b）：
            //      WidgetGroup#drawWidgetsBackground :0-88   —— 只是 for(widgets) 逐个
            //          `widget.drawInBackground(graphics, mouseX, mouseY, partialTicks)`：
            //          <b>没有</b> pushPose / translate，mouseX/mouseY 也原样传下去；
            //      Widget#drawBackgroundTexture :29-70     —— 背景纹理用的是
            //          `getPosition()`（= parentPosition + selfPosition，绝对坐标）；
            //      ModularUI#updateScreenSize :58-91       —— mainGroup.setParentPosition(guiLeft, guiTop)。
            //    ⇒ "控件自己的背景/文字"由 LDLib 按绝对坐标画，位置是对的；
            //      而 {@code graphics.fill(x,y,…)} 里的 x,y 是<b>屏幕</b>坐标 —— 不加上本层绝对位置，
            //      就会画在 GUI 原点（左上角）。现场：压暗层与灰框出现在左上角并压住 JEI。
            final int ox = getPositionX();
            final int oy = getPositionY();
            graphics.pose().pushPose();
            graphics.pose().translate((float) ox, (float) oy, 0.0f);
            graphics.fill(0, 0, ShanhaiSaveEditLayout.W, ShanhaiSaveEditLayout.H, SCRIM);
            graphics.fill(BOX_X - 1, BOX_Y - 1, BOX_X + BOX_W + 1, BOX_Y + BOX_H + 1, BOX_FRAME);
            graphics.fill(BOX_X, BOX_Y, BOX_X + BOX_W, BOX_Y + BOX_H, BOX_BG);
            graphics.pose().popPose();
            logAbsOnce(ox, oy);
        }
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
    }

    /**
     * 一行可核对的位置读数（只在客户端、只在第一次真正弹出时写一次）。
     *
     * <p>它回答两件事：① 这一层自己的坐标（{@code (0,0)} = 与面板左上角重合）；
     * ② 那个居中方框<b>在屏幕上的</b>左上角 = 本层绝对位置 + {@code (BOX_X, BOX_Y)}。
     */
    private void logAbsOnce(int ox, int oy) {
        if (this.absLogged) {
            return;
        }
        this.absLogged = true;
        GTDishanhaiMod.LOGGER.info("{} close_confirm_abs self=({},{}) abs=({},{}) parent_abs={} "
                        + "box_abs=({},{}) draw=pose_translate",
                PREFIX, getSelfPositionX(), getSelfPositionY(), ox, oy,
                getParent() == null ? "-" : (getParent().getPositionX() + "," + getParent().getPositionY()),
                ox + BOX_X, oy + BOX_Y);
    }

    // ================================================================== 服务端 → 客户端：暂存条数

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        if (this.remote) {
            return;
        }
        final int now = ShanhaiSaveEditFactory.stagedCount(this.holder);
        if (now != this.pushedCount) {
            this.pushedCount = now;
            writeUpdateInfo(UPDATE_STAGED_COUNT, buf -> buf.writeVarInt(now));
        }
    }

    /** 🔴 只在服务端调：把"暂存了几处"推给客户端。 */
    private void pushCount() {
        if (this.remote) {
            return;
        }
        final int now = ShanhaiSaveEditFactory.stagedCount(this.holder);
        this.pushedCount = now;
        writeUpdateInfo(UPDATE_STAGED_COUNT, buf -> buf.writeVarInt(now));
    }

    @Override
    public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
        if (id == UPDATE_STAGED_COUNT) {
            this.stagedCount = buffer.readVarInt();
            return;
        }
        super.readUpdateInfo(id, buffer);
    }

    // ================================================================== 两个按钮

    /**
     * 「确认关闭（丢弃暂存）」。
     *
     * <p>⚠️ 这个回调会跑<b>两次</b>：客户端点下去那一下（LDLib 先在本地跑 callback），
     * 以及服务端收到 {@code ButtonWidget} 的 client action 时（{@code handleClientAction}）。
     * 所以按 {@code remote} 分工：
     * <ul>
     *   <li><b>服务端</b>：{@link ShanhaiSaveEditFactory#forget(Holder)} —— 清 Holder + 6 项暂存
     *       （幂等；正常路径其实是容器移除时的 close listener 先到）；</li>
     *   <li><b>客户端</b>：{@link #reallyClose()} —— 真关（发关容器包 + setScreen(null)）。</li>
     * </ul>
     */
    private void onConfirm() {
        if (!this.remote) {
            final int staged = ShanhaiSaveEditFactory.stagedCount(this.holder);
            final String breakdown = ShanhaiSaveEditFactory.stagedBreakdown(this.holder);
            ShanhaiSaveEditFactory.forget(this.holder);
            GTDishanhaiMod.LOGGER.info("{} close_confirmed player={} staged_discarded={} {} disk_written=0",
                    PREFIX, playerName(), staged, breakdown);
            this.asking = false;
            this.syncChildren();
            return;
        }
        GTDishanhaiMod.LOGGER.info("{} close_confirm_click player={} staged={} (只认鼠标确认)",
                PREFIX, playerName(), this.stagedCount);
        reallyClose();
    }

    // ================================================================== 真关（唯一收口）

    /**
     * 🔴 <b>「真关」唯一收口</b>（客户端）：把面板真的关掉。
     *
     * <p>为什么是 {@code player.closeContainer()}（javap 事实，见类注释 §五）：
     * 它内部 = ①{@code ServerboundContainerClosePacket} 告诉服务端关容器
     * ②{@code setScreen(null)}（任务书要的那一句就在这条路的尾巴上）。
     * 只调 {@code setScreen(null)} 会漏掉 ① ⇒ 服务端那个 {@code ModularUIContainer} 还开着。
     *
     * <p>服务端收到关容器包 ⇒ {@code ModularUIContainer#removed} →
     * {@code ModularUI.triggerCloseListeners} ⇒ {@code ShanhaiSaveEditFactory.forget(holder)}
     * ⇒ 暂存清空、复用表摘掉（这就是"暂存只活到关闭面板"的落地）。
     */
    public static void reallyClose() {
        // 🔴 客户端这一侧"真关"也是一种中止：只要那一次确认流程还停在"停服之前"（世界没停、一个字节
        //    都没写），就把全局锁放掉 —— 与服务端那条 forget→releaseIfOwned 互为兜底
        //    （万一旁挂的关容器包没送到，也不会留下"一进面板就 BUSY"）。
        //    窗口一开就不在这里放：那条路由 ShanhaiSaveEditReload 自己走完或自己 fail。
        if (ShanhaiSaveEditFlow.releaseIfActivePreWindow("close_confirm_really_close")) {
            final String line = ShanhaiSaveEditFlow.takeNotify();
            if (line != null && !line.isEmpty()) {
                GTDishanhaiMod.LOGGER.info("{} {}", PREFIX, line);
            }
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        boolean closed = false;
        try {
            if (mc.player != null) {
                mc.player.closeContainer();
                closed = true;
            }
        } catch (Throwable t) {
            GTDishanhaiMod.LOGGER.warn("{} close_container_failed err={}", PREFIX, t.toString());
        }
        if (!closed) {
            // 兜底：玩家还没登录/已经断开时也别把界面留在半空中
            try {
                mc.setScreen(null);
            } catch (Throwable t) {
                GTDishanhaiMod.LOGGER.warn("{} close_screen_failed err={}", PREFIX, t.toString());
            }
        }
    }

    // ================================================================== 文本

    private String titleText() {
        return com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.e1a3a2665a6b963d");
    }

    private String noteText() {
        return com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.0ffaf354d0dadc14");
    }

    private String countText() {
        if (this.stagedCount < 0) {
            return com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.3b52f7662f991986");
        }
        return this.stagedCount <= 0
                ? com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.d9fa7dff086d2a29")
                : com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.59d3fd2f7ae8fe31") + this.stagedCount + com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.787585e53f2877a0");
    }

    /** 日志里那个玩家名（客户端那份 Holder 是空的 ⇒ 退回 {@code -}）。 */
    private String playerName() {
        try {
            return this.holder != null && this.holder.ctx() != null ? this.holder.ctx().playerName : "-";
        } catch (Throwable t) {
            return "-";
        }
    }

    // ================================================================== 判据 / 读数

    /** 给判据/日志用的一行读数（不依赖任何实例状态）。 */
    public static String layoutLine() {
        return "close_confirm box=" + BOX_X + "," + BOX_Y + "," + BOX_W + "x" + BOX_H
                + " buttons=2 labels=3 text_w=" + TEXT_W
                + " keys_blocked=" + KEY_ESC + "/" + KEY_ENTER + "/" + KEY_KP_ENTER
                + " mouse_only=true key_widget_count=0"
                // 坐标口径（🔴 这半行是给"到底画在哪"用的）：本层 self=(0,0) ⇒ 与面板左上角重合；
                // box 是"面板内坐标"；手写 fill 现在会 pose.translate(本层绝对位置) 再画，
                // 屏幕坐标 = 面板左上角(panel_abs) + (50,68)。运行时读数见 close_confirm_abs。
                + " coord=self(0,0)+panel_abs box_rel=(50,68) draw=pose_translate"
                + " click_guard_ms=" + IGNORE_CLICK_MS;
    }
}
